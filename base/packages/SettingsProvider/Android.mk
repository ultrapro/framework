LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_JAVA_LIBRARIES := telephony-common \
                        mediatek-framework

LOCAL_JAVA_STATIC_LIBRARIES := com.android.providers.settings.ext

LOCAL_PACKAGE_NAME := SettingsProvider
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)

########################
include $(call all-makefiles-under,$(LOCAL_PATH))
