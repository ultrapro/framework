/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content.pm;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.PatternMatcher;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * Package archive parsing
 *
 * {@hide}
 */
public class PackageParser {
    private static final boolean DEBUG_JAR = false;
    private static final boolean DEBUG_PARSER = false;
    private static final boolean DEBUG_BACKUP = false;

    /** File name in an APK for the Android manifest. */
    private static final String ANDROID_MANIFEST_FILENAME = "AndroidManifest.xml";

    /** @hide */
    public static class NewPermissionInfo {
        public final String name;
        public final int sdkVersion;
        public final int fileVersion;
        
        public NewPermissionInfo(String name, int sdkVersion, int fileVersion) {
            this.name = name;
            this.sdkVersion = sdkVersion;
            this.fileVersion = fileVersion;
        }
    }

    /** @hide */
    public static class SplitPermissionInfo {
        public final String rootPerm;
        public final String[] newPerms;
        public final int targetSdk;

        public SplitPermissionInfo(String rootPerm, String[] newPerms, int targetSdk) {
            this.rootPerm = rootPerm;
            this.newPerms = newPerms;
            this.targetSdk = targetSdk;
        }
    }

    /**
     * List of new permissions that have been added since 1.0.
     * NOTE: These must be declared in SDK version order, with permissions
     * added to older SDKs appearing before those added to newer SDKs.
     * If sdkVersion is 0, then this is not a permission that we want to
     * automatically add to older apps, but we do want to allow it to be
     * granted during a platform update.
     * @hide
     */
    public static final PackageParser.NewPermissionInfo NEW_PERMISSIONS[] =
        new PackageParser.NewPermissionInfo[] {
            new PackageParser.NewPermissionInfo(android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.os.Build.VERSION_CODES.DONUT, 0),
            new PackageParser.NewPermissionInfo(android.Manifest.permission.READ_PHONE_STATE,
                    android.os.Build.VERSION_CODES.DONUT, 0)
    };

    /**
     * List of permissions that have been split into more granular or dependent
     * permissions.
     * @hide
     */
    public static final PackageParser.SplitPermissionInfo SPLIT_PERMISSIONS[] =
        new PackageParser.SplitPermissionInfo[] {
            // READ_EXTERNAL_STORAGE is always required when an app requests
            // WRITE_EXTERNAL_STORAGE, because we can't have an app that has
            // write access without read access.  The hack here with the target
            // target SDK version ensures that this grant is always done.
            new PackageParser.SplitPermissionInfo(android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    new String[] { android.Manifest.permission.READ_EXTERNAL_STORAGE },
                    android.os.Build.VERSION_CODES.CUR_DEVELOPMENT+1),
            new PackageParser.SplitPermissionInfo(android.Manifest.permission.READ_CONTACTS,
                    new String[] { android.Manifest.permission.READ_CALL_LOG },
                    android.os.Build.VERSION_CODES.JELLY_BEAN),
            new PackageParser.SplitPermissionInfo(android.Manifest.permission.WRITE_CONTACTS,
                    new String[] { android.Manifest.permission.WRITE_CALL_LOG },
                    android.os.Build.VERSION_CODES.JELLY_BEAN)
    };

    private String mArchiveSourcePath;
    private String[] mSeparateProcesses;
    private boolean mOnlyCoreApps;
    private static final int SDK_VERSION = Build.VERSION.SDK_INT;
    private static final String SDK_CODENAME = "REL".equals(Build.VERSION.CODENAME)
            ? null : Build.VERSION.CODENAME;

    private int mParseError = PackageManager.INSTALL_SUCCEEDED;

    private static final Object mSync = new Object();
    private static WeakReference<byte[]> mReadBuffer;

    private static boolean sCompatibilityModeEnabled = true;
    private static final int PARSE_DEFAULT_INSTALL_LOCATION = PackageInfo.INSTALL_LOCATION_UNSPECIFIED;

    static class ParsePackageItemArgs {
        final Package owner;
        final String[] outError;
        final int nameRes;
        final int labelRes;
        final int iconRes;
        final int logoRes;
        
        String tag;
        TypedArray sa;
        
        ParsePackageItemArgs(Package _owner, String[] _outError,
                int _nameRes, int _labelRes, int _iconRes, int _logoRes) {
            owner = _owner;
            outError = _outError;
            nameRes = _nameRes;
            labelRes = _labelRes;
            iconRes = _iconRes;
            logoRes = _logoRes;
        }
    }
    
    static class ParseComponentArgs extends ParsePackageItemArgs {
        final String[] sepProcesses;
        final int processRes;
        final int descriptionRes;
        final int enabledRes;
        int flags;
        
        ParseComponentArgs(Package _owner, String[] _outError,
                int _nameRes, int _labelRes, int _iconRes, int _logoRes,
                String[] _sepProcesses, int _processRes,
                int _descriptionRes, int _enabledRes) {
            super(_owner, _outError, _nameRes, _labelRes, _iconRes, _logoRes);
            sepProcesses = _sepProcesses;
            processRes = _processRes;
            descriptionRes = _descriptionRes;
            enabledRes = _enabledRes;
        }
    }

    /* Light weight package info.
     * @hide
     */
    public static class PackageLite {
        public final String packageName;
        public final int versionCode;
        public final int installLocation;
        public final VerifierInfo[] verifiers;

        public PackageLite(String packageName, int versionCode,
                int installLocation, List<VerifierInfo> verifiers) {
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.installLocation = installLocation;
            this.verifiers = verifiers.toArray(new VerifierInfo[verifiers.size()]);
        }
    }

    private ParsePackageItemArgs mParseInstrumentationArgs;
    private ParseComponentArgs mParseActivityArgs;
    private ParseComponentArgs mParseActivityAliasArgs;
    private ParseComponentArgs mParseServiceArgs;
    private ParseComponentArgs mParseProviderArgs;
    
    /** If set to true, we will only allow package files that exactly match
     *  the DTD.  Otherwise, we try to get as much from the package as we
     *  can without failing.  This should normally be set to false, to
     *  support extensions to the DTD in future versions. */
    private static final boolean RIGID_PARSER = false;

    private static final String TAG = "PackageParser";

    public PackageParser(String archiveSourcePath) {
        mArchiveSourcePath = archiveSourcePath;
    }

    public void setSeparateProcesses(String[] procs) {
        mSeparateProcesses = procs;
    }

    public void setOnlyCoreApps(boolean onlyCoreApps) {
        mOnlyCoreApps = onlyCoreApps;
    }

///LEWA BEGIN
    private static final boolean isPackageFilename(String name) {
        // Woody Guo @ 2012/07/09: Treat lwt as apk
        return name.endsWith(".apk") || name.endsWith(".lwt");
    }

    public static String getLockedZipFilePath(String path) {
        if (path == null) {
            return null;
        }
        if (isPackageFilename(path)) {
            return path.substring(0, path.length() - 4) + ".locked.zip";
        } else {
            return path + ".locked.zip";
        }
    }
///LEWA END
    /*
    public static PackageInfo generatePackageInfo(PackageParser.Package p,
            int gids[], int flags, long firstInstallTime, long lastUpdateTime,
            HashSet<String> grantedPermissions) {
        PackageUserState state = new PackageUserState();
        return generatePackageInfo(p, gids, flags, firstInstallTime, lastUpdateTime,
                grantedPermissions, state, UserHandle.getCallingUserId());
    }
    */

    /**
     * Generate and return the {@link PackageInfo} for a parsed package.
     *
     * @param p the parsed package.
     * @param flags indicating which optional information is included.
     */
    public static PackageInfo generatePackageInfo(PackageParser.Package p,
            int gids[], int flags, long firstInstallTime, long lastUpdateTime,
            HashSet<String> grantedPermissions, PackageUserState state) {

        return generatePackageInfo(p, gids, flags, firstInstallTime, lastUpdateTime,
                grantedPermissions, state, UserHandle.getCallingUserId());
    }

    private static boolean checkUseInstalled(int flags, PackageUserState state) {
        return state.installed || ((flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0);
    }

    public static PackageInfo generatePackageInfo(PackageParser.Package p,
            int gids[], int flags, long firstInstallTime, long lastUpdateTime,
            HashSet<String> grantedPermissions, PackageUserState state, int userId) {

        if (!checkUseInstalled(flags, state)) {
            return null;
        }
        PackageInfo pi = new PackageInfo();
        pi.packageName = p.packageName;
        pi.versionCode = p.mVersionCode;
        pi.versionName = p.mVersionName;
        pi.sharedUserId = p.mSharedUserId;
        pi.sharedUserLabel = p.mSharedUserLabel;
        /** M: [ALPS00295677][MP Feature Patch Back]Theme manager on ICS feature patch back @{ */
        pi.isThemePackage = p.isThemePackage;
        pi.themeNameId = p.mThemeNameId;
        /** @} */
///LEWA BEGIN
        pi.isThemeApk = p.mIsThemeApk;
        pi.setDrmProtectedThemeApk(false);
        if (pi.isThemeApk) {
            int N = p.mThemeInfos.size();
            if (N > 0) {
                pi.themeInfos = new ThemeInfo[N];
                for (int i = 0; i < N; i++) {
                    pi.themeInfos[i] = p.mThemeInfos.get(i);
                    pi.setDrmProtectedThemeApk(pi.isDrmProtectedThemeApk() || pi.themeInfos[i].isDrmProtected);
                }
                if (pi.isDrmProtectedThemeApk()) {
                    pi.setLockedZipFilePath(PackageParser.getLockedZipFilePath(p.mPath));
                }
            }
        }
///LEWA END
        pi.applicationInfo = generateApplicationInfo(p, flags, state, userId);
        pi.installLocation = p.installLocation;
        pi.firstInstallTime = firstInstallTime;
        pi.lastUpdateTime = lastUpdateTime;
        if ((flags&PackageManager.GET_GIDS) != 0) {
            pi.gids = gids;
        }
        if ((flags&PackageManager.GET_CONFIGURATIONS) != 0) {
            int N = p.configPreferences.size();
            if (N > 0) {
                pi.configPreferences = new ConfigurationInfo[N];
                p.configPreferences.toArray(pi.configPreferences);
            }
            N = p.reqFeatures != null ? p.reqFeatures.size() : 0;
            if (N > 0) {
                pi.reqFeatures = new FeatureInfo[N];
                p.reqFeatures.toArray(pi.reqFeatures);
            }
        }
        if ((flags&PackageManager.GET_ACTIVITIES) != 0) {
            int N = p.activities.size();
            if (N > 0) {
                if ((flags&PackageManager.GET_DISABLED_COMPONENTS) != 0) {
                    pi.activities = new ActivityInfo[N];
                } else {
                    int num = 0;
                    for (int i=0; i<N; i++) {
                        if (p.activities.get(i).info.enabled) num++;
                    }
                    pi.activities = new ActivityInfo[num];
                }
                for (int i=0, j=0; i<N; i++) {
                    final Activity activity = p.activities.get(i);
                    if (activity.info.enabled
                        || (flags&PackageManager.GET_DISABLED_COMPONENTS) != 0) {
                        pi.activities[j++] = generateActivityInfo(p.activities.get(i), flags,
                                state, userId);
                    }
                }
            }
        }
        if ((flags&PackageManager.GET_RECEIVERS) != 0) {
            int N = p.receivers.size();
            if (N > 0) {
                if ((flags&PackageManager.GET_DISABLED_COMPONENTS) != 0) {
                    pi.receivers = new ActivityInfo[N];
                } else {
                    int num = 0;
                    for (int i=0; i<N; i++) {
                        if (p.receivers.get(i).info.enabled) num++;
                    }
                    pi.receivers = new ActivityInfo[num];
                }
                for (int i=0, j=0; i<N; i++) {
                    final Activity activity = p.receivers.get(i);
                    if (activity.info.enabled
                        || (flags&PackageManager.GET_DISABLED_COMPONENTS) != 0) {
                        pi.receivers[j++] = generateActivityInfo(p.receivers.get(i), flags,
                                state, userId);
                    }
                }
            }
        }
        if ((flags&PackageManager.GET_SERVICES) != 0) {
            int N = p.services.size();
            if (N > 0) {
                if ((flags&PackageManager.GET_DISABLED_COMPONENTS) != 0) {
                    pi.services = new ServiceInfo[N];
                } else {
                    int num = 0;
                    for (int i=0; i<N; i++) {
                        if (p.services.get(i).info.enabled) num++;
                    }
                    pi.services = new ServiceInfo[num];
                }
                for (int i=0, j=0; i<N; i++) {
                    final Service service = p.services.get(i);
                    if (service.info.enabled
                        || (flags&PackageManager.GET_DISABLED_COMPONENTS) != 0) {
                        pi.services[j++] = generateServiceInfo(p.services.get(i), flags,
                                state, userId);
                    }
                }
            }
        }
        if ((flags&PackageManager.GET_PROVIDERS) != 0) {
            int N = p.providers.size();
            if (N > 0) {
                if ((flags&PackageManager.GET_DISABLED_COMPONENTS) != 0) {
                    pi.providers = new ProviderInfo[N];
                } else {
                    int num = 0;
                    for (int i=0; i<N; i++) {
                        if (p.providers.get(i).info.enabled) num++;
                    }
                    pi.providers = new ProviderInfo[num];
                }
                for (int i=0, j=0; i<N; i++) {
                    final Provider provider = p.providers.get(i);
                    if (provider.info.enabled
                        || (flags&PackageManager.GET_DISABLED_COMPONENTS) != 0) {
                        pi.providers[j++] = generateProviderInfo(p.providers.get(i), flags,
                                state, userId);
                    }
                }
            }
        }
        if ((flags&PackageManager.GET_INSTRUMENTATION) != 0) {
            int N = p.instrumentation.size();
            if (N > 0) {
                pi.instrumentation = new InstrumentationInfo[N];
                for (int i=0; i<N; i++) {
                    pi.instrumentation[i] = generateInstrumentationInfo(
                            p.instrumentation.get(i), flags);
                }
            }
        }
        if ((flags&PackageManager.GET_PERMISSIONS) != 0) {
            int N = p.permissions.size();
            if (N > 0) {
                pi.permissions = new PermissionInfo[N];
                for (int i=0; i<N; i++) {
                    pi.permissions[i] = generatePermissionInfo(p.permissions.get(i), flags);
                }
            }
            N = p.requestedPermissions.size();
            if (N > 0) {
                pi.requestedPermissions = new String[N];
                pi.requestedPermissionsFlags = new int[N];
                for (int i=0; i<N; i++) {
                    final String perm = p.requestedPermissions.get(i);
                    pi.requestedPermissions[i] = perm;
                    if (p.requestedPermissionsRequired.get(i)) {
                        pi.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_REQUIRED;
                    }
                    if (grantedPermissions != null && grantedPermissions.contains(perm)) {
                        pi.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_GRANTED;
                    }
                }
            }
        }
        if ((flags&PackageManager.GET_SIGNATURES) != 0) {
           int N = (p.mSignatures != null) ? p.mSignatures.length : 0;
           if (N > 0) {
                pi.signatures = new Signature[N];
                System.arraycopy(p.mSignatures, 0, pi.signatures, 0, N);
            }
        }
        return pi;
    }

    private Certificate[] loadCertificates(JarFile jarFile, JarEntry je,
            byte[] readBuffer) {
        try {
            // We must read the stream for the JarEntry to retrieve
            // its certificates.
            InputStream is = new BufferedInputStream(jarFile.getInputStream(je));
            while (is.read(readBuffer, 0, readBuffer.length) != -1) {
                // not using
            }
            is.close();
            return je != null ? je.getCertificates() : null;
        } catch (IOException e) {
            Slog.w(TAG, "Exception reading " + je.getName() + " in "
                    + jarFile.getName(), e);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception reading " + je.getName() + " in "
                    + jarFile.getName(), e);
        }
        return null;
    }

    public final static int PARSE_IS_SYSTEM = 1<<0;
    public final static int PARSE_CHATTY = 1<<1;
    public final static int PARSE_MUST_BE_APK = 1<<2;
    public final static int PARSE_IGNORE_PROCESSES = 1<<3;
    public final static int PARSE_FORWARD_LOCK = 1<<4;
    public final static int PARSE_ON_SDCARD = 1<<5;
    public final static int PARSE_IS_SYSTEM_DIR = 1<<6;
    public final static int PARSE_UNINSTALL_APP = 1<<7;
    /// M: [ALPS00338366] Add PARSE_IS_VENDOR for operator apps
    public final static int PARSE_IS_VENDOR = 1<<8;

    public int getParseError() {
        return mParseError;
    }

    public Package parsePackage(File sourceFile, String destCodePath,
            DisplayMetrics metrics, int flags) {
        mParseError = PackageManager.INSTALL_SUCCEEDED;

        mArchiveSourcePath = sourceFile.getPath();
        if (!sourceFile.isFile()) {
            Slog.w(TAG, "Skipping dir: " + mArchiveSourcePath);
            mParseError = PackageManager.INSTALL_PARSE_FAILED_NOT_APK;
            return null;
        }
        if (!isPackageFilename(sourceFile.getName())
                && (flags&PARSE_MUST_BE_APK) != 0) {
            if ((flags&PARSE_IS_SYSTEM) == 0) {
                // We expect to have non-.apk files in the system dir,
                // so don't warn about them.
                Slog.w(TAG, "Skipping non-package file: " + mArchiveSourcePath);
            }
            mParseError = PackageManager.INSTALL_PARSE_FAILED_NOT_APK;
            return null;
        }

        if (DEBUG_JAR)
            Slog.d(TAG, "Scanning package: " + mArchiveSourcePath);

        XmlResourceParser parser = null;
        AssetManager assmgr = null;
        Resources res = null;
        boolean assetError = true;
        try {
            assmgr = new AssetManager();
            int cookie = assmgr.addAssetPath(mArchiveSourcePath);
            if (cookie != 0) {
                res = new Resources(assmgr, metrics, null);
                assmgr.setConfiguration(0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        Build.VERSION.RESOURCES_SDK_INT);
                parser = assmgr.openXmlResourceParser(cookie, ANDROID_MANIFEST_FILENAME);
                assetError = false;
            } else {
                Slog.w(TAG, "Failed adding asset path:"+mArchiveSourcePath);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Unable to read AndroidManifest.xml of "
                    + mArchiveSourcePath, e);
        }
        if (assetError) {
            if (assmgr != null) assmgr.close();
            mParseError = PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST;
            return null;
        }
        String[] errorText = new String[1];
        Package pkg = null;
        Exception errorException = null;
        try {
            // XXXX todo: need to figure out correct configuration.
            pkg = parsePackage(res, parser, flags, errorText);
        } catch (Exception e) {
            errorException = e;
            mParseError = PackageManager.INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION;
        }


        if (pkg == null) {
            // If we are only parsing core apps, then a null with INSTALL_SUCCEEDED
            // just means to skip this app so don't make a fuss about it.
            if (!mOnlyCoreApps || mParseError != PackageManager.INSTALL_SUCCEEDED) {
                if (errorException != null) {
                    Slog.w(TAG, mArchiveSourcePath, errorException);
                } else {
                    Slog.w(TAG, mArchiveSourcePath + " (at "
                            + parser.getPositionDescription()
                            + "): " + errorText[0]);
                }
                if (mParseError == PackageManager.INSTALL_SUCCEEDED) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                }
            }
            parser.close();
            assmgr.close();
            return null;
        }

        parser.close();
        assmgr.close();

        // Set code and resource paths
        pkg.mPath = destCodePath;
        pkg.mScanPath = mArchiveSourcePath;
        //pkg.applicationInfo.sourceDir = destCodePath;
        //pkg.applicationInfo.publicSourceDir = destRes;
        pkg.mSignatures = null;

        return pkg;
    }

    public boolean collectCertificates(Package pkg, int flags) {
        pkg.mSignatures = null;

        WeakReference<byte[]> readBufferRef;
        byte[] readBuffer = null;
        synchronized (mSync) {
            readBufferRef = mReadBuffer;
            if (readBufferRef != null) {
                mReadBuffer = null;
                readBuffer = readBufferRef.get();
            }
            if (readBuffer == null) {
                readBuffer = new byte[8192];
                readBufferRef = new WeakReference<byte[]>(readBuffer);
            }
        }

        try {
            JarFile jarFile = new JarFile(mArchiveSourcePath);

            Certificate[] certs = null;

            if ((flags&PARSE_IS_SYSTEM) != 0) {
                // If this package comes from the system image, then we
                // can trust it...  we'll just use the AndroidManifest.xml
                // to retrieve its signatures, not validating all of the
                // files.
                JarEntry jarEntry = jarFile.getJarEntry(ANDROID_MANIFEST_FILENAME);
                certs = loadCertificates(jarFile, jarEntry, readBuffer);
                if (certs == null) {
                    Slog.e(TAG, "Package " + pkg.packageName
                            + " has no certificates at entry "
                            + jarEntry.getName() + "; ignoring!");
                    jarFile.close();
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_NO_CERTIFICATES;
                    return false;
                }
                if (DEBUG_JAR) {
                    Slog.i(TAG, "File " + mArchiveSourcePath + ": entry=" + jarEntry
                            + " certs=" + (certs != null ? certs.length : 0));
                    if (certs != null) {
                        final int N = certs.length;
                        for (int i=0; i<N; i++) {
                            Slog.i(TAG, "  Public key: "
                                    + certs[i].getPublicKey().getEncoded()
                                    + " " + certs[i].getPublicKey());
                        }
                    }
                }
            } else {
                Enumeration<JarEntry> entries = jarFile.entries();
                final Manifest manifest = jarFile.getManifest();
                while (entries.hasMoreElements()) {
                    final JarEntry je = entries.nextElement();
                    if (je.isDirectory()) continue;

                    final String name = je.getName();

                    if (name.startsWith("META-INF/"))
                        continue;

                    if (ANDROID_MANIFEST_FILENAME.equals(name)) {
                        final Attributes attributes = manifest.getAttributes(name);
                        pkg.manifestDigest = ManifestDigest.fromAttributes(attributes);
                    }

                    final Certificate[] localCerts = loadCertificates(jarFile, je, readBuffer);
                    if (DEBUG_JAR) {
                        Slog.i(TAG, "File " + mArchiveSourcePath + " entry " + je.getName()
                                + ": certs=" + certs + " ("
                                + (certs != null ? certs.length : 0) + ")");
                    }

                    if (localCerts == null) {
                        Slog.e(TAG, "Package " + pkg.packageName
                                + " has no certificates at entry "
                                + je.getName() + "; ignoring!");
                        jarFile.close();
                        mParseError = PackageManager.INSTALL_PARSE_FAILED_NO_CERTIFICATES;
                        return false;
                    } else if (certs == null) {
                        certs = localCerts;
                    } else {
                        // Ensure all certificates match.
                        for (int i=0; i<certs.length; i++) {
                            boolean found = false;
                            for (int j=0; j<localCerts.length; j++) {
                                if (certs[i] != null &&
                                        certs[i].equals(localCerts[j])) {
                                    found = true;
                                    break;
                                }
                            }
                            if (!found || certs.length != localCerts.length) {
                                Slog.e(TAG, "Package " + pkg.packageName
                                        + " has mismatched certificates at entry "
                                        + je.getName() + "; ignoring!");
                                jarFile.close();
                                mParseError = PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
                                return false;
                            }
                        }
                    }
                }
            }
            jarFile.close();

            synchronized (mSync) {
                mReadBuffer = readBufferRef;
            }

            if (certs != null && certs.length > 0) {
                final int N = certs.length;
                pkg.mSignatures = new Signature[certs.length];
                for (int i=0; i<N; i++) {
                    pkg.mSignatures[i] = new Signature(
                            certs[i].getEncoded());
                }
            } else {
                Slog.e(TAG, "Package " + pkg.packageName
                        + " has no certificates; ignoring!");
                mParseError = PackageManager.INSTALL_PARSE_FAILED_NO_CERTIFICATES;
                return false;
            }
        } catch (CertificateEncodingException e) {
            Slog.w(TAG, "Exception reading " + mArchiveSourcePath, e);
            mParseError = PackageManager.INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING;
            return false;
        } catch (IOException e) {
            Slog.w(TAG, "Exception reading " + mArchiveSourcePath, e);
            mParseError = PackageManager.INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING;
            return false;
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception reading " + mArchiveSourcePath, e);
            mParseError = PackageManager.INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION;
            return false;
        }

        return true;
    }

    /*
     * Utility method that retrieves just the package name and install
     * location from the apk location at the given file path.
     * @param packageFilePath file location of the apk
     * @param flags Special parse flags
     * @return PackageLite object with package information or null on failure.
     */
    public static PackageLite parsePackageLite(String packageFilePath, int flags) {
        AssetManager assmgr = null;
        final XmlResourceParser parser;
        final Resources res;
        try {
            assmgr = new AssetManager();
            assmgr.setConfiguration(0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    Build.VERSION.RESOURCES_SDK_INT);

            int cookie = assmgr.addAssetPath(packageFilePath);
            if (cookie == 0) {
                return null;
            }

            final DisplayMetrics metrics = new DisplayMetrics();
            metrics.setToDefaults();
            res = new Resources(assmgr, metrics, null);
            parser = assmgr.openXmlResourceParser(cookie, ANDROID_MANIFEST_FILENAME);
        } catch (Exception e) {
            if (assmgr != null) assmgr.close();
            Slog.w(TAG, "Unable to read AndroidManifest.xml of "
                    + packageFilePath, e);
            return null;
        }

        final AttributeSet attrs = parser;
        final String errors[] = new String[1];
        PackageLite packageLite = null;
        try {
            packageLite = parsePackageLite(res, parser, attrs, flags, errors);
        } catch (IOException e) {
            Slog.w(TAG, packageFilePath, e);
        } catch (XmlPullParserException e) {
            Slog.w(TAG, packageFilePath, e);
        } finally {
            if (parser != null) parser.close();
            if (assmgr != null) assmgr.close();
        }
        if (packageLite == null) {
            Slog.e(TAG, "parsePackageLite error: " + errors[0]);
            return null;
        }
        return packageLite;
    }

    private static String validateName(String name, boolean requiresSeparator) {
        final int N = name.length();
        boolean hasSep = false;
        boolean front = true;
        for (int i=0; i<N; i++) {
            final char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                front = false;
                continue;
            }
            if (!front) {
                if ((c >= '0' && c <= '9') || c == '_') {
                    continue;
                }
            }
            if (c == '.') {
                hasSep = true;
                front = true;
                continue;
            }
            return "bad character '" + c + "'";
        }
        return hasSep || !requiresSeparator
                ? null : "must have at least one '.' separator";
    }

    private static String parsePackageName(XmlPullParser parser,
            AttributeSet attrs, int flags, String[] outError)
            throws IOException, XmlPullParserException {

        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
            ;
        }

        if (type != XmlPullParser.START_TAG) {
            outError[0] = "No start tag found";
            return null;
        }
        if (DEBUG_PARSER)
            Slog.v(TAG, "Root element name: '" + parser.getName() + "'");
        if (!parser.getName().equals("manifest")) {
            outError[0] = "No <manifest> tag";
            return null;
        }
        String pkgName = attrs.getAttributeValue(null, "package");
        if (pkgName == null || pkgName.length() == 0) {
            outError[0] = "<manifest> does not specify package";
            return null;
        }
        String nameError = validateName(pkgName, true);
///LEWA BEGIN
        // if (nameError != null && !"android".equals(pkgName)) {
        if (nameError != null && !"android".equals(pkgName) && !"lewa".equals(pkgName)) {
///LEWA END
            outError[0] = "<manifest> specifies bad package name \""
                + pkgName + "\": " + nameError;
            return null;
        }

        return pkgName.intern();
    }

    private static PackageLite parsePackageLite(Resources res, XmlPullParser parser,
            AttributeSet attrs, int flags, String[] outError) throws IOException,
            XmlPullParserException {

        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
            ;
        }

        if (type != XmlPullParser.START_TAG) {
            outError[0] = "No start tag found";
            return null;
        }
        if (DEBUG_PARSER)
            Slog.v(TAG, "Root element name: '" + parser.getName() + "'");
        if (!parser.getName().equals("manifest")) {
            outError[0] = "No <manifest> tag";
            return null;
        }
        String pkgName = attrs.getAttributeValue(null, "package");
        if (pkgName == null || pkgName.length() == 0) {
            outError[0] = "<manifest> does not specify package";
            return null;
        }
        String nameError = validateName(pkgName, true);
        if (nameError != null && !"android".equals(pkgName)) {
            outError[0] = "<manifest> specifies bad package name \""
                + pkgName + "\": " + nameError;
            return null;
        }
        int installLocation = PARSE_DEFAULT_INSTALL_LOCATION;
        int versionCode = 0;
        int numFound = 0;
        for (int i = 0; i < attrs.getAttributeCount(); i++) {
            String attr = attrs.getAttributeName(i);
            if (attr.equals("installLocation")) {
                installLocation = attrs.getAttributeIntValue(i,
                        PARSE_DEFAULT_INSTALL_LOCATION);
                numFound++;
            } else if (attr.equals("versionCode")) {
                versionCode = attrs.getAttributeIntValue(i, 0);
                numFound++;
            }
            if (numFound >= 2) {
                break;
            }
        }

        // Only search the tree when the tag is directly below <manifest>
        final int searchDepth = parser.getDepth() + 1;

        final List<VerifierInfo> verifiers = new ArrayList<VerifierInfo>();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() >= searchDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getDepth() == searchDepth && "package-verifier".equals(parser.getName())) {
                final VerifierInfo verifier = parseVerifier(res, parser, attrs, flags, outError);
                if (verifier != null) {
                    verifiers.add(verifier);
                }
            }
        }

        return new PackageLite(pkgName.intern(), versionCode, installLocation, verifiers);
    }

    /**
     * Temporary.
     */
    static public Signature stringToSignature(String str) {
        final int N = str.length();
        byte[] sig = new byte[N];
        for (int i=0; i<N; i++) {
            sig[i] = (byte)str.charAt(i);
        }
        return new Signature(sig);
    }

    private Package parsePackage(
        Resources res, XmlResourceParser parser, int flags, String[] outError)
        throws XmlPullParserException, IOException {
        AttributeSet attrs = parser;

        mParseInstrumentationArgs = null;
        mParseActivityArgs = null;
        mParseServiceArgs = null;
        mParseProviderArgs = null;
        
        String pkgName = parsePackageName(parser, attrs, flags, outError);
        if (pkgName == null) {
            mParseError = PackageManager.INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME;
            return null;
        }
        int type;

        if (mOnlyCoreApps) {
            boolean core = attrs.getAttributeBooleanValue(null, "coreApp", false);
            if (!core) {
                mParseError = PackageManager.INSTALL_SUCCEEDED;
                return null;
            }
        }

        final Package pkg = new Package(pkgName);
        boolean foundApp = false;
        
        TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifest);
        pkg.mVersionCode = sa.getInteger(
                com.android.internal.R.styleable.AndroidManifest_versionCode, 0);
        pkg.mVersionName = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifest_versionName, 0);
        if (pkg.mVersionName != null) {
            pkg.mVersionName = pkg.mVersionName.intern();
        }
        String str = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifest_sharedUserId, 0);
        if (str != null && str.length() > 0) {
            String nameError = validateName(str, true);
            if (nameError != null && !"android".equals(pkgName)) {
                outError[0] = "<manifest> specifies bad sharedUserId name \""
                    + str + "\": " + nameError;
                mParseError = PackageManager.INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID;
                return null;
            }
            pkg.mSharedUserId = str.intern();
            pkg.mSharedUserLabel = sa.getResourceId(
                    com.android.internal.R.styleable.AndroidManifest_sharedUserLabel, 0);
        }
        sa.recycle();

        pkg.installLocation = sa.getInteger(
                com.android.internal.R.styleable.AndroidManifest_installLocation,
                PARSE_DEFAULT_INSTALL_LOCATION);
        pkg.applicationInfo.installLocation = pkg.installLocation;

        /* Set the global "forward lock" flag */
        if ((flags & PARSE_FORWARD_LOCK) != 0) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_FORWARD_LOCK;
        }

        /* Set the global "on SD card" flag */
        if ((flags & PARSE_ON_SDCARD) != 0) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_EXTERNAL_STORAGE;
        }

        /** M: [ALPS00295677][MP Feature Patch Back]Theme manager on ICS feature patch back @{ */
        TypedArray a = res.obtainAttributes(attrs, com.mediatek.internal.R.styleable.MTKManifest);
        pkg.isThemePackage = a.getInteger(com.mediatek.internal.R.styleable.MTKManifest_isThemePackage, 0);
        pkg.mThemeNameId = a.getResourceId(com.mediatek.internal.R.styleable.MTKManifest_themeNameId, 0);
        a.recycle();
        /** @} */

        // Resource boolean are -1, so 1 means we don't know the value.
        int supportsSmallScreens = 1;
        int supportsNormalScreens = 1;
        int supportsLargeScreens = 1;
        int supportsXLargeScreens = 1;
        int resizeable = 1;
        int anyDensity = 1;
        
        int outerDepth = parser.getDepth();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("application")) {
                if (foundApp) {
                    if (RIGID_PARSER) {
                        outError[0] = "<manifest> has more than one <application>";
                        mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                        return null;
                    } else {
                        Slog.w(TAG, "<manifest> has more than one <application>");
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                }

                foundApp = true;
                if (!parseApplication(pkg, res, parser, attrs, flags, outError)) {
                    return null;
                }
            } else if (tagName.equals("permission-group")) {
                if (parsePermissionGroup(pkg, flags, res, parser, attrs, outError) == null) {
                    return null;
                }
            } else if (tagName.equals("permission")) {
                if (parsePermission(pkg, res, parser, attrs, outError) == null) {
                    return null;
                }
            } else if (tagName.equals("permission-tree")) {
                if (parsePermissionTree(pkg, res, parser, attrs, outError) == null) {
                    return null;
                }
            } else if (tagName.equals("uses-permission")) {
                sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.AndroidManifestUsesPermission);

                // Note: don't allow this value to be a reference to a resource
                // that may change.
                String name = sa.getNonResourceString(
                        com.android.internal.R.styleable.AndroidManifestUsesPermission_name);
                /* Not supporting optional permissions yet.
                boolean required = sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestUsesPermission_required, true);
                */

                sa.recycle();

                if (name != null && !pkg.requestedPermissions.contains(name)) {
                    pkg.requestedPermissions.add(name.intern());
                    pkg.requestedPermissionsRequired.add(Boolean.TRUE);
                }

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals("uses-configuration")) {
                ConfigurationInfo cPref = new ConfigurationInfo();
                sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.AndroidManifestUsesConfiguration);
                cPref.reqTouchScreen = sa.getInt(
                        com.android.internal.R.styleable.AndroidManifestUsesConfiguration_reqTouchScreen,
                        Configuration.TOUCHSCREEN_UNDEFINED);
                cPref.reqKeyboardType = sa.getInt(
                        com.android.internal.R.styleable.AndroidManifestUsesConfiguration_reqKeyboardType,
                        Configuration.KEYBOARD_UNDEFINED);
                if (sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestUsesConfiguration_reqHardKeyboard,
                        false)) {
                    cPref.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_HARD_KEYBOARD;
                }
                cPref.reqNavigation = sa.getInt(
                        com.android.internal.R.styleable.AndroidManifestUsesConfiguration_reqNavigation,
                        Configuration.NAVIGATION_UNDEFINED);
                if (sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestUsesConfiguration_reqFiveWayNav,
                        false)) {
                    cPref.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_FIVE_WAY_NAV;
                }
                sa.recycle();
                pkg.configPreferences.add(cPref);

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals("uses-feature")) {
                FeatureInfo fi = new FeatureInfo();
                sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.AndroidManifestUsesFeature);
                // Note: don't allow this value to be a reference to a resource
                // that may change.
                fi.name = sa.getNonResourceString(
                        com.android.internal.R.styleable.AndroidManifestUsesFeature_name);
                if (fi.name == null) {
                    fi.reqGlEsVersion = sa.getInt(
                            com.android.internal.R.styleable.AndroidManifestUsesFeature_glEsVersion,
                            FeatureInfo.GL_ES_VERSION_UNDEFINED);
                }
                if (sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestUsesFeature_required,
                        true)) {
                    fi.flags |= FeatureInfo.FLAG_REQUIRED;
                }
                sa.recycle();
                if (pkg.reqFeatures == null) {
                    pkg.reqFeatures = new ArrayList<FeatureInfo>();
                }
                pkg.reqFeatures.add(fi);
                
                if (fi.name == null) {
                    ConfigurationInfo cPref = new ConfigurationInfo();
                    cPref.reqGlEsVersion = fi.reqGlEsVersion;
                    pkg.configPreferences.add(cPref);
                }

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals("uses-sdk")) {
                if (SDK_VERSION > 0) {
                    sa = res.obtainAttributes(attrs,
                            com.android.internal.R.styleable.AndroidManifestUsesSdk);

                    int minVers = 0;
                    String minCode = null;
                    int targetVers = 0;
                    String targetCode = null;
                    
                    TypedValue val = sa.peekValue(
                            com.android.internal.R.styleable.AndroidManifestUsesSdk_minSdkVersion);
                    if (val != null) {
                        if (val.type == TypedValue.TYPE_STRING && val.string != null) {
                            targetCode = minCode = val.string.toString();
                        } else {
                            // If it's not a string, it's an integer.
                            targetVers = minVers = val.data;
                        }
                    }
                    
                    val = sa.peekValue(
                            com.android.internal.R.styleable.AndroidManifestUsesSdk_targetSdkVersion);
                    if (val != null) {
                        if (val.type == TypedValue.TYPE_STRING && val.string != null) {
                            targetCode = minCode = val.string.toString();
                        } else {
                            // If it's not a string, it's an integer.
                            targetVers = val.data;
                        }
                    }
                    
                    sa.recycle();

                    if (minCode != null) {
                        if (!minCode.equals(SDK_CODENAME)) {
                            if (SDK_CODENAME != null) {
                                outError[0] = "Requires development platform " + minCode
                                        + " (current platform is " + SDK_CODENAME + ")";
                            } else {
                                outError[0] = "Requires development platform " + minCode
                                        + " but this is a release platform.";
                            }
                            mParseError = PackageManager.INSTALL_FAILED_OLDER_SDK;
                            return null;
                        }
                    } else if (minVers > SDK_VERSION) {
                        outError[0] = "Requires newer sdk version #" + minVers
                                + " (current version is #" + SDK_VERSION + ")";
                        mParseError = PackageManager.INSTALL_FAILED_OLDER_SDK;
                        return null;
                    }
                    
                    if (targetCode != null) {
                        if (!targetCode.equals(SDK_CODENAME)) {
                            if (SDK_CODENAME != null) {
                                outError[0] = "Requires development platform " + targetCode
                                        + " (current platform is " + SDK_CODENAME + ")";
                            } else {
                                outError[0] = "Requires development platform " + targetCode
                                        + " but this is a release platform.";
                            }
                            mParseError = PackageManager.INSTALL_FAILED_OLDER_SDK;
                            return null;
                        }
                        // If the code matches, it definitely targets this SDK.
                        pkg.applicationInfo.targetSdkVersion
                                = android.os.Build.VERSION_CODES.CUR_DEVELOPMENT;
                    } else {
                        pkg.applicationInfo.targetSdkVersion = targetVers;
                    }
                }

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals("supports-screens")) {
                sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens);

                pkg.applicationInfo.requiresSmallestWidthDp = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_requiresSmallestWidthDp,
                        0);
                pkg.applicationInfo.compatibleWidthLimitDp = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_compatibleWidthLimitDp,
                        0);
                pkg.applicationInfo.largestWidthLimitDp = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_largestWidthLimitDp,
                        0);

                // This is a trick to get a boolean and still able to detect
                // if a value was actually set.
                supportsSmallScreens = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_smallScreens,
                        supportsSmallScreens);
                supportsNormalScreens = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_normalScreens,
                        supportsNormalScreens);
                supportsLargeScreens = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_largeScreens,
                        supportsLargeScreens);
                supportsXLargeScreens = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_xlargeScreens,
                        supportsXLargeScreens);
                resizeable = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_resizeable,
                        resizeable);
                anyDensity = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_anyDensity,
                        anyDensity);

                sa.recycle();
                
                XmlUtils.skipCurrentTag(parser);
                
            } else if (tagName.equals("protected-broadcast")) {
                sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.AndroidManifestProtectedBroadcast);

                // Note: don't allow this value to be a reference to a resource
                // that may change.
                String name = sa.getNonResourceString(
                        com.android.internal.R.styleable.AndroidManifestProtectedBroadcast_name);

                sa.recycle();

                if (name != null && (flags&PARSE_IS_SYSTEM) != 0) {
                    if (pkg.protectedBroadcasts == null) {
                        pkg.protectedBroadcasts = new ArrayList<String>();
                    }
                    if (!pkg.protectedBroadcasts.contains(name)) {
                        pkg.protectedBroadcasts.add(name.intern());
                    }
                }

                XmlUtils.skipCurrentTag(parser);
                
            } else if (tagName.equals("instrumentation")) {
                if (parseInstrumentation(pkg, res, parser, attrs, outError) == null) {
                    return null;
                }
                
            } else if (tagName.equals("original-package")) {
                sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.AndroidManifestOriginalPackage);

                String orig =sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestOriginalPackage_name, 0);
                if (!pkg.packageName.equals(orig)) {
                    if (pkg.mOriginalPackages == null) {
                        pkg.mOriginalPackages = new ArrayList<String>();
                        pkg.mRealPackage = pkg.packageName;
                    }
                    pkg.mOriginalPackages.add(orig);
                }

                sa.recycle();

                XmlUtils.skipCurrentTag(parser);
                
            } else if (tagName.equals("adopt-permissions")) {
                sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.AndroidManifestOriginalPackage);

                String name = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestOriginalPackage_name, 0);

                sa.recycle();

                if (name != null) {
                    if (pkg.mAdoptPermissions == null) {
                        pkg.mAdoptPermissions = new ArrayList<String>();
                    }
                    pkg.mAdoptPermissions.add(name);
                }

                XmlUtils.skipCurrentTag(parser);
                
            } else if (tagName.equals("uses-gl-texture")) {
                // Just skip this tag
                XmlUtils.skipCurrentTag(parser);
                continue;
                
            } else if (tagName.equals("compatible-screens")) {
                // Just skip this tag
                XmlUtils.skipCurrentTag(parser);
                continue;
                
            } else if (tagName.equals("eat-comment")) {
                // Just skip this tag
                XmlUtils.skipCurrentTag(parser);
                continue;
///LEWA BEGIN
            } else if (tagName.equals("theme")) {
                // this is a theme apk.
                pkg.mIsThemeApk = true;
                pkg.mThemeInfos.add(new ThemeInfo(parser, res, attrs));
///LEWA END
            } else if (RIGID_PARSER) {
                outError[0] = "Bad element under <manifest>: "
                    + parser.getName();
                mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                return null;

            } else {
                Slog.w(TAG, "Unknown element under <manifest>: " + parser.getName()
                        + " at " + mArchiveSourcePath + " "
                        + parser.getPositionDescription());
                XmlUtils.skipCurrentTag(parser);
                continue;
            }
        }

        if (!foundApp && pkg.instrumentation.size() == 0) {
            outError[0] = "<manifest> does not contain an <application> or <instrumentation>";
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_EMPTY;
        }

        final int NP = PackageParser.NEW_PERMISSIONS.length;
        StringBuilder implicitPerms = null;
        for (int ip=0; ip<NP; ip++) {
            final PackageParser.NewPermissionInfo npi
                    = PackageParser.NEW_PERMISSIONS[ip];
            if (pkg.applicationInfo.targetSdkVersion >= npi.sdkVersion) {
                break;
            }
            if (!pkg.requestedPermissions.contains(npi.name)) {
                if (implicitPerms == null) {
                    implicitPerms = new StringBuilder(128);
                    implicitPerms.append(pkg.packageName);
                    implicitPerms.append(": compat added ");
                } else {
                    implicitPerms.append(' ');
                }
                implicitPerms.append(npi.name);
                pkg.requestedPermissions.add(npi.name);
                pkg.requestedPermissionsRequired.add(Boolean.TRUE);
            }
        }
        if (implicitPerms != null) {
            Slog.i(TAG, implicitPerms.toString());
        }

        final int NS = PackageParser.SPLIT_PERMISSIONS.length;
        for (int is=0; is<NS; is++) {
            final PackageParser.SplitPermissionInfo spi
                    = PackageParser.SPLIT_PERMISSIONS[is];
            if (pkg.applicationInfo.targetSdkVersion >= spi.targetSdk
                    || !pkg.requestedPermissions.contains(spi.rootPerm)) {
                continue;
            }
            for (int in=0; in<spi.newPerms.length; in++) {
                final String perm = spi.newPerms[in];
                if (!pkg.requestedPermissions.contains(perm)) {
                    pkg.requestedPermissions.add(perm);
                    pkg.requestedPermissionsRequired.add(Boolean.TRUE);
                }
            }
        }

        if (supportsSmallScreens < 0 || (supportsSmallScreens > 0
                && pkg.applicationInfo.targetSdkVersion
                        >= android.os.Build.VERSION_CODES.DONUT)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS;
        }
        if (supportsNormalScreens != 0) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS;
        }
        if (supportsLargeScreens < 0 || (supportsLargeScreens > 0
                && pkg.applicationInfo.targetSdkVersion
                        >= android.os.Build.VERSION_CODES.DONUT)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS;
        }
        if (supportsXLargeScreens < 0 || (supportsXLargeScreens > 0
                && pkg.applicationInfo.targetSdkVersion
                        >= android.os.Build.VERSION_CODES.GINGERBREAD)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS;
        }
        if (resizeable < 0 || (resizeable > 0
                && pkg.applicationInfo.targetSdkVersion
                        >= android.os.Build.VERSION_CODES.DONUT)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS;
        }
        if (anyDensity < 0 || (anyDensity > 0
                && pkg.applicationInfo.targetSdkVersion
                        >= android.os.Build.VERSION_CODES.DONUT)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES;
        }
///LEWA BEGIN
        if (pkg.mIsThemeApk) {
            pkg.applicationInfo.isThemeable = false;
        }
///LEWA END

        return pkg;
    }

    private static String buildClassName(String pkg, CharSequence clsSeq,
            String[] outError) {
        if (clsSeq == null || clsSeq.length() <= 0) {
            outError[0] = "Empty class name in package " + pkg;
            return null;
        }
        String cls = clsSeq.toString();
        char c = cls.charAt(0);
        if (c == '.') {
            return (pkg + cls).intern();
        }
        if (cls.indexOf('.') < 0) {
            StringBuilder b = new StringBuilder(pkg);
            b.append('.');
            b.append(cls);
            return b.toString().intern();
        }
        if (c >= 'a' && c <= 'z') {
            return cls.intern();
        }
        outError[0] = "Bad class name " + cls + " in package " + pkg;
        return null;
    }

    private static String buildCompoundName(String pkg,
            CharSequence procSeq, String type, String[] outError) {
        String proc = procSeq.toString();
        char c = proc.charAt(0);
        if (pkg != null && c == ':') {
            if (proc.length() < 2) {
                outError[0] = "Bad " + type + " name " + proc + " in package " + pkg
                        + ": must be at least two characters";
                return null;
            }
            String subName = proc.substring(1);
            String nameError = validateName(subName, false);
            if (nameError != null) {
                outError[0] = "Invalid " + type + " name " + proc + " in package "
                        + pkg + ": " + nameError;
                return null;
            }
            return (pkg + proc).intern();
        }
        String nameError = validateName(proc, true);
        if (nameError != null && !"system".equals(proc)) {
            outError[0] = "Invalid " + type + " name " + proc + " in package "
                    + pkg + ": " + nameError;
            return null;
        }
        return proc.intern();
    }
    
    private static String buildProcessName(String pkg, String defProc,
            CharSequence procSeq, int flags, String[] separateProcesses,
            String[] outError) {
        if ((flags&PARSE_IGNORE_PROCESSES) != 0 && !"system".equals(procSeq)) {
            return defProc != null ? defProc : pkg;
        }
        if (separateProcesses != null) {
            for (int i=separateProcesses.length-1; i>=0; i--) {
                String sp = separateProcesses[i];
                if (sp.equals(pkg) || sp.equals(defProc) || sp.equals(procSeq)) {
                    return pkg;
                }
            }
        }
        if (procSeq == null || procSeq.length() <= 0) {
            return defProc;
        }
        return buildCompoundName(pkg, procSeq, "process", outError);
    }

    private static String buildTaskAffinityName(String pkg, String defProc,
            CharSequence procSeq, String[] outError) {
        if (procSeq == null) {
            return defProc;
        }
        if (procSeq.length() <= 0) {
            return null;
        }
        return buildCompoundName(pkg, procSeq, "taskAffinity", outError);
    }
    
    private PermissionGroup parsePermissionGroup(Package owner, int flags, Resources res,
            XmlPullParser parser, AttributeSet attrs, String[] outError)
        throws XmlPullParserException, IOException {
        PermissionGroup perm = new PermissionGroup(owner);

        TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifestPermissionGroup);

        if (!parsePackageItemInfo(owner, perm.info, outError,
                "<permission-group>", sa,
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_name,
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_label,
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_icon,
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_logo)) {
            sa.recycle();
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        perm.info.descriptionRes = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_description,
                0);
        perm.info.flags = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_permissionGroupFlags, 0);
        perm.info.priority = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_priority, 0);
        if (perm.info.priority > 0 && (flags&PARSE_IS_SYSTEM) == 0) {
            perm.info.priority = 0;
        }

        sa.recycle();
        
        if (!parseAllMetaData(res, parser, attrs, "<permission-group>", perm,
                outError)) {
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        owner.permissionGroups.add(perm);

        return perm;
    }

    private Permission parsePermission(Package owner, Resources res,
            XmlPullParser parser, AttributeSet attrs, String[] outError)
        throws XmlPullParserException, IOException {
        Permission perm = new Permission(owner);

        TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifestPermission);

        if (!parsePackageItemInfo(owner, perm.info, outError,
                "<permission>", sa,
                com.android.internal.R.styleable.AndroidManifestPermission_name,
                com.android.internal.R.styleable.AndroidManifestPermission_label,
                com.android.internal.R.styleable.AndroidManifestPermission_icon,
                com.android.internal.R.styleable.AndroidManifestPermission_logo)) {
            sa.recycle();
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        // Note: don't allow this value to be a reference to a resource
        // that may change.
        perm.info.group = sa.getNonResourceString(
                com.android.internal.R.styleable.AndroidManifestPermission_permissionGroup);
        if (perm.info.group != null) {
            perm.info.group = perm.info.group.intern();
        }
        
        perm.info.descriptionRes = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestPermission_description,
                0);

        perm.info.protectionLevel = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestPermission_protectionLevel,
                PermissionInfo.PROTECTION_NORMAL);

        perm.info.flags = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestPermission_permissionFlags, 0);

        sa.recycle();

        if (perm.info.protectionLevel == -1) {
            outError[0] = "<permission> does not specify protectionLevel";
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        perm.info.protectionLevel = PermissionInfo.fixProtectionLevel(perm.info.protectionLevel);

        if ((perm.info.protectionLevel&PermissionInfo.PROTECTION_MASK_FLAGS) != 0) {
            if ((perm.info.protectionLevel&PermissionInfo.PROTECTION_MASK_BASE) !=
                    PermissionInfo.PROTECTION_SIGNATURE) {
                outError[0] = "<permission>  protectionLevel specifies a flag but is "
                        + "not based on signature type";
                mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                return null;
            }
        }
        
        if (!parseAllMetaData(res, parser, attrs, "<permission>", perm,
                outError)) {
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        owner.permissions.add(perm);

        return perm;
    }

    private Permission parsePermissionTree(Package owner, Resources res,
            XmlPullParser parser, AttributeSet attrs, String[] outError)
        throws XmlPullParserException, IOException {
        Permission perm = new Permission(owner);

        TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifestPermissionTree);

        if (!parsePackageItemInfo(owner, perm.info, outError,
                "<permission-tree>", sa,
                com.android.internal.R.styleable.AndroidManifestPermissionTree_name,
                com.android.internal.R.styleable.AndroidManifestPermissionTree_label,
                com.android.internal.R.styleable.AndroidManifestPermissionTree_icon,
                com.android.internal.R.styleable.AndroidManifestPermissionTree_logo)) {
            sa.recycle();
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        sa.recycle();
        
        int index = perm.info.name.indexOf('.');
        if (index > 0) {
            index = perm.info.name.indexOf('.', index+1);
        }
        if (index < 0) {
            outError[0] = "<permission-tree> name has less than three segments: "
                + perm.info.name;
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        perm.info.descriptionRes = 0;
        perm.info.protectionLevel = PermissionInfo.PROTECTION_NORMAL;
        perm.tree = true;

        if (!parseAllMetaData(res, parser, attrs, "<permission-tree>", perm,
                outError)) {
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        owner.permissions.add(perm);

        return perm;
    }

    private Instrumentation parseInstrumentation(Package owner, Resources res,
            XmlPullParser parser, AttributeSet attrs, String[] outError)
        throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifestInstrumentation);

        if (mParseInstrumentationArgs == null) {
            mParseInstrumentationArgs = new ParsePackageItemArgs(owner, outError,
                    com.android.internal.R.styleable.AndroidManifestInstrumentation_name,
                    com.android.internal.R.styleable.AndroidManifestInstrumentation_label,
                    com.android.internal.R.styleable.AndroidManifestInstrumentation_icon,
                    com.android.internal.R.styleable.AndroidManifestInstrumentation_logo);
            mParseInstrumentationArgs.tag = "<instrumentation>";
        }
        
        mParseInstrumentationArgs.sa = sa;
        
        Instrumentation a = new Instrumentation(mParseInstrumentationArgs,
                new InstrumentationInfo());
        if (outError[0] != null) {
            sa.recycle();
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        String str;
        // Note: don't allow this value to be a reference to a resource
        // that may change.
        str = sa.getNonResourceString(
                com.android.internal.R.styleable.AndroidManifestInstrumentation_targetPackage);
        a.info.targetPackage = str != null ? str.intern() : null;

        a.info.handleProfiling = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestInstrumentation_handleProfiling,
                false);

        a.info.functionalTest = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestInstrumentation_functionalTest,
                false);

        sa.recycle();

        if (a.info.targetPackage == null) {
            outError[0] = "<instrumentation> does not specify targetPackage";
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        if (!parseAllMetaData(res, parser, attrs, "<instrumentation>", a,
                outError)) {
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        owner.instrumentation.add(a);

        return a;
    }

///LEWA BEGIN
    private void parseApplicationThemeAttributes(XmlPullParser parser, AttributeSet attrs,
            ApplicationInfo appInfo) {
        for (int i = 0; i < attrs.getAttributeCount(); i++) {
            if (!ApplicationInfo.isPlutoNamespace(parser.getAttributeNamespace(i))) {
                continue;
            }
            String attrName = attrs.getAttributeName(i);
            if (attrName.equalsIgnoreCase(ApplicationInfo.PLUTO_ISTHEMEABLE_ATTRIBUTE_NAME)) {
                appInfo.isThemeable = attrs.getAttributeBooleanValue(i, false);
                return;
            }
        }
    }

    private void parseActivityThemeAttributes(XmlPullParser parser, AttributeSet attrs,
            ActivityInfo ai) {
        for (int i = 0; i < attrs.getAttributeCount(); i++) {
            if (!ApplicationInfo.isPlutoNamespace(parser.getAttributeNamespace(i))) {
                continue;
            }
            String attrName = attrs.getAttributeName(i);
            if (attrName.equalsIgnoreCase(ApplicationInfo.PLUTO_HANDLE_THEME_CONFIG_CHANGES_ATTRIBUTE_NAME)) {
                ai.configChanges |= ActivityInfo.CONFIG_THEME_RESOURCE;
                ai.configChanges |= ActivityInfo.CONFIG_FONT;
            }
        }
    }
///LEWA END

    private boolean parseApplication(Package owner, Resources res,
            XmlPullParser parser, AttributeSet attrs, int flags, String[] outError)
        throws XmlPullParserException, IOException {
        final ApplicationInfo ai = owner.applicationInfo;
        final String pkgName = owner.applicationInfo.packageName;

        TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifestApplication);

        String name = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestApplication_name, 0);
        if (name != null) {
            ai.className = buildClassName(pkgName, name, outError);
            if (ai.className == null) {
                sa.recycle();
                mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                return false;
            }
        }

        String manageSpaceActivity = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestApplication_manageSpaceActivity, 0);
        if (manageSpaceActivity != null) {
            ai.manageSpaceActivityName = buildClassName(pkgName, manageSpaceActivity,
                    outError);
        }

        boolean allowBackup = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_allowBackup, true);
        if (allowBackup) {
            ai.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;

            // backupAgent, killAfterRestore, and restoreAnyVersion are only relevant
            // if backup is possible for the given application.
            String backupAgent = sa.getNonConfigurationString(
                    com.android.internal.R.styleable.AndroidManifestApplication_backupAgent, 0);
            if (backupAgent != null) {
                ai.backupAgentName = buildClassName(pkgName, backupAgent, outError);
                if (DEBUG_BACKUP) {
                    Slog.v(TAG, "android:backupAgent = " + ai.backupAgentName
                            + " from " + pkgName + "+" + backupAgent);
                }

                if (sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestApplication_killAfterRestore,
                        true)) {
                    ai.flags |= ApplicationInfo.FLAG_KILL_AFTER_RESTORE;
                }
                if (sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestApplication_restoreAnyVersion,
                        false)) {
                    ai.flags |= ApplicationInfo.FLAG_RESTORE_ANY_VERSION;
                }
            }
        }

        TypedValue v = sa.peekValue(
                com.android.internal.R.styleable.AndroidManifestApplication_label);
        if (v != null && (ai.labelRes=v.resourceId) == 0) {
            ai.nonLocalizedLabel = v.coerceToString();
        }

        ai.icon = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestApplication_icon, 0);
        ai.logo = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestApplication_logo, 0);
        ai.theme = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestApplication_theme, 0);
        ai.descriptionRes = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestApplication_description, 0);

        if ((flags&PARSE_IS_SYSTEM) != 0) {
            if (sa.getBoolean(
                    com.android.internal.R.styleable.AndroidManifestApplication_persistent,
                    false)) {
                ai.flags |= ApplicationInfo.FLAG_PERSISTENT;
            }
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_debuggable,
                false)) {
            ai.flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_vmSafeMode,
                false)) {
            ai.flags |= ApplicationInfo.FLAG_VM_SAFE_MODE;
        }

        boolean hardwareAccelerated = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_hardwareAccelerated,
                owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.ICE_CREAM_SANDWICH);

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_hasCode,
                true)) {
            ai.flags |= ApplicationInfo.FLAG_HAS_CODE;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_allowTaskReparenting,
                false)) {
            ai.flags |= ApplicationInfo.FLAG_ALLOW_TASK_REPARENTING;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_allowClearUserData,
                true)) {
            ai.flags |= ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_testOnly,
                false)) {
            ai.flags |= ApplicationInfo.FLAG_TEST_ONLY;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_largeHeap,
                false)) {
            ai.flags |= ApplicationInfo.FLAG_LARGE_HEAP;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_supportsRtl,
                false /* default is no RTL support*/)) {
            ai.flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        }

        String str;
        str = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestApplication_permission, 0);
        ai.permission = (str != null && str.length() > 0) ? str.intern() : null;

        if (owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.FROYO) {
            str = sa.getNonConfigurationString(
                    com.android.internal.R.styleable.AndroidManifestApplication_taskAffinity, 0);
        } else {
            // Some older apps have been seen to use a resource reference
            // here that on older builds was ignored (with a warning).  We
            // need to continue to do this for them so they don't break.
            str = sa.getNonResourceString(
                    com.android.internal.R.styleable.AndroidManifestApplication_taskAffinity);
        }
        ai.taskAffinity = buildTaskAffinityName(ai.packageName, ai.packageName,
                str, outError);

        if (outError[0] == null) {
            CharSequence pname;
            if (owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.FROYO) {
                pname = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestApplication_process, 0);
            } else {
                // Some older apps have been seen to use a resource reference
                // here that on older builds was ignored (with a warning).  We
                // need to continue to do this for them so they don't break.
                pname = sa.getNonResourceString(
                        com.android.internal.R.styleable.AndroidManifestApplication_process);
            }
            ai.processName = buildProcessName(ai.packageName, null, pname,
                    flags, mSeparateProcesses, outError);
    
            ai.enabled = sa.getBoolean(
                    com.android.internal.R.styleable.AndroidManifestApplication_enabled, true);
            
            if (false) {
                if (sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestApplication_cantSaveState,
                        false)) {
                    ai.flags |= ApplicationInfo.FLAG_CANT_SAVE_STATE;

                    // A heavy-weight application can not be in a custom process.
                    // We can do direct compare because we intern all strings.
                    if (ai.processName != null && ai.processName != ai.packageName) {
                        outError[0] = "cantSaveState applications can not use custom processes";
                    }
                }
            }
        }

        ai.uiOptions = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestApplication_uiOptions, 0);

        sa.recycle();

        if (outError[0] != null) {
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }

        final int innerDepth = parser.getDepth();

        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > innerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("activity")) {
                Activity a = parseActivity(owner, res, parser, attrs, flags, outError, false,
                        hardwareAccelerated);
                if (a == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                owner.activities.add(a);

            } else if (tagName.equals("receiver")) {
                Activity a = parseActivity(owner, res, parser, attrs, flags, outError, true, false);
                if (a == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                owner.receivers.add(a);

            } else if (tagName.equals("service")) {
                Service s = parseService(owner, res, parser, attrs, flags, outError);
                if (s == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                owner.services.add(s);

            } else if (tagName.equals("provider")) {
                Provider p = parseProvider(owner, res, parser, attrs, flags, outError);
                if (p == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                owner.providers.add(p);

            } else if (tagName.equals("activity-alias")) {
                Activity a = parseActivityAlias(owner, res, parser, attrs, flags, outError);
                if (a == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                owner.activities.add(a);

            } else if (parser.getName().equals("meta-data")) {
                // note: application meta-data is stored off to the side, so it can
                // remain null in the primary copy (we like to avoid extra copies because
                // it can be large)
                if ((owner.mAppMetaData = parseMetaData(res, parser, attrs, owner.mAppMetaData,
                        outError)) == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

            } else if (tagName.equals("uses-library")) {
                sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.AndroidManifestUsesLibrary);

                // Note: don't allow this value to be a reference to a resource
                // that may change.
                String lname = sa.getNonResourceString(
                        com.android.internal.R.styleable.AndroidManifestUsesLibrary_name);
                boolean req = sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestUsesLibrary_required,
                        true);

                sa.recycle();

                if (lname != null) {
                    if (req) {
                        if (owner.usesLibraries == null) {
                            owner.usesLibraries = new ArrayList<String>();
                        }
                        if (!owner.usesLibraries.contains(lname)) {
                            owner.usesLibraries.add(lname.intern());
                        }
                    } else {
                        if (owner.usesOptionalLibraries == null) {
                            owner.usesOptionalLibraries = new ArrayList<String>();
                        }
                        if (!owner.usesOptionalLibraries.contains(lname)) {
                            owner.usesOptionalLibraries.add(lname.intern());
                        }
                    }
                }

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals("uses-package")) {
                // Dependencies for app installers; we don't currently try to
                // enforce this.
                XmlUtils.skipCurrentTag(parser);

            } else {
                if (!RIGID_PARSER) {
                    Slog.w(TAG, "Unknown element under <application>: " + tagName
                            + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under <application>: " + tagName;
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }
            }
        }

        return true;
    }

    private boolean parsePackageItemInfo(Package owner, PackageItemInfo outInfo,
            String[] outError, String tag, TypedArray sa,
            int nameRes, int labelRes, int iconRes, int logoRes) {
        String name = sa.getNonConfigurationString(nameRes, 0);
        if (name == null) {
            outError[0] = tag + " does not specify android:name";
            return false;
        }

        outInfo.name
            = buildClassName(owner.applicationInfo.packageName, name, outError);
        if (outInfo.name == null) {
            return false;
        }

        int iconVal = sa.getResourceId(iconRes, 0);
        if (iconVal != 0) {
            outInfo.icon = iconVal;
            outInfo.nonLocalizedLabel = null;
        }
        
        int logoVal = sa.getResourceId(logoRes, 0);
        if (logoVal != 0) {
            outInfo.logo = logoVal;
        }

        TypedValue v = sa.peekValue(labelRes);
        if (v != null && (outInfo.labelRes=v.resourceId) == 0) {
            outInfo.nonLocalizedLabel = v.coerceToString();
        }

        outInfo.packageName = owner.packageName;

        return true;
    }

    private Activity parseActivity(Package owner, Resources res,
            XmlPullParser parser, AttributeSet attrs, int flags, String[] outError,
            boolean receiver, boolean hardwareAccelerated)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifestActivity);

        if (mParseActivityArgs == null) {
            mParseActivityArgs = new ParseComponentArgs(owner, outError,
                    com.android.internal.R.styleable.AndroidManifestActivity_name,
                    com.android.internal.R.styleable.AndroidManifestActivity_label,
                    com.android.internal.R.styleable.AndroidManifestActivity_icon,
                    com.android.internal.R.styleable.AndroidManifestActivity_logo,
                    mSeparateProcesses,
                    com.android.internal.R.styleable.AndroidManifestActivity_process,
                    com.android.internal.R.styleable.AndroidManifestActivity_description,
                    com.android.internal.R.styleable.AndroidManifestActivity_enabled);
        }
        
        mParseActivityArgs.tag = receiver ? "<receiver>" : "<activity>";
        mParseActivityArgs.sa = sa;
        mParseActivityArgs.flags = flags;
        
        Activity a = new Activity(mParseActivityArgs, new ActivityInfo());
        if (outError[0] != null) {
            sa.recycle();
            return null;
        }

        boolean setExported = sa.hasValue(
                com.android.internal.R.styleable.AndroidManifestActivity_exported);
        if (setExported) {
            a.info.exported = sa.getBoolean(
                    com.android.internal.R.styleable.AndroidManifestActivity_exported, false);
        }

        a.info.theme = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestActivity_theme, 0);

        a.info.uiOptions = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestActivity_uiOptions,
                a.info.applicationInfo.uiOptions);

        String parentName = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestActivity_parentActivityName, 0);
        if (parentName != null) {
            String parentClassName = buildClassName(a.info.packageName, parentName, outError);
            if (outError[0] == null) {
                a.info.parentActivityName = parentClassName;
            } else {
                Log.e(TAG, "Activity " + a.info.name + " specified invalid parentActivityName " +
                        parentName);
                outError[0] = null;
            }
        }

        String str;
        str = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestActivity_permission, 0);
        if (str == null) {
            a.info.permission = owner.applicationInfo.permission;
        } else {
            a.info.permission = str.length() > 0 ? str.toString().intern() : null;
        }

        str = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestActivity_taskAffinity, 0);
        a.info.taskAffinity = buildTaskAffinityName(owner.applicationInfo.packageName,
                owner.applicationInfo.taskAffinity, str, outError);

        a.info.flags = 0;
        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestActivity_multiprocess,
                false)) {
            a.info.flags |= ActivityInfo.FLAG_MULTIPROCESS;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestActivity_finishOnTaskLaunch,
                false)) {
            a.info.flags |= ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestActivity_clearTaskOnLaunch,
                false)) {
            a.info.flags |= ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestActivity_noHistory,
                false)) {
            a.info.flags |= ActivityInfo.FLAG_NO_HISTORY;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestActivity_alwaysRetainTaskState,
                false)) {
            a.info.flags |= ActivityInfo.FLAG_ALWAYS_RETAIN_TASK_STATE;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestActivity_stateNotNeeded,
                false)) {
            a.info.flags |= ActivityInfo.FLAG_STATE_NOT_NEEDED;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestActivity_excludeFromRecents,
                false)) {
            a.info.flags |= ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestActivity_allowTaskReparenting,
                (owner.applicationInfo.flags&ApplicationInfo.FLAG_ALLOW_TASK_REPARENTING) != 0)) {
            a.info.flags |= ActivityInfo.FLAG_ALLOW_TASK_REPARENTING;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestActivity_finishOnCloseSystemDialogs,
                false)) {
            a.info.flags |= ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestActivity_showOnLockScreen,
                false)) {
            a.info.flags |= ActivityInfo.FLAG_SHOW_ON_LOCK_SCREEN;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestActivity_immersive,
                false)) {
            a.info.flags |= ActivityInfo.FLAG_IMMERSIVE;
        }

        if (!receiver) {
            if (sa.getBoolean(
                    com.android.internal.R.styleable.AndroidManifestActivity_hardwareAccelerated,
                    hardwareAccelerated)) {
                a.info.flags |= ActivityInfo.FLAG_HARDWARE_ACCELERATED;
            }

            a.info.launchMode = sa.getInt(
                    com.android.internal.R.styleable.AndroidManifestActivity_launchMode,
                    ActivityInfo.LAUNCH_MULTIPLE);
            a.info.screenOrientation = sa.getInt(
                    com.android.internal.R.styleable.AndroidManifestActivity_screenOrientation,
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            a.info.configChanges = sa.getInt(
                    com.android.internal.R.styleable.AndroidManifestActivity_configChanges,
                    0);
            a.info.softInputMode = sa.getInt(
                    com.android.internal.R.styleable.AndroidManifestActivity_windowSoftInputMode,
                    0);
        } else {
            a.info.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
            a.info.configChanges = 0;
        }

        if (receiver) {
            if (sa.getBoolean(
                    com.android.internal.R.styleable.AndroidManifestActivity_singleUser,
                    false)) {
                a.info.flags |= ActivityInfo.FLAG_SINGLE_USER;
                if (a.info.exported) {
                    Slog.w(TAG, "Activity exported request ignored due to singleUser: "
                            + a.className + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    a.info.exported = false;
                }
                setExported = true;
            }
            if (sa.getBoolean(
                    com.android.internal.R.styleable.AndroidManifestActivity_primaryUserOnly,
                    false)) {
                a.info.flags |= ActivityInfo.FLAG_PRIMARY_USER_ONLY;
            }
        }

        sa.recycle();

        if (receiver && (owner.applicationInfo.flags&ApplicationInfo.FLAG_CANT_SAVE_STATE) != 0) {
            // A heavy-weight application can not have receives in its main process
            // We can do direct compare because we intern all strings.
            if (a.info.processName == owner.packageName) {
                outError[0] = "Heavy-weight applications can not have receivers in main process";
            }
        }
        
        if (outError[0] != null) {
            return null;
        }

///LEWA BEGIN
        parseActivityThemeAttributes(parser, attrs, a.info);
///LEWA END

        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("intent-filter")) {
                ActivityIntentInfo intent = new ActivityIntentInfo(a);
                if (!parseIntent(res, parser, attrs, flags, intent, outError, !receiver)) {
                    return null;
                }
                if (intent.countActions() == 0) {
                    Slog.w(TAG, "No actions in intent filter at "
                            + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                } else {
                    a.intents.add(intent);
                }
            } else if (parser.getName().equals("meta-data")) {
                if ((a.metaData=parseMetaData(res, parser, attrs, a.metaData,
                        outError)) == null) {
                    return null;
                }
            } else {
                if (!RIGID_PARSER) {
                    Slog.w(TAG, "Problem in package " + mArchiveSourcePath + ":");
                    if (receiver) {
                        Slog.w(TAG, "Unknown element under <receiver>: " + parser.getName()
                                + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                    } else {
                        Slog.w(TAG, "Unknown element under <activity>: " + parser.getName()
                                + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    if (receiver) {
                        outError[0] = "Bad element under <receiver>: " + parser.getName();
                    } else {
                        outError[0] = "Bad element under <activity>: " + parser.getName();
                    }
                    return null;
                }
            }
        }

        if (!setExported) {
            a.info.exported = a.intents.size() > 0;
        }

        return a;
    }

    private Activity parseActivityAlias(Package owner, Resources res,
            XmlPullParser parser, AttributeSet attrs, int flags, String[] outError)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifestActivityAlias);

        String targetActivity = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestActivityAlias_targetActivity, 0);
        if (targetActivity == null) {
            outError[0] = "<activity-alias> does not specify android:targetActivity";
            sa.recycle();
            return null;
        }

        targetActivity = buildClassName(owner.applicationInfo.packageName,
                targetActivity, outError);
        if (targetActivity == null) {
            sa.recycle();
            return null;
        }

        if (mParseActivityAliasArgs == null) {
            mParseActivityAliasArgs = new ParseComponentArgs(owner, outError,
                    com.android.internal.R.styleable.AndroidManifestActivityAlias_name,
                    com.android.internal.R.styleable.AndroidManifestActivityAlias_label,
                    com.android.internal.R.styleable.AndroidManifestActivityAlias_icon,
                    com.android.internal.R.styleable.AndroidManifestActivityAlias_logo,
                    mSeparateProcesses,
                    0,
                    com.android.internal.R.styleable.AndroidManifestActivityAlias_description,
                    com.android.internal.R.styleable.AndroidManifestActivityAlias_enabled);
            mParseActivityAliasArgs.tag = "<activity-alias>";
        }
        
        mParseActivityAliasArgs.sa = sa;
        mParseActivityAliasArgs.flags = flags;
        
        Activity target = null;

        final int NA = owner.activities.size();
        for (int i=0; i<NA; i++) {
            Activity t = owner.activities.get(i);
            if (targetActivity.equals(t.info.name)) {
                target = t;
                break;
            }
        }

        if (target == null) {
            outError[0] = "<activity-alias> target activity " + targetActivity
                    + " not found in manifest";
            sa.recycle();
            return null;
        }

        ActivityInfo info = new ActivityInfo();
        info.targetActivity = targetActivity;
        info.configChanges = target.info.configChanges;
        info.flags = target.info.flags;
        info.icon = target.info.icon;
        info.logo = target.info.logo;
        info.labelRes = target.info.labelRes;
        info.nonLocalizedLabel = target.info.nonLocalizedLabel;
        info.launchMode = target.info.launchMode;
        info.processName = target.info.processName;
        if (info.descriptionRes == 0) {
            info.descriptionRes = target.info.descriptionRes;
        }
        info.screenOrientation = target.info.screenOrientation;
        info.taskAffinity = target.info.taskAffinity;
        info.theme = target.info.theme;
        info.softInputMode = target.info.softInputMode;
        info.uiOptions = target.info.uiOptions;
        info.parentActivityName = target.info.parentActivityName;
        
        Activity a = new Activity(mParseActivityAliasArgs, info);
        if (outError[0] != null) {
            sa.recycle();
            return null;
        }

        final boolean setExported = sa.hasValue(
                com.android.internal.R.styleable.AndroidManifestActivityAlias_exported);
        if (setExported) {
            a.info.exported = sa.getBoolean(
                    com.android.internal.R.styleable.AndroidManifestActivityAlias_exported, false);
        }

        String str;
        str = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestActivityAlias_permission, 0);
        if (str != null) {
            a.info.permission = str.length() > 0 ? str.toString().intern() : null;
        }

        String parentName = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestActivityAlias_parentActivityName,
                0);
        if (parentName != null) {
            String parentClassName = buildClassName(a.info.packageName, parentName, outError);
            if (outError[0] == null) {
                a.info.parentActivityName = parentClassName;
            } else {
                Log.e(TAG, "Activity alias " + a.info.name +
                        " specified invalid parentActivityName " + parentName);
                outError[0] = null;
            }
        }

        sa.recycle();

        if (outError[0] != null) {
            return null;
        }

        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("intent-filter")) {
                ActivityIntentInfo intent = new ActivityIntentInfo(a);
                if (!parseIntent(res, parser, attrs, flags, intent, outError, true)) {
                    return null;
                }
                if (intent.countActions() == 0) {
                    Slog.w(TAG, "No actions in intent filter at "
                            + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                } else {
                    a.intents.add(intent);
                }
            } else if (parser.getName().equals("meta-data")) {
                if ((a.metaData=parseMetaData(res, parser, attrs, a.metaData,
                        outError)) == null) {
                    return null;
                }
            } else {
                if (!RIGID_PARSER) {
                    Slog.w(TAG, "Unknown element under <activity-alias>: " + parser.getName()
                            + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under <activity-alias>: " + parser.getName();
                    return null;
                }
            }
        }

        if (!setExported) {
            a.info.exported = a.intents.size() > 0;
        }

        return a;
    }

    private Provider parseProvider(Package owner, Resources res,
            XmlPullParser parser, AttributeSet attrs, int flags, String[] outError)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifestProvider);

        if (mParseProviderArgs == null) {
            mParseProviderArgs = new ParseComponentArgs(owner, outError,
                    com.android.internal.R.styleable.AndroidManifestProvider_name,
                    com.android.internal.R.styleable.AndroidManifestProvider_label,
                    com.android.internal.R.styleable.AndroidManifestProvider_icon,
                    com.android.internal.R.styleable.AndroidManifestProvider_logo,
                    mSeparateProcesses,
                    com.android.internal.R.styleable.AndroidManifestProvider_process,
                    com.android.internal.R.styleable.AndroidManifestProvider_description,
                    com.android.internal.R.styleable.AndroidManifestProvider_enabled);
            mParseProviderArgs.tag = "<provider>";
        }
        
        mParseProviderArgs.sa = sa;
        mParseProviderArgs.flags = flags;
        
        Provider p = new Provider(mParseProviderArgs, new ProviderInfo());
        if (outError[0] != null) {
            sa.recycle();
            return null;
        }

        boolean providerExportedDefault = false;

        if (owner.applicationInfo.targetSdkVersion < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            // For compatibility, applications targeting API level 16 or lower
            // should have their content providers exported by default, unless they
            // specify otherwise.
            providerExportedDefault = true;
        }

        p.info.exported = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestProvider_exported,
                providerExportedDefault);

        String cpname = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestProvider_authorities, 0);

        p.info.isSyncable = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestProvider_syncable,
                false);

        String permission = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestProvider_permission, 0);
        String str = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestProvider_readPermission, 0);
        if (str == null) {
            str = permission;
        }
        if (str == null) {
            p.info.readPermission = owner.applicationInfo.permission;
        } else {
            p.info.readPermission =
                str.length() > 0 ? str.toString().intern() : null;
        }
        str = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestProvider_writePermission, 0);
        if (str == null) {
            str = permission;
        }
        if (str == null) {
            p.info.writePermission = owner.applicationInfo.permission;
        } else {
            p.info.writePermission =
                str.length() > 0 ? str.toString().intern() : null;
        }

        p.info.grantUriPermissions = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestProvider_grantUriPermissions,
                false);

        p.info.multiprocess = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestProvider_multiprocess,
                false);

        p.info.initOrder = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestProvider_initOrder,
                0);

        p.info.flags = 0;

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestProvider_singleUser,
                false)) {
            p.info.flags |= ProviderInfo.FLAG_SINGLE_USER;
            if (p.info.exported) {
                Slog.w(TAG, "Provider exported request ignored due to singleUser: "
                        + p.className + " at " + mArchiveSourcePath + " "
                        + parser.getPositionDescription());
                p.info.exported = false;
            }
        }

        sa.recycle();

        if ((owner.applicationInfo.flags&ApplicationInfo.FLAG_CANT_SAVE_STATE) != 0) {
            // A heavy-weight application can not have providers in its main process
            // We can do direct compare because we intern all strings.
            if (p.info.processName == owner.packageName) {
                outError[0] = "Heavy-weight applications can not have providers in main process";
                return null;
            }
        }
        
        if (cpname == null) {
            outError[0] = "<provider> does not include authorities attribute";
            return null;
        }
        p.info.authority = cpname.intern();

        if (!parseProviderTags(res, parser, attrs, p, outError)) {
            return null;
        }

        return p;
    }

    private boolean parseProviderTags(Resources res,
            XmlPullParser parser, AttributeSet attrs,
            Provider outInfo, String[] outError)
            throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("meta-data")) {
                if ((outInfo.metaData=parseMetaData(res, parser, attrs,
                        outInfo.metaData, outError)) == null) {
                    return false;
                }
                
            } else if (parser.getName().equals("grant-uri-permission")) {
                TypedArray sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.AndroidManifestGrantUriPermission);

                PatternMatcher pa = null;

                String str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestGrantUriPermission_path, 0);
                if (str != null) {
                    pa = new PatternMatcher(str, PatternMatcher.PATTERN_LITERAL);
                }

                str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestGrantUriPermission_pathPrefix, 0);
                if (str != null) {
                    pa = new PatternMatcher(str, PatternMatcher.PATTERN_PREFIX);
                }

                str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestGrantUriPermission_pathPattern, 0);
                if (str != null) {
                    pa = new PatternMatcher(str, PatternMatcher.PATTERN_SIMPLE_GLOB);
                }
                
                sa.recycle();

                if (pa != null) {
                    if (outInfo.info.uriPermissionPatterns == null) {
                        outInfo.info.uriPermissionPatterns = new PatternMatcher[1];
                        outInfo.info.uriPermissionPatterns[0] = pa;
                    } else {
                        final int N = outInfo.info.uriPermissionPatterns.length;
                        PatternMatcher[] newp = new PatternMatcher[N+1];
                        System.arraycopy(outInfo.info.uriPermissionPatterns, 0, newp, 0, N);
                        newp[N] = pa;
                        outInfo.info.uriPermissionPatterns = newp;
                    }
                    outInfo.info.grantUriPermissions = true;
                } else {
                    if (!RIGID_PARSER) {
                        Slog.w(TAG, "Unknown element under <path-permission>: "
                                + parser.getName() + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    } else {
                        outError[0] = "No path, pathPrefix, or pathPattern for <path-permission>";
                        return false;
                    }
                }
                XmlUtils.skipCurrentTag(parser);

            } else if (parser.getName().equals("path-permission")) {
                TypedArray sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.AndroidManifestPathPermission);

                PathPermission pa = null;

                String permission = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestPathPermission_permission, 0);
                String readPermission = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestPathPermission_readPermission, 0);
                if (readPermission == null) {
                    readPermission = permission;
                }
                String writePermission = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestPathPermission_writePermission, 0);
                if (writePermission == null) {
                    writePermission = permission;
                }
                
                boolean havePerm = false;
                if (readPermission != null) {
                    readPermission = readPermission.intern();
                    havePerm = true;
                }
                if (writePermission != null) {
                    writePermission = writePermission.intern();
                    havePerm = true;
                }

                if (!havePerm) {
                    if (!RIGID_PARSER) {
                        Slog.w(TAG, "No readPermission or writePermssion for <path-permission>: "
                                + parser.getName() + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    } else {
                        outError[0] = "No readPermission or writePermssion for <path-permission>";
                        return false;
                    }
                }
                
                String path = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestPathPermission_path, 0);
                if (path != null) {
                    pa = new PathPermission(path,
                            PatternMatcher.PATTERN_LITERAL, readPermission, writePermission);
                }

                path = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestPathPermission_pathPrefix, 0);
                if (path != null) {
                    pa = new PathPermission(path,
                            PatternMatcher.PATTERN_PREFIX, readPermission, writePermission);
                }

                path = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestPathPermission_pathPattern, 0);
                if (path != null) {
                    pa = new PathPermission(path,
                            PatternMatcher.PATTERN_SIMPLE_GLOB, readPermission, writePermission);
                }

                sa.recycle();

                if (pa != null) {
                    if (outInfo.info.pathPermissions == null) {
                        outInfo.info.pathPermissions = new PathPermission[1];
                        outInfo.info.pathPermissions[0] = pa;
                    } else {
                        final int N = outInfo.info.pathPermissions.length;
                        PathPermission[] newp = new PathPermission[N+1];
                        System.arraycopy(outInfo.info.pathPermissions, 0, newp, 0, N);
                        newp[N] = pa;
                        outInfo.info.pathPermissions = newp;
                    }
                } else {
                    if (!RIGID_PARSER) {
                        Slog.w(TAG, "No path, pathPrefix, or pathPattern for <path-permission>: "
                                + parser.getName() + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    outError[0] = "No path, pathPrefix, or pathPattern for <path-permission>";
                    return false;
                }
                XmlUtils.skipCurrentTag(parser);

            } else {
                if (!RIGID_PARSER) {
                    Slog.w(TAG, "Unknown element under <provider>: "
                            + parser.getName() + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under <provider>: " + parser.getName();
                    return false;
                }
            }
        }
        return true;
    }

    private Service parseService(Package owner, Resources res,
            XmlPullParser parser, AttributeSet attrs, int flags, String[] outError)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifestService);

        if (mParseServiceArgs == null) {
            mParseServiceArgs = new ParseComponentArgs(owner, outError,
                    com.android.internal.R.styleable.AndroidManifestService_name,
                    com.android.internal.R.styleable.AndroidManifestService_label,
                    com.android.internal.R.styleable.AndroidManifestService_icon,
                    com.android.internal.R.styleable.AndroidManifestService_logo,
                    mSeparateProcesses,
                    com.android.internal.R.styleable.AndroidManifestService_process,
                    com.android.internal.R.styleable.AndroidManifestService_description,
                    com.android.internal.R.styleable.AndroidManifestService_enabled);
            mParseServiceArgs.tag = "<service>";
        }
        
        mParseServiceArgs.sa = sa;
        mParseServiceArgs.flags = flags;
        
        Service s = new Service(mParseServiceArgs, new ServiceInfo());
        if (outError[0] != null) {
            sa.recycle();
            return null;
        }

        boolean setExported = sa.hasValue(
                com.android.internal.R.styleable.AndroidManifestService_exported);
        if (setExported) {
            s.info.exported = sa.getBoolean(
                    com.android.internal.R.styleable.AndroidManifestService_exported, false);
        }

        String str = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestService_permission, 0);
        if (str == null) {
            s.info.permission = owner.applicationInfo.permission;
        } else {
            s.info.permission = str.length() > 0 ? str.toString().intern() : null;
        }

        s.info.flags = 0;
        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestService_stopWithTask,
                false)) {
            s.info.flags |= ServiceInfo.FLAG_STOP_WITH_TASK;
        }
        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestService_isolatedProcess,
                false)) {
            s.info.flags |= ServiceInfo.FLAG_ISOLATED_PROCESS;
        }
        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestService_singleUser,
                false)) {
            s.info.flags |= ServiceInfo.FLAG_SINGLE_USER;
            if (s.info.exported) {
                Slog.w(TAG, "Service exported request ignored due to singleUser: "
                        + s.className + " at " + mArchiveSourcePath + " "
                        + parser.getPositionDescription());
                s.info.exported = false;
            }
            setExported = true;
        }

        sa.recycle();

        if ((owner.applicationInfo.flags&ApplicationInfo.FLAG_CANT_SAVE_STATE) != 0) {
            // A heavy-weight application can not have services in its main process
            // We can do direct compare because we intern all strings.
            if (s.info.processName == owner.packageName) {
                outError[0] = "Heavy-weight applications can not have services in main process";
                return null;
            }
        }
        
        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("intent-filter")) {
                ServiceIntentInfo intent = new ServiceIntentInfo(s);
                if (!parseIntent(res, parser, attrs, flags, intent, outError, false)) {
                    return null;
                }

                s.intents.add(intent);
            } else if (parser.getName().equals("meta-data")) {
                if ((s.metaData=parseMetaData(res, parser, attrs, s.metaData,
                        outError)) == null) {
                    return null;
                }
            } else {
                if (!RIGID_PARSER) {
                    Slog.w(TAG, "Unknown element under <service>: "
                            + parser.getName() + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under <service>: " + parser.getName();
                    return null;
                }
            }
        }

        if (!setExported) {
            s.info.exported = s.intents.size() > 0;
        }

        return s;
    }

    private boolean parseAllMetaData(Resources res,
            XmlPullParser parser, AttributeSet attrs, String tag,
            Component outInfo, String[] outError)
            throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("meta-data")) {
                if ((outInfo.metaData=parseMetaData(res, parser, attrs,
                        outInfo.metaData, outError)) == null) {
                    return false;
                }
            } else {
                if (!RIGID_PARSER) {
                    Slog.w(TAG, "Unknown element under " + tag + ": "
                            + parser.getName() + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under " + tag + ": " + parser.getName();
                    return false;
                }
            }
        }
        return true;
    }

    private Bundle parseMetaData(Resources res,
            XmlPullParser parser, AttributeSet attrs,
            Bundle data, String[] outError)
            throws XmlPullParserException, IOException {

        TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifestMetaData);

        if (data == null) {
            data = new Bundle();
        }

        String name = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestMetaData_name, 0);
        if (name == null) {
            outError[0] = "<meta-data> requires an android:name attribute";
            sa.recycle();
            return null;
        }

        name = name.intern();
        
        TypedValue v = sa.peekValue(
                com.android.internal.R.styleable.AndroidManifestMetaData_resource);
        if (v != null && v.resourceId != 0) {
            //Slog.i(TAG, "Meta data ref " + name + ": " + v);
            data.putInt(name, v.resourceId);
        } else {
            v = sa.peekValue(
                    com.android.internal.R.styleable.AndroidManifestMetaData_value);
            //Slog.i(TAG, "Meta data " + name + ": " + v);
            if (v != null) {
                if (v.type == TypedValue.TYPE_STRING) {
                    CharSequence cs = v.coerceToString();
                    data.putString(name, cs != null ? cs.toString().intern() : null);
                } else if (v.type == TypedValue.TYPE_INT_BOOLEAN) {
                    data.putBoolean(name, v.data != 0);
                } else if (v.type >= TypedValue.TYPE_FIRST_INT
                        && v.type <= TypedValue.TYPE_LAST_INT) {
                    data.putInt(name, v.data);
                } else if (v.type == TypedValue.TYPE_FLOAT) {
                    data.putFloat(name, v.getFloat());
                } else {
                    if (!RIGID_PARSER) {
                        Slog.w(TAG, "<meta-data> only supports string, integer, float, color, boolean, and resource reference types: "
                                + parser.getName() + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                    } else {
                        outError[0] = "<meta-data> only supports string, integer, float, color, boolean, and resource reference types";
                        data = null;
                    }
                }
            } else {
                outError[0] = "<meta-data> requires an android:value or android:resource attribute";
                data = null;
            }
        }

        sa.recycle();

        XmlUtils.skipCurrentTag(parser);

        return data;
    }

    private static VerifierInfo parseVerifier(Resources res, XmlPullParser parser,
            AttributeSet attrs, int flags, String[] outError) throws XmlPullParserException,
            IOException {
        final TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifestPackageVerifier);

        final String packageName = sa.getNonResourceString(
                com.android.internal.R.styleable.AndroidManifestPackageVerifier_name);

        final String encodedPublicKey = sa.getNonResourceString(
                com.android.internal.R.styleable.AndroidManifestPackageVerifier_publicKey);

        sa.recycle();

        if (packageName == null || packageName.length() == 0) {
            Slog.i(TAG, "verifier package name was null; skipping");
            return null;
        } else if (encodedPublicKey == null) {
            Slog.i(TAG, "verifier " + packageName + " public key was null; skipping");
        }

        EncodedKeySpec keySpec;
        try {
            final byte[] encoded = Base64.decode(encodedPublicKey, Base64.DEFAULT);
            keySpec = new X509EncodedKeySpec(encoded);
        } catch (IllegalArgumentException e) {
            Slog.i(TAG, "Could not parse verifier " + packageName + " public key; invalid Base64");
            return null;
        }

        /* First try the key as an RSA key. */
        try {
            final KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            final PublicKey publicKey = keyFactory.generatePublic(keySpec);
            return new VerifierInfo(packageName, publicKey);
        } catch (NoSuchAlgorithmException e) {
            Log.wtf(TAG, "Could not parse public key because RSA isn't included in build");
            return null;
        } catch (InvalidKeySpecException e) {
            // Not a RSA public key.
        }

        /* Now try it as a DSA key. */
        try {
            final KeyFactory keyFactory = KeyFactory.getInstance("DSA");
            final PublicKey publicKey = keyFactory.generatePublic(keySpec);
            return new VerifierInfo(packageName, publicKey);
        } catch (NoSuchAlgorithmException e) {
            Log.wtf(TAG, "Could not parse public key because DSA isn't included in build");
            return null;
        } catch (InvalidKeySpecException e) {
            // Not a DSA public key.
        }

        return null;
    }

    private static final String ANDROID_RESOURCES
            = "http://schemas.android.com/apk/res/android";

    private boolean parseIntent(Resources res,
            XmlPullParser parser, AttributeSet attrs, int flags,
            IntentInfo outInfo, String[] outError, boolean isActivity)
            throws XmlPullParserException, IOException {

        TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifestIntentFilter);

        int priority = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestIntentFilter_priority, 0);
        outInfo.setPriority(priority);

        TypedValue v = sa.peekValue(
                com.android.internal.R.styleable.AndroidManifestIntentFilter_label);
        if (v != null && (outInfo.labelRes=v.resourceId) == 0) {
            outInfo.nonLocalizedLabel = v.coerceToString();
        }

        outInfo.icon = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestIntentFilter_icon, 0);
        
        outInfo.logo = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestIntentFilter_logo, 0);

        sa.recycle();

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String nodeName = parser.getName();
            if (nodeName.equals("action")) {
                String value = attrs.getAttributeValue(
                        ANDROID_RESOURCES, "name");
                if (value == null || value == "") {
                    outError[0] = "No value supplied for <android:name>";
                    return false;
                }
                XmlUtils.skipCurrentTag(parser);

                outInfo.addAction(value);
            } else if (nodeName.equals("category")) {
                String value = attrs.getAttributeValue(
                        ANDROID_RESOURCES, "name");
                if (value == null || value == "") {
                    outError[0] = "No value supplied for <android:name>";
                    return false;
                }
                XmlUtils.skipCurrentTag(parser);

                outInfo.addCategory(value);

            } else if (nodeName.equals("data")) {
                sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.AndroidManifestData);

                String str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_mimeType, 0);
                if (str != null) {
                    try {
                        outInfo.addDataType(str);
                    } catch (IntentFilter.MalformedMimeTypeException e) {
                        outError[0] = e.toString();
                        sa.recycle();
                        return false;
                    }
                }

                str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_scheme, 0);
                if (str != null) {
                    outInfo.addDataScheme(str);
                }

                String host = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_host, 0);
                String port = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_port, 0);
                if (host != null) {
                    outInfo.addDataAuthority(host, port);
                }

                str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_path, 0);
                if (str != null) {
                    outInfo.addDataPath(str, PatternMatcher.PATTERN_LITERAL);
                }

                str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_pathPrefix, 0);
                if (str != null) {
                    outInfo.addDataPath(str, PatternMatcher.PATTERN_PREFIX);
                }

                str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_pathPattern, 0);
                if (str != null) {
                    outInfo.addDataPath(str, PatternMatcher.PATTERN_SIMPLE_GLOB);
                }

                sa.recycle();
                XmlUtils.skipCurrentTag(parser);
            } else if (!RIGID_PARSER) {
                Slog.w(TAG, "Unknown element under <intent-filter>: "
                        + parser.getName() + " at " + mArchiveSourcePath + " "
                        + parser.getPositionDescription());
                XmlUtils.skipCurrentTag(parser);
            } else {
                outError[0] = "Bad element under <intent-filter>: " + parser.getName();
                return false;
            }
        }

        outInfo.hasDefault = outInfo.hasCategory(Intent.CATEGORY_DEFAULT);

        if (DEBUG_PARSER) {
            final StringBuilder cats = new StringBuilder("Intent d=");
            cats.append(outInfo.hasDefault);
            cats.append(", cat=");

            final Iterator<String> it = outInfo.categoriesIterator();
            if (it != null) {
                while (it.hasNext()) {
                    cats.append(' ');
                    cats.append(it.next());
                }
            }
            Slog.d(TAG, cats.toString());
        }

        return true;
    }

    public final static class Package {
        public String packageName;

        // For now we only support one application per package.
        public final ApplicationInfo applicationInfo = new ApplicationInfo();

        public final ArrayList<Permission> permissions = new ArrayList<Permission>(0);
        public final ArrayList<PermissionGroup> permissionGroups = new ArrayList<PermissionGroup>(0);
        public final ArrayList<Activity> activities = new ArrayList<Activity>(0);
        public final ArrayList<Activity> receivers = new ArrayList<Activity>(0);
        public final ArrayList<Provider> providers = new ArrayList<Provider>(0);
        public final ArrayList<Service> services = new ArrayList<Service>(0);
        public final ArrayList<Instrumentation> instrumentation = new ArrayList<Instrumentation>(0);

        public final ArrayList<String> requestedPermissions = new ArrayList<String>();
        public final ArrayList<Boolean> requestedPermissionsRequired = new ArrayList<Boolean>();

        public ArrayList<String> protectedBroadcasts;
        
        public ArrayList<String> usesLibraries = null;
        public ArrayList<String> usesOptionalLibraries = null;
        public String[] usesLibraryFiles = null;

        public ArrayList<String> mOriginalPackages = null;
        public String mRealPackage = null;
        public ArrayList<String> mAdoptPermissions = null;
        
        // We store the application meta-data independently to avoid multiple unwanted references
        public Bundle mAppMetaData = null;

        // If this is a 3rd party app, this is the path of the zip file.
        public String mPath;

        // The version code declared for this package.
        public int mVersionCode;
        
        // The version name declared for this package.
        public String mVersionName;
        
        // The shared user id that this package wants to use.
        public String mSharedUserId;

        /** M: [ALPS00295677][MP Feature Patch Back]Theme manager on ICS feature patch back @{ */
        /** @hide */
        public int isThemePackage;
        /** @hide */
        public int mThemeNameId;
        /** @} */

        // The shared user label that this package wants to use.
        public int mSharedUserLabel;

        // Signatures that were read from the package.
        public Signature mSignatures[];

        // For use by package manager service for quick lookup of
        // preferred up order.
        public int mPreferredOrder = 0;

        // For use by the package manager to keep track of the path to the
        // file an app came from.
        public String mScanPath;
        
        // For use by package manager to keep track of where it has done dexopt.
        public boolean mDidDexOpt;

///LEWA BEGIN
        // Is Theme Apk
        public boolean mIsThemeApk = false;
        
        // Theme info
        public final ArrayList<ThemeInfo> mThemeInfos = new ArrayList<ThemeInfo>(0);
///LEWA END

        // // User set enabled state.
        // public int mSetEnabled = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        //
        // // Whether the package has been stopped.
        // public boolean mSetStopped = false;

        // Additional data supplied by callers.
        public Object mExtras;

        // Whether an operation is currently pending on this package
        public boolean mOperationPending;

        /*
         *  Applications hardware preferences
         */
        public final ArrayList<ConfigurationInfo> configPreferences =
                new ArrayList<ConfigurationInfo>();

        /*
         *  Applications requested features
         */
        public ArrayList<FeatureInfo> reqFeatures = null;

        public int installLocation;

        /**
         * Digest suitable for comparing whether this package's manifest is the
         * same as another.
         */
        public ManifestDigest manifestDigest;

        public Package(String _name) {
            packageName = _name;
            applicationInfo.packageName = _name;
            applicationInfo.uid = -1;
        }

        public void setPackageName(String newName) {
            packageName = newName;
            applicationInfo.packageName = newName;
            for (int i=permissions.size()-1; i>=0; i--) {
                permissions.get(i).setPackageName(newName);
            }
            for (int i=permissionGroups.size()-1; i>=0; i--) {
                permissionGroups.get(i).setPackageName(newName);
            }
            for (int i=activities.size()-1; i>=0; i--) {
                activities.get(i).setPackageName(newName);
            }
            for (int i=receivers.size()-1; i>=0; i--) {
                receivers.get(i).setPackageName(newName);
            }
            for (int i=providers.size()-1; i>=0; i--) {
                providers.get(i).setPackageName(newName);
            }
            for (int i=services.size()-1; i>=0; i--) {
                services.get(i).setPackageName(newName);
            }
            for (int i=instrumentation.size()-1; i>=0; i--) {
                instrumentation.get(i).setPackageName(newName);
            }
        }

        public boolean hasComponentClassName(String name) {
            for (int i=activities.size()-1; i>=0; i--) {
                if (name.equals(activities.get(i).className)) {
                    return true;
                }
            }
            for (int i=receivers.size()-1; i>=0; i--) {
                if (name.equals(receivers.get(i).className)) {
                    return true;
                }
            }
            for (int i=providers.size()-1; i>=0; i--) {
                if (name.equals(providers.get(i).className)) {
                    return true;
                }
            }
            for (int i=services.size()-1; i>=0; i--) {
                if (name.equals(services.get(i).className)) {
                    return true;
                }
            }
            for (int i=instrumentation.size()-1; i>=0; i--) {
                if (name.equals(instrumentation.get(i).className)) {
                    return true;
                }
            }
            return false;
        }

        public String toString() {
            return "Package{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + packageName + "}";
        }
    }

    public static class Component<II extends IntentInfo> {
        public final Package owner;
        public final ArrayList<II> intents;
        public final String className;
        public Bundle metaData;

        ComponentName componentName;
        String componentShortName;
        
        public Component(Package _owner) {
            owner = _owner;
            intents = null;
            className = null;
        }

        public Component(final ParsePackageItemArgs args, final PackageItemInfo outInfo) {
            owner = args.owner;
            intents = new ArrayList<II>(0);
            String name = args.sa.getNonConfigurationString(args.nameRes, 0);
            if (name == null) {
                className = null;
                args.outError[0] = args.tag + " does not specify android:name";
                return;
            }

            outInfo.name
                = buildClassName(owner.applicationInfo.packageName, name, args.outError);
            if (outInfo.name == null) {
                className = null;
                args.outError[0] = args.tag + " does not have valid android:name";
                return;
            }

            className = outInfo.name;

            int iconVal = args.sa.getResourceId(args.iconRes, 0);
            if (iconVal != 0) {
                outInfo.icon = iconVal;
                outInfo.nonLocalizedLabel = null;
            }
            
            int logoVal = args.sa.getResourceId(args.logoRes, 0);
            if (logoVal != 0) {
                outInfo.logo = logoVal;
            }

            TypedValue v = args.sa.peekValue(args.labelRes);
            if (v != null && (outInfo.labelRes=v.resourceId) == 0) {
                outInfo.nonLocalizedLabel = v.coerceToString();
            }

            outInfo.packageName = owner.packageName;
        }

        public Component(final ParseComponentArgs args, final ComponentInfo outInfo) {
            this(args, (PackageItemInfo)outInfo);
            if (args.outError[0] != null) {
                return;
            }

            if (args.processRes != 0) {
                CharSequence pname;
                if (owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.FROYO) {
                    pname = args.sa.getNonConfigurationString(args.processRes, 0);
                } else {
                    // Some older apps have been seen to use a resource reference
                    // here that on older builds was ignored (with a warning).  We
                    // need to continue to do this for them so they don't break.
                    pname = args.sa.getNonResourceString(args.processRes);
                }
                outInfo.processName = buildProcessName(owner.applicationInfo.packageName,
                        owner.applicationInfo.processName, pname,
                        args.flags, args.sepProcesses, args.outError);
            }
            
            if (args.descriptionRes != 0) {
                outInfo.descriptionRes = args.sa.getResourceId(args.descriptionRes, 0);
            }
            
            outInfo.enabled = args.sa.getBoolean(args.enabledRes, true);
        }

        public Component(Component<II> clone) {
            owner = clone.owner;
            intents = clone.intents;
            className = clone.className;
            componentName = clone.componentName;
            componentShortName = clone.componentShortName;
        }
        
        public ComponentName getComponentName() {
            if (componentName != null) {
                return componentName;
            }
            if (className != null) {
                componentName = new ComponentName(owner.applicationInfo.packageName,
                        className);
            }
            return componentName;
        }
        
        public String getComponentShortName() {
            if (componentShortName != null) {
                return componentShortName;
            }
            ComponentName component = getComponentName();
            if (component != null) {
                componentShortName = component.flattenToShortString();
            }
            return componentShortName;
        }
        
        public void setPackageName(String packageName) {
            componentName = null;
            componentShortName = null;
        }
    }
    
    public final static class Permission extends Component<IntentInfo> {
        public final PermissionInfo info;
        public boolean tree;
        public PermissionGroup group;

        public Permission(Package _owner) {
            super(_owner);
            info = new PermissionInfo();
        }

        public Permission(Package _owner, PermissionInfo _info) {
            super(_owner);
            info = _info;
        }
        
        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            return "Permission{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + info.name + "}";
        }
    }

    public final static class PermissionGroup extends Component<IntentInfo> {
        public final PermissionGroupInfo info;

        public PermissionGroup(Package _owner) {
            super(_owner);
            info = new PermissionGroupInfo();
        }

        public PermissionGroup(Package _owner, PermissionGroupInfo _info) {
            super(_owner);
            info = _info;
        }

        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            return "PermissionGroup{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + info.name + "}";
        }
    }

    private static boolean copyNeeded(int flags, Package p,
            PackageUserState state, Bundle metaData, int userId) {
        if (userId != 0) {
            // We always need to copy for other users, since we need
            // to fix up the uid.
            return true;
        }
        if (state.enabled != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            boolean enabled = state.enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            if (p.applicationInfo.enabled != enabled) {
                return true;
            }
        }
        if (!state.installed) {
            return true;
        }
        if (state.stopped) {
            return true;
        }
        if ((flags & PackageManager.GET_META_DATA) != 0
                && (metaData != null || p.mAppMetaData != null)) {
            return true;
        }
        if ((flags & PackageManager.GET_SHARED_LIBRARY_FILES) != 0
                && p.usesLibraryFiles != null) {
            return true;
        }
        return false;
    }

    public static ApplicationInfo generateApplicationInfo(Package p, int flags,
            PackageUserState state) {
        return generateApplicationInfo(p, flags, state, UserHandle.getCallingUserId());
    }

    public static ApplicationInfo generateApplicationInfo(Package p, int flags,
            PackageUserState state, int userId) {
        if (p == null) return null;
        if (!checkUseInstalled(flags, state)) {
            return null;
        }
        if (!copyNeeded(flags, p, state, null, userId)) {
            // CompatibilityMode is global state. It's safe to modify the instance
            // of the package.
            if (!sCompatibilityModeEnabled) {
                p.applicationInfo.disableCompatibilityMode();
            }
            // Make sure we report as installed.  Also safe to do, since the
            // default state should be installed (we will always copy if we
            // need to report it is not installed).
            p.applicationInfo.flags |= ApplicationInfo.FLAG_INSTALLED;
            if (state.enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                p.applicationInfo.enabled = true;
            } else if (state.enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state.enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                p.applicationInfo.enabled = false;
            }
            p.applicationInfo.enabledSetting = state.enabled;
            return p.applicationInfo;
        }

        // Make shallow copy so we can store the metadata/libraries safely
        ApplicationInfo ai = new ApplicationInfo(p.applicationInfo);
        if (userId != 0) {
            ai.uid = UserHandle.getUid(userId, ai.uid);
            ai.dataDir = PackageManager.getDataDirForUser(userId, ai.packageName);
        }
        if ((flags & PackageManager.GET_META_DATA) != 0) {
            ai.metaData = p.mAppMetaData;
        }
        if ((flags & PackageManager.GET_SHARED_LIBRARY_FILES) != 0) {
            ai.sharedLibraryFiles = p.usesLibraryFiles;
        }
        if (!sCompatibilityModeEnabled) {
            ai.disableCompatibilityMode();
        }
        if (state.stopped) {
            ai.flags |= ApplicationInfo.FLAG_STOPPED;
        } else {
            ai.flags &= ~ApplicationInfo.FLAG_STOPPED;
        }
        if (state.installed) {
            ai.flags |= ApplicationInfo.FLAG_INSTALLED;
        } else {
            ai.flags &= ~ApplicationInfo.FLAG_INSTALLED;
        }
        if (state.enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            ai.enabled = true;
        } else if (state.enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state.enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
            ai.enabled = false;
        }
        ai.enabledSetting = state.enabled;
        return ai;
    }

    public static final PermissionInfo generatePermissionInfo(
            Permission p, int flags) {
        if (p == null) return null;
        if ((flags&PackageManager.GET_META_DATA) == 0) {
            return p.info;
        }
        PermissionInfo pi = new PermissionInfo(p.info);
        pi.metaData = p.metaData;
        return pi;
    }

    public static final PermissionGroupInfo generatePermissionGroupInfo(
            PermissionGroup pg, int flags) {
        if (pg == null) return null;
        if ((flags&PackageManager.GET_META_DATA) == 0) {
            return pg.info;
        }
        PermissionGroupInfo pgi = new PermissionGroupInfo(pg.info);
        pgi.metaData = pg.metaData;
        return pgi;
    }

    public final static class Activity extends Component<ActivityIntentInfo> {
        public final ActivityInfo info;

        public Activity(final ParseComponentArgs args, final ActivityInfo _info) {
            super(args, _info);
            info = _info;
            info.applicationInfo = args.owner.applicationInfo;
        }
        
        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            return "Activity{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + getComponentShortName() + "}";
        }
    }

    public static final ActivityInfo generateActivityInfo(Activity a, int flags,
            PackageUserState state, int userId) {
        if (a == null) return null;
        if (!checkUseInstalled(flags, state)) {
            return null;
        }
        if (!copyNeeded(flags, a.owner, state, a.metaData, userId)) {
            return a.info;
        }
        // Make shallow copies so we can store the metadata safely
        ActivityInfo ai = new ActivityInfo(a.info);
        ai.metaData = a.metaData;
        ai.applicationInfo = generateApplicationInfo(a.owner, flags, state, userId);
        return ai;
    }

    public final static class Service extends Component<ServiceIntentInfo> {
        public final ServiceInfo info;

        public Service(final ParseComponentArgs args, final ServiceInfo _info) {
            super(args, _info);
            info = _info;
            info.applicationInfo = args.owner.applicationInfo;
        }
        
        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            return "Service{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + getComponentShortName() + "}";
        }
    }

    public static final ServiceInfo generateServiceInfo(Service s, int flags,
            PackageUserState state, int userId) {
        if (s == null) return null;
        if (!checkUseInstalled(flags, state)) {
            return null;
        }
        if (!copyNeeded(flags, s.owner, state, s.metaData, userId)) {
            return s.info;
        }
        // Make shallow copies so we can store the metadata safely
        ServiceInfo si = new ServiceInfo(s.info);
        si.metaData = s.metaData;
        si.applicationInfo = generateApplicationInfo(s.owner, flags, state, userId);
        return si;
    }

    public final static class Provider extends Component {
        public final ProviderInfo info;
        public boolean syncable;

        public Provider(final ParseComponentArgs args, final ProviderInfo _info) {
            super(args, _info);
            info = _info;
            info.applicationInfo = args.owner.applicationInfo;
            syncable = false;
        }
        
        public Provider(Provider existingProvider) {
            super(existingProvider);
            this.info = existingProvider.info;
            this.syncable = existingProvider.syncable;
        }

        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            return "Provider{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + info.name + "}";
        }
    }

    public static final ProviderInfo generateProviderInfo(Provider p, int flags,
            PackageUserState state, int userId) {
        if (p == null) return null;
        if (!checkUseInstalled(flags, state)) {
            return null;
        }
        if (!copyNeeded(flags, p.owner, state, p.metaData, userId)
                && ((flags & PackageManager.GET_URI_PERMISSION_PATTERNS) != 0
                        || p.info.uriPermissionPatterns == null)) {
            return p.info;
        }
        // Make shallow copies so we can store the metadata safely
        ProviderInfo pi = new ProviderInfo(p.info);
        pi.metaData = p.metaData;
        if ((flags & PackageManager.GET_URI_PERMISSION_PATTERNS) == 0) {
            pi.uriPermissionPatterns = null;
        }
        pi.applicationInfo = generateApplicationInfo(p.owner, flags, state, userId);
        return pi;
    }

    public final static class Instrumentation extends Component {
        public final InstrumentationInfo info;

        public Instrumentation(final ParsePackageItemArgs args, final InstrumentationInfo _info) {
            super(args, _info);
            info = _info;
        }
        
        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            return "Instrumentation{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + getComponentShortName() + "}";
        }
    }

    public static final InstrumentationInfo generateInstrumentationInfo(
            Instrumentation i, int flags) {
        if (i == null) return null;
        if ((flags&PackageManager.GET_META_DATA) == 0) {
            return i.info;
        }
        InstrumentationInfo ii = new InstrumentationInfo(i.info);
        ii.metaData = i.metaData;
        return ii;
    }

    public static class IntentInfo extends IntentFilter {
        public boolean hasDefault;
        public int labelRes;
        public CharSequence nonLocalizedLabel;
        public int icon;
        public int logo;
    }

    public final static class ActivityIntentInfo extends IntentInfo {
        public final Activity activity;

        public ActivityIntentInfo(Activity _activity) {
            activity = _activity;
        }

        public String toString() {
            return "ActivityIntentInfo{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + activity.info.name + "}";
        }
    }

    public final static class ServiceIntentInfo extends IntentInfo {
        public final Service service;

        public ServiceIntentInfo(Service _service) {
            service = _service;
        }

        public String toString() {
            return "ServiceIntentInfo{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + service.info.name + "}";
        }
    }

    /**
     * @hide
     */
    public static void setCompatibilityModeEnabled(boolean compatibilityModeEnabled) {
        sCompatibilityModeEnabled = compatibilityModeEnabled;
    }
}
