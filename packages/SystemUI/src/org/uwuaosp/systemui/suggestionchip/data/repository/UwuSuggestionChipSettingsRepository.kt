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

package org.uwuaosp.systemui.suggestionchip.data.repository

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class UwuSuggestionChipSettingsRepository
@Inject
constructor(
    @Application scope: CoroutineScope,
    @Application private val context: Context,
    private val secureSettingsRepository: SecureSettingsRepository,
    private val secureSettings: SecureSettings,
    private val userTracker: UserTracker,
) {
    val torchEnabled =
        secureSettingsRepository
            .boolSetting(KEY_TORCH_ENABLED, false)
            .stateIn(scope, SharingStarted.Eagerly, false)

    val musicEnabled =
        secureSettingsRepository
            .boolSetting(KEY_MUSIC_ENABLED, false)
            .stateIn(scope, SharingStarted.Eagerly, false)

    val musicPackageName =
        currentUserId()
            .flatMapLatest { userId ->
                secureSettings
                    .observerFlow(userId, KEY_MUSIC_PACKAGE)
                    .onStart { emit(Unit) }
                    .map { secureSettings.getStringForUser(KEY_MUSIC_PACKAGE, userId).orEmpty() }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, "")

    private fun currentUserId(): Flow<Int> =
        callbackFlow {
                val callback =
                    object : UserTracker.Callback {
                        override fun onUserChanged(newUser: Int, userContext: Context) {
                            trySend(newUser)
                        }
                    }
                trySend(userTracker.userId)
                userTracker.addCallback(callback, context.mainExecutor)
                awaitClose { userTracker.removeCallback(callback) }
            }
            .distinctUntilChanged()

    companion object {
        const val KEY_TORCH_ENABLED = "uwuaosp_torch_suggestion_enabled"
        const val KEY_MUSIC_ENABLED = "uwuaosp_music_suggestion_enabled"
        const val KEY_MUSIC_PACKAGE = "uwuaosp_music_suggestion_package"
    }
}
