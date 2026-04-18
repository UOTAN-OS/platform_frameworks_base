/*
 * Copyright (C) 2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.android;

import android.os.Build;
import android.os.SystemProperties;

import java.util.LinkedHashMap;

/** @hide */
public class DebugConstants {

    private DebugConstants() {}

    public static LinkedHashMap<String, String> CONSTANTS_MAP = new LinkedHashMap<>();

    static {
        CONSTANTS_MAP.put("DEBUG_GLOBAL", "persist.uwuaosp.debug.global");
        CONSTANTS_MAP.put("DEBUG_POP_UP", "persist.uwuaosp.popup_view.debug");
        CONSTANTS_MAP.put("DEBUG_WMS_RESOLUTION", "persist.uwuaosp.wm.resolution.debug");
        CONSTANTS_MAP.put("DEBUG_WMS_TOP_APP", "persist.uwuaosp.wm.top_app.debug");
    }

    // Enable this to debug all uwuAOSP features
    private static final boolean DEBUG_GLOBAL = Build.IS_ENG || SystemProperties.getBoolean(
        "persist.uwuaosp.debug.global", false
    );

    // Enable this to debug Pop-Up View feature
    public static final boolean DEBUG_POP_UP = DEBUG_GLOBAL || SystemProperties.getBoolean(
        "persist.uwuaosp.popup_view.debug", false
    );

    // Enable this to debug resolution switch feature
    // Package: com.android.server.wm.DisplayResolutionController
    // Key: DisplayResolutionController
    public static final boolean DEBUG_WMS_RESOLUTION = DEBUG_GLOBAL || SystemProperties.getBoolean(
        "persist.uwuaosp.wm.resolution.debug", false
    );

    // Enable this to debug top activity change
    // Package: com.android.server.wm.TopActivityRecorder
    // Key: TopActivityRecorder
    public static final boolean DEBUG_WMS_TOP_APP = DEBUG_GLOBAL || SystemProperties.getBoolean(
        "persist.uwuaosp.wm.top_app.debug", false
    );
}
