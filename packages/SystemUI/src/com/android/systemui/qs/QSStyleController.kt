/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.SystemExitRestarter
import com.android.systemui.qs.flags.QSStyleRuntime
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.ShadeController
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import org.json.JSONObject

/** Coordinates persistent, recoverable switches between the legacy A11 and Compose A16 QS. */
@SysUISingleton
class QSStyleController
@Inject
constructor(
    @Application private val context: Context,
    private val userTracker: UserTracker,
    private val shadeController: Provider<ShadeController>,
    private val restarter: SystemExitRestarter,
    private val currentTilesInteractor: CurrentTilesInteractor,
    @Main private val mainExecutor: Executor,
    @Background private val backgroundExecutor: Executor,
) : CoreStartable {
    private val resolver: ContentResolver = context.contentResolver
    private val transactionRunning = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)

    private val styleObserver =
        object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                mainExecutor.execute { handleRequestedStyle(userTracker.userId) }
            }
        }

    private val userCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                val applied = readAppliedStyle(newUser)
                if (applied != QSStyleRuntime.style) {
                    requestRestart("QS backend changed for user $newUser")
                } else {
                    handleRequestedStyle(newUser)
                }
            }
        }

    override fun start() {
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.QS_UI_STYLE),
            false,
            styleObserver,
            UserHandle.USER_ALL,
        )
        userTracker.addCallback(userCallback, mainExecutor)
        ensureCurrentStyleInitialized(userTracker.userId)
    }

    private fun ensureCurrentStyleInitialized(userId: Int) {
        backgroundExecutor.execute {
            val applied = readAppliedStyle(userId)
            val key = tilesKey(applied)
            val saved = Settings.Secure.getStringForUser(resolver, key, userId)
            if (saved == null) {
                val active =
                    Settings.Secure.getStringForUser(resolver, Settings.Secure.QS_TILES, userId)
                        ?: defaultTiles(applied)
                val initialized =
                    if (applied == QSStyleRuntime.STYLE_A11) {
                        addDefaultA11Items(
                            sanitizeTiles(active, allowA11Items = false),
                            userId,
                        )
                    } else {
                        sanitizeTiles(active, allowA11Items = false)
                    }
                if (applied == QSStyleRuntime.STYLE_A11 && initialized != active) {
                    setActiveTiles(userId, initialized)
                }
                // Save the initialized per-style copy last. If SystemUI exits between these two
                // writes, the next start still sees a null style copy and safely retries.
                Settings.Secure.putStringForUser(resolver, key, initialized, userId)
            } else if (applied == QSStyleRuntime.STYLE_A11) {
                val migrated = normalizeA11Tiles(saved, userId)
                if (migrated != saved && setActiveTiles(userId, migrated)) {
                    Settings.Secure.putStringForUser(resolver, key, migrated, userId)
                }
            }
            mainExecutor.execute { handleRequestedStyle(userId) }
        }
    }

    private fun handleRequestedStyle(userId: Int) {
        if (userId != userTracker.userId || restartRequested.get()) return
        val requested =
            Settings.Secure.getIntForUser(
                resolver,
                Settings.Secure.QS_UI_STYLE,
                QSStyleRuntime.STYLE_A11,
                userId,
            )
        val applied = readAppliedStyle(userId)
        if (requested == applied) return
        if (requested !in QSStyleRuntime.STYLE_A11..QSStyleRuntime.STYLE_A16) {
            rollbackRequest(userId, applied)
            return
        }
        if (requested == QSStyleRuntime.STYLE_A16 && !QSStyleRuntime.isComposeAvailable) {
            Log.w(TAG, "Rejecting A16 request because Compose QS is unavailable")
            rollbackRequest(userId, applied)
            return
        }
        if (!transactionRunning.compareAndSet(false, true)) return
        shadeController.get().animateCollapseShade()
        backgroundExecutor.execute {
            val success = switchStyle(userId, applied, requested)
            transactionRunning.set(false)
            if (success) {
                mainExecutor.execute { requestRestart("Quick Settings style changed") }
            }
        }
    }

    private fun switchStyle(userId: Int, from: Int, to: Int): Boolean {
        val backup =
            currentTilesInteractor.currentTilesSpecs
                .joinToString(",") { it.spec }
                .ifBlank {
                    Settings.Secure.getStringForUser(resolver, Settings.Secure.QS_TILES, userId)
                        ?: context.getString(R.string.quick_settings_tiles_default)
                }
        val oldTiles =
            if (from == QSStyleRuntime.STYLE_A11) {
                normalizeA11Tiles(sanitizeTiles(backup, allowA11Items = true), userId)
            } else {
                sanitizeTiles(backup, allowA11Items = false)
            }
        val targetKey = tilesKey(to)
        var target = Settings.Secure.getStringForUser(resolver, targetKey, userId)
        if (target == null) {
            target =
                if (to == QSStyleRuntime.STYLE_A11) {
                    addDefaultA11Items(
                        sanitizeTiles(
                            defaultTiles(QSStyleRuntime.STYLE_A11),
                            allowA11Items = false,
                        ),
                        userId,
                    )
                } else {
                    sanitizeTiles(
                        defaultTiles(QSStyleRuntime.STYLE_A16),
                        allowA11Items = false,
                    )
                }
        }
        target = sanitizeTiles(target, allowA11Items = to == QSStyleRuntime.STYLE_A11)
        if (to == QSStyleRuntime.STYLE_A11) {
            target = normalizeA11Tiles(target, userId)
        }
        if (target.isBlank()) {
            rollbackRequest(userId, from)
            return false
        }
        val state =
            JSONObject()
                .put("version", TRANSACTION_VERSION)
                .put("from", from)
                .put("to", to)
                .put("backup", backup)
                .put("target", target)
                .toString()
        try {
            check(
                Settings.Secure.putStringForUser(
                    resolver,
                    Settings.Secure.QS_UI_STYLE_SWITCH_STATE,
                    state,
                    userId,
                )
            )
            check(
                Settings.Secure.putStringForUser(
                    resolver,
                    tilesKey(from),
                    oldTiles,
                    userId,
                )
            )
            check(
                Settings.Secure.getStringForUser(resolver, tilesKey(from), userId) ==
                    oldTiles
            )
            check(
                Settings.Secure.putStringForUser(resolver, targetKey, target, userId)
            )
            check(setActiveTiles(userId, target))
            check(
                Settings.Secure.putIntForUser(
                    resolver,
                    Settings.Secure.QS_UI_STYLE_APPLIED,
                    to,
                    userId,
                )
            )
            Settings.Secure.putStringForUser(
                resolver,
                Settings.Secure.QS_UI_STYLE_SWITCH_STATE,
                null,
                userId,
            )
            Log.i(TAG, "Committed QS style switch ${styleName(from)} -> ${styleName(to)}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "QS style switch failed; rolling back", e)
            setActiveTiles(userId, backup)
            Settings.Secure.putIntForUser(
                resolver,
                Settings.Secure.QS_UI_STYLE_APPLIED,
                from,
                userId,
            )
            rollbackRequest(userId, from)
            Settings.Secure.putStringForUser(
                resolver,
                Settings.Secure.QS_UI_STYLE_SWITCH_STATE,
                null,
                userId,
            )
            return false
        }
    }

    private fun sanitizeTiles(raw: String, allowA11Items: Boolean): String {
        return raw
            .split(',')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filter {
                allowA11Items ||
                    (it != VOLUME_SLIDER_SPEC &&
                        it != BRIGHTNESS_SLIDER_SPEC &&
                        it != AUTO_BRIGHTNESS_SPEC)
            }
            .distinct()
            .joinToString(",")
    }

    private fun setActiveTiles(userId: Int, specs: String): Boolean {
        if (userId != userTracker.userId) return false
        currentTilesInteractor.setTiles(
            specs.split(',').map(String::trim).filter(String::isNotEmpty).map(TileSpec::create)
        )
        repeat(ACTIVE_TILES_WRITE_RETRIES) {
            if (
                Settings.Secure.getStringForUser(
                    resolver,
                    Settings.Secure.QS_TILES,
                    userId,
                ) == specs
            ) {
                return true
            }
            Thread.sleep(ACTIVE_TILES_WRITE_RETRY_DELAY_MS)
        }
        return false
    }

    private fun addDefaultA11Items(raw: String, userId: Int): String {
        if (isA11Pad()) return removeA11PadControlTiles(raw)
        val specs = raw.split(',').filter(String::isNotBlank).toMutableList()
        specs.remove(VOLUME_SLIDER_SPEC)
        specs.remove(BRIGHTNESS_SLIDER_SPEC)
        val walletIndex = specs.indexOf("wallet")
        val insertion = if (walletIndex >= 0) walletIndex + 1 else specs.size
        specs.add(insertion, VOLUME_SLIDER_SPEC)
        specs.add(insertion + 1, BRIGHTNESS_SLIDER_SPEC)
        if (!specs.contains(AUTO_BRIGHTNESS_SPEC) && shouldInsertAutoBrightness(userId)) {
            specs.add(insertion + 2, AUTO_BRIGHTNESS_SPEC)
        }
        return specs.joinToString(",")
    }

    private fun migrateA11AutoBrightness(raw: String, userId: Int): String {
        if (!shouldInsertAutoBrightness(userId)) return raw
        val specs = raw.split(',').filter(String::isNotBlank).toMutableList()
        if (specs.contains(AUTO_BRIGHTNESS_SPEC)) return raw
        val brightness = specs.indexOf(BRIGHTNESS_SLIDER_SPEC)
        specs.add(if (brightness >= 0) brightness + 1 else specs.size, AUTO_BRIGHTNESS_SPEC)
        return specs.joinToString(",")
    }

    private fun normalizeA11Tiles(raw: String, userId: Int): String =
        if (isA11Pad()) removeA11PadControlTiles(raw) else migrateA11AutoBrightness(raw, userId)

    /** The tablet uses the native horizontal brightness row, not A11 slider pseudo-tiles. */
    private fun removeA11PadControlTiles(raw: String): String =
        raw
            .split(',')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filter { it != VOLUME_SLIDER_SPEC && it != BRIGHTNESS_SLIDER_SPEC }
            .distinct()
            .joinToString(",")

    private fun isA11Pad(): Boolean =
        context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP

    private fun shouldInsertAutoBrightness(userId: Int): Boolean {
        return try {
            val raw =
                Settings.Secure.getStringForUser(
                    resolver,
                    Settings.Secure.QS_TILE_LAYOUT_A11,
                    userId,
                )
            val layout = if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
            if (layout.optInt("version", 0) >= A11_LAYOUT_MIGRATION_VERSION) {
                false
            } else {
                layout.put("version", A11_LAYOUT_MIGRATION_VERSION)
                Settings.Secure.putStringForUser(
                    resolver,
                    Settings.Secure.QS_TILE_LAYOUT_A11,
                    layout.toString(),
                    userId,
                )
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun readAppliedStyle(userId: Int): Int {
        return Settings.Secure.getIntForUser(
            resolver,
            Settings.Secure.QS_UI_STYLE_APPLIED,
            QSStyleRuntime.STYLE_A11,
            userId,
        )
    }

    private fun rollbackRequest(userId: Int, applied: Int) {
        Settings.Secure.putIntForUser(
            resolver,
            Settings.Secure.QS_UI_STYLE,
            applied,
            userId,
        )
    }

    private fun requestRestart(reason: String) {
        if (restartRequested.compareAndSet(false, true)) {
            restarter.restartAndroid(reason)
        }
    }

    private fun tilesKey(style: Int): String =
        if (style == QSStyleRuntime.STYLE_A16) {
            Settings.Secure.QS_TILES_A16
        } else {
            Settings.Secure.QS_TILES_A11
        }

    private fun styleName(style: Int): String =
        if (style == QSStyleRuntime.STYLE_A16) "A16" else "A11"

    private fun defaultTiles(style: Int): String =
        context.getString(
            if (style == QSStyleRuntime.STYLE_A16) {
                R.string.quick_settings_tiles_new_default
            } else {
                R.string.quick_settings_tiles_default
            }
        )

    companion object {
        private const val TAG = "QSStyleController"
        private const val TRANSACTION_VERSION = 1
        private const val A11_LAYOUT_MIGRATION_VERSION = 2
        private const val ACTIVE_TILES_WRITE_RETRIES = 100
        private const val ACTIVE_TILES_WRITE_RETRY_DELAY_MS = 20L
        private const val TABLET_MIN_WIDTH_DP = 720
        const val VOLUME_SLIDER_SPEC = "volume_slider"
        const val BRIGHTNESS_SLIDER_SPEC = "brightness_slider"
        const val AUTO_BRIGHTNESS_SPEC = "auto_brightness"
    }
}
