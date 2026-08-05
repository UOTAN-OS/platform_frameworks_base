/*
 * Copyright (C) 2026 The uwuAOSP Project
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

package org.uwuaosp.systemui.torch

import android.content.Context
import android.provider.Settings
import com.android.systemui.CoreStartable
import com.android.systemui.clipboardoverlay.ClipboardOverlayController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.FlashlightController
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.uwuaosp.systemui.suggestionchip.data.repository.UwuSuggestionChipSettingsRepository

@SysUISingleton
class TorchSuggestionStartable
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    @Application private val context: Context,
    private val flashlightController: FlashlightController,
    private val secureSettingsRepository: SecureSettingsRepository,
    private val settingsRepository: UwuSuggestionChipSettingsRepository,
    private val overlayProvider: Provider<ClipboardOverlayController>,
    private val userTracker: UserTracker,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val keyguardInteractor: KeyguardInteractor,
) : CoreStartable {
    private var overlay: ClipboardOverlayController? = null

    override fun start() {
        if (!flashlightController.hasFlashlight()) {
            return
        }
        scope.launch {
            combine(
                settingsRepository.torchEnabled,
                secureSettingsRepository.boolSetting(Settings.Secure.FLASHLIGHT_ENABLED, false),
                deviceEntryInteractor.isUnlocked,
                keyguardInteractor.isKeyguardShowing,
            ) { enabled, flashlightOn, isUnlocked, isKeyguardShowing ->
                enabled && flashlightOn && isUnlocked && !isKeyguardShowing
            }
                .distinctUntilChanged()
                .collect { shouldShow ->
                    if (shouldShow && isUserSetupComplete()) {
                        showSuggestion()
                    } else {
                        overlay?.dismissSuggestion()
                    }
                }
        }
    }

    private fun showSuggestion() {
        if (overlay == null) {
            overlay =
                overlayProvider.get().also {
                    it.setOnSessionCompleteListener { overlay = null }
                }
        }
        overlay?.setTorchSuggestion()
    }

    private fun isUserSetupComplete(): Boolean {
        return (
            Settings.Secure.getIntForUser(
                context.contentResolver,
                Settings.Secure.USER_SETUP_COMPLETE,
                0,
                userTracker.userHandle.identifier,
            ) == 1
        )
    }
}
