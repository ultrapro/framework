/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaScannerJNI"
#include <utils/Log.h>
#include <utils/threads.h>
#include <utils/Unicode.h>
#include <media/mediascanner.h>
#ifdef MTK_TB_DEBUG_SUPPORT
#include <media/mtkdc/MtkdcMediaScanner.h>
#endif
#include <media/stagefright/StagefrightMediaScanner.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

using namespace android;


static const char* const kClassMediaScannerClient =
        "android/media/MediaScannerClient";

static const char* const kClassMediaScanner =
        "android/media/MediaScanner";

static const char* const kRunTimeException =
        "java/lang/RuntimeException";

static const char* const kIllegalArgumentException =
        "java/lang/IllegalArgumentException";

struct fields_t {
    jfieldID    context;
#ifdef MTK_TB_DEBUG_SUPPORT
    jfieldID    context1;
#endif
};
static fields_t fields;

static status_t checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
        return UNKNOWN_ERROR;
    }
    return OK;
}

class MyMediaScannerClient : public MediaScannerClient
{
public:
    MyMediaScannerClient(JNIEnv *env, jobject client)
        :   mEnv(env),
            mClient(env->NewGlobalRef(client)),
            mScanFileMethodID(0),
            mHandleStringTagMethodID(0),
            mSetMimeTypeMethodID(0)
    {
        ALOGV("MyMediaScannerClient constructor");
        jclass mediaScannerClientInterface =
                env->FindClass(kClassMediaScannerClient);

        if (mediaScannerClientInterface == NULL) {
            ALOGE("Class %s not found", kClassMediaScannerClient);
        } else {
            mScanFileMethodID = env->GetMethodID(
                                    mediaScannerClientInterface,
                                    "scanFile",
                                    "(Ljava/lang/String;JJZZ)V");

            mHandleStringTagMethodID = env->GetMethodID(
                                    mediaScannerClientInterface,
                                    "handleStringTag",
                                    "(Ljava/lang/String;Ljava/lang/String;)V");

            mSetMimeTypeMethodID = env->GetMethodID(
                                    mediaScannerClientInterface,
                                    "setMimeType",
                                    "(Ljava/lang/String;)V");
        }
    }

    virtual ~MyMediaScannerClient()
    {
        ALOGV("MyMediaScannerClient destructor");
        mEnv->DeleteGlobalRef(mClient);
    }

    virtual status_t scanFile(const char* path, long long lastModified,
            long long fileSize, bool isDirectory, bool noMedia)
    {
        ALOGV("scanFile: path(%s), time(%lld), size(%lld) and isDir(%d)",
            path, lastModified, fileSize, isDirectory);

        jstring pathStr;
        if ((pathStr = mEnv->NewStringUTF(path)) == NULL) {
            mEnv->ExceptionClear();
            return NO_MEMORY;
        }

        mEnv->CallVoidMethod(mClient, mScanFileMethodID, pathStr, lastModified,
                fileSize, isDirectory, noMedia);

        mEnv->DeleteLocalRef(pathStr);
        return checkAndClearExceptionFromCallback(mEnv, "scanFile");
    }

    virtual status_t handleStringTag(const char* name, const char* value)
    {
        ALOGV("handleStringTag: name(%s) and value(%s)", name, value);
        jstring nameStr, valueStr;
        if ((nameStr = mEnv->NewStringUTF(name)) == NULL) {
            mEnv->ExceptionClear();
            return NO_MEMORY;
        }

        // Check if the value is valid UTF-8 string and replace
        // any un-printable characters with '?' when it's not.
        char *cleaned = NULL;
        if (utf8_length(value) == -1) {
            cleaned = strdup(value);
            char *chp = cleaned;
            char ch;
            while ((ch = *chp)) {
                if (ch & 0x80) {
                    *chp = '?';
                }
                chp++;
            }
            value = cleaned;
        }
        valueStr = mEnv->NewStringUTF(value);
        free(cleaned);
        if (valueStr == NULL) {
            mEnv->DeleteLocalRef(nameStr);
            mEnv->ExceptionClear();
            return NO_MEMORY;
        }

        mEnv->CallVoidMethod(
            mClient, mHandleStringTagMethodID, nameStr, valueStr);

        mEnv->DeleteLocalRef(nameStr);
        mEnv->DeleteLocalRef(valueStr);
        return checkAndClearExceptionFromCallback(mEnv, "handleStringTag");
    }

    virtual status_t setMimeType(const char* mimeType)
    {
        ALOGV("setMimeType: %s", mimeType);
        jstring mimeTypeStr;
        if ((mimeTypeStr = mEnv->NewStringUTF(mimeType)) == NULL) {
            mEnv->ExceptionClear();
            return NO_MEMORY;
        }

        mEnv->CallVoidMethod(mClient, mSetMimeTypeMethodID, mimeTypeStr);

        mEnv->DeleteLocalRef(mimeTypeStr);
        return checkAndClearExceptionFromCallback(mEnv, "setMimeType");
    }

private:
    JNIEnv *mEnv;
    jobject mClient;
    jmethodID mScanFileMethodID;
    jmethodID mHandleStringTagMethodID;
    jmethodID mSetMimeTypeMethodID;
};


static MediaScanner *getNativeScanner_l(JNIEnv* env, jobject thiz)
{
    return (MediaScanner *) env->GetIntField(thiz, fields.context);
}

static void setNativeScanner_l(JNIEnv* env, jobject thiz, MediaScanner *s)
{
    env->SetIntField(thiz, fields.context, (int)s);
}

static void
android_media_MediaScanner_processDirectory(
        JNIEnv *env, jobject thiz, jstring path, jobject client)
{
    ALOGV("processDirectory");
    MediaScanner *mp = getNativeScanner_l(env, thiz);
    if (mp == NULL) {
        jniThrowException(env, kRunTimeException, "No scanner available");
        return;
    }

    if (path == NULL) {
        jniThrowException(env, kIllegalArgumentException, NULL);
        return;
    }

    const char *pathStr = env->GetStringUTFChars(path, NULL);
    if (pathStr == NULL) {  // Out of memory
        return;
    }

    MyMediaScannerClient myClient(env, client);
    MediaScanResult result = mp->processDirectory(pathStr, myClient);
    if (result == MEDIA_SCAN_RESULT_ERROR) {
        ALOGE("An error occurred while scanning directory '%s'.", pathStr);
    }
    env->ReleaseStringUTFChars(path, pathStr);
}

static void
android_media_MediaScanner_processFile(
        JNIEnv *env, jobject thiz, jstring path,
        jstring mimeType, jobject client)
{
    ALOGV("processFile");

    // Lock already hold by processDirectory
    MediaScanner *mp = getNativeScanner_l(env, thiz);
    if (mp == NULL) {
        jniThrowException(env, kRunTimeException, "No scanner available");
        return;
    }

    if (path == NULL) {
        jniThrowException(env, kIllegalArgumentException, NULL);
        return;
    }

    const char *pathStr = env->GetStringUTFChars(path, NULL);
    if (pathStr == NULL) {  // Out of memory
        return;
    }

    const char *mimeTypeStr =
        (mimeType ? env->GetStringUTFChars(mimeType, NULL) : NULL);
    if (mimeType && mimeTypeStr == NULL) {  // Out of memory
        // ReleaseStringUTFChars can be called with an exception pending.
        env->ReleaseStringUTFChars(path, pathStr);
        return;
    }

    MyMediaScannerClient myClient(env, client);
    MediaScanResult result = mp->processFile(pathStr, mimeTypeStr, myClient);
    if (result == MEDIA_SCAN_RESULT_ERROR) {
        ALOGE("An error occurred while scanning file '%s'.", pathStr);
    #ifdef MTK_TB_DEBUG_SUPPORT
	mp = (MediaScanner *)env->GetIntField(thiz, fields.context1);
	result = mp->processFile(pathStr, mimeTypeStr, myClient);
	if (result == MEDIA_SCAN_RESULT_ERROR) {
	    ALOGE("An error occurred while scanning file '%s' with mtkdc.", pathStr);
	}
    #endif
    }
    env->ReleaseStringUTFChars(path, pathStr);
    if (mimeType) {
        env->ReleaseStringUTFChars(mimeType, mimeTypeStr);
    }
}

static void
android_media_MediaScanner_setLocale(
        JNIEnv *env, jobject thiz, jstring locale)
{
    ALOGV("setLocale");
    MediaScanner *mp = getNativeScanner_l(env, thiz);
    if (mp == NULL) {
        jniThrowException(env, kRunTimeException, "No scanner available");
        return;
    }

    if (locale == NULL) {
        jniThrowException(env, kIllegalArgumentException, NULL);
        return;
    }
    const char *localeStr = env->GetStringUTFChars(locale, NULL);
    if (localeStr == NULL) {  // Out of memory
        return;
    }
    mp->setLocale(localeStr);

    env->ReleaseStringUTFChars(locale, localeStr);
}

static jbyteArray
android_media_MediaScanner_extractAlbumArt(
        JNIEnv *env, jobject thiz, jobject fileDescriptor)
{
    ALOGV("extractAlbumArt");
    MediaScanner *mp = getNativeScanner_l(env, thiz);
    if (mp == NULL) {
        jniThrowException(env, kRunTimeException, "No scanner available");
        return NULL;
    }

    if (fileDescriptor == NULL) {
        jniThrowException(env, kIllegalArgumentException, NULL);
        return NULL;
    }

    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    char* data = mp->extractAlbumArt(fd);
    close(fd);
    if (!data) {
        return NULL;
    }
    long len = *((long*)data);

    jbyteArray array = env->NewByteArray(len);
    if (array != NULL) {
        jbyte* bytes = env->GetByteArrayElements(array, NULL);
        memcpy(bytes, data + 4, len);
        env->ReleaseByteArrayElements(array, bytes, 0);
    }

done:
    free(data);
    // if NewByteArray() returned NULL, an out-of-memory
    // exception will have been raised. I just want to
    // return null in that case.
    env->ExceptionClear();
    return array;
}

// This function gets a field ID, which in turn causes class initialization.
// It is called from a static block in MediaScanner, which won't run until the
// first time an instance of this class is used.
static void
android_media_MediaScanner_native_init(JNIEnv *env)
{
    ALOGV("native_init");
    jclass clazz = env->FindClass(kClassMediaScanner);
    if (clazz == NULL) {
        return;
    }

    fields.context = env->GetFieldID(clazz, "mNativeContext", "I");
#ifdef MTK_TB_DEBUG_SUPPORT
    fields.context1 = env->GetFieldID(clazz, "mContext1", "I");
#endif
    if (fields.context == NULL) {
        return;
    }
}

static void
android_media_MediaScanner_native_setup(JNIEnv *env, jobject thiz)
{
    ALOGV("native_setup");
    MediaScanner *mp = new StagefrightMediaScanner;

    if (mp == NULL) {
        jniThrowException(env, kRunTimeException, "Out of memory");
        return;
    }

    env->SetIntField(thiz, fields.context, (int)mp);

#ifdef MTK_TB_DEBUG_SUPPORT
    MediaScanner *mp1 = new MtkdcMediaScanner();
    
    if (mp == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
        return;
    }

    env->SetIntField(thiz, fields.context1, (int)mp1);
#endif
}

static void
android_media_MediaScanner_native_finalize(JNIEnv *env, jobject thiz)
{
    ALOGV("native_finalize");
    MediaScanner *mp = getNativeScanner_l(env, thiz);
    
#ifndef MTK_TB_DEBUG_SUPPORT
    if (mp == 0) {
        return;
    }
    delete mp;
#else
    if (mp != 0) delete mp;

    MediaScanner *mp1 = (MediaScanner *)env->GetIntField(thiz, fields.context1);
    if (mp1!= 0) delete mp1;
#endif

    setNativeScanner_l(env, thiz, 0);
}

static JNINativeMethod gMethods[] = {
    {
        "processDirectory",
        "(Ljava/lang/String;Landroid/media/MediaScannerClient;)V",
        (void *)android_media_MediaScanner_processDirectory
    },

    {
        "processFile",
        "(Ljava/lang/String;Ljava/lang/String;Landroid/media/MediaScannerClient;)V",
        (void *)android_media_MediaScanner_processFile
    },

    {
        "setLocale",
        "(Ljava/lang/String;)V",
        (void *)android_media_MediaScanner_setLocale
    },

    {
        "extractAlbumArt",
        "(Ljava/io/FileDescriptor;)[B",
        (void *)android_media_MediaScanner_extractAlbumArt
    },

    {
        "native_init",
        "()V",
        (void *)android_media_MediaScanner_native_init
    },

    {
        "native_setup",
        "()V",
        (void *)android_media_MediaScanner_native_setup
    },

    {
        "native_finalize",
        "()V",
        (void *)android_media_MediaScanner_native_finalize
    },
};

// This function only registers the native methods, and is called from
// JNI_OnLoad in android_media_MediaPlayer.cpp
int register_android_media_MediaScanner(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods(env,
                kClassMediaScanner, gMethods, NELEM(gMethods));
}
