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

#ifndef ANDROID_IAUDIOFLINGER_H
#define ANDROID_IAUDIOFLINGER_H

#include <stdint.h>
#include <sys/types.h>
#include <unistd.h>

#include <utils/RefBase.h>
#include <utils/Errors.h>
#include <binder/IInterface.h>
#include <media/IAudioTrack.h>
#include <media/IAudioRecord.h>
#include <media/IAudioFlingerClient.h>
#include <system/audio.h>
#include <system/audio_policy.h>
#include <hardware/audio_policy.h>
#include <hardware/audio_effect.h>
#include <media/IEffect.h>
#include <media/IEffectClient.h>
#include <utils/String8.h>

namespace android {

// ----------------------------------------------------------------------------

class IAudioFlinger : public IInterface
{
public:
    DECLARE_META_INTERFACE(AudioFlinger);

    // or-able bits shared by createTrack and openRecord, but not all combinations make sense
    enum {
        TRACK_DEFAULT = 0,  // client requests a default AudioTrack
        TRACK_TIMED   = 1,  // client requests a TimedAudioTrack
        TRACK_FAST    = 2,  // client requests a fast AudioTrack
    };
    typedef uint32_t track_flags_t;

    /* create an audio track and registers it with AudioFlinger.
     * return null if the track cannot be created.
     */
    virtual sp<IAudioTrack> createTrack(
                                pid_t pid,
                                audio_stream_type_t streamType,
                                uint32_t sampleRate,
                                audio_format_t format,
                                audio_channel_mask_t channelMask,
                                int frameCount,
                                track_flags_t flags,
                                const sp<IMemory>& sharedBuffer,
                                audio_io_handle_t output,
                                pid_t tid,  // -1 means unused, otherwise must be valid non-0
                                int *sessionId,
                                status_t *status) = 0;

    virtual sp<IAudioRecord> openRecord(
                                pid_t pid,
                                audio_io_handle_t input,
                                uint32_t sampleRate,
                                audio_format_t format,
                                audio_channel_mask_t channelMask,
                                int frameCount,
                                track_flags_t flags,
                                pid_t tid,  // -1 means unused, otherwise must be valid non-0
                                int *sessionId,
                                status_t *status) = 0;

    /* query the audio hardware state. This state never changes,
     * and therefore can be cached.
     */
    virtual     uint32_t    sampleRate(audio_io_handle_t output) const = 0;
#if 0
    virtual     int         channelCount(audio_io_handle_t output) const = 0;
#endif
    virtual     audio_format_t format(audio_io_handle_t output) const = 0;
    virtual     size_t      frameCount(audio_io_handle_t output) const = 0;

    // return estimated latency in milliseconds
    virtual     uint32_t    latency(audio_io_handle_t output) const = 0;

    /* set/get the audio hardware state. This will probably be used by
     * the preference panel, mostly.
     */
    virtual     status_t    setMasterVolume(float value) = 0;
    virtual     status_t    setMasterMute(bool muted) = 0;

    virtual     float       masterVolume() const = 0;
    virtual     bool        masterMute() const = 0;

    /* set/get stream type state. This will probably be used by
     * the preference panel, mostly.
     */
    virtual     status_t    setStreamVolume(audio_stream_type_t stream, float value,
                                    audio_io_handle_t output) = 0;
    virtual     status_t    setStreamMute(audio_stream_type_t stream, bool muted) = 0;

    virtual     float       streamVolume(audio_stream_type_t stream,
                                    audio_io_handle_t output) const = 0;
    virtual     bool        streamMute(audio_stream_type_t stream) const = 0;

    // set audio mode
    virtual     status_t    setMode(audio_mode_t mode) = 0;

    // mic mute/state
    virtual     status_t    setMicMute(bool state) = 0;
    virtual     bool        getMicMute() const = 0;

    virtual     status_t    setParameters(audio_io_handle_t ioHandle,
                                    const String8& keyValuePairs) = 0;
    virtual     String8     getParameters(audio_io_handle_t ioHandle, const String8& keys) const = 0;

    // register a current process for audio output change notifications
    virtual void registerClient(const sp<IAudioFlingerClient>& client) = 0;

    // retrieve the audio recording buffer size
    virtual size_t getInputBufferSize(uint32_t sampleRate, audio_format_t format,
            audio_channel_mask_t channelMask) const = 0;

    virtual audio_io_handle_t openOutput(audio_module_handle_t module,
                                         audio_devices_t *pDevices,
                                         uint32_t *pSamplingRate,
                                         audio_format_t *pFormat,
                                         audio_channel_mask_t *pChannelMask,
                                         uint32_t *pLatencyMs,
                                         audio_output_flags_t flags) = 0;
    virtual audio_io_handle_t openDuplicateOutput(audio_io_handle_t output1,
                                    audio_io_handle_t output2) = 0;
    virtual status_t closeOutput(audio_io_handle_t output) = 0;
    virtual status_t suspendOutput(audio_io_handle_t output) = 0;
    virtual status_t restoreOutput(audio_io_handle_t output) = 0;

    virtual audio_io_handle_t openInput(audio_module_handle_t module,
                                        audio_devices_t *pDevices,
                                        uint32_t *pSamplingRate,
                                        audio_format_t *pFormat,
                                        audio_channel_mask_t *pChannelMask) = 0;
    virtual status_t closeInput(audio_io_handle_t input) = 0;

    virtual status_t setStreamOutput(audio_stream_type_t stream, audio_io_handle_t output) = 0;

    virtual status_t setVoiceVolume(float volume) = 0;

    virtual status_t getRenderPosition(uint32_t *halFrames, uint32_t *dspFrames,
                                    audio_io_handle_t output) const = 0;

    virtual unsigned int getInputFramesLost(audio_io_handle_t ioHandle) const = 0;

    virtual int newAudioSessionId() = 0;

    virtual void acquireAudioSessionId(int audioSession) = 0;
    virtual void releaseAudioSessionId(int audioSession) = 0;

    virtual status_t queryNumberEffects(uint32_t *numEffects) const = 0;

    virtual status_t queryEffect(uint32_t index, effect_descriptor_t *pDescriptor) const = 0;

    virtual status_t getEffectDescriptor(const effect_uuid_t *pEffectUUID,
                                        effect_descriptor_t *pDescriptor) const = 0;

    virtual sp<IEffect> createEffect(pid_t pid,
                                    effect_descriptor_t *pDesc,
                                    const sp<IEffectClient>& client,
                                    int32_t priority,
                                    audio_io_handle_t output,
                                    int sessionId,
                                    status_t *status,
                                    int *id,
                                    int *enabled) = 0;

    virtual status_t moveEffects(int session, audio_io_handle_t srcOutput,
                                    audio_io_handle_t dstOutput) = 0;

    virtual audio_module_handle_t loadHwModule(const char *name) = 0;

    // helpers for android.media.AudioManager.getProperty(), see description there for meaning
    // FIXME move these APIs to AudioPolicy to permit a more accurate implementation
    // that looks on primary device for a stream with fast flag, primary flag, or first one.
    virtual int32_t getPrimaryOutputSamplingRate() = 0;
    virtual int32_t getPrimaryOutputFrameCount() = 0;
#ifdef MTK_AUDIO
	// Interfaces mtk added 
    // add , get EM parameter
    virtual status_t GetEMParameter(void *ptr, size_t len) = 0;
    virtual status_t SetEMParameter(void *ptr, size_t len) = 0;
    virtual status_t SetAudioCommand(int parameters1, int parameter2) = 0;
    virtual status_t GetAudioCommand(int parameters1) = 0;
    virtual status_t SetAudioData(int par1,size_t len,void *ptr)=0;
    virtual status_t GetAudioData(int par1,size_t len,void *ptr)=0;
    //add by Tina, set acf preview param
    virtual status_t SetACFPreviewParameter(void *ptr, size_t len) = 0;
    virtual status_t SetHCFPreviewParameter(void *ptr, size_t len) = 0;
    /////////////////////////////////////////////////////////////////////////
    //    for PCMxWay Interface API ...   
    /////////////////////////////////////////////////////////////////////////
    virtual int xWayPlay_Start(int sample_rate) = 0;
    virtual int xWayPlay_Stop(void) = 0;
    virtual int xWayPlay_Write(void *buffer, int size_bytes) = 0;
    virtual int xWayPlay_GetFreeBufferCount(void) = 0;
    virtual int xWayRec_Start(int sample_rate) = 0;
    virtual int xWayRec_Stop(void) = 0;
    virtual int xWayRec_Read(void *buffer, int size_bytes) = 0;
    //wendy 
    virtual int ReadRefFromRing(void*buf, uint32_t datasz, void* DLtime) = 0;
    virtual int GetVoiceUnlockULTime(void* DLtime) = 0;
    virtual int SetVoiceUnlockSRC(uint outSR, uint outCH) = 0;    
    virtual bool startVoiceUnlockDL() = 0;    
    virtual bool stopVoiceUnlockDL() = 0;
    virtual void freeVoiceUnlockDLInstance () = 0;
    virtual int GetVoiceUnlockDLLatency() = 0;
    virtual bool getVoiceUnlockDLInstance() = 0;
#endif

};


// ----------------------------------------------------------------------------

class BnAudioFlinger : public BnInterface<IAudioFlinger>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_IAUDIOFLINGER_H
