/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef CAMERA_SOURCE_H_

#define CAMERA_SOURCE_H_

#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaSource.h>
#include <camera/ICamera.h>
#include <camera/ICameraRecordingProxyListener.h>
#include <camera/CameraParameters.h>
#include <utils/List.h>
#include <utils/RefBase.h>

namespace android {

class IMemory;
class Camera;
class Surface;

class CameraSource : public MediaSource, public MediaBufferObserver {
public:
    /**
     * Factory method to create a new CameraSource using the current
     * settings (such as video size, frame rate, color format, etc)
     * from the default camera.
     *
     * @return NULL on error.
     */
    static CameraSource *Create();

    /**
     * Factory method to create a new CameraSource.
     *
     * @param camera the video input frame data source. If it is NULL,
     *          we will try to connect to the camera with the given
     *          cameraId.
     *
     * @param cameraId the id of the camera that the source will connect
     *          to if camera is NULL; otherwise ignored.
     *
     * @param videoSize the dimension (in pixels) of the video frame
     * @param frameRate the target frames per second
     * @param surface the preview surface for display where preview
     *          frames are sent to
     * @param storeMetaDataInVideoBuffers true to request the camera
     *          source to store meta data in video buffers; false to
     *          request the camera source to store real YUV frame data
     *          in the video buffers. The camera source may not support
     *          storing meta data in video buffers, if so, a request
     *          to do that will NOT be honored. To find out whether
     *          meta data is actually being stored in video buffers
     *          during recording, call isMetaDataStoredInVideoBuffers().
     *
     * @return NULL on error.
     */
    static CameraSource *CreateFromCamera(const sp<ICamera> &camera,
                                          const sp<ICameraRecordingProxy> &proxy,
                                          int32_t cameraId,
                                          Size videoSize,
                                          int32_t frameRate,
                                          const sp<Surface>& surface,
#ifndef ANDROID_DEFAULT_CODE
										 bool storeMetaDataInVideoBuffers = false, bool supportMCIbuffer = false);
#else
                                          bool storeMetaDataInVideoBuffers = false);
#endif

    virtual ~CameraSource();

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop() { return reset(); }
    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

    /**
     * Check whether a CameraSource object is properly initialized.
     * Must call this method before stop().
     * @return OK if initialization has successfully completed.
     */
    virtual status_t initCheck() const;

    /**
     * Returns the MetaData associated with the CameraSource,
     * including:
     * kKeyColorFormat: YUV color format of the video frames
     * kKeyWidth, kKeyHeight: dimension (in pixels) of the video frames
     * kKeySampleRate: frame rate in frames per second
     * kKeyMIMEType: always fixed to be MEDIA_MIMETYPE_VIDEO_RAW
     */
    virtual sp<MetaData> getFormat();

    /**
     * Tell whether this camera source stores meta data or real YUV
     * frame data in video buffers.
     *
     * @return true if meta data is stored in the video
     *      buffers; false if real YUV data is stored in
     *      the video buffers.
     */
    bool isMetaDataStoredInVideoBuffers() const;

    virtual void signalBufferReturned(MediaBuffer* buffer);
#ifdef MTK_USES_VR_DYNAMIC_QUALITY_MECHANISM
	//you should set a valid target_fps value,which is litter than init fps. It will used to calculate the number of frames from which we pick one frame and drop others in CameraSource.
	status_t changeCameraFrameRate(int32_t target_fps);
#endif

protected:
    class ProxyListener: public BnCameraRecordingProxyListener {
    public:
        ProxyListener(const sp<CameraSource>& source);
        virtual void dataCallbackTimestamp(int64_t timestampUs, int32_t msgType,
                const sp<IMemory> &data);

    private:
        sp<CameraSource> mSource;
    };

    // isBinderAlive needs linkToDeath to work.
    class DeathNotifier: public IBinder::DeathRecipient {
    public:
        DeathNotifier() {}
        virtual void binderDied(const wp<IBinder>& who);
    };

    enum CameraFlags {
        FLAGS_SET_CAMERA = 1L << 0,
        FLAGS_HOT_CAMERA = 1L << 1,
    };

    int32_t  mCameraFlags;
    Size     mVideoSize;
    int32_t  mNumInputBuffers;
    int32_t  mVideoFrameRate;
    int32_t  mColorFormat;
    status_t mInitCheck;

    sp<Camera>   mCamera;
    sp<ICameraRecordingProxy>   mCameraRecordingProxy;
    sp<DeathNotifier> mDeathNotifier;
    sp<Surface>  mSurface;
    sp<MetaData> mMeta;

    int64_t mStartTimeUs;
    int32_t mNumFramesReceived;
    int64_t mLastFrameTimestampUs;
    bool mStarted;
    int32_t mNumFramesEncoded;

    // Time between capture of two frames.
    int64_t mTimeBetweenFrameCaptureUs;

    CameraSource(const sp<ICamera>& camera, const sp<ICameraRecordingProxy>& proxy,
                 int32_t cameraId,
                 Size videoSize, int32_t frameRate,
                 const sp<Surface>& surface,
#ifndef ANDROID_DEFAULT_CODE
				bool storeMetaDataInVideoBuffers,bool supportMCIbuffer = false);	
#else
                 bool storeMetaDataInVideoBuffers);
#endif
    virtual void startCameraRecording();
    virtual void releaseRecordingFrame(const sp<IMemory>& frame);

    // Returns true if need to skip the current frame.
    // Called from dataCallbackTimestamp.
    virtual bool skipCurrentFrame(int64_t timestampUs) {return false;}

    // Callback called when still camera raw data is available.
    virtual void dataCallback(int32_t msgType, const sp<IMemory> &data) {}

    virtual void dataCallbackTimestamp(int64_t timestampUs, int32_t msgType,
            const sp<IMemory> &data);

private:
    friend class CameraSourceListener;

    Mutex mLock;
    Condition mFrameAvailableCondition;
    Condition mFrameCompleteCondition;
    List<sp<IMemory> > mFramesReceived;
    List<sp<IMemory> > mFramesBeingEncoded;
    List<int64_t> mFrameTimes;

    int64_t mFirstFrameTimeUs;
    int32_t mNumFramesDropped;
    int32_t mNumGlitches;
    int64_t mGlitchDurationThresholdUs;
    bool mCollectStats;
    bool mIsMetaDataStoredInVideoBuffers;

    void releaseQueuedFrames();
    void releaseOneRecordingFrame(const sp<IMemory>& frame);


    status_t init(const sp<ICamera>& camera, const sp<ICameraRecordingProxy>& proxy,
                  int32_t cameraId, Size videoSize, int32_t frameRate,
                  bool storeMetaDataInVideoBuffers);

    status_t initWithCameraAccess(
                  const sp<ICamera>& camera, const sp<ICameraRecordingProxy>& proxy,
                  int32_t cameraId, Size videoSize, int32_t frameRate,
                  bool storeMetaDataInVideoBuffers);

    status_t isCameraAvailable(const sp<ICamera>& camera,
                               const sp<ICameraRecordingProxy>& proxy,
                               int32_t cameraId);
    status_t isCameraColorFormatSupported(const CameraParameters& params);
    status_t configureCamera(CameraParameters* params,
                    int32_t width, int32_t height,
                    int32_t frameRate);

    status_t checkVideoSize(const CameraParameters& params,
                    int32_t width, int32_t height);

    status_t checkFrameRate(const CameraParameters& params,
                    int32_t frameRate);

    void stopCameraRecording();
    void releaseCamera();
    status_t reset();
#ifndef ANDROID_DEFAULT_CODE
	void releaseCamera_l(bool locked = false);
#ifdef MTK_USES_VR_DYNAMIC_QUALITY_MECHANISM
        float mDropRate;
	int32_t mNumRemainFrameReceived;
	int32_t mLastNumFramesReceived;
#endif
	int32_t mCamVideoBufferMode;
	//void* mVecCamBufInfo;
	uint32_t* mCamMemVaArray;
	int32_t* mCamMemIonFdArray;

	//for MCI buffer to save bandwith during recording
	bool mSupportMCIbuffer;
	void* mCamRecSetting;
	//CameraRecSetting mCamRecSetting;
#endif

    CameraSource(const CameraSource &);
    CameraSource &operator=(const CameraSource &);
};

}  // namespace android

#endif  // CAMERA_SOURCE_H_
