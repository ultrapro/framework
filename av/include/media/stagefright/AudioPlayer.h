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

#ifndef AUDIO_PLAYER_H_

#define AUDIO_PLAYER_H_

#include <media/MediaPlayerInterface.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/TimeSource.h>
#include <utils/threads.h>

namespace android {

class MediaSource;
class AudioTrack;
class AwesomePlayer;

class AudioPlayer : public TimeSource {
public:
    enum {
        REACHED_EOS,
        SEEK_COMPLETE
    };

    AudioPlayer(const sp<MediaPlayerBase::AudioSink> &audioSink,
                bool allowDeepBuffering = false,
                AwesomePlayer *audioObserver = NULL);

    virtual ~AudioPlayer();

    // Caller retains ownership of "source".
    void setSource(const sp<MediaSource> &source);

    // Return time in us.
    virtual int64_t getRealTimeUs();

    status_t start(bool sourceAlreadyStarted = false);

    void pause(bool playPendingSamples = false);
    void resume();

    // Returns the timestamp of the last buffer played (in us).
    int64_t getMediaTimeUs();

    // Returns true iff a mapping is established, i.e. the AudioPlayer
    // has played at least one frame of audio.
    bool getMediaTimeMapping(int64_t *realtime_us, int64_t *mediatime_us);

    status_t seekTo(int64_t time_us);

    bool isSeeking();
    bool reachedEOS(status_t *finalStatus);

    status_t setPlaybackRatePermille(int32_t ratePermille);

private:
    friend class VideoEditorAudioPlayer;
    sp<MediaSource> mSource;
    AudioTrack *mAudioTrack;

    MediaBuffer *mInputBuffer;

    int mSampleRate;
    int64_t mLatencyUs;
    size_t mFrameSize;

    Mutex mLock;
    int64_t mNumFramesPlayed;
    int64_t mNumFramesPlayedSysTimeUs;

    int64_t mPositionTimeMediaUs;
    int64_t mPositionTimeRealUs;

    bool mSeeking;
    bool mReachedEOS;
    status_t mFinalStatus;
    int64_t mSeekTimeUs;

    bool mStarted;

    bool mIsFirstBuffer;
    status_t mFirstBufferResult;
    MediaBuffer *mFirstBuffer;

    sp<MediaPlayerBase::AudioSink> mAudioSink;
    bool mAllowDeepBuffering;       // allow audio deep audio buffers. Helps with low power audio
                                    // playback but implies high latency
    AwesomePlayer *mObserver;
    int64_t mPinnedTimeUs;

    static void AudioCallback(int event, void *user, void *info);
    void AudioCallback(int event, void *info);

    static size_t AudioSinkCallback(
            MediaPlayerBase::AudioSink *audioSink,
            void *data, size_t size, void *me);

    size_t fillBuffer(void *data, size_t size);

    int64_t getRealTimeUsLocked() const;

    void reset();

    uint32_t getNumFramesPendingPlayout() const;

    AudioPlayer(const AudioPlayer &);
    AudioPlayer &operator=(const AudioPlayer &);
#ifndef ANDROID_DEFAULT_CODE
public:
	void enableAudioPad();
	uint32_t getNumFramesPlayout() const;
private:
		
    int64_t mLastBufferTimeUs;
    int64_t mLastBufferSize; 
    mutable int64_t mAVSyncLastRealTime;    
    bool mPadEnable; 
#endif
};

}  // namespace android

#endif  // AUDIO_PLAYER_H_
