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

package org.uwuaosp.systemui.music

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.provider.Settings
import com.android.systemui.CoreStartable
import com.android.systemui.clipboardoverlay.ClipboardOverlayController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.kotlin.pairwise
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.uwuaosp.systemui.suggestionchip.data.repository.UwuSuggestionChipSettingsRepository
import org.uwuaosp.systemui.suggestionchip.music.data.repository.MusicSuggestionDeviceRepository

@SysUISingleton
class MusicSuggestionStartable
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    @Application private val context: Context,
    private val settingsRepository: UwuSuggestionChipSettingsRepository,
    private val deviceRepository: MusicSuggestionDeviceRepository,
    private val overlayProvider: Provider<ClipboardOverlayController>,
    private val userTracker: UserTracker,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val keyguardInteractor: KeyguardInteractor,
) : CoreStartable {
    private companion object {
        const val AUTO_DISMISS_DELAY_MS = 30_000L
    }

    private data class Config(val enabled: Boolean, val packageName: String)
    private data class SuggestionState(val showable: Boolean, val packageName: String)

    private var overlay: ClipboardOverlayController? = null
    private var autoDismissJob: Job? = null

    private val config: StateFlow<Config> =
        combine(settingsRepository.musicEnabled, settingsRepository.musicPackageName) {
                enabled,
                packageName ->
                Config(enabled = enabled, packageName = packageName)
            }
            .stateIn(scope, SharingStarted.Eagerly, Config(false, ""))

    override fun start() {
        scope.launch {
            combine(
                deviceRepository.isHeadsetConnected,
                config,
                deviceEntryInteractor.isUnlocked,
                keyguardInteractor.isKeyguardShowing,
            ) { connected, currentConfig, isUnlocked, isKeyguardShowing ->
                SuggestionState(
                    showable =
                        connected &&
                            currentConfig.enabled &&
                            currentConfig.packageName.isNotBlank() &&
                            isUnlocked &&
                            !isKeyguardShowing,
                    packageName = currentConfig.packageName,
                )
            }.pairwise(initialValue = SuggestionState(showable = false, packageName = ""))
                .collect { (oldState, newState) ->
                    if (!newState.showable) {
                        cancelAutoDismiss()
                        overlay?.dismissSuggestion()
                    } else if (!oldState.showable || oldState.packageName != newState.packageName) {
                        maybeShowSuggestion(newState.packageName)
                    }
                }
        }
    }

    private fun maybeShowSuggestion(packageName: String) {
        if (packageName.isBlank() || !isUserSetupComplete()) {
            return
        }
        val launchIntent = createLaunchIntent(packageName) ?: return
        val icon = createAppIcon(packageName) ?: return
        if (overlay == null) {
            overlay =
                overlayProvider.get().also {
                    it.setOnSessionCompleteListener {
                        cancelAutoDismiss()
                        overlay = null
                    }
                }
        }
        overlay?.setMusicSuggestion(
            icon,
            context.getString(com.android.systemui.res.R.string.uwu_music_suggestion_chip_content_description),
            launchIntent,
        )
        scheduleAutoDismiss()
    }

    private fun scheduleAutoDismiss() {
        cancelAutoDismiss()
        autoDismissJob =
            scope.launch {
                delay(AUTO_DISMISS_DELAY_MS)
                overlay?.dismissSuggestion()
            }
    }

    private fun cancelAutoDismiss() {
        autoDismissJob?.cancel()
        autoDismissJob = null
    }

    private fun createLaunchIntent(packageName: String): Intent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return Intent(launchIntent).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    private fun createAppIcon(packageName: String): Drawable? {
        return runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
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
