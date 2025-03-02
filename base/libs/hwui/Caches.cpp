/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "OpenGLRenderer"

#include <utils/Log.h>
#include <utils/String8.h>

#include "Caches.h"
#include "DisplayListRenderer.h"
#include "Properties.h"
#include "LayerRenderer.h"

namespace android {

#ifdef USE_OPENGL_RENDERER
using namespace uirenderer;
ANDROID_SINGLETON_STATIC_INSTANCE(Caches);
#endif

namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Macros
///////////////////////////////////////////////////////////////////////////////

#if DEBUG_CACHE_FLUSH
    #define FLUSH_LOGD(...) \
    {                                 \
        if (g_HWUI_debug_cache_flush) \
            ALOGD(__VA_ARGS__); \
    }
#else
    #define FLUSH_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

Caches::Caches(): Singleton<Caches>(), mInitialized(false) {
    init();
    initFont();
    initExtensions();
    initConstraints();
    initProperties();

    mDebugLevel = readDebugLevel();
    ALOGD("Enabling debug mode %d", mDebugLevel);
}

void Caches::init() {
    if (mInitialized) return;

    glGenBuffers(1, &meshBuffer);
    glBindBuffer(GL_ARRAY_BUFFER, meshBuffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(gMeshVertices), gMeshVertices, GL_STATIC_DRAW);

    mCurrentBuffer = meshBuffer;
    mCurrentIndicesBuffer = 0;
    mCurrentPositionPointer = this;
    mCurrentPositionStride = 0;
    mCurrentTexCoordsPointer = this;

    mTexCoordsArrayEnabled = false;

    glDisable(GL_SCISSOR_TEST);
    scissorEnabled = false;
    mScissorX = mScissorY = mScissorWidth = mScissorHeight = 0;

    glActiveTexture(gTextureUnits[0]);
    mTextureUnit = 0;

    mRegionMesh = NULL;

    blend = false;
    lastSrcMode = GL_ZERO;
    lastDstMode = GL_ZERO;
    currentProgram = NULL;

    mFunctorsCount = 0;

    mInitialized = true;
}

void Caches::initFont() {
    fontRenderer = GammaFontRenderer::createRenderer();
}

void Caches::initExtensions() {
    if (extensions.hasDebugMarker()) {
        eventMark = glInsertEventMarkerEXT;
        startMark = glPushGroupMarkerEXT;
        endMark = glPopGroupMarkerEXT;
    } else {
        eventMark = eventMarkNull;
        startMark = startMarkNull;
        endMark = endMarkNull;
    }

    if (extensions.hasDebugLabel()) {
        setLabel = glLabelObjectEXT;
        getLabel = glGetObjectLabelEXT;
    } else {
        setLabel = setLabelNull;
        getLabel = getLabelNull;
    }
}

void Caches::initConstraints() {
    GLint maxTextureUnits;
    glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, &maxTextureUnits);
    if (maxTextureUnits < REQUIRED_TEXTURE_UNITS_COUNT) {
        ALOGW("At least %d texture units are required!", REQUIRED_TEXTURE_UNITS_COUNT);
    }

    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTextureSize);
}

void Caches::initProperties() {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_DEBUG_LAYERS_UPDATES, property, NULL) > 0) {
        INIT_LOGD("  Layers updates debug enabled: %s", property);
        debugLayersUpdates = !strcmp(property, "true");
    } else {
        debugLayersUpdates = false;
    }

    if (property_get(PROPERTY_DEBUG_OVERDRAW, property, NULL) > 0) {
        INIT_LOGD("  Overdraw debug enabled: %s", property);
        debugOverdraw = !strcmp(property, "true");
    } else {
        debugOverdraw = false;
    }
}

void Caches::terminate() {
    if (!mInitialized) return;

    glDeleteBuffers(1, &meshBuffer);
    mCurrentBuffer = 0;

    glDeleteBuffers(1, &mRegionMeshIndices);
    delete[] mRegionMesh;
    mRegionMesh = NULL;

    fboCache.clear();

    programCache.clear();
    currentProgram = NULL;

    mInitialized = false;
}

///////////////////////////////////////////////////////////////////////////////
// Debug
///////////////////////////////////////////////////////////////////////////////

void Caches::dumpMemoryUsage() {
    String8 stringLog;
    dumpMemoryUsage(stringLog);
    ALOGD("%s", stringLog.string());
}

void Caches::dumpMemoryUsage(String8 &log) {
    log.appendFormat("Current memory usage / total memory usage (bytes):\n");
    log.appendFormat("  TextureCache         %8d / %8d\n",
            textureCache.getSize(), textureCache.getMaxSize());
    log.appendFormat("  LayerCache           %8d / %8d\n",
            layerCache.getSize(), layerCache.getMaxSize());
    log.appendFormat("  GradientCache        %8d / %8d\n",
            gradientCache.getSize(), gradientCache.getMaxSize());
    log.appendFormat("  PathCache            %8d / %8d\n",
            pathCache.getSize(), pathCache.getMaxSize());
    log.appendFormat("  CircleShapeCache     %8d / %8d\n",
            circleShapeCache.getSize(), circleShapeCache.getMaxSize());
    log.appendFormat("  OvalShapeCache       %8d / %8d\n",
            ovalShapeCache.getSize(), ovalShapeCache.getMaxSize());
    log.appendFormat("  RoundRectShapeCache  %8d / %8d\n",
            roundRectShapeCache.getSize(), roundRectShapeCache.getMaxSize());
    log.appendFormat("  RectShapeCache       %8d / %8d\n",
            rectShapeCache.getSize(), rectShapeCache.getMaxSize());
    log.appendFormat("  ArcShapeCache        %8d / %8d\n",
            arcShapeCache.getSize(), arcShapeCache.getMaxSize());
    log.appendFormat("  TextDropShadowCache  %8d / %8d\n", dropShadowCache.getSize(),
            dropShadowCache.getMaxSize());
    for (uint32_t i = 0; i < fontRenderer->getFontRendererCount(); i++) {
        const uint32_t size = fontRenderer->getFontRendererSize(i);
        log.appendFormat("  FontRenderer %d       %8d / %8d\n", i, size, size);
    }
    log.appendFormat("Other:\n");
    log.appendFormat("  FboCache             %8d / %8d\n",
            fboCache.getSize(), fboCache.getMaxSize());
    log.appendFormat("  PatchCache           %8d / %8d\n",
            patchCache.getSize(), patchCache.getMaxSize());

    uint32_t total = 0;
    total += textureCache.getSize();
    total += layerCache.getSize();
    total += gradientCache.getSize();
    total += pathCache.getSize();
    total += dropShadowCache.getSize();
    total += roundRectShapeCache.getSize();
    total += circleShapeCache.getSize();
    total += ovalShapeCache.getSize();
    total += rectShapeCache.getSize();
    total += arcShapeCache.getSize();
    for (uint32_t i = 0; i < fontRenderer->getFontRendererCount(); i++) {
        total += fontRenderer->getFontRendererSize(i);
    }

    log.appendFormat("Total memory usage:\n");
    log.appendFormat("  %d bytes, %.2f MB\n", total, total / 1024.0f / 1024.0f);
}

///////////////////////////////////////////////////////////////////////////////
// Memory management
///////////////////////////////////////////////////////////////////////////////

void Caches::clearGarbage() {
    textureCache.clearGarbage();
    pathCache.clearGarbage();

    Vector<DisplayList*> displayLists;
    Vector<Layer*> layers;

    { // scope for the lock
        Mutex::Autolock _l(mGarbageLock);
        displayLists = mDisplayListGarbage;
        layers = mLayerGarbage;
        mDisplayListGarbage.clear();
        mLayerGarbage.clear();
    }

    size_t count = displayLists.size();
    for (size_t i = 0; i < count; i++) {
        DisplayList* displayList = displayLists.itemAt(i);
        delete displayList;
    }

    count = layers.size();
    for (size_t i = 0; i < count; i++) {
        Layer* layer = layers.itemAt(i);
        delete layer;
    }
    layers.clear();
}

void Caches::deleteLayerDeferred(Layer* layer) {
    Mutex::Autolock _l(mGarbageLock);
    mLayerGarbage.push(layer);
}

void Caches::deleteDisplayListDeferred(DisplayList* displayList) {
    Mutex::Autolock _l(mGarbageLock);
    mDisplayListGarbage.push(displayList);
}

void Caches::flush(FlushMode mode) {
    FLUSH_LOGD("Flushing caches (mode %d)", mode);

    switch (mode) {
        case kFlushMode_Full:
            textureCache.clear();
            patchCache.clear();
            dropShadowCache.clear();
            gradientCache.clear();
            fontRenderer->clear();
            dither.clear();
            // fall through
        case kFlushMode_Moderate:
            fontRenderer->flush();
            textureCache.flush();
            pathCache.clear();
            roundRectShapeCache.clear();
            circleShapeCache.clear();
            ovalShapeCache.clear();
            rectShapeCache.clear();
            arcShapeCache.clear();
            // fall through
        case kFlushMode_Layers:
            layerCache.clear();
            break;
    }

    clearGarbage();
}

///////////////////////////////////////////////////////////////////////////////
// VBO
///////////////////////////////////////////////////////////////////////////////

bool Caches::bindMeshBuffer() {
    return bindMeshBuffer(meshBuffer);
}

bool Caches::bindMeshBuffer(const GLuint buffer) {
    if (mCurrentBuffer != buffer) {
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
        mCurrentBuffer = buffer;
        return true;
    }
    return false;
}

bool Caches::unbindMeshBuffer() {
    if (mCurrentBuffer) {
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        mCurrentBuffer = 0;
        return true;
    }
    return false;
}

bool Caches::bindIndicesBuffer(const GLuint buffer) {
    if (mCurrentIndicesBuffer != buffer) {
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffer);
        mCurrentIndicesBuffer = buffer;
        return true;
    }
    return false;
}

bool Caches::unbindIndicesBuffer() {
    if (mCurrentIndicesBuffer) {
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        mCurrentIndicesBuffer = 0;
        return true;
    }
    return false;
}

///////////////////////////////////////////////////////////////////////////////
// Meshes and textures
///////////////////////////////////////////////////////////////////////////////

void Caches::bindPositionVertexPointer(bool force, GLvoid* vertices, GLsizei stride) {
    if (force || vertices != mCurrentPositionPointer || stride != mCurrentPositionStride) {
        GLuint slot = currentProgram->position;
        glVertexAttribPointer(slot, 2, GL_FLOAT, GL_FALSE, stride, vertices);
        mCurrentPositionPointer = vertices;
        mCurrentPositionStride = stride;
    }
}

void Caches::bindTexCoordsVertexPointer(bool force, GLvoid* vertices) {
    if (force || vertices != mCurrentTexCoordsPointer) {
        GLuint slot = currentProgram->texCoords;
        glVertexAttribPointer(slot, 2, GL_FLOAT, GL_FALSE, gMeshStride, vertices);
        mCurrentTexCoordsPointer = vertices;
    }
}

void Caches::resetVertexPointers() {
    mCurrentPositionPointer = this;
    mCurrentTexCoordsPointer = this;
}

void Caches::resetTexCoordsVertexPointer() {
    mCurrentTexCoordsPointer = this;
}

void Caches::enableTexCoordsVertexArray() {
    if (!mTexCoordsArrayEnabled) {
        glEnableVertexAttribArray(Program::kBindingTexCoords);
        mCurrentTexCoordsPointer = this;
        mTexCoordsArrayEnabled = true;
    }
}

void Caches::disbaleTexCoordsVertexArray() {
    if (mTexCoordsArrayEnabled) {
        glDisableVertexAttribArray(Program::kBindingTexCoords);
        mTexCoordsArrayEnabled = false;
    }
}

void Caches::activeTexture(GLuint textureUnit) {
    if (mTextureUnit != textureUnit) {
        glActiveTexture(gTextureUnits[textureUnit]);
        mTextureUnit = textureUnit;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Scissor
///////////////////////////////////////////////////////////////////////////////

bool Caches::setScissor(GLint x, GLint y, GLint width, GLint height) {
    if (scissorEnabled && (x != mScissorX || y != mScissorY ||
            width != mScissorWidth || height != mScissorHeight)) {

        if (x < 0) {
            width += x;
            x = 0;
        }
        if (y < 0) {
            height += y;
            y = 0;
        }
        if (width < 0) {
            width = 0;
        }
        if (height < 0) {
            height = 0;
        }
        glScissor(x, y, width, height);

        mScissorX = x;
        mScissorY = y;
        mScissorWidth = width;
        mScissorHeight = height;

        return true;
    }
    return false;
}

bool Caches::enableScissor() {
    if (!scissorEnabled) {
        glEnable(GL_SCISSOR_TEST);
        scissorEnabled = true;
        resetScissor();
        return true;
    }
    return false;
}

bool Caches::disableScissor() {
    if (scissorEnabled) {
        glDisable(GL_SCISSOR_TEST);
        scissorEnabled = false;
        return true;
    }
    return false;
}

void Caches::setScissorEnabled(bool enabled) {
    if (scissorEnabled != enabled) {
        if (enabled) glEnable(GL_SCISSOR_TEST);
        else glDisable(GL_SCISSOR_TEST);
        scissorEnabled = enabled;
    }
}

void Caches::resetScissor() {
    mScissorX = mScissorY = mScissorWidth = mScissorHeight = 0;
}

///////////////////////////////////////////////////////////////////////////////
// Tiling
///////////////////////////////////////////////////////////////////////////////

void Caches::startTiling(GLuint x, GLuint y, GLuint width, GLuint height, bool opaque) {
    if (extensions.hasTiledRendering() && !debugOverdraw) {
        glStartTilingQCOM(x, y, width, height, (opaque ? GL_NONE : GL_COLOR_BUFFER_BIT0_QCOM));
    }
}

void Caches::endTiling() {
    if (extensions.hasTiledRendering() && !debugOverdraw) {
        glEndTilingQCOM(GL_COLOR_BUFFER_BIT0_QCOM);
    }
}

bool Caches::hasRegisteredFunctors() {
    return mFunctorsCount > 0;
}

void Caches::registerFunctors(uint32_t functorCount) {
    mFunctorsCount += functorCount;
}

void Caches::unregisterFunctors(uint32_t functorCount) {
    if (functorCount > mFunctorsCount) {
        mFunctorsCount = 0;
    } else {
        mFunctorsCount -= functorCount;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Regions
///////////////////////////////////////////////////////////////////////////////

TextureVertex* Caches::getRegionMesh() {
    // Create the mesh, 2 triangles and 4 vertices per rectangle in the region
    if (!mRegionMesh) {
        mRegionMesh = new TextureVertex[REGION_MESH_QUAD_COUNT * 4];

        uint16_t* regionIndices = new uint16_t[REGION_MESH_QUAD_COUNT * 6];
        for (int i = 0; i < REGION_MESH_QUAD_COUNT; i++) {
            uint16_t quad = i * 4;
            int index = i * 6;
            regionIndices[index    ] = quad;       // top-left
            regionIndices[index + 1] = quad + 1;   // top-right
            regionIndices[index + 2] = quad + 2;   // bottom-left
            regionIndices[index + 3] = quad + 2;   // bottom-left
            regionIndices[index + 4] = quad + 1;   // top-right
            regionIndices[index + 5] = quad + 3;   // bottom-right
        }

        glGenBuffers(1, &mRegionMeshIndices);
        bindIndicesBuffer(mRegionMeshIndices);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, REGION_MESH_QUAD_COUNT * 6 * sizeof(uint16_t),
                regionIndices, GL_STATIC_DRAW);

        delete[] regionIndices;
    } else {
        bindIndicesBuffer(mRegionMeshIndices);
    }

    return mRegionMesh;
}

bool Caches::checkInvalidDisplayLists() {
#if MTK_DEBUG_ERROR_CHECK
    Mutex::Autolock _l(mDisplayListLock);
    if (invalidDisplayLists.size() != 0) {
        ALOGW("Invalid DisplayLists: %d (%lld)", invalidDisplayLists.size(), systemTime(SYSTEM_TIME_MONOTONIC));
        for (size_t i = 0; i < invalidDisplayLists.size(); i++) {
            ALOGW("\t[%d] %p", i, invalidDisplayLists.itemAt(i));
        }
        invalidDisplayLists.clear();
        return false;
    }
#endif
    return true;
}

#if MTK_DEBUG_ERROR_CHECK
bool Caches::dumpActiveDisplayLists(DisplayList *displayList) {
    Mutex::Autolock _l(mDisplayListLock);
    ALOGW("Active DisplayLists: %d (%lld)", activeDisplayLists.size(), systemTime(SYSTEM_TIME_MONOTONIC));
    bool isFound = false;
    DisplayList *item;
    for (size_t i = 0; i < activeDisplayLists.size(); i++) {
        item = activeDisplayLists.itemAt(i);
        ALOGW("\t[%d] %p, tid=%d, time=%lld", i, item, item->mTid, item->mTimestamp);
        if (item == displayList)
            isFound = true;
    }
    return isFound;
}

bool Caches::dumpActiveLayers(Layer *layer) {
    Mutex::Autolock _l(mLayerLock);
    ALOGW("Active Layers: %d (%lld)", activeLayers.size(), systemTime(SYSTEM_TIME_MONOTONIC));
    bool isFound = false;
    Layer *item;
    for (size_t i = 0; i < activeLayers.size(); i++) {
        item = activeLayers.itemAt(i);
        ALOGW("\t[%d] %p, tid=%d, time=%lld", i, item, item->mTid, item->mTimestamp);
        if (item == layer)
            isFound = true;
    }
    return isFound;
}
#endif

}; // namespace uirenderer
}; // namespace android
