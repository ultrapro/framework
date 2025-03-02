/*
 * Copyright (C) 2012 The Android Open Source Project
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

#define LOG_TAG "RemoteDisplay"

#include "jni.h"
#include "JNIHelp.h"

#include "android_os_Parcel.h"
#include "android_util_Binder.h"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>

#include <binder/IServiceManager.h>

#include <gui/ISurfaceTexture.h>

#include <media/IMediaPlayerService.h>
#include <media/IRemoteDisplay.h>
#include <media/IRemoteDisplayClient.h>

#include <utils/Log.h>

#include <ScopedUtfChars.h>

namespace android {

static struct {
    jmethodID notifyDisplayConnected;
    jmethodID notifyDisplayDisconnected;
    jmethodID notifyDisplayError;
#ifndef ANDROID_DEFAULT_CODE
    ///M:@{
    jmethodID notifyDisplayKeyEvent;
    jmethodID notifyDisplayTouchEvent;
    jmethodID notifyDisplayGenericMsgEvent;
    ///@}
#endif
} gRemoteDisplayClassInfo;

// ----------------------------------------------------------------------------

class NativeRemoteDisplayClient : public BnRemoteDisplayClient {
public:
    NativeRemoteDisplayClient(JNIEnv* env, jobject remoteDisplayObj) :
            mRemoteDisplayObjGlobal(env->NewGlobalRef(remoteDisplayObj)) {
    }

protected:
    ~NativeRemoteDisplayClient() {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->DeleteGlobalRef(mRemoteDisplayObjGlobal);
    }

public:
    virtual void onDisplayConnected(const sp<ISurfaceTexture>& surfaceTexture,
            uint32_t width, uint32_t height, uint32_t flags) {
        JNIEnv* env = AndroidRuntime::getJNIEnv();

        jobject surfaceObj = android_view_Surface_createFromISurfaceTexture(env, surfaceTexture);
        if (surfaceObj == NULL) {
            ALOGE("Could not create Surface from surface texture %p provided by media server.",
                    surfaceTexture.get());
            ///M: Disable temoarily and wait for MR1's extendion mode@{
            //return;
            /// @{
        }

        env->CallVoidMethod(mRemoteDisplayObjGlobal,
                gRemoteDisplayClassInfo.notifyDisplayConnected,
                surfaceObj, width, height, flags);
        env->DeleteLocalRef(surfaceObj);
        checkAndClearExceptionFromCallback(env, "notifyDisplayConnected");
    }

    virtual void onDisplayDisconnected() {
        JNIEnv* env = AndroidRuntime::getJNIEnv();

        env->CallVoidMethod(mRemoteDisplayObjGlobal,
                gRemoteDisplayClassInfo.notifyDisplayDisconnected);
        checkAndClearExceptionFromCallback(env, "notifyDisplayDisconnected");
    }

    virtual void onDisplayError(int32_t error) {
        JNIEnv* env = AndroidRuntime::getJNIEnv();

        env->CallVoidMethod(mRemoteDisplayObjGlobal,
                gRemoteDisplayClassInfo.notifyDisplayError, error);
        checkAndClearExceptionFromCallback(env, "notifyDisplayError");
    }
#ifndef ANDROID_DEFAULT_CODE
    ///M:@{
    virtual void onDisplayKeyEvent(uint32_t keyCode, uint32_t flags) {
        ALOGD("onDisplayKeyEvent ENTRY");
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->CallVoidMethod(mRemoteDisplayObjGlobal,
                gRemoteDisplayClassInfo.notifyDisplayKeyEvent, keyCode,flags);
        checkAndClearExceptionFromCallback(env, "notifyDisplayKeyEvent");
        ALOGD("onDisplayKeyEvent EXIT");
    }
    virtual void onDisplayTouchEvent(uint32_t x, uint32_t y, uint32_t flags) {
        ALOGD("onDisplayTouchEvent ENTRY");
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->CallVoidMethod(mRemoteDisplayObjGlobal,
                gRemoteDisplayClassInfo.notifyDisplayTouchEvent, x,y,flags);
        checkAndClearExceptionFromCallback(env, "notifyDisplayTouchEvent");
        ALOGD("onDisplayTouchEvent EXIT");
    }

    virtual void onDisplayGenericMsgEvent(uint32_t event) {
        ALOGD("onDisplayGenericMsgEvent ENTRY");
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->CallVoidMethod(mRemoteDisplayObjGlobal,
                gRemoteDisplayClassInfo.notifyDisplayGenericMsgEvent, event);
        ALOGD("onDisplayGenericMsgEvent EXIT");
    }
    

    ///@}    
#endif

private:
    jobject mRemoteDisplayObjGlobal;

    static void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
        if (env->ExceptionCheck()) {
            ALOGE("An exception was thrown by callback '%s'.", methodName);
            LOGE_EX(env);
            env->ExceptionClear();
        }
    }
};

class NativeRemoteDisplay {
public:
    NativeRemoteDisplay(const sp<IRemoteDisplay>& display,
            const sp<NativeRemoteDisplayClient>& client) :
            mDisplay(display), mClient(client) {
    }

    ~NativeRemoteDisplay() {
        mDisplay->dispose();
    }

private:
    sp<IRemoteDisplay> mDisplay;
    sp<NativeRemoteDisplayClient> mClient;
};


// ----------------------------------------------------------------------------

static jint nativeListen(JNIEnv* env, jobject remoteDisplayObj, jstring ifaceStr) {
    ScopedUtfChars iface(env, ifaceStr);

    sp<IServiceManager> sm = defaultServiceManager();
    sp<IMediaPlayerService> service = interface_cast<IMediaPlayerService>(
            sm->getService(String16("media.player")));
    if (service == NULL) {
        ALOGE("Could not obtain IMediaPlayerService from service manager");
        return 0;
    }

    sp<NativeRemoteDisplayClient> client(new NativeRemoteDisplayClient(env, remoteDisplayObj));
    sp<IRemoteDisplay> display = service->listenForRemoteDisplay(
            client, String8(iface.c_str()));
    if (display == NULL) {
        ALOGE("Media player service rejected request to listen for remote display '%s'.",
                iface.c_str());
        return 0;
    }

    NativeRemoteDisplay* wrapper = new NativeRemoteDisplay(display, client);
    return reinterpret_cast<jint>(wrapper);
}

static void nativeDispose(JNIEnv* env, jobject remoteDisplayObj, jint ptr) {
    NativeRemoteDisplay* wrapper = reinterpret_cast<NativeRemoteDisplay*>(ptr);
    delete wrapper;
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"nativeListen", "(Ljava/lang/String;)I",
            (void*)nativeListen },
    {"nativeDispose", "(I)V",
            (void*)nativeDispose },
};

int register_android_media_RemoteDisplay(JNIEnv* env)
{
    int err = AndroidRuntime::registerNativeMethods(env, "android/media/RemoteDisplay",
            gMethods, NELEM(gMethods));

    jclass clazz = env->FindClass("android/media/RemoteDisplay");
    gRemoteDisplayClassInfo.notifyDisplayConnected =
            env->GetMethodID(clazz, "notifyDisplayConnected",
                    "(Landroid/view/Surface;III)V");
    gRemoteDisplayClassInfo.notifyDisplayDisconnected =
            env->GetMethodID(clazz, "notifyDisplayDisconnected", "()V");
    gRemoteDisplayClassInfo.notifyDisplayError =
            env->GetMethodID(clazz, "notifyDisplayError", "(I)V");
#ifndef ANDROID_DEFAULT_CODE
    ///M:@{
    gRemoteDisplayClassInfo.notifyDisplayKeyEvent =
            env->GetMethodID(clazz, "notifyDisplayKeyEvent", "(II)V");
    gRemoteDisplayClassInfo.notifyDisplayTouchEvent =
            env->GetMethodID(clazz, "notifyDisplayTouchEvent", "(III)V");
    gRemoteDisplayClassInfo.notifyDisplayGenericMsgEvent =
            env->GetMethodID(clazz, "notifyDisplayGenericMsgEvent", "(I)V");    
    ///@}
#endif
    return err;
}

};
