LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=       \
	stagefright.cpp \
	jpeg.cpp	\
	SineSource.cpp

LOCAL_SHARED_LIBRARIES := \
	libstagefright libmedia libmedia_native libutils libbinder libstagefright_foundation \
        libjpeg libgui

LOCAL_C_INCLUDES:= \
	frameworks/av/media/libstagefright \
	frameworks/av/media/libstagefright/include \
	$(TOP)/frameworks/native/include/media/openmax \
	external/jpeg \

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE:= stagefright

include $(BUILD_EXECUTABLE)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=         \
        SineSource.cpp    \
        record.cpp

LOCAL_SHARED_LIBRARIES := \
	libstagefright liblog libutils libbinder libstagefright_foundation

LOCAL_C_INCLUDES:= \
	frameworks/av/media/libstagefright \
	$(TOP)/frameworks/native/include/media/openmax

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE:= record

include $(BUILD_EXECUTABLE)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=         \
        SineSource.cpp    \
        recordvideo.cpp

LOCAL_SHARED_LIBRARIES := \
	libstagefright liblog libutils libbinder libstagefright_foundation

LOCAL_C_INCLUDES:= \
	frameworks/av/media/libstagefright \
	$(TOP)/frameworks/native/include/media/openmax

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE:= recordvideo

include $(BUILD_EXECUTABLE)


################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=         \
        SineSource.cpp    \
        audioloop.cpp

LOCAL_SHARED_LIBRARIES := \
	libstagefright liblog libutils libbinder libstagefright_foundation

LOCAL_C_INCLUDES:= \
	frameworks/av/media/libstagefright \
	$(TOP)/frameworks/native/include/media/openmax

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE:= audioloop

include $(BUILD_EXECUTABLE)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=         \
        stream.cpp    \
		PlayListSource.cpp 

LOCAL_SHARED_LIBRARIES := \
	libstagefright liblog libutils libbinder libgui \
        libstagefright_foundation libmedia libmedia_native libcutils

LOCAL_C_INCLUDES:= \
	frameworks/av/media/libstagefright \
	$(TOP)/frameworks/native/include/media/openmax

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE:= stream

include $(BUILD_EXECUTABLE)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=         \
        sf2.cpp    \

LOCAL_SHARED_LIBRARIES := \
	libstagefright liblog libutils libbinder libstagefright_foundation \
        libmedia libmedia_native libgui libcutils libui

LOCAL_C_INCLUDES:= \
	frameworks/av/media/libstagefright \
	$(TOP)/frameworks/native/include/media/openmax

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE:= sf2

include $(BUILD_EXECUTABLE)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
        codec.cpp               \
        SimplePlayer.cpp        \

LOCAL_SHARED_LIBRARIES := \
	libstagefright liblog libutils libbinder libstagefright_foundation \
        libmedia libmedia_native libgui libcutils libui

LOCAL_C_INCLUDES:= \
	frameworks/av/media/libstagefright \
	$(TOP)/frameworks/native/include/media/openmax

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE:= codec

include $(BUILD_EXECUTABLE)

