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

#define LOG_TAG "CameraClient"
//#define LOG_NDEBUG 0

#include <cutils/properties.h>
#include <gui/SurfaceTextureClient.h>
#include <gui/Surface.h>

#include "CameraClient.h"
#include "CameraHardwareInterface.h"
#include "CameraService.h"

//!++
#ifdef  MTK_CAMERAPROFILE_SUPPORT
    #include "CameraProfile.h"
#endif
    
#ifdef  MTK_CAMERA_BSP_SUPPORT

    #include <camera/MtkCamera.h>
    #include <camera/MtkCameraParameters.h>
#endif
//!--

namespace android {

#define LOG1(...) ALOGD_IF(gLogLevel >= 1, __VA_ARGS__);
#define LOG2(...) ALOGD_IF(gLogLevel >= 2, __VA_ARGS__);

static int getCallingPid() {
    return IPCThreadState::self()->getCallingPid();
}

static int getCallingUid() {
    return IPCThreadState::self()->getCallingUid();
}

CameraClient::CameraClient(const sp<CameraService>& cameraService,
        const sp<ICameraClient>& cameraClient,
        int cameraId, int cameraFacing, int clientPid, int servicePid):
        Client(cameraService, cameraClient,
                cameraId, cameraFacing, clientPid, servicePid)
{
    int callingPid = getCallingPid();
    LOG1("CameraClient::CameraClient E (pid %d, id %d)", callingPid, cameraId);

    mHardware = NULL;
    mMsgEnabled = 0;
    mSurface = 0;
    mPreviewWindow = 0;
    mDestructionStarted = false;

    // Callback is disabled by default
    mPreviewCallbackFlag = CAMERA_FRAME_CALLBACK_FLAG_NOOP;
    mOrientation = getOrientation(0, mCameraFacing == CAMERA_FACING_FRONT);
    mPlayShutterSound = true;
    LOG1("CameraClient::CameraClient X (pid %d, id %d)", callingPid, cameraId);
}

status_t CameraClient::initialize(camera_module_t *module) {
    int callingPid = getCallingPid();
    LOG1("CameraClient::initialize E (pid %d, id %d)", callingPid, mCameraId);

    char camera_device_name[10];
    status_t res;
    snprintf(camera_device_name, sizeof(camera_device_name), "%d", mCameraId);

    mHardware = new CameraHardwareInterface(camera_device_name);
    res = mHardware->initialize(&module->common);
    if (res != OK) {
        ALOGE("%s: Camera %d: unable to initialize device: %s (%d)",
                __FUNCTION__, mCameraId, strerror(-res), res);
        mHardware.clear();
        return NO_INIT;
    }

    mHardware->setCallbacks(notifyCallback,
            dataCallback,
            dataCallbackTimestamp,
            (void *)mCameraId);

    // Enable zoom, error, focus, and metadata messages by default
    enableMsgType(CAMERA_MSG_ERROR | CAMERA_MSG_ZOOM | CAMERA_MSG_FOCUS |
                  CAMERA_MSG_PREVIEW_METADATA | CAMERA_MSG_FOCUS_MOVE);

//!++
#ifdef  MTK_CAMERA_BSP_SUPPORT
    // Enable MTK-extended messages by default
    enableMsgType(MTK_CAMERA_MSG_EXT_NOTIFY | MTK_CAMERA_MSG_EXT_DATA);
#endif
//!--

    LOG1("CameraClient::initialize X (pid %d, id %d)", callingPid, mCameraId);
    return OK;
}


// tear down the client
CameraClient::~CameraClient() {
    // this lock should never be NULL
    Mutex* lock = mCameraService->getClientLockById(mCameraId);
    lock->lock();
    mDestructionStarted = true;
    // client will not be accessed from callback. should unlock to prevent dead-lock in disconnect
    lock->unlock();
    int callingPid = getCallingPid();
    LOG1("CameraClient::~CameraClient E (pid %d, this %p)", callingPid, this);

    disconnect();
    LOG1("CameraClient::~CameraClient X (pid %d, this %p)", callingPid, this);
}

status_t CameraClient::dump(int fd, const Vector<String16>& args) {
    const size_t SIZE = 256;
    char buffer[SIZE];

    size_t len = snprintf(buffer, SIZE, "Client[%d] (%p) PID: %d\n",
            mCameraId,
            getCameraClient()->asBinder().get(),
            mClientPid);
    len = (len > SIZE - 1) ? SIZE - 1 : len;
    write(fd, buffer, len);
    return mHardware->dump(fd, args);
}

// ----------------------------------------------------------------------------

status_t CameraClient::checkPid() const {
    int callingPid = getCallingPid();
    if (callingPid == mClientPid) return NO_ERROR;

    ALOGW("attempt to use a locked camera from a different process"
         " (old pid %d, new pid %d)", mClientPid, callingPid);
    return EBUSY;
}

status_t CameraClient::checkPidAndHardware() const {
    status_t result = checkPid();
    if (result != NO_ERROR) return result;
    if (mHardware == 0) {
        ALOGE("attempt to use a camera after disconnect() (pid %d)", getCallingPid());
        return INVALID_OPERATION;
    }
    return NO_ERROR;
}

status_t CameraClient::lock() {
    int callingPid = getCallingPid();
    LOG1("lock (pid %d)", callingPid);
    Mutex::Autolock lock(mLock);

    // lock camera to this client if the the camera is unlocked
    if (mClientPid == 0) {
        mClientPid = callingPid;
        return NO_ERROR;
    }

    // returns NO_ERROR if the client already owns the camera, EBUSY otherwise
    return checkPid();
}

status_t CameraClient::unlock() {
    int callingPid = getCallingPid();
    LOG1("unlock (pid %d)", callingPid);
    Mutex::Autolock lock(mLock);

    // allow anyone to use camera (after they lock the camera)
    status_t result = checkPid();
    if (result == NO_ERROR) {
        if (mHardware->recordingEnabled()) {
            ALOGE("Not allowed to unlock camera during recording.");
            return INVALID_OPERATION;
        }
        mClientPid = 0;
        LOG1("clear mCameraClient (pid %d)", callingPid);
        // we need to remove the reference to ICameraClient so that when the app
        // goes away, the reference count goes to 0.
        mCameraClient.clear();
    }
    return result;
}

// connect a new client to the camera
status_t CameraClient::connect(const sp<ICameraClient>& client) {
    int callingPid = getCallingPid();
    LOG1("connect E (pid %d)", callingPid);
    Mutex::Autolock lock(mLock);

    if (mClientPid != 0 && checkPid() != NO_ERROR) {
        ALOGW("Tried to connect to a locked camera (old pid %d, new pid %d)",
                mClientPid, callingPid);
        return EBUSY;
    }

    if (mCameraClient != 0 && (client->asBinder() == mCameraClient->asBinder())) {
        LOG1("Connect to the same client");
        return NO_ERROR;
    }

    mPreviewCallbackFlag = CAMERA_FRAME_CALLBACK_FLAG_NOOP;
    mClientPid = callingPid;
    mCameraClient = client;

    LOG1("connect X (pid %d)", callingPid);
    return NO_ERROR;
}

static void disconnectWindow(const sp<ANativeWindow>& window) {
#ifdef  MTK_CAMERAPROFILE_SUPPORT
    AutoCPTLog cptlog(Event_CS_disconnectWindow);
#endif
    if (window != 0) {
        status_t result = native_window_api_disconnect(window.get(),
                NATIVE_WINDOW_API_CAMERA);
        if (result != NO_ERROR) {
            ALOGW("native_window_api_disconnect failed: %s (%d)", strerror(-result),
                    result);
        }
    }
}

void CameraClient::disconnect() {
#ifdef  MTK_CAMERAPROFILE_SUPPORT
    AutoCPTLog cptlog(Event_CS_disconnect);
#endif
    int callingPid = getCallingPid();
    LOG1("disconnect E (pid %d)", callingPid);
    Mutex::Autolock lock(mLock);

    // Allow both client and the media server to disconnect at all times
    if (callingPid != mClientPid && callingPid != mServicePid) {
        ALOGW("different client - don't disconnect");
        return;
    }

    if (mClientPid <= 0) {
        LOG1("camera is unlocked (mClientPid = %d), don't tear down hardware", mClientPid);
        return;
    }

    // Make sure disconnect() is done once and once only, whether it is called
    // from the user directly, or called by the destructor.
    if (mHardware == 0) return;

    LOG1("hardware teardown");
    // Before destroying mHardware, we must make sure it's in the
    // idle state.
    // Turn off all messages.
    disableMsgType(CAMERA_MSG_ALL_MSGS);
    mHardware->stopPreview();
    mHardware->cancelPicture();
    // Release the hardware resources.
    mHardware->release();

    // Release the held ANativeWindow resources.
    if (mPreviewWindow != 0) {
        disconnectWindow(mPreviewWindow);
        mPreviewWindow = 0;
        mHardware->setPreviewWindow(mPreviewWindow);
    }
    mHardware.clear();

    CameraService::Client::disconnect();

    LOG1("disconnect X (pid %d)", callingPid);
}

// ----------------------------------------------------------------------------

status_t CameraClient::setPreviewWindow(const sp<IBinder>& binder,
        const sp<ANativeWindow>& window) {
    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    // return if no change in surface.
    if (binder == mSurface) {
        return NO_ERROR;
    }

    if (window != 0) {
        result = native_window_api_connect(window.get(), NATIVE_WINDOW_API_CAMERA);
        if (result != NO_ERROR) {
            ALOGE("native_window_api_connect failed: %s (%d)", strerror(-result),
                    result);
            return result;
        }
    }

    // If preview has been already started, register preview buffers now.
    if (mHardware->previewEnabled()) {
        if (window != 0) {
            native_window_set_scaling_mode(window.get(),
                    NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW);
            native_window_set_buffers_transform(window.get(), mOrientation);
            result = mHardware->setPreviewWindow(window);
        }
    }

    if (result == NO_ERROR) {
        // Everything has succeeded.  Disconnect the old window and remember the
        // new window.
        disconnectWindow(mPreviewWindow);
        mSurface = binder;
        mPreviewWindow = window;
    } else {
        // Something went wrong after we connected to the new window, so
        // disconnect here.
        disconnectWindow(window);
    }

    return result;
}

// set the Surface that the preview will use
status_t CameraClient::setPreviewDisplay(const sp<Surface>& surface) {
#ifdef  MTK_CAMERAPROFILE_SUPPORT
    AutoCPTLog cptlog(Event_CS_setPreviewDisplay);
#endif
    LOG1("setPreviewDisplay(%p) (pid %d)", surface.get(), getCallingPid());

    sp<IBinder> binder(surface != 0 ? surface->asBinder() : 0);
    sp<ANativeWindow> window(surface);
    return setPreviewWindow(binder, window);
}

// set the SurfaceTexture that the preview will use
status_t CameraClient::setPreviewTexture(
        const sp<ISurfaceTexture>& surfaceTexture) {
    LOG1("setPreviewTexture(%p) (pid %d)", surfaceTexture.get(),
            getCallingPid());

    sp<IBinder> binder;
    sp<ANativeWindow> window;
    if (surfaceTexture != 0) {
        binder = surfaceTexture->asBinder();
        window = new SurfaceTextureClient(surfaceTexture);
    }
    return setPreviewWindow(binder, window);
}

// set the preview callback flag to affect how the received frames from
// preview are handled.
void CameraClient::setPreviewCallbackFlag(int callback_flag) {
    LOG1("setPreviewCallbackFlag(%d) (pid %d)", callback_flag, getCallingPid());
    Mutex::Autolock lock(mLock);
    if (checkPidAndHardware() != NO_ERROR) return;

    mPreviewCallbackFlag = callback_flag;
    if (mPreviewCallbackFlag & CAMERA_FRAME_CALLBACK_FLAG_ENABLE_MASK) {
        enableMsgType(CAMERA_MSG_PREVIEW_FRAME);
    } else {
        disableMsgType(CAMERA_MSG_PREVIEW_FRAME);
    }
}

// start preview mode
status_t CameraClient::startPreview() {
#ifdef  MTK_CAMERAPROFILE_SUPPORT
    AutoCPTLog cptlog(Event_CS_startPreview);
#endif
    LOG1("startPreview (pid %d)", getCallingPid());
    return startCameraMode(CAMERA_PREVIEW_MODE);
}

// start recording mode
status_t CameraClient::startRecording() {
#ifdef  MTK_CAMERAPROFILE_SUPPORT
    AutoCPTLog cptlog(Event_CS_startRecording);
#endif
    LOG1("startRecording (pid %d)", getCallingPid());
    return startCameraMode(CAMERA_RECORDING_MODE);
}

// start preview or recording
status_t CameraClient::startCameraMode(camera_mode mode) {
    LOG1("startCameraMode(%d)", mode);
    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    switch(mode) {
        case CAMERA_PREVIEW_MODE:
            if (mSurface == 0 && mPreviewWindow == 0) {
                LOG1("mSurface is not set yet.");
                // still able to start preview in this case.
            }
            return startPreviewMode();
        case CAMERA_RECORDING_MODE:
            if (mSurface == 0 && mPreviewWindow == 0) {
                ALOGE("mSurface or mPreviewWindow must be set before startRecordingMode.");
                return INVALID_OPERATION;
            }
            return startRecordingMode();
        default:
            return UNKNOWN_ERROR;
    }
}

status_t CameraClient::startPreviewMode() {
    LOG1("startPreviewMode");
    status_t result = NO_ERROR;

    // if preview has been enabled, nothing needs to be done
    if (mHardware->previewEnabled()) {
        return NO_ERROR;
    }

    if (mPreviewWindow != 0) {
        native_window_set_scaling_mode(mPreviewWindow.get(),
                NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW);
        native_window_set_buffers_transform(mPreviewWindow.get(),
                mOrientation);
    }
    mHardware->setPreviewWindow(mPreviewWindow);
    result = mHardware->startPreview();

    return result;
}

status_t CameraClient::startRecordingMode() {
    LOG1("startRecordingMode");
    status_t result = NO_ERROR;

    // if recording has been enabled, nothing needs to be done
    if (mHardware->recordingEnabled()) {
        return NO_ERROR;
    }

    // if preview has not been started, start preview first
    if (!mHardware->previewEnabled()) {
        result = startPreviewMode();
        if (result != NO_ERROR) {
            return result;
        }
    }

    // start recording mode
    enableMsgType(CAMERA_MSG_VIDEO_FRAME);
    //!++
#ifdef  MTK_CAMERA_BSP_SUPPORT
    playRecordingSound();
#else
    mCameraService->playSound(CameraService::SOUND_RECORDING);
#endif
    //!--

    result = mHardware->startRecording();
    if (result != NO_ERROR) {
        ALOGE("mHardware->startRecording() failed with status %d", result);
    }
    return result;
}

// stop preview mode
void CameraClient::stopPreview() {
#ifdef  MTK_CAMERAPROFILE_SUPPORT
    AutoCPTLog cptlog(Event_CS_stopPreview);
#endif
    LOG1("stopPreview (pid %d)", getCallingPid());
    Mutex::Autolock lock(mLock);
    if (checkPidAndHardware() != NO_ERROR) return;


    disableMsgType(CAMERA_MSG_PREVIEW_FRAME);
    mHardware->stopPreview();

    mPreviewBuffer.clear();
}

// stop recording mode
void CameraClient::stopRecording() {
#ifdef  MTK_CAMERAPROFILE_SUPPORT
    AutoCPTLog cptlog(Event_CS_stopRecording);
#endif
    LOG1("stopRecording (pid %d)", getCallingPid());
    Mutex::Autolock lock(mLock);
    if (checkPidAndHardware() != NO_ERROR) return;

    disableMsgType(CAMERA_MSG_VIDEO_FRAME);
    mHardware->stopRecording();
    //!++
#ifdef  MTK_CAMERA_BSP_SUPPORT
    playRecordingSound();
#else
    mCameraService->playSound(CameraService::SOUND_RECORDING);
#endif
    //!--
    mPreviewBuffer.clear();
}

// release a recording frame
void CameraClient::releaseRecordingFrame(const sp<IMemory>& mem) {
#ifdef  MTK_CAMERAPROFILE_SUPPORT
    AutoCPTLog cptlog(Event_CS_releaseRecordingFrame);
#endif
#ifdef  MTK_CAMERA_BSP_SUPPORT
        ssize_t offset;
        size_t size;
        sp<IMemoryHeap> heap = mem->getMemory(&offset, &size);
        void *data = ((uint8_t *)heap->base()) + offset;
        LOG1("RRF:VA(0x%08X)", (uint32_t)data);
#endif
    Mutex::Autolock lock(mLock);
#ifdef  MTK_CAMERA_BSP_SUPPORT
    LOG1("RRF:VA(0x%08X), get mLock (%d)", (uint32_t)data, getCallingPid()); 
#endif
    if (checkPidAndHardware() != NO_ERROR) return;
    mHardware->releaseRecordingFrame(mem);
}

status_t CameraClient::storeMetaDataInBuffers(bool enabled)
{
    LOG1("storeMetaDataInBuffers: %s", enabled? "true": "false");
    Mutex::Autolock lock(mLock);
    if (checkPidAndHardware() != NO_ERROR) {
        return UNKNOWN_ERROR;
    }
    return mHardware->storeMetaDataInBuffers(enabled);
}

bool CameraClient::previewEnabled() {
    LOG1("previewEnabled (pid %d)", getCallingPid());

    Mutex::Autolock lock(mLock);
    if (checkPidAndHardware() != NO_ERROR) return false;
    return mHardware->previewEnabled();
}

bool CameraClient::recordingEnabled() {
    LOG1("recordingEnabled (pid %d)", getCallingPid());

    Mutex::Autolock lock(mLock);
    if (checkPidAndHardware() != NO_ERROR) return false;
    return mHardware->recordingEnabled();
}

status_t CameraClient::autoFocus() {
    LOG1("autoFocus (pid %d)", getCallingPid());

    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    return mHardware->autoFocus();
}

status_t CameraClient::cancelAutoFocus() {
    LOG1("cancelAutoFocus (pid %d)", getCallingPid());

    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    return mHardware->cancelAutoFocus();
}

// take a picture - image is returned in callback
status_t CameraClient::takePicture(int msgType) {
#ifdef  MTK_CAMERAPROFILE_SUPPORT
    AutoCPTLog cptlog(Event_CS_takePicture);
#endif
    LOG1("takePicture (pid %d): 0x%x", getCallingPid(), msgType);

    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    if ((msgType & CAMERA_MSG_RAW_IMAGE) &&
        (msgType & CAMERA_MSG_RAW_IMAGE_NOTIFY)) {
        ALOGE("CAMERA_MSG_RAW_IMAGE and CAMERA_MSG_RAW_IMAGE_NOTIFY"
                " cannot be both enabled");
        return BAD_VALUE;
    }

    // We only accept picture related message types
    // and ignore other types of messages for takePicture().
    int picMsgType = msgType
                        & (CAMERA_MSG_SHUTTER |
                           CAMERA_MSG_POSTVIEW_FRAME |
                           CAMERA_MSG_RAW_IMAGE |
                           CAMERA_MSG_RAW_IMAGE_NOTIFY |
                           CAMERA_MSG_COMPRESSED_IMAGE);

    enableMsgType(picMsgType);

    return mHardware->takePicture();
}

// set preview/capture parameters - key/value pairs
status_t CameraClient::setParameters(const String8& params) {
#ifdef  MTK_CAMERAPROFILE_SUPPORT
    AutoCPTLog cptlog(Event_CS_setParameters);
#endif
    LOG1("setParameters (pid %d) (%s)", getCallingPid(), params.string());

    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    CameraParameters p(params);
    return mHardware->setParameters(p);
}

// get preview/capture parameters - key/value pairs
String8 CameraClient::getParameters() const {
#ifdef  MTK_CAMERAPROFILE_SUPPORT
    AutoCPTLog cptlog(Event_CS_getParameters);
#endif
    Mutex::Autolock lock(mLock);
    if (checkPidAndHardware() != NO_ERROR) return String8();

    String8 params(mHardware->getParameters().flatten());
    LOG1("getParameters (pid %d) (%s)", getCallingPid(), params.string());
    return params;
}

// enable shutter sound
status_t CameraClient::enableShutterSound(bool enable) {
    LOG1("enableShutterSound (pid %d)", getCallingPid());

    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    if (enable) {
        mPlayShutterSound = true;
        return OK;
    }

    // Disabling shutter sound may not be allowed. In that case only
    // allow the mediaserver process to disable the sound.
    char value[PROPERTY_VALUE_MAX];
    property_get("ro.camera.sound.forced", value, "0");
    if (strcmp(value, "0") != 0) {
        // Disabling shutter sound is not allowed. Deny if the current
        // process is not mediaserver.
        if (getCallingPid() != getpid()) {
            ALOGE("Failed to disable shutter sound. Permission denied (pid %d)", getCallingPid());
            return PERMISSION_DENIED;
        }
    }

    mPlayShutterSound = false;
    return OK;
}

status_t CameraClient::sendCommand(int32_t cmd, int32_t arg1, int32_t arg2) {
#ifdef  MTK_CAMERAPROFILE_SUPPORT
    AutoCPTLog cptlog(Event_CS_sendCommand, arg1, arg2);
#endif
    LOG1("sendCommand (pid %d)", getCallingPid());
    int orientation;
    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    if (cmd == CAMERA_CMD_SET_DISPLAY_ORIENTATION) {
#ifdef  MTK_CAMERA_BSP_SUPPORT
        LOG1("CAMERA_CMD_SET_DISPLAY_ORIENTATION - tid(%d), (degrees, mirror)=(%d, %d)", ::gettid(), arg1, mCameraFacing);
#endif
        // Mirror the preview if the camera is front-facing.
        orientation = getOrientation(arg1, mCameraFacing == CAMERA_FACING_FRONT);
        if (orientation == -1) return BAD_VALUE;

        if (mOrientation != orientation) {
            mOrientation = orientation;
            if (mPreviewWindow != 0) {
                native_window_set_buffers_transform(mPreviewWindow.get(),
                        mOrientation);
            }
        }
        return OK;
    } else if (cmd == CAMERA_CMD_ENABLE_SHUTTER_SOUND) {
        switch (arg1) {
            case 0:
                return enableShutterSound(false);
            case 1:
                return enableShutterSound(true);
            default:
                return BAD_VALUE;
        }
        return OK;
    } else if (cmd == CAMERA_CMD_PLAY_RECORDING_SOUND) {
        mCameraService->playSound(CameraService::SOUND_RECORDING);
    } else if (cmd == CAMERA_CMD_SET_VIDEO_BUFFER_COUNT) {
        // Silently ignore this command
        return INVALID_OPERATION;
    } else if (cmd == CAMERA_CMD_PING) {
        // If mHardware is 0, checkPidAndHardware will return error.
        return OK;
    }

    return mHardware->sendCommand(cmd, arg1, arg2);
}

// ----------------------------------------------------------------------------

void CameraClient::enableMsgType(int32_t msgType) {
    android_atomic_or(msgType, &mMsgEnabled);
    mHardware->enableMsgType(msgType);
}

void CameraClient::disableMsgType(int32_t msgType) {
    android_atomic_and(~msgType, &mMsgEnabled);
#ifdef  MTK_CAMERA_BSP_SUPPORT
    if (mHardware == 0) {
        ALOGW("[disableMsgType] mHardware == 0 (CallingPid %d) (tid %d)", getCallingPid(), ::gettid());
        return;
    }
#endif
    mHardware->disableMsgType(msgType);
}

#define CHECK_MESSAGE_INTERVAL 10 // 10ms
bool CameraClient::lockIfMessageWanted(int32_t msgType) {
    #ifdef  MTK_CAMERA_BSP_SUPPORT
        return true;
    #endif  //MTK_CAMERA_BSP_SUPPORT

    int sleepCount = 0;
    while (mMsgEnabled & msgType) {
        if (mLock.tryLock() == NO_ERROR) {
            if (sleepCount > 0) {
                LOG1("lockIfMessageWanted(%d): waited for %d ms",
                    msgType, sleepCount * CHECK_MESSAGE_INTERVAL);
            }
            return true;
        }
        if (sleepCount++ == 0) {
            LOG1("lockIfMessageWanted(%d): enter sleep", msgType);
        }
        usleep(CHECK_MESSAGE_INTERVAL * 1000);
    }
    ALOGW("lockIfMessageWanted(%d): dropped unwanted message", msgType);
    return false;
}

// Callback messages can be dispatched to internal handlers or pass to our
// client's callback functions, depending on the message type.
//
// notifyCallback:
//      CAMERA_MSG_SHUTTER              handleShutter
//      (others)                        c->notifyCallback
// dataCallback:
//      CAMERA_MSG_PREVIEW_FRAME        handlePreviewData
//      CAMERA_MSG_POSTVIEW_FRAME       handlePostview
//      CAMERA_MSG_RAW_IMAGE            handleRawPicture
//      CAMERA_MSG_COMPRESSED_IMAGE     handleCompressedPicture
//      (others)                        c->dataCallback
// dataCallbackTimestamp
//      (others)                        c->dataCallbackTimestamp
//
// NOTE: the *Callback functions grab mLock of the client before passing
// control to handle* functions. So the handle* functions must release the
// lock before calling the ICameraClient's callbacks, so those callbacks can
// invoke methods in the Client class again (For example, the preview frame
// callback may want to releaseRecordingFrame). The handle* functions must
// release the lock after all accesses to member variables, so it must be
// handled very carefully.

void CameraClient::notifyCallback(int32_t msgType, int32_t ext1,
        int32_t ext2, void* user) {
    LOG2("notifyCallback(%d)", msgType);

    Mutex* lock = getClientLockFromCookie(user);
    if (lock == NULL) return;
    Mutex::Autolock alock(*lock);

    CameraClient* client =
            static_cast<CameraClient*>(getClientFromCookie(user));
    if (client == NULL) return;

    if (!client->lockIfMessageWanted(msgType)) return;

    switch (msgType) {

#ifdef  MTK_CAMERA_BSP_SUPPORT
        case MTK_CAMERA_MSG_EXT_NOTIFY:
            client->handleMtkExtNotify(ext1, ext2);
            break;
#endif  //MTK_CAMERA_BSP_SUPPORT

        case CAMERA_MSG_SHUTTER:
            // ext1 is the dimension of the yuv picture.
            client->handleShutter();
            break;
        default:
            client->handleGenericNotify(msgType, ext1, ext2);
            break;
    }
}

void CameraClient::dataCallback(int32_t msgType,
        const sp<IMemory>& dataPtr, camera_frame_metadata_t *metadata, void* user) {
    LOG2("dataCallback(%d)", msgType);

    Mutex* lock = getClientLockFromCookie(user);
    if (lock == NULL) return;
    Mutex::Autolock alock(*lock);

    CameraClient* client =
            static_cast<CameraClient*>(getClientFromCookie(user));
    if (client == NULL) return;

    if (!client->lockIfMessageWanted(msgType)) return;
    if (dataPtr == 0 && metadata == NULL) {
        ALOGE("Null data returned in data callback");
        client->handleGenericNotify(CAMERA_MSG_ERROR, UNKNOWN_ERROR, 0);
        return;
    }

    switch (msgType & ~CAMERA_MSG_PREVIEW_METADATA) {

#ifdef  MTK_CAMERA_BSP_SUPPORT
        case MTK_CAMERA_MSG_EXT_DATA:
            client->handleMtkExtData(dataPtr, metadata);
            break;
#endif  //MTK_CAMERA_BSP_SUPPORT

        case CAMERA_MSG_PREVIEW_FRAME:
            client->handlePreviewData(msgType, dataPtr, metadata);
            break;
        case CAMERA_MSG_POSTVIEW_FRAME:
            client->handlePostview(dataPtr);
            break;
        case CAMERA_MSG_RAW_IMAGE:
            client->handleRawPicture(dataPtr);
            break;
        case CAMERA_MSG_COMPRESSED_IMAGE:
            client->handleCompressedPicture(dataPtr);
            break;
        default:
            client->handleGenericData(msgType, dataPtr, metadata);
            break;
    }
}

void CameraClient::dataCallbackTimestamp(nsecs_t timestamp,
        int32_t msgType, const sp<IMemory>& dataPtr, void* user) {
#ifdef  MTK_CAMERAPROFILE_SUPPORT
    AutoCPTLog cptlog(Event_CS_dataCallbackTimestamp);
#endif
    LOG2("dataCallbackTimestamp(%d)", msgType);

    Mutex* lock = getClientLockFromCookie(user);
    if (lock == NULL) return;
    Mutex::Autolock alock(*lock);

    CameraClient* client =
            static_cast<CameraClient*>(getClientFromCookie(user));
    if (client == NULL) return;

    if (!client->lockIfMessageWanted(msgType)) return;

    if (dataPtr == 0) {
        ALOGE("Null data returned in data with timestamp callback");
        client->handleGenericNotify(CAMERA_MSG_ERROR, UNKNOWN_ERROR, 0);
        return;
    }

    client->handleGenericDataTimestamp(timestamp, msgType, dataPtr);
}

// snapshot taken callback
void CameraClient::handleShutter(void) {
    if (mPlayShutterSound) {
        mCameraService->playSound(CameraService::SOUND_SHUTTER);
    }

    sp<ICameraClient> c = mCameraClient;
    if (c != 0) {
        mLock.unlock();
        c->notifyCallback(CAMERA_MSG_SHUTTER, 0, 0);
        if (!lockIfMessageWanted(CAMERA_MSG_SHUTTER)) return;
    }
    disableMsgType(CAMERA_MSG_SHUTTER);

    mLock.unlock();
}

// preview callback - frame buffer update
void CameraClient::handlePreviewData(int32_t msgType,
                                              const sp<IMemory>& mem,
                                              camera_frame_metadata_t *metadata) {
    ssize_t offset;
    size_t size;
    sp<IMemoryHeap> heap = mem->getMemory(&offset, &size);

    // local copy of the callback flags
    int flags = mPreviewCallbackFlag;

    // is callback enabled?
    if (!(flags & CAMERA_FRAME_CALLBACK_FLAG_ENABLE_MASK)) {
        // If the enable bit is off, the copy-out and one-shot bits are ignored
        LOG2("frame callback is disabled");
        mLock.unlock();
        return;
    }

    // hold a strong pointer to the client
    sp<ICameraClient> c = mCameraClient;

    // clear callback flags if no client or one-shot mode
    if (c == 0 || (mPreviewCallbackFlag & CAMERA_FRAME_CALLBACK_FLAG_ONE_SHOT_MASK)) {
        LOG2("Disable preview callback");
        mPreviewCallbackFlag &= ~(CAMERA_FRAME_CALLBACK_FLAG_ONE_SHOT_MASK |
                                  CAMERA_FRAME_CALLBACK_FLAG_COPY_OUT_MASK |
                                  CAMERA_FRAME_CALLBACK_FLAG_ENABLE_MASK);
        disableMsgType(CAMERA_MSG_PREVIEW_FRAME);
    }

    if (c != 0) {
        // Is the received frame copied out or not?
        if (flags & CAMERA_FRAME_CALLBACK_FLAG_COPY_OUT_MASK) {
            LOG2("frame is copied");
            copyFrameAndPostCopiedFrame(msgType, c, heap, offset, size, metadata);
        } else {
            LOG2("frame is forwarded");
            mLock.unlock();
            c->dataCallback(msgType, mem, metadata);
        }
    } else {
        mLock.unlock();
    }
}

// picture callback - postview image ready
void CameraClient::handlePostview(const sp<IMemory>& mem) {
    disableMsgType(CAMERA_MSG_POSTVIEW_FRAME);

    sp<ICameraClient> c = mCameraClient;
    mLock.unlock();
    if (c != 0) {
        c->dataCallback(CAMERA_MSG_POSTVIEW_FRAME, mem, NULL);
    }
}

// picture callback - raw image ready
void CameraClient::handleRawPicture(const sp<IMemory>& mem) {
    disableMsgType(CAMERA_MSG_RAW_IMAGE);

    ssize_t offset;
    size_t size;
    sp<IMemoryHeap> heap = mem->getMemory(&offset, &size);

    sp<ICameraClient> c = mCameraClient;
    mLock.unlock();
    if (c != 0) {
        c->dataCallback(CAMERA_MSG_RAW_IMAGE, mem, NULL);
    }
}

// picture callback - compressed picture ready
void CameraClient::handleCompressedPicture(const sp<IMemory>& mem) {
    disableMsgType(CAMERA_MSG_COMPRESSED_IMAGE);

    sp<ICameraClient> c = mCameraClient;
    mLock.unlock();
    if (c != 0) {
        c->dataCallback(CAMERA_MSG_COMPRESSED_IMAGE, mem, NULL);
    }
}


void CameraClient::handleGenericNotify(int32_t msgType,
    int32_t ext1, int32_t ext2) {
    sp<ICameraClient> c = mCameraClient;
    mLock.unlock();
    if (c != 0) {
        c->notifyCallback(msgType, ext1, ext2);
    }
}

void CameraClient::handleGenericData(int32_t msgType,
    const sp<IMemory>& dataPtr, camera_frame_metadata_t *metadata) {
    sp<ICameraClient> c = mCameraClient;
    mLock.unlock();
    if (c != 0) {
        c->dataCallback(msgType, dataPtr, metadata);
    }
}

void CameraClient::handleGenericDataTimestamp(nsecs_t timestamp,
    int32_t msgType, const sp<IMemory>& dataPtr) {
    sp<ICameraClient> c = mCameraClient;
#if MTK_CAMERA_BSP_SUPPORT
#else
    mLock.unlock();
#endif
    if (c != 0) {
        c->dataCallbackTimestamp(timestamp, msgType, dataPtr);
    }
}

void CameraClient::copyFrameAndPostCopiedFrame(
        int32_t msgType, const sp<ICameraClient>& client,
        const sp<IMemoryHeap>& heap, size_t offset, size_t size,
        camera_frame_metadata_t *metadata) {
    LOG2("copyFrameAndPostCopiedFrame");
    // It is necessary to copy out of pmem before sending this to
    // the callback. For efficiency, reuse the same MemoryHeapBase
    // provided it's big enough. Don't allocate the memory or
    // perform the copy if there's no callback.
    // hold the preview lock while we grab a reference to the preview buffer
    sp<MemoryHeapBase> previewBuffer;

    if (mPreviewBuffer == 0) {
        mPreviewBuffer = new MemoryHeapBase(size, 0, NULL);
    } else if (size > mPreviewBuffer->virtualSize()) {
        mPreviewBuffer.clear();
        mPreviewBuffer = new MemoryHeapBase(size, 0, NULL);
    }
    if (mPreviewBuffer == 0) {
        ALOGE("failed to allocate space for preview buffer");
        mLock.unlock();
        return;
    }
    previewBuffer = mPreviewBuffer;

    memcpy(previewBuffer->base(), (uint8_t *)heap->base() + offset, size);

    sp<MemoryBase> frame = new MemoryBase(previewBuffer, 0, size);
    if (frame == 0) {
        ALOGE("failed to allocate space for frame callback");
        mLock.unlock();
        return;
    }

    mLock.unlock();
    client->dataCallback(msgType, frame, metadata);
}

int CameraClient::getOrientation(int degrees, bool mirror) {
    if (!mirror) {
        if (degrees == 0) return 0;
        else if (degrees == 90) return HAL_TRANSFORM_ROT_90;
        else if (degrees == 180) return HAL_TRANSFORM_ROT_180;
        else if (degrees == 270) return HAL_TRANSFORM_ROT_270;
    } else {  // Do mirror (horizontal flip)
        if (degrees == 0) {           // FLIP_H and ROT_0
            return HAL_TRANSFORM_FLIP_H;
        } else if (degrees == 90) {   // FLIP_H and ROT_90
            return HAL_TRANSFORM_FLIP_H | HAL_TRANSFORM_ROT_90;
        } else if (degrees == 180) {  // FLIP_H and ROT_180
            return HAL_TRANSFORM_FLIP_V;
        } else if (degrees == 270) {  // FLIP_H and ROT_270
            return HAL_TRANSFORM_FLIP_V | HAL_TRANSFORM_ROT_90;
        }
    }
    ALOGE("Invalid setDisplayOrientation degrees=%d", degrees);
    return -1;
}

}; // namespace android
