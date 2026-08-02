/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs;

import android.content.Context;
import android.provider.Settings;

import com.android.systemui.qs.flags.QSComposeFragment;

import org.json.JSONObject;

/** Reads and sanitizes the A11-only tile span setting. */
public final class A11TileLayoutSpec {

    private A11TileLayoutSpec() {
    }

    public static int getColumnSpan(Context context, String spec) {
        if (isSlider(spec)) {
            return 1;
        }
        if (!QSComposeFragment.isEnabled()
                && context.getResources().getConfiguration().smallestScreenWidthDp >= 720) {
            return 1;
        }
        final String raw = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.QS_TILE_LAYOUT_A11);
        if (raw != null && raw.length() <= 64 * 1024) {
            try {
                final JSONObject root = new JSONObject(raw);
                final int version = root.optInt("version", -1);
                if (version == 1 || version == 2) {
                    final int span = root.getJSONObject("spans").optInt(spec, defaultSpan(spec));
                    if (span == 1 || span == 2) {
                        return span;
                    }
                }
            } catch (Exception ignored) {
                // Fall through to the stable defaults without discarding other tile state.
            }
        }
        return defaultSpan(spec);
    }

    public static int getRowSpan(Context context, String spec) {
        if (isSlider(spec)) {
            return 2;
        }
        final String raw = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.QS_TILE_LAYOUT_A11);
        if (raw != null && raw.length() <= 64 * 1024) {
            try {
                final JSONObject root = new JSONObject(raw);
                final int version = root.optInt("version", -1);
                if (version == 1 || version == 2) {
                    final JSONObject rows = root.optJSONObject("rows");
                    final int span = rows == null ? 1 : rows.optInt(spec, 1);
                    if (span == 1 || span == 2) {
                        return span;
                    }
                }
            } catch (Exception ignored) {
                // Fall through to the stable default.
            }
        }
        return 1;
    }

    public static boolean isSlider(String spec) {
        return QSStyleController.VOLUME_SLIDER_SPEC.equals(spec)
                || QSStyleController.BRIGHTNESS_SLIDER_SPEC.equals(spec);
    }

    private static int defaultSpan(String spec) {
        return "internet".equals(spec) || "dnd".equals(spec) ? 2 : 1;
    }
}
