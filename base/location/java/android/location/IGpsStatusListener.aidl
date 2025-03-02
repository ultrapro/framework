/*
 * Copyright (C) 2008, The Android Open Source Project
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

package android.location;

import android.location.Location;

/**
 * {@hide}
 */
oneway interface IGpsStatusListener
{
    void onGpsStarted();
    void onGpsStopped();
    void onFirstFix(int ttff);
    /* This Google default code was not used due to it can not support the Multi-Satellite System 
    void onSvStatusChanged(int svCount, in int[] prns, in float[] snrs, 
            in float[] elevations, in float[] azimuths, 
            int ephemerisMask, int almanacMask, int usedInFixMask);
    */
    /*MTK interface for support the Multi-Satellite System MTK81084 chen.wang */
    void onSvStatusChanged(int svCount, in int[] prns, in float[] snrs, 
            in float[] elevations, in float[] azimuths, 
            in int [] ephemerisMask, in int [] almanacMask, in int [] usedInFixMask, in int timeToFirstFix);
    void onNmeaReceived(long timestamp, String nmea);
} 
