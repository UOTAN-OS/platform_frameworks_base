/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.data.repository

import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.qs.shared.model.QsVisualStyle
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class QsAppearanceRepository
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    secureSettingsRepository: SecureSettingsRepository,
) {
    val visualStyle: StateFlow<QsVisualStyle> =
        secureSettingsRepository
            .intSetting(
                Settings.Secure.QS_UI_STYLE,
                Settings.Secure.QS_UI_STYLE_DEFAULT,
            )
            .map(QsVisualStyle::fromSettingValue)
            .stateIn(
                applicationScope,
                SharingStarted.Eagerly,
                QsVisualStyle.DEFAULT_QS,
            )

    val uwuTransparencyEnabled: StateFlow<Boolean> =
        secureSettingsRepository
            .boolSetting(Settings.Secure.UWU_QS_TRANSPARENCY_ENABLED, defaultValue = false)
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    val collapsedBrightnessEnabled: StateFlow<Boolean> =
        secureSettingsRepository
            .boolSetting(Settings.Secure.QS_SHOW_COLLAPSED_BRIGHTNESS, defaultValue = false)
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    val isPlatformTransparencyAllowed: StateFlow<Boolean> =
        combine(visualStyle, uwuTransparencyEnabled) { style, uwuEnabled ->
                style == QsVisualStyle.DEFAULT_QS || uwuEnabled
            }
            .stateIn(applicationScope, SharingStarted.Eagerly, true)
}
