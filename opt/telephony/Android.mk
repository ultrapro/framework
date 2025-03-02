# Copyright (C) 2011 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_AIDL_INCLUDES := $(LOCAL_PATH)/src/java
LOCAL_SRC_FILES := \
	src/java/com/android/internal/telephony/ISms.aidl \
    src/java/com/android/internal/telephony/IIccPhoneBook.aidl \
    src/java/com/android/internal/telephony/EventLogTags.logtags \

LOCAL_SRC_FILES += $(call all-java-files-under, src/java)

LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := telephony-common
LOCAL_JAVA_LIBRARIES += lewa-framework

include $(BUILD_JAVA_LIBRARY)

# Include subdirectory makefiles
# ============================================================
include $(call all-makefiles-under,$(LOCAL_PATH))

