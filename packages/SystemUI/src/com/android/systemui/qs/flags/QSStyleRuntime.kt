/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.flags

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.Flags
import com.android.systemui.res.R
import org.json.JSONObject

/**
 * Process-stable Quick Settings backend selection.
 *
 * SystemUI has several static flag call sites which must all observe the same backend for the
 * lifetime of the process. Style changes are therefore committed to settings and applied after a
 * process restart.
 */
object QSStyleRuntime {
    const val STYLE_A11 = 0
    const val STYLE_A16 = 1

    private const val TAG = "QSStyleRuntime"
    private const val TRANSACTION_VERSION = 1
    private const val MAX_TRANSACTION_LENGTH = 64 * 1024

    @Volatile private var initialized = false
    @Volatile private var selectedStyle = STYLE_A11
    @Volatile private var selectedUser = ActivityManager.getCurrentUser()

    @JvmStatic
    fun initialize(context: Context) {
        synchronized(this) {
            if (initialized) return
            val userId = ActivityManager.getCurrentUser()
            recoverInterruptedSwitch(context.applicationContext, userId)
            var applied =
                Settings.Secure.getIntForUser(
                    context.contentResolver,
                    Settings.Secure.QS_UI_STYLE_APPLIED,
                    STYLE_A11,
                    userId,
                )
            if (applied != STYLE_A11 && applied != STYLE_A16) {
                applied = STYLE_A11
            }
            if (applied == STYLE_A16 && !isComposeAvailable) {
                Log.w(TAG, "Compose QS is unavailable; rolling back to A11")
                applied = STYLE_A11
                Settings.Secure.putIntForUser(
                    context.contentResolver,
                    Settings.Secure.QS_UI_STYLE,
                    STYLE_A11,
                    userId,
                )
                Settings.Secure.putIntForUser(
                    context.contentResolver,
                    Settings.Secure.QS_UI_STYLE_APPLIED,
                    STYLE_A11,
                    userId,
                )
            }
            selectedUser = userId
            selectedStyle = applied
            initialized = true
            Log.i(TAG, "Initialized user=$userId style=${styleName(applied)}")
        }
    }

    @JvmStatic
    val isComposeAvailable: Boolean
        get() = Flags.qsUiRefactorComposeFragment()

    @JvmStatic
    val isCompose: Boolean
        get() =
            if (initialized) {
                selectedStyle == STYLE_A16 && isComposeAvailable
            } else {
                // Unit tests historically select the backend through the aconfig flag. Production
                // initializes this object before constructing the Dagger graph.
                isComposeAvailable
            }

    @JvmStatic
    val style: Int
        get() = if (initialized) selectedStyle else if (isComposeAvailable) STYLE_A16 else STYLE_A11

    @JvmStatic
    val userId: Int
        get() = selectedUser

    private fun recoverInterruptedSwitch(context: Context, userId: Int) {
        val resolver = context.contentResolver
        val raw =
            Settings.Secure.getStringForUser(
                resolver,
                Settings.Secure.QS_UI_STYLE_SWITCH_STATE,
                userId,
            ) ?: return
        if (raw.length > MAX_TRANSACTION_LENGTH) {
            recoverCorruptTransaction(context, userId, "transaction too large")
            return
        }
        try {
            val state = JSONObject(raw)
            if (state.optInt("version", -1) != TRANSACTION_VERSION) {
                recoverCorruptTransaction(context, userId, "unknown transaction version")
                return
            }
            val from = state.getInt("from")
            val to = state.getInt("to")
            val backup = state.getString("backup")
            val target = state.getString("target")
            val applied =
                Settings.Secure.getIntForUser(
                    resolver,
                    Settings.Secure.QS_UI_STYLE_APPLIED,
                    STYLE_A11,
                    userId,
                )
            if (applied == to) {
                Settings.Secure.putStringForUser(
                    resolver,
                    Settings.Secure.QS_TILES,
                    target,
                    userId,
                )
                Log.i(TAG, "Completed interrupted QS style switch to ${styleName(to)}")
            } else {
                Settings.Secure.putStringForUser(
                    resolver,
                    Settings.Secure.QS_TILES,
                    backup,
                    userId,
                )
                Settings.Secure.putIntForUser(
                    resolver,
                    Settings.Secure.QS_UI_STYLE,
                    from,
                    userId,
                )
                Settings.Secure.putIntForUser(
                    resolver,
                    Settings.Secure.QS_UI_STYLE_APPLIED,
                    from,
                    userId,
                )
                Log.w(TAG, "Rolled back interrupted QS style switch to ${styleName(from)}")
            }
            clearTransaction(resolver = resolver, userId = userId)
        } catch (e: Exception) {
            recoverCorruptTransaction(context, userId, e.javaClass.simpleName)
        }
    }

    private fun recoverCorruptTransaction(context: Context, userId: Int, reason: String) {
        val resolver = context.contentResolver
        Log.e(TAG, "Discarding corrupt QS style switch state: $reason")
        Settings.Secure.putIntForUser(
            resolver,
            Settings.Secure.QS_UI_STYLE,
            STYLE_A11,
            userId,
        )
        Settings.Secure.putIntForUser(
            resolver,
            Settings.Secure.QS_UI_STYLE_APPLIED,
            STYLE_A11,
            userId,
        )
        if (Settings.Secure.getStringForUser(resolver, Settings.Secure.QS_TILES, userId).isNullOrBlank()) {
            Settings.Secure.putStringForUser(
                resolver,
                Settings.Secure.QS_TILES,
                context.getString(R.string.quick_settings_tiles_default),
                userId,
            )
        }
        clearTransaction(resolver = resolver, userId = userId)
    }

    private fun clearTransaction(resolver: android.content.ContentResolver, userId: Int) {
        Settings.Secure.putStringForUser(
            resolver,
            Settings.Secure.QS_UI_STYLE_SWITCH_STATE,
            null,
            userId,
        )
    }

    private fun styleName(style: Int): String = if (style == STYLE_A16) "A16" else "A11"

    @VisibleForTesting
    fun setStyleForTest(style: Int) {
        selectedStyle = style
        initialized = true
    }

    @VisibleForTesting
    fun resetForTest() {
        selectedStyle = STYLE_A11
        initialized = false
    }
}
