/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 */
/* MediaTek Inc. (C) 2010. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER ON
 * AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR NONINFRINGEMENT.
 * NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH RESPECT TO THE
 * SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY, INCORPORATED IN, OR
 * SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES TO LOOK ONLY TO SUCH
 * THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO. RECEIVER EXPRESSLY ACKNOWLEDGES
 * THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES
 * CONTAINED IN MEDIATEK SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK
 * SOFTWARE RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S ENTIRE AND
 * CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE RELEASED HEREUNDER WILL BE,
 * AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE MEDIATEK SOFTWARE AT ISSUE,
 * OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE CHARGE PAID BY RECEIVER TO
 * MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek Software")
 * have been modified by MediaTek Inc. All revisions are subject to any receiver's
 * applicable license agreements with MediaTek Inc.
 */

/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

/**
 * {@hide}
 */
public interface IccConstants {
    // GSM SIM file ids from TS 51.011
    static final int EF_ADN = 0x6F3A;
    static final int EF_FDN = 0x6F3B;
    static final int EF_SDN = 0x6F49;
    static final int EF_EXT1 = 0x6F4A;
    static final int EF_EXT2 = 0x6F4B;
    static final int EF_EXT3 = 0x6F4C;
    static final int EF_EXT6 = 0x6fc8;   // Ext record for EF[MBDN]
    static final int EF_MWIS = 0x6FCA;
    static final int EF_MBDN = 0x6fc7;
    static final int EF_PNN = 0x6fc5;
    static final int EF_OPL = 0x6fc6;
    static final int EF_SPN = 0x6F46;
    static final int EF_SMS = 0x6F3C;
    static final int EF_ICCID = 0x2fe2;
    static final int EF_AD = 0x6FAD;
    static final int EF_MBI = 0x6fc9;
    static final int EF_MSISDN = 0x6f40;
    static final int EF_SPDI = 0x6fcd;
    static final int EF_SST = 0x6f38;
    static final int EF_CFIS = 0x6FCB;
    static final int EF_IMG = 0x4f20;
//MTK-START [mtk80601][111215][ALPS00093395]
    static final int EF_GID1 = 0x6F3E;
    static final int EF_GID2 = 0x6F3F;
    static final int EF_ECC = 0x6FB7;
//MTK-END [mtk80601][111215][ALPS00093395]
    static final int EF_LI = 0x6F05;
    static final int EF_ELP = 0x2F05;
    // USIM SIM file ids from TS 31.102
    public static final int EF_PBR = 0x4F30;

    // GSM SIM file ids from CPHS (phase 2, version 4.2) CPHS4_2.WW6
    static final int EF_MAILBOX_CPHS = 0x6F17;
    static final int EF_VOICE_MAIL_INDICATOR_CPHS = 0x6F11;
    static final int EF_CFF_CPHS = 0x6F13;
    static final int EF_SPN_CPHS = 0x6f14;
    static final int EF_SPN_SHORT_CPHS = 0x6f18;
    static final int EF_INFO_CPHS = 0x6f16;
//MTK-START [mtk80601][111215][ALPS00093395]
    static final int EF_CSP_CPHS = 0x6f15;
//MTK-END [mtk80601][111215][ALPS00093395]

    // CDMA RUIM file ids from 3GPP2 C.S0023-0
    static final int EF_CST = 0x6f32;
    static final int EF_RUIM_SPN =0x6F41;

    // MTK-START [ALPS00092673] Orange feature merge back added by mtk80589 in 2011.11.15
    /*
      Detail description:
      This feature provides a interface to get menu title string from EF_SUME
    */
    // SET UP MENU ELEMENTS
    static final int EF_SUME = 0x6F54;
    // MTK-END [ALPS00092673] Orange feature merge back added by mtk80589 in 2011.11.15

    // ETSI TS.102.221
    static final int EF_PL = 0x2F05;
    // 3GPP2 C.S0065
    static final int EF_CSIM_LI = 0x6F3A;
    static final int EF_CSIM_SPN =0x6F41;
    static final int EF_CSIM_MDN = 0x6F44;
    static final int EF_CSIM_IMSIM = 0x6F22;
    static final int EF_CSIM_CDMAHOME = 0x6F28;
    static final int EF_CSIM_EPRL = 0x6F5A;

    //ISIM access
    static final int EF_IMPU = 0x6f04;
    static final int EF_IMPI = 0x6f02;
    static final int EF_DOMAIN = 0x6f03;
    static final int EF_IST = 0x6f07;
    static final int EF_PCSCF = 0x6f09;

    // ALPS00302702 RAT balancing
    static public final int EF_RAT = 0x4F36; // ADF(USIM)/7F66/5F30/4F36

    // SMS record length from TS 51.011 10.5.3
    static public final int SMS_RECORD_LENGTH = 176;

    static final String MF_SIM = "3F00";
    static final String DF_TELECOM = "7F10";
    static final String DF_PHONEBOOK = "5F3A";
    static final String DF_GRAPHICS = "5F50";
    static final String DF_GSM = "7F20";
    static final String DF_CDMA = "7F25";
//MTK-START [mtk80601][111215][ALPS00093395]
    static final String DF_USIM = "7FFF";
//MTK-END [mtk80601][111215][ALPS00093395]

    //UICC access
    static final String DF_ADF = "7FFF";
}
