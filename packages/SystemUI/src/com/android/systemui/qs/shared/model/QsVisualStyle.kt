/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.shared.model

import android.provider.Settings

enum class QsVisualStyle(val settingValue: Int) {
    UWU_QS(Settings.Secure.QS_UI_STYLE_UWU),
    DEFAULT_QS(Settings.Secure.QS_UI_STYLE_DEFAULT);

    companion object {
        fun fromSettingValue(value: Int): QsVisualStyle =
            entries.firstOrNull { it.settingValue == value } ?: DEFAULT_QS
    }
}
