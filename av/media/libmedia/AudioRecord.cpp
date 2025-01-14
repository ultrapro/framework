/*
**
** Copyright 2008, The Android Open Source Project
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

#define LOG_NDEBUG 0
#define LOG_TAG "AudioRecord"

#include <sys/resource.h>
#include <sys/types.h>

#include <binder/IPCThreadState.h>
#include <cutils/atomic.h>
#include <cutils/compiler.h>
#include <media/AudioRecord.h>
#include <media/AudioSystem.h>
#include <system/audio.h>
#include <utils/Log.h>

#include <private/media/AudioTrackShared.h>

#ifndef ANDROID_DEFAULT_CODE
#include <cutils/xlog.h>
#endif

namespace android {
// ---------------------------------------------------------------------------
#ifndef ANDROID_DEFAULT_CODE
    static AudioEffect *mpAEC = NULL;
    static AudioEffect *mpAGC = NULL;
    static AudioEffect *mpNS = NULL;
#endif
// static
status_t AudioRecord::getMinFrameCount(
        int* frameCount,
        uint32_t sampleRate,
        audio_format_t format,
        audio_channel_mask_t channelMask)
{
    if (frameCount == NULL) return BAD_VALUE;

    // default to 0 in case of error
    *frameCount = 0;

    size_t size = 0;
    if (AudioSystem::getInputBufferSize(sampleRate, format, channelMask, &size)
            != NO_ERROR) {
        ALOGE("AudioSystem could not query the input buffer size.");
        return NO_INIT;
    }

    if (size == 0) {
        ALOGE("Unsupported configuration: sampleRate %d, format %d, channelMask %#x",
            sampleRate, format, channelMask);
        return BAD_VALUE;
    }

    // We double the size of input buffer for ping pong use of record buffer.
    size <<= 1;

    if (audio_is_linear_pcm(format)) {
        int channelCount = popcount(channelMask);
        size /= channelCount * audio_bytes_per_sample(format);
    }

    *frameCount = size;
    return NO_ERROR;
}

// ---------------------------------------------------------------------------

AudioRecord::AudioRecord()
    : mStatus(NO_INIT), mSessionId(0),
      mPreviousPriority(ANDROID_PRIORITY_NORMAL), mPreviousSchedulingGroup(SP_DEFAULT)
{
}

AudioRecord::AudioRecord(
        audio_source_t inputSource,
        uint32_t sampleRate,
        audio_format_t format,
        audio_channel_mask_t channelMask,
        int frameCount,
        callback_t cbf,
        void* user,
        int notificationFrames,
        int sessionId)
    : mStatus(NO_INIT), mSessionId(0),
      mPreviousPriority(ANDROID_PRIORITY_NORMAL), mPreviousSchedulingGroup(SP_DEFAULT)
{
    mStatus = set(inputSource, sampleRate, format, channelMask,
            frameCount, cbf, user, notificationFrames, sessionId);
}
//MTK80721 HDRecord 2011-12-23
//#ifdef MTK_AUDIO_HD_REC_SUPPORT
#ifndef ANDROID_DEFAULT_CODE
AudioRecord::AudioRecord(
        audio_source_t inputSource,
        String8 Params,
        uint32_t sampleRate,
        audio_format_t format,
        audio_channel_mask_t channelMask,
        int frameCount,
        callback_t cbf,
        void* user,
        int notificationFrames,
        int sessionId)
{
/*
    const sp<IAudioFlinger>& audioFlinger = AudioSystem::get_audio_flinger();
    LOGD("audioFlinger->setParameters,%s+",HDRecordMode.string());
    audioFlinger->setParameters(0, HDRecordMode);
    LOGD("audioFlinger->setParameters,%s-",HDRecordMode.string());

    AudioRecord(inputSource, sampleRate, format, channels, frameCount, flags, cbf, user, notificationFrames, sessionId);
*/
	

    mStatus = NO_INIT;
    mSessionId = 0;
    
    if (Params.find("HDREC_SET_VOICE_MODE") > -1 || Params.find("HDREC_SET_VIDEO_MODE") > -1
        || Params.find("LRChannelSwitch") > -1)
    {
    const sp<IAudioFlinger>& audioFlinger = AudioSystem::get_audio_flinger();
    
    ALOGD("audioFlinger->setParameters,%s+",Params.string());
    status_t sResult = audioFlinger->setParameters(0, Params);    
        ALOGD("audioFlinger->setParameters:%s-,result=%d",Params.string(),sResult);    
    }
    //if (sResult != NO_ERROR)
    //{
    //    SXLOGE("audioFlinger->setParameters,error:%s,%d",Params.string(),sResult);    
    //}
    //else
    //{
    //    SXLOGD("audioFlinger->setParameters,%s-",Params.string());
    //}
    mStatus = set(inputSource, sampleRate, format, channelMask,
            frameCount, cbf, user, notificationFrames,false,sessionId);
    
    ssize_t effectpos = Params.find("PREPROCESS_EFFECT") ;
    if (effectpos > -1)
    {
        const char *cparams = Params.string();
        const char *key_start = cparams+effectpos;
        const char *equal_pos = strchr(key_start, '=');
        if (equal_pos == NULL)
        {
            ALOGE("Preprocess effect miss a value");
        }
        else
        {
            const char *value_start = equal_pos + 1;
            const char *semicolon_pos = strchr(value_start, ';');
            String8 value=String8("0");
            value.setTo(value_start, semicolon_pos - value_start);
            int ieffect = atoi(value.string());
            char pUUID[37] = "";
            //ref:Record.java:initAndStartMediaRecorder
            int iAEC=1, iNS=iAEC<<1, iAGC = iAEC<<2; 
            //AudioEffect.java:AEC/NS/AGC
            if ((ieffect & iAEC) > 0)
            {
                if (mpAEC == NULL)
                {
                    strcpy(pUUID, "0a8abfe0-654c-11e0-ba26-0002a5d5c51b");
                    ALOGD("create AEC effect+");
                    mpAEC = new AudioEffect(pUUID,NULL,0,NULL,NULL,mSessionId,0);
                    ALOGD("create AEC effect-");
                }
                ALOGD("AEC effect setEnable+");
                mpAEC->setEnabled(true);
                ALOGD("AEC effect setEnable-");
            }
            if ((ieffect & iNS) > 0)
            {
                if (mpNS == NULL)
                {
                    strcpy(pUUID, "58b4b260-8e06-11e0-aa8e-0002a5d5c51b");
                    ALOGD("create NS effect+");
                    mpNS = new AudioEffect(pUUID,NULL,0,NULL,NULL,mSessionId,0);
                    ALOGD("create NS effect-");
                }
                mpNS->setEnabled(true);
            }
            if ((ieffect & iAGC) > 0)
            {
                if (mpAGC == NULL)
                {
                    strcpy(pUUID, "0a8abfe0-654c-11e0-ba26-0002a5d5c51b");
                    mpAGC= new AudioEffect(pUUID,NULL,0,NULL,NULL,mSessionId,0);
                }
                mpAGC->setEnabled(true);
            }
        }
    }
}

void AudioRecord::fn_ReleaseEffect(AudioEffect *&pEffect)
{
    if (pEffect != NULL)
    {
        pEffect->setEnabled(false);
        delete pEffect;
        pEffect = NULL;
    }
}
#endif
//

AudioRecord::~AudioRecord()
{
    if (mStatus == NO_ERROR) {
        // Make sure that callback function exits in the case where
        // it is looping on buffer empty condition in obtainBuffer().
        // Otherwise the callback thread will never exit.
        stop();
        if (mAudioRecordThread != 0) {
            mAudioRecordThread->requestExit();  // see comment in AudioRecord.h
            mAudioRecordThread->requestExitAndWait();
            mAudioRecordThread.clear();
        }
        mAudioRecord.clear();
        IPCThreadState::self()->flushCommands();

#ifndef ANDROID_DEFAULT_CODE
        fn_ReleaseEffect(mpAEC);
        fn_ReleaseEffect(mpAGC);
        fn_ReleaseEffect(mpNS);
#endif

        AudioSystem::releaseAudioSessionId(mSessionId);
    }
}

status_t AudioRecord::set(
        audio_source_t inputSource,
        uint32_t sampleRate,
        audio_format_t format,
        audio_channel_mask_t channelMask,
        int frameCount,
        callback_t cbf,
        void* user,
        int notificationFrames,
        bool threadCanCallJava,
        int sessionId)
{

    ALOGV("set(): sampleRate %d, channelMask %#x, frameCount %d",sampleRate, channelMask, frameCount);

    AutoMutex lock(mLock);

    if (mAudioRecord != 0) {
        return INVALID_OPERATION;
    }

    if (inputSource == AUDIO_SOURCE_DEFAULT) {
        inputSource = AUDIO_SOURCE_MIC;
    }

    if (sampleRate == 0) {
        sampleRate = DEFAULT_SAMPLE_RATE;
    }
    // these below should probably come from the audioFlinger too...
    if (format == AUDIO_FORMAT_DEFAULT) {
        format = AUDIO_FORMAT_PCM_16_BIT;
    }
    // validate parameters
    if (!audio_is_valid_format(format)) {
        ALOGE("Invalid format");
        return BAD_VALUE;
    }

    if (!audio_is_input_channel(channelMask)) {
        return BAD_VALUE;
    }

    int channelCount = popcount(channelMask);

    if (sessionId == 0 ) {
        mSessionId = AudioSystem::newAudioSessionId();
    } else {
        mSessionId = sessionId;
    }
    ALOGV("set(): mSessionId %d", mSessionId);
#ifndef ANDROID_DEFAULT_CODE
    /*for audioflinger is only support 1 recordhandle. for giving more time to let last recordhandle release, we add retry 3 times.*/
    int err_try=0;

INPUT_LOOP:

#endif
    audio_io_handle_t input = AudioSystem::getInput(inputSource,
                                                    sampleRate,
                                                    format,
                                                    channelMask,
                                                    mSessionId);
#ifndef ANDROID_DEFAULT_CODE
    /*for audioflinger is only support 1 recordhandle. for giving more time to let last recordhandle release, we add retry 3 times.*/
    if((input == 0)&& (err_try<3))
    {
        usleep(5000);
        ALOGD("getInput(): fail retry %d", err_try);
        err_try++;
        goto INPUT_LOOP;
    }
#endif

    if (input == 0) {
        ALOGE("Could not get audio input for record source %d", inputSource);
        return BAD_VALUE;
    }

    // validate framecount
    int minFrameCount = 0;
    status_t status = getMinFrameCount(&minFrameCount, sampleRate, format, channelMask);
    if (status != NO_ERROR) {
        return status;
    }
    ALOGV("AudioRecord::set() minFrameCount = %d", minFrameCount);

    if (frameCount == 0) {
        frameCount = minFrameCount;
    } else if (frameCount < minFrameCount) {
        return BAD_VALUE;
    }

    if (notificationFrames == 0) {
        notificationFrames = frameCount/2;
    }

    // create the IAudioRecord
    status = openRecord_l(sampleRate, format, channelMask,
                        frameCount, input);
    if (status != NO_ERROR) {
        return status;
    }

    if (cbf != NULL) {
        mAudioRecordThread = new AudioRecordThread(*this, threadCanCallJava);
        mAudioRecordThread->run("AudioRecord", ANDROID_PRIORITY_AUDIO);
    }

    mStatus = NO_ERROR;

    mFormat = format;
    // Update buffer size in case it has been limited by AudioFlinger during track creation
    mFrameCount = mCblk->frameCount;
    mChannelCount = (uint8_t)channelCount;
    mChannelMask = channelMask;
    mActive = false;
    mCbf = cbf;
    mNotificationFrames = notificationFrames;
    mRemainingFrames = notificationFrames;
    mUserData = user;
    // TODO: add audio hardware input latency here
    mLatency = (1000*mFrameCount) / sampleRate;
    mMarkerPosition = 0;
    mMarkerReached = false;
    mNewPosition = 0;
    mUpdatePeriod = 0;
    mInputSource = inputSource;
    mInput = input;
    AudioSystem::acquireAudioSessionId(mSessionId);

    return NO_ERROR;
}

status_t AudioRecord::initCheck() const
{
    return mStatus;
}

// -------------------------------------------------------------------------

uint32_t AudioRecord::latency() const
{
    return mLatency;
}

audio_format_t AudioRecord::format() const
{
    return mFormat;
}

int AudioRecord::channelCount() const
{
    return mChannelCount;
}

uint32_t AudioRecord::frameCount() const
{
    return mFrameCount;
}

size_t AudioRecord::frameSize() const
{
    if (audio_is_linear_pcm(mFormat)) {
        return channelCount()*audio_bytes_per_sample(mFormat);
    }
#ifndef ANDROID_DEFAULT_CODE
    else if (mFormat == AUDIO_FORMAT_VM_FMT) {
        return channelCount()*sizeof(int16_t);
    }
#endif
    else {
        return sizeof(uint8_t);
    }
}

audio_source_t AudioRecord::inputSource() const
{
    return mInputSource;
}

// -------------------------------------------------------------------------

status_t AudioRecord::start(AudioSystem::sync_event_t event, int triggerSession)
{
    status_t ret = NO_ERROR;
    sp<AudioRecordThread> t = mAudioRecordThread;

    ALOGV("start, sync event %d trigger session %d", event, triggerSession);

    AutoMutex lock(mLock);
    // acquire a strong reference on the IAudioRecord and IMemory so that they cannot be destroyed
    // while we are accessing the cblk
    sp<IAudioRecord> audioRecord = mAudioRecord;
    sp<IMemory> iMem = mCblkMemory;
    audio_track_cblk_t* cblk = mCblk;

    if (!mActive) {
        mActive = true;

        cblk->lock.lock();
        if (!(cblk->flags & CBLK_INVALID_MSK)) {
            cblk->lock.unlock();
            ALOGV("mAudioRecord->start()");
            ret = mAudioRecord->start(event, triggerSession);
            cblk->lock.lock();
            if (ret == DEAD_OBJECT) {
                android_atomic_or(CBLK_INVALID_ON, &cblk->flags);
            }
        }
        if (cblk->flags & CBLK_INVALID_MSK) {
            ret = restoreRecord_l(cblk);
        }
        cblk->lock.unlock();
        if (ret == NO_ERROR) {
            mNewPosition = cblk->user + mUpdatePeriod;
            cblk->bufferTimeoutMs = (event == AudioSystem::SYNC_EVENT_NONE) ? MAX_RUN_TIMEOUT_MS :
                                            AudioSystem::kSyncRecordStartTimeOutMs;
            cblk->waitTimeMs = 0;
            if (t != 0) {
                t->resume();
            } else {
                mPreviousPriority = getpriority(PRIO_PROCESS, 0);
                get_sched_policy(0, &mPreviousSchedulingGroup);
                androidSetThreadPriority(0, ANDROID_PRIORITY_AUDIO);
            }
        } else {
            mActive = false;
        }
    }

    return ret;
}

void AudioRecord::stop()
{
    sp<AudioRecordThread> t = mAudioRecordThread;

    ALOGV("stop");

    AutoMutex lock(mLock);
    if (mActive) {
        mActive = false;
        mCblk->cv.signal();
        mAudioRecord->stop();
        // the record head position will reset to 0, so if a marker is set, we need
        // to activate it again
        mMarkerReached = false;
        if (t != 0) {
            t->pause();
        } else {
            setpriority(PRIO_PROCESS, 0, mPreviousPriority);
            set_sched_policy(0, mPreviousSchedulingGroup);
        }
    }
}

bool AudioRecord::stopped() const
{
    AutoMutex lock(mLock);
    return !mActive;
}

uint32_t AudioRecord::getSampleRate() const
{
    return mCblk->sampleRate;
}

status_t AudioRecord::setMarkerPosition(uint32_t marker)
{
    if (mCbf == NULL) return INVALID_OPERATION;

    AutoMutex lock(mLock);
    mMarkerPosition = marker;
    mMarkerReached = false;

    return NO_ERROR;
}

status_t AudioRecord::getMarkerPosition(uint32_t *marker) const
{
    if (marker == NULL) return BAD_VALUE;

    AutoMutex lock(mLock);
    *marker = mMarkerPosition;

    return NO_ERROR;
}

status_t AudioRecord::setPositionUpdatePeriod(uint32_t updatePeriod)
{
    if (mCbf == NULL) return INVALID_OPERATION;

    uint32_t curPosition;
    getPosition(&curPosition);

    AutoMutex lock(mLock);
    mNewPosition = curPosition + updatePeriod;
    mUpdatePeriod = updatePeriod;

    return NO_ERROR;
}

status_t AudioRecord::getPositionUpdatePeriod(uint32_t *updatePeriod) const
{
    if (updatePeriod == NULL) return BAD_VALUE;

    AutoMutex lock(mLock);
    *updatePeriod = mUpdatePeriod;

    return NO_ERROR;
}

status_t AudioRecord::getPosition(uint32_t *position) const
{
    if (position == NULL) return BAD_VALUE;

    AutoMutex lock(mLock);
    *position = mCblk->user;

    return NO_ERROR;
}

unsigned int AudioRecord::getInputFramesLost() const
{
    // no need to check mActive, because if inactive this will return 0, which is what we want
        return AudioSystem::getInputFramesLost(mInput);
}

// -------------------------------------------------------------------------

// must be called with mLock held
status_t AudioRecord::openRecord_l(
        uint32_t sampleRate,
        audio_format_t format,
        audio_channel_mask_t channelMask,
        int frameCount,
        audio_io_handle_t input)
{
    status_t status;
    const sp<IAudioFlinger>& audioFlinger = AudioSystem::get_audio_flinger();
    if (audioFlinger == 0) {
        return NO_INIT;
    }

    pid_t tid = -1;
    // FIXME see similar logic at AudioTrack

    int originalSessionId = mSessionId;
    sp<IAudioRecord> record = audioFlinger->openRecord(getpid(), input,
                                                       sampleRate, format,
                                                       channelMask,
                                                       frameCount,
                                                       IAudioFlinger::TRACK_DEFAULT,
                                                       tid,
                                                       &mSessionId,
                                                       &status);
    ALOGE_IF(originalSessionId != 0 && mSessionId != originalSessionId,
            "session ID changed from %d to %d", originalSessionId, mSessionId);

    if (record == 0) {
        ALOGE("AudioFlinger could not create record track, status: %d", status);
        return status;
    }
    sp<IMemory> cblk = record->getCblk();
    if (cblk == 0) {
        ALOGE("Could not get control block");
        return NO_INIT;
    }
    mAudioRecord.clear();
    mAudioRecord = record;
    mCblkMemory.clear();
    mCblkMemory = cblk;
    mCblk = static_cast<audio_track_cblk_t*>(cblk->pointer());
    mCblk->buffers = (char*)mCblk + sizeof(audio_track_cblk_t);
    android_atomic_and(~CBLK_DIRECTION_MSK, &mCblk->flags);
    mCblk->bufferTimeoutMs = MAX_RUN_TIMEOUT_MS;
    mCblk->waitTimeMs = 0;
    return NO_ERROR;
}

status_t AudioRecord::obtainBuffer(Buffer* audioBuffer, int32_t waitCount)
{
    AutoMutex lock(mLock);
    bool active;
    status_t result = NO_ERROR;
    audio_track_cblk_t* cblk = mCblk;
    uint32_t framesReq = audioBuffer->frameCount;
    uint32_t waitTimeMs = (waitCount < 0) ? cblk->bufferTimeoutMs : WAIT_PERIOD_MS;

    audioBuffer->frameCount  = 0;
    audioBuffer->size        = 0;

    uint32_t framesReady = cblk->framesReady();

    if (framesReady == 0) {
        cblk->lock.lock();
        goto start_loop_here;
        while (framesReady == 0) {
            active = mActive;
            if (CC_UNLIKELY(!active)) {
                cblk->lock.unlock();
                return NO_MORE_BUFFERS;
            }
            if (CC_UNLIKELY(!waitCount)) {
                cblk->lock.unlock();
                return WOULD_BLOCK;
            }
            if (!(cblk->flags & CBLK_INVALID_MSK)) {
                mLock.unlock();
                result = cblk->cv.waitRelative(cblk->lock, milliseconds(waitTimeMs));
                cblk->lock.unlock();
                mLock.lock();
                if (!mActive) {
                    return status_t(STOPPED);
                }
                cblk->lock.lock();
            }
            if (cblk->flags & CBLK_INVALID_MSK) {
                goto create_new_record;
            }
            if (CC_UNLIKELY(result != NO_ERROR)) {
                cblk->waitTimeMs += waitTimeMs;
                if (cblk->waitTimeMs >= cblk->bufferTimeoutMs) {
                    ALOGW(   "obtainBuffer timed out (is the CPU pegged?) "
                            "user=%08x, server=%08x", cblk->user, cblk->server);
                    cblk->lock.unlock();
                    // callback thread or sync event hasn't changed
                    result = mAudioRecord->start(AudioSystem::SYNC_EVENT_SAME, 0);
                    cblk->lock.lock();
                    if (result == DEAD_OBJECT) {
                        android_atomic_or(CBLK_INVALID_ON, &cblk->flags);
create_new_record:
                        result = AudioRecord::restoreRecord_l(cblk);
                    }
                    if (result != NO_ERROR) {
                        ALOGW("obtainBuffer create Track error %d", result);
                        cblk->lock.unlock();
                        return result;
                    }
                    cblk->waitTimeMs = 0;
                }
                if (--waitCount == 0) {
                    cblk->lock.unlock();
                    return TIMED_OUT;
                }
            }
            // read the server count again
        start_loop_here:
            framesReady = cblk->framesReady();
        }
        cblk->lock.unlock();
    }

    cblk->waitTimeMs = 0;
    // reset time out to running value after obtaining a buffer
    cblk->bufferTimeoutMs = MAX_RUN_TIMEOUT_MS;

    if (framesReq > framesReady) {
        framesReq = framesReady;
    }

    uint32_t u = cblk->user;
    uint32_t bufferEnd = cblk->userBase + cblk->frameCount;

    if (framesReq > bufferEnd - u) {
        framesReq = bufferEnd - u;
    }

    audioBuffer->flags       = 0;
    audioBuffer->channelCount= mChannelCount;
    audioBuffer->format      = mFormat;
    audioBuffer->frameCount  = framesReq;
    audioBuffer->size        = framesReq*cblk->frameSize;
    audioBuffer->raw         = (int8_t*)cblk->buffer(u);
    active = mActive;
    return active ? status_t(NO_ERROR) : status_t(STOPPED);
}

void AudioRecord::releaseBuffer(Buffer* audioBuffer)
{
    AutoMutex lock(mLock);
    mCblk->stepUser(audioBuffer->frameCount);
}

audio_io_handle_t AudioRecord::getInput() const
{
    AutoMutex lock(mLock);
    return mInput;
}

// must be called with mLock held
audio_io_handle_t AudioRecord::getInput_l()
{
    mInput = AudioSystem::getInput(mInputSource,
                                mCblk->sampleRate,
                                mFormat,
                                mChannelMask,
                                mSessionId);
    return mInput;
}

int AudioRecord::getSessionId() const
{
    // no lock needed because session ID doesn't change after first set()
    return mSessionId;
}

// -------------------------------------------------------------------------

ssize_t AudioRecord::read(void* buffer, size_t userSize)
{
    ssize_t read = 0;
    Buffer audioBuffer;
    int8_t *dst = static_cast<int8_t*>(buffer);

    if (ssize_t(userSize) < 0) {
        // sanity-check. user is most-likely passing an error code.
        ALOGE("AudioRecord::read(buffer=%p, size=%u (%d)",
                buffer, userSize, userSize);
        return BAD_VALUE;
    }

    mLock.lock();
    // acquire a strong reference on the IAudioRecord and IMemory so that they cannot be destroyed
    // while we are accessing the cblk
    sp<IAudioRecord> audioRecord = mAudioRecord;
    sp<IMemory> iMem = mCblkMemory;
    mLock.unlock();

    do {

        audioBuffer.frameCount = userSize/frameSize();

        // By using a wait count corresponding to twice the timeout period in
        // obtainBuffer() we give a chance to recover once for a read timeout
        // (if media_server crashed for instance) before returning a length of
        // 0 bytes read to the client
        status_t err = obtainBuffer(&audioBuffer, ((2 * MAX_RUN_TIMEOUT_MS) / WAIT_PERIOD_MS));
        if (err < 0) {
            // out of buffers, return #bytes written
            if (err == status_t(NO_MORE_BUFFERS))
                break;
            if (err == status_t(TIMED_OUT))
                err = 0;
            return ssize_t(err);
        }

        size_t bytesRead = audioBuffer.size;
        memcpy(dst, audioBuffer.i8, bytesRead);

        dst += bytesRead;
        userSize -= bytesRead;
        read += bytesRead;

        releaseBuffer(&audioBuffer);
    } while (userSize);

    return read;
}

// -------------------------------------------------------------------------

bool AudioRecord::processAudioBuffer(const sp<AudioRecordThread>& thread)
{
    Buffer audioBuffer;
    uint32_t frames = mRemainingFrames;
    size_t readSize;

    mLock.lock();
    // acquire a strong reference on the IAudioRecord and IMemory so that they cannot be destroyed
    // while we are accessing the cblk
    sp<IAudioRecord> audioRecord = mAudioRecord;
    sp<IMemory> iMem = mCblkMemory;
    audio_track_cblk_t* cblk = mCblk;
    bool active = mActive;
    uint32_t markerPosition = mMarkerPosition;
    uint32_t newPosition = mNewPosition;
    uint32_t user = cblk->user;
    // determine whether a marker callback will be needed, while locked
    bool needMarker = !mMarkerReached && (mMarkerPosition > 0) && (user >= mMarkerPosition);
    if (needMarker) {
        mMarkerReached = true;
    }
    // determine the number of new position callback(s) that will be needed, while locked
    uint32_t updatePeriod = mUpdatePeriod;
    uint32_t needNewPos = updatePeriod > 0 && user >= newPosition ?
            ((user - newPosition) / updatePeriod) + 1 : 0;
    mNewPosition = newPosition + updatePeriod * needNewPos;
    mLock.unlock();

    // perform marker callback, while unlocked
    if (needMarker) {
        mCbf(EVENT_MARKER, mUserData, &markerPosition);
    }

    // perform new position callback(s), while unlocked
    for (; needNewPos > 0; --needNewPos) {
        uint32_t temp = newPosition;
        mCbf(EVENT_NEW_POS, mUserData, &temp);
        newPosition += updatePeriod;
    }

    do {
        audioBuffer.frameCount = frames;
        // Calling obtainBuffer() with a wait count of 1
        // limits wait time to WAIT_PERIOD_MS. This prevents from being
        // stuck here not being able to handle timed events (position, markers).
        status_t err = obtainBuffer(&audioBuffer, 1);
        if (err < NO_ERROR) {
            if (err != TIMED_OUT) {
                ALOGE_IF(err != status_t(NO_MORE_BUFFERS), "Error obtaining an audio buffer, giving up.");
#ifndef ANDROID_DEFAULT_CODE                                                    
                mCbf(EVENT_WAIT_TIEMOUT, mUserData, 0);
#endif
                return false;
            }
            break;
        }
        if (err == status_t(STOPPED)) return false;

        size_t reqSize = audioBuffer.size;
        mCbf(EVENT_MORE_DATA, mUserData, &audioBuffer);
        readSize = audioBuffer.size;

        // Sanity check on returned size
        if (ssize_t(readSize) <= 0) {
            // The callback is done filling buffers
            // Keep this thread going to handle timed events and
            // still try to get more data in intervals of WAIT_PERIOD_MS
            // but don't just loop and block the CPU, so wait
            usleep(WAIT_PERIOD_MS*1000);
            break;
        }
        if (readSize > reqSize) readSize = reqSize;

        audioBuffer.size = readSize;
        audioBuffer.frameCount = readSize/frameSize();
        frames -= audioBuffer.frameCount;

        releaseBuffer(&audioBuffer);

    } while (frames);


    // Manage overrun callback
    if (active && (cblk->framesAvailable() == 0)) {
        // The value of active is stale, but we are almost sure to be active here because
        // otherwise we would have exited when obtainBuffer returned STOPPED earlier.
        ALOGV("Overrun user: %x, server: %x, flags %04x", cblk->user, cblk->server, cblk->flags);
        if (!(android_atomic_or(CBLK_UNDERRUN_ON, &cblk->flags) & CBLK_UNDERRUN_MSK)) {
            mCbf(EVENT_OVERRUN, mUserData, NULL);
        }
    }

    if (frames == 0) {
        mRemainingFrames = mNotificationFrames;
    } else {
        mRemainingFrames = frames;
    }
    return true;
}

// must be called with mLock and cblk.lock held. Callers must also hold strong references on
// the IAudioRecord and IMemory in case they are recreated here.
// If the IAudioRecord is successfully restored, the cblk pointer is updated
status_t AudioRecord::restoreRecord_l(audio_track_cblk_t*& cblk)
{
    status_t result;

    if (!(android_atomic_or(CBLK_RESTORING_ON, &cblk->flags) & CBLK_RESTORING_MSK)) {
        ALOGW("dead IAudioRecord, creating a new one");
        // signal old cblk condition so that other threads waiting for available buffers stop
        // waiting now
        cblk->cv.broadcast();
        cblk->lock.unlock();

        // if the new IAudioRecord is created, openRecord_l() will modify the
        // following member variables: mAudioRecord, mCblkMemory and mCblk.
        // It will also delete the strong references on previous IAudioRecord and IMemory
        result = openRecord_l(cblk->sampleRate, mFormat, mChannelMask,
                mFrameCount, getInput_l());
        if (result == NO_ERROR) {
            // callback thread or sync event hasn't changed
            result = mAudioRecord->start(AudioSystem::SYNC_EVENT_SAME, 0);
        }
        if (result != NO_ERROR) {
            mActive = false;
        }

        // signal old cblk condition for other threads waiting for restore completion
        android_atomic_or(CBLK_RESTORED_ON, &cblk->flags);
        cblk->cv.broadcast();
    } else {
        if (!(cblk->flags & CBLK_RESTORED_MSK)) {
            ALOGW("dead IAudioRecord, waiting for a new one to be created");
            mLock.unlock();
            result = cblk->cv.waitRelative(cblk->lock, milliseconds(RESTORE_TIMEOUT_MS));
            cblk->lock.unlock();
            mLock.lock();
        } else {
            ALOGW("dead IAudioRecord, already restored");
            result = NO_ERROR;
            cblk->lock.unlock();
        }
        if (result != NO_ERROR || !mActive) {
            result = status_t(STOPPED);
        }
    }
    ALOGV("restoreRecord_l() status %d mActive %d cblk %p, old cblk %p flags %08x old flags %08x",
        result, mActive, mCblk, cblk, mCblk->flags, cblk->flags);

    if (result == NO_ERROR) {
        // from now on we switch to the newly created cblk
        cblk = mCblk;
    }
    cblk->lock.lock();

    ALOGW_IF(result != NO_ERROR, "restoreRecord_l() error %d", result);

    return result;
}

// =========================================================================

AudioRecord::AudioRecordThread::AudioRecordThread(AudioRecord& receiver, bool bCanCallJava)
    : Thread(bCanCallJava), mReceiver(receiver), mPaused(true)
{
}

AudioRecord::AudioRecordThread::~AudioRecordThread()
{
}

bool AudioRecord::AudioRecordThread::threadLoop()
{
    {
        AutoMutex _l(mMyLock);
        if (mPaused) {
            mMyCond.wait(mMyLock);
            // caller will check for exitPending()
            return true;
    }
    }
    if (!mReceiver.processAudioBuffer(this)) {
        pause();
    }
    return true;
}

void AudioRecord::AudioRecordThread::requestExit()
{
    // must be in this order to avoid a race condition
    Thread::requestExit();
    resume();
}

void AudioRecord::AudioRecordThread::pause()
{
    AutoMutex _l(mMyLock);
    mPaused = true;
}

void AudioRecord::AudioRecordThread::resume()
{
    AutoMutex _l(mMyLock);
    if (mPaused) {
        mPaused = false;
        mMyCond.signal();
    }
}

// -------------------------------------------------------------------------

}; // namespace android
