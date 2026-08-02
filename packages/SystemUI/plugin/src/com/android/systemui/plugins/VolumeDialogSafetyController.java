/*
 * Copyright (C) 2026 The LineageOS-Sado Project
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

package com.android.systemui.plugins;

import android.media.AudioManager;

import com.android.systemui.plugins.annotations.ProvidesInterface;

/** SystemUI-owned warning dialogs exposed to volume dialog plugins. */
@ProvidesInterface(version = VolumeDialogSafetyController.VERSION)
public interface VolumeDialogSafetyController {
    int VERSION = 1;

    void showSafetyWarning(int flags);

    void showCsdWarning(@AudioManager.CsdWarning int warning, int durationMs);

    void dismissWarnings();
}
