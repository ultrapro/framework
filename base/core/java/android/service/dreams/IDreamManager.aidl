/**
 * Copyright (c) 2012, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.service.dreams;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.content.ComponentName;
import android.os.IBinder;

/** @hide */
interface IDreamManager {
    void dream();
    void awaken();
    void setDreamComponents(in ComponentName[] componentNames);
    ComponentName[] getDreamComponents();
    ComponentName getDefaultDreamComponent();
    void testDream(in ComponentName componentName);
    boolean isDreaming();
    void finishSelf(in IBinder token);
    /// M: ALPS00446494 Daydream BeanFlinger show once again after press back key to exit @{
    /**
     * Remove window token, need run after remove window
     * @hide
     */
    void removeToken(in IBinder token);
    /// @}
}
