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

package org.uwuaosp.systemui.suggestionchip.music.data.repository

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class MusicSuggestionDeviceRepository
@Inject
constructor(@Application context: Context, @Application scope: CoroutineScope) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    val isHeadsetConnected: StateFlow<Boolean> =
        callbackFlow {
                if (audioManager == null) {
                    trySend(false)
                    close()
                    return@callbackFlow
                }

                val callback =
                    object : AudioDeviceCallback() {
                        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                            trySend(hasSupportedOutputDevice())
                        }

                        override fun onAudioDevicesRemoved(
                            removedDevices: Array<out AudioDeviceInfo>
                        ) {
                            trySend(hasSupportedOutputDevice())
                        }
                    }

                trySend(hasSupportedOutputDevice())
                audioManager.registerAudioDeviceCallback(callback, null)
                awaitClose { audioManager.unregisterAudioDeviceCallback(callback) }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

    private fun hasSupportedOutputDevice(): Boolean {
        return audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.any { device -> isSupportedDeviceType(device.type) } == true
    }

    private fun isSupportedDeviceType(type: Int): Boolean {
        return when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID -> true
            else -> false
        }
    }
}
