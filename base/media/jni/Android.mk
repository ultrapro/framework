LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    android_media_MediaCrypto.cpp \
    android_media_MediaCodec.cpp \
    android_media_MediaCodecList.cpp \
    android_media_MediaExtractor.cpp \
    android_media_MediaPlayer.cpp \
    android_media_MediaRecorder.cpp \
    android_media_MediaScanner.cpp \
    android_media_MediaMetadataRetriever.cpp \
    android_media_ResampleInputStream.cpp \
    android_media_MediaProfiles.cpp \
    android_media_AmrInputStream.cpp \
    android_media_Utils.cpp \
    android_mtp_MtpDatabase.cpp \
    android_mtp_MtpDevice.cpp \
    android_mtp_MtpServer.cpp \

LOCAL_SHARED_LIBRARIES := \
    libandroid_runtime \
    libnativehelper \
    libutils \
    libbinder \
    libmedia \
    libmedia_native \
    libskia \
    libui \
    libcutils \
    libgui \
    libstagefright \
    libstagefright_foundation \
    libcamera_client \
    libmtp \
    libusbhost \
    libexif \
    libstagefright_amrnb_common

ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
LOCAL_SHARED_LIBRARIES += \
    libamr_wrap
endif

ifeq ($(strip $(MTK_TB_DEBUG_SUPPORT)),yes)
LOCAL_SHARED_LIBRARIES += \
    libmtkdcplayer
endif

LOCAL_REQUIRED_MODULES := \
    libexif_jni

LOCAL_STATIC_LIBRARIES := \
    libstagefright_amrnbenc


LOCAL_C_INCLUDES += \
    external/jhead \
    external/tremor/Tremor \
    frameworks/base/core/jni \
    frameworks/av/media/libmedia \
    frameworks/av/media/libstagefright \
    frameworks/av/media/libstagefright/codecs/amrnb/enc/src \
    frameworks/av/media/libstagefright/codecs/amrnb/common \
    frameworks/av/media/libstagefright/codecs/amrnb/common/include \
    $(TOP)/mediatek/external/amr \
    frameworks/av/media/mtp \
    frameworks/native/include/media/openmax \
    $(PV_INCLUDES) \
    $(JNI_H_INCLUDE) \
    $(call include-path-for, corecg graphics)

ifeq ($(strip $(MTK_TB_DEBUG_SUPPORT)),yes)
LOCAL_C_INCLUDES += \
    $(MTK_PATH_SOURCE)/frameworks/base/include 
endif

ifeq ($(strip $(MTK_HIGH_QUALITY_THUMBNAIL)),yes)
LOCAL_CFLAGS += -DMTK_HIGH_QUALITY_THUMBNAIL
endif

ifeq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
LOCAL_CFLAGS += -DANDROID_DEFAULT_CODE
endif

ifeq ($(HAVE_CMMB_FEATURE),yes)
LOCAL_CFLAGS += -DMTK_CMMB_ENABLE
endif

LOCAL_CFLAGS +=

LOCAL_LDLIBS := -lpthread

LOCAL_MODULE:= libmedia_jni

include $(BUILD_SHARED_LIBRARY)

# build libsoundpool.so
# build libaudioeffect_jni.so
include $(call all-makefiles-under,$(LOCAL_PATH))
