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

#ifndef ANDROID_LAYER_H
#define ANDROID_LAYER_H

#include <stdint.h>
#include <sys/types.h>

#include <gui/SurfaceTexture.h>

#include <utils/Timers.h>

#include <ui/GraphicBuffer.h>
#include <ui/PixelFormat.h>

#include <gui/ISurfaceComposerClient.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

#include "LayerBase.h"
#include "SurfaceTextureLayer.h"
#include "Transform.h"

namespace android {

// ---------------------------------------------------------------------------

class Client;
class GLExtensions;

// ---------------------------------------------------------------------------

class Layer : public LayerBaseClient
{
public:
            Layer(SurfaceFlinger* flinger, const sp<Client>& client);
    virtual ~Layer();

    virtual const char* getTypeId() const { return "Layer"; }

    // the this layer's size and format
    status_t setBuffers(uint32_t w, uint32_t h, 
            PixelFormat format, uint32_t flags=0);

    bool isFixedSize() const;

    // LayerBase interface
    virtual void setGeometry(const sp<const DisplayDevice>& hw,
            HWComposer::HWCLayerInterface& layer);
    virtual void setPerFrameData(const sp<const DisplayDevice>& hw,
            HWComposer::HWCLayerInterface& layer);
    virtual void setAcquireFence(const sp<const DisplayDevice>& hw,
            HWComposer::HWCLayerInterface& layer);
    virtual void onLayerDisplayed(const sp<const DisplayDevice>& hw,
            HWComposer::HWCLayerInterface* layer);
    virtual bool onPreComposition();
    virtual void onPostComposition();

    virtual void onDraw(const sp<const DisplayDevice>& hw, const Region& clip) const;
    virtual uint32_t doTransaction(uint32_t transactionFlags);
    virtual Region latchBuffer(bool& recomputeVisibleRegions);
    virtual bool isOpaque() const;
    virtual bool isSecure() const           { return mSecure; }
    virtual bool isProtected() const;
    virtual void onRemoved();
    virtual sp<Layer> getLayer() const { return const_cast<Layer*>(this); }
    virtual void setName(const String8& name);
    virtual bool isVisible() const;

    // LayerBaseClient interface
    virtual wp<IBinder> getSurfaceTextureBinder() const;

    // only for debugging
    inline const sp<GraphicBuffer>& getActiveBuffer() const { return mActiveBuffer; }

    // Updates the transform hint in our SurfaceTexture to match
    // the current orientation of the default display device.
    virtual void updateTransformHint() const;

protected:
    virtual void onFirstRef();
    virtual void dump(String8& result, char* scratch, size_t size) const;
    virtual void dumpStats(String8& result, char* buffer, size_t SIZE) const;
    virtual void clearStats();

private:
    friend class SurfaceTextureLayer;
    void onFrameQueued();
    virtual sp<ISurface> createSurface();
    uint32_t getEffectiveUsage(uint32_t usage) const;
    bool isCropped() const;
    Rect computeBufferCrop() const;
    static bool getOpacityForFormat(uint32_t format);

    // -----------------------------------------------------------------------

    // constants
    sp<SurfaceTexture> mSurfaceTexture;
    GLuint mTextureName;

    // thread-safe
    volatile int32_t mQueuedFrames;

    // main thread
    sp<GraphicBuffer> mActiveBuffer;
    Rect mCurrentCrop;
    uint32_t mCurrentTransform;
    uint32_t mCurrentScalingMode;
    bool mCurrentOpacity;
    bool mRefreshPending;
    bool mFrameLatencyNeeded;
    int mFrameLatencyOffset;

    struct Statistics {
        Statistics() : timestamp(0), set(0), vsync(0) { }
        nsecs_t timestamp;  // buffer timestamp
        nsecs_t set;        // buffer displayed timestamp
        nsecs_t vsync;      // vsync immediately before set
    };

    // protected by mLock
    Statistics mFrameStats[128];

    // constants
    PixelFormat mFormat;
    const GLExtensions& mGLExtensions;
    bool mOpaqueLayer;

    // page-flip thread (currently main thread)
    bool mSecure;         // no screenshots
    bool mProtectedByApp; // application requires protected path to external sink
    
    // [MTK] {{{
    void dumpActiveBuffer() const;            // debug info
    
    // for continuous buffers dump
    int mContBufsDumpById;
    uint32_t mBackupBufsIndex;
    uint32_t mBackupBufsMax; 
    Vector< sp<GraphicBuffer> > mBackBufs;
    
    virtual void setContBufsDumpById(int identity);        
    void dumpGraphicBuffer(sp<GraphicBuffer> buf, int index) const;
    void backupProcess(void* dst, void* src, size_t size);

public:
    void dumpContinuousBuffer() const;
    virtual void activeBufferBackup();
    virtual bool queryBackupBufs(){return mQueuedFrames > 0;};
    // [MTK] }}}
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_LAYER_H
