/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.data.repository

import android.provider.Settings
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@SysUISingleton
class QsVisualStyleMigrationStartable
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val secureSettingsRepository: SecureSettingsRepository,
) : CoreStartable {
    override fun start() {
        applicationScope.launch {
            secureSettingsRepository
                .intSetting(Settings.Secure.QS_VISUAL_STYLE_MIGRATION_VERSION)
                .collectLatest { version ->
                    if (version < CURRENT_MIGRATION_VERSION) {
                        secureSettingsRepository.setInt(
                            Settings.Secure.QS_UI_STYLE,
                            Settings.Secure.QS_UI_STYLE_DEFAULT,
                        )
                        secureSettingsRepository.setInt(
                            Settings.Secure.QS_VISUAL_STYLE_MIGRATION_VERSION,
                            CURRENT_MIGRATION_VERSION,
                        )
                    }
                }
        }
    }

    companion object {
        const val CURRENT_MIGRATION_VERSION = 1
    }
}
