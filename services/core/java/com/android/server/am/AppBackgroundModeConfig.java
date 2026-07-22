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

package com.android.server.am;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

final class AppBackgroundModeConfig {
    static final int MODE_DEFAULT = Settings.Secure.UWU_APP_BACKGROUND_MODE_DEFAULT;
    static final int MODE_TOMBSTONE = Settings.Secure.UWU_APP_BACKGROUND_MODE_TOMBSTONE;
    static final int MODE_FULL = Settings.Secure.UWU_APP_BACKGROUND_MODE_FULL;

    static final long FREEZE_DELAY_MS = 3_000L;
    static final long AUDIO_STOP_FREEZE_DELAY_MS = 6_000L;
    static final long BINDER_RECOVERY_RETRY_DELAY_MS = 1_000L;

    static final class ParseResult {
        final ArrayMap<String, Integer> modes;
        final String normalized;
        final boolean changed;

        ParseResult(ArrayMap<String, Integer> modes, String normalized, boolean changed) {
            this.modes = modes;
            this.normalized = normalized;
            this.changed = changed;
        }
    }

    private AppBackgroundModeConfig() {}

    static ParseResult parse(@Nullable String value, @NonNull Predicate<String> packageAllowed) {
        final TreeMap<String, Integer> sorted = new TreeMap<>();
        boolean malformed = false;
        if (value != null && !value.isBlank()) {
            try {
                final JSONObject object = new JSONObject(value);
                final Iterator<String> keys = object.keys();
                while (keys.hasNext()) {
                    final String packageName = keys.next();
                    final int mode = object.optInt(packageName, MODE_DEFAULT);
                    if ((mode == MODE_TOMBSTONE || mode == MODE_FULL)
                            && packageAllowed.test(packageName)) {
                        sorted.put(packageName, mode);
                    } else {
                        malformed = true;
                    }
                }
            } catch (JSONException e) {
                malformed = true;
            }
        }

        final ArrayMap<String, Integer> modes = new ArrayMap<>(sorted.size());
        final JSONObject normalizedObject = new JSONObject();
        for (Map.Entry<String, Integer> entry : sorted.entrySet()) {
            modes.put(entry.getKey(), entry.getValue());
            try {
                normalizedObject.put(entry.getKey(), entry.getValue());
            } catch (JSONException impossible) {
                throw new AssertionError(impossible);
            }
        }
        final String normalized = sorted.isEmpty() ? null : normalizedObject.toString();
        final boolean changed = malformed || !equalNullable(value, normalized);
        return new ParseResult(modes, normalized, changed);
    }

    @VisibleForTesting
    static int resolveUidMode(int... packageModes) {
        if (packageModes == null || packageModes.length == 0) {
            return MODE_DEFAULT;
        }
        boolean allTombstone = true;
        for (int mode : packageModes) {
            if (mode == MODE_FULL) {
                return MODE_FULL;
            }
            if (mode != MODE_TOMBSTONE) {
                allTombstone = false;
            }
        }
        return allTombstone ? MODE_TOMBSTONE : MODE_DEFAULT;
    }

    @VisibleForTesting
    static boolean shouldIgnoreTaskRemoval(boolean enabled, int mode) {
        return enabled && (mode == MODE_TOMBSTONE || mode == MODE_FULL);
    }

    @VisibleForTesting
    static boolean isCoreApplication(@Nullable ApplicationInfo info, boolean criticalPackage) {
        return info == null || !UserHandle.isApp(info.uid)
                || (info.flags & ApplicationInfo.FLAG_PERSISTENT) != 0 || criticalPackage;
    }

    private static boolean equalNullable(@Nullable String first, @Nullable String second) {
        if (first == null) {
            return second == null;
        }
        return first.equals(second);
    }
}
