/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef ANDROID_IREMOTEDISPLAY_H
#define ANDROID_IREMOTEDISPLAY_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/RefBase.h>
#include <binder/IInterface.h>
#include <binder/Parcel.h>

namespace android {

/*
 * Represents a remote display, such as a Wifi display.
 *
 * When the remote display is created, it may not yet be connected to the
 * display.  The remote display asynchronously reports events such as successful
 * connection, disconnection and errors to an IRemoteDisplayClient interface provided by
 * the client.
 */
class IRemoteDisplay : public IInterface
{
public:

    ///M: add for rtsp generic message @{
    enum{
        kGenericMessagePlay=1,
        kGenericMessagePause=2,
        kGenericMessageTeardown=3
    };
    enum{
        kBitrateAuto =0,
        kBitrateLevel0 =1,
        kBitrateLevel1 =2,
        kBitrateLevel2     =3
    };
    ///@}
    
    DECLARE_META_INTERFACE(RemoteDisplay);

    // Disconnects the remote display and stops listening for new connections.
    virtual status_t dispose() = 0;

#ifndef ANDROID_DEFAULT_CODE
    ///M: add for rtsp generic message@{
    virtual status_t sendGenericMsg(int cmd)=0;
    virtual status_t setBitrateControl(int level)=0;
    ///@}
#endif
    
};


// ----------------------------------------------------------------------------

class BnRemoteDisplay : public BnInterface<IRemoteDisplay>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

}; // namespace android

#endif // ANDROID_IREMOTEDISPLAY_H
