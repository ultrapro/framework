#include "SkMovie.h"
#include "SkStream.h"
#include "GraphicsJNI.h"
#include "SkTemplates.h"
#include "SkUtils.h"
#include "CreateJavaOutputStreamAdaptor.h"

#include <androidfw/Asset.h>
#include <androidfw/ResourceTypes.h>
#include <netinet/in.h>
#include "utils/Log.h"

#if 0
    #define TRACE_BITMAP(code)  code
#else
    #define TRACE_BITMAP(code)
#endif

static jclass       gMovie_class;
static jmethodID    gMovie_constructorMethodID;
static jfieldID     gMovie_nativeInstanceID;

jobject create_jmovie(JNIEnv* env, SkMovie* moov) {
    if (NULL == moov) {
        return NULL;
    }
    return env->NewObject(gMovie_class, gMovie_constructorMethodID,
            static_cast<jint>(reinterpret_cast<uintptr_t>(moov)));
}

static SkMovie* J2Movie(JNIEnv* env, jobject movie) {
    SkASSERT(env);
    SkASSERT(movie);
    SkASSERT(env->IsInstanceOf(movie, gMovie_class));
    SkMovie* m = (SkMovie*)env->GetIntField(movie, gMovie_nativeInstanceID);
    SkASSERT(m);
    return m;
}

///////////////////////////////////////////////////////////////////////////////

static int movie_width(JNIEnv* env, jobject movie) {
    NPE_CHECK_RETURN_ZERO(env, movie);
    return J2Movie(env, movie)->width();
}

static int movie_height(JNIEnv* env, jobject movie) {
    NPE_CHECK_RETURN_ZERO(env, movie);
    return J2Movie(env, movie)->height();
}

static jboolean movie_isOpaque(JNIEnv* env, jobject movie) {
    NPE_CHECK_RETURN_ZERO(env, movie);
    return J2Movie(env, movie)->isOpaque();
}

static int movie_duration(JNIEnv* env, jobject movie) {
    NPE_CHECK_RETURN_ZERO(env, movie);
    return J2Movie(env, movie)->duration();
}

static jboolean movie_setTime(JNIEnv* env, jobject movie, int ms) {
    NPE_CHECK_RETURN_ZERO(env, movie);
    return J2Movie(env, movie)->setTime(ms);
}

static void movie_draw(JNIEnv* env, jobject movie, jobject canvas,
                       jfloat fx, jfloat fy, jobject jpaint) {
    NPE_CHECK_RETURN_VOID(env, movie);
    NPE_CHECK_RETURN_VOID(env, canvas);
    // its OK for paint to be null

    SkMovie* m = J2Movie(env, movie);
    SkCanvas* c = GraphicsJNI::getNativeCanvas(env, canvas);
    SkScalar sx = SkFloatToScalar(fx);
    SkScalar sy = SkFloatToScalar(fy);
    const SkBitmap& b = m->bitmap();
    const SkPaint* p = jpaint ? GraphicsJNI::getNativePaint(env, jpaint) : NULL;

    c->drawBitmap(b, sx, sy, p);
}

// for add gif begin
//the following 4 methods are intented for no one but Movie to use.
//please see Movie.java for information

static int movie_gifFrameDuration(JNIEnv* env, jobject movie, int frameIndex) {
    NPE_CHECK_RETURN_ZERO(env, movie);
    SkMovie* m = J2Movie(env, movie);
//LOGE("Movie:movie_gifFrameDuration: frame number %d, duration is %d", frameIndex,m->getGifFrameDuration(frameIndex));
    return m->getGifFrameDuration(frameIndex);
}

static jobject movie_gifFrameBitmap(JNIEnv* env, jobject movie, int frameIndex) {
    NPE_CHECK_RETURN_ZERO(env, movie);
    SkMovie* m = J2Movie(env, movie);
    int averTimePoint = 0;
    int frameDuration = 0;
    int frameCount = m->getGifTotalFrameCount();
    if (frameIndex < 0 && frameIndex >= frameCount )
        return NULL;
    m->setCurrFrame(frameIndex);
//then we get frameIndex Bitmap (the current frame of movie is frameIndex now)
    SkBitmap *createdBitmap = m->createGifFrameBitmap();
    if (createdBitmap != NULL)
    {
        return GraphicsJNI::createBitmap(env, createdBitmap, false, NULL);
    }
    else 
    {
        return NULL;
    }
}

static int movie_gifTotalFrameCount(JNIEnv* env, jobject movie) {
    NPE_CHECK_RETURN_ZERO(env, movie);
    SkMovie* m = J2Movie(env, movie);
//LOGE("Movie:movie_gifTotalFrameCount: frame count %d", m->getGifTotalFrameCount());
    return m->getGifTotalFrameCount();
}

static void movie_closeGif(JNIEnv* env, jobject movie) {
    NPE_CHECK_RETURN_VOID(env, movie);
    SkMovie* m = J2Movie(env, movie);
//LOGE("Movie:movie_closeGif()");
    delete m;
}

// for add gif end

static jobject movie_decodeStream(JNIEnv* env, jobject clazz, jobject istream) {

    NPE_CHECK_RETURN_ZERO(env, istream);

    // what is the lifetime of the array? Can the skstream hold onto it?
    jbyteArray byteArray = env->NewByteArray(16*1024);
    SkStream* strm = CreateJavaInputStreamAdaptor(env, istream, byteArray);
    if (NULL == strm) {
        return 0;
    }

    SkMovie* moov = SkMovie::DecodeStream(strm);
    strm->unref();
    return create_jmovie(env, moov);
}

static jobject movie_decodeByteArray(JNIEnv* env, jobject clazz,
                                     jbyteArray byteArray,
                                     int offset, int length) {

    NPE_CHECK_RETURN_ZERO(env, byteArray);

    int totalLength = env->GetArrayLength(byteArray);
    if ((offset | length) < 0 || offset + length > totalLength) {
        doThrowAIOOBE(env);
        return 0;
    }

    AutoJavaByteArray   ar(env, byteArray);
    SkMovie* moov = SkMovie::DecodeMemory(ar.ptr() + offset, length);
    return create_jmovie(env, moov);
}

static void movie_destructor(JNIEnv* env, jobject, SkMovie* movie) {
    delete movie;
}

//////////////////////////////////////////////////////////////////////////////////////////////

#include <android_runtime/AndroidRuntime.h>

static JNINativeMethod gMethods[] = {
    {   "width",    "()I",  (void*)movie_width  },
    {   "height",   "()I",  (void*)movie_height  },
    {   "isOpaque", "()Z",  (void*)movie_isOpaque  },
    {   "duration", "()I",  (void*)movie_duration  },
    {   "setTime",  "(I)Z", (void*)movie_setTime  },
    {   "draw",     "(Landroid/graphics/Canvas;FFLandroid/graphics/Paint;)V",
                            (void*)movie_draw  },
//for add gif begin
//the following 4 methods are intented for no one but Movie to use.
//please see Movie.java for information
    {   "gifFrameDuration",     "(I)I",
                            (void*)movie_gifFrameDuration  },
    {   "gifFrameBitmap",   "(I)Landroid/graphics/Bitmap;",
                            (void*)movie_gifFrameBitmap  },
    {   "gifTotalFrameCount",   "()I",
                            (void*)movie_gifTotalFrameCount  },
    {   "closeGif",   "()V",
                            (void*)movie_closeGif  },
    { "decodeMarkedStream", "(Ljava/io/InputStream;)Landroid/graphics/Movie;",
                            (void*)movie_decodeStream },
//    { "decodeStream", "(Ljava/io/InputStream;)Landroid/graphics/Movie;",
//                            (void*)movie_decodeStream },
// for add gif end
    { "nativeDestructor","(I)V", (void*)movie_destructor },
    { "decodeByteArray", "([BII)Landroid/graphics/Movie;",
                            (void*)movie_decodeByteArray },
};

#define kClassPathName  "android/graphics/Movie"

#define RETURN_ERR_IF_NULL(value)   do { if (!(value)) { assert(0); return -1; } } while (false)

int register_android_graphics_Movie(JNIEnv* env)
{
    gMovie_class = env->FindClass(kClassPathName);
    RETURN_ERR_IF_NULL(gMovie_class);
    gMovie_class = (jclass)env->NewGlobalRef(gMovie_class);

    gMovie_constructorMethodID = env->GetMethodID(gMovie_class, "<init>", "(I)V");
    RETURN_ERR_IF_NULL(gMovie_constructorMethodID);

    gMovie_nativeInstanceID = env->GetFieldID(gMovie_class, "mNativeMovie", "I");
    RETURN_ERR_IF_NULL(gMovie_nativeInstanceID);

    return android::AndroidRuntime::registerNativeMethods(env, kClassPathName,
                                                       gMethods, SK_ARRAY_COUNT(gMethods));
}
