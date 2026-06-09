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

package com.android.systemui.statusbar.phone.popup

import android.app.ActivityOptions
import android.app.WindowConfiguration
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.ImageView
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject

@SysUISingleton
class PopUpQuickMenuController
@Inject
constructor(
    @Application private val context: Context,
    private val userTracker: UserTracker,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val touchCoordinatesQueue = ConcurrentLinkedQueue<Triple<Float, Float, Boolean>>()

    private var overlayView: PopUpQuickMenuView? = null
    private var isGestureActive = false

    fun show(isLeft: Boolean, initialTouchX: Float = -1f, initialTouchY: Float = -1f) {
        hide()

        val innerTargets = getInnerRingTargets()
        val outerTargets = getOuterRingTargets()
        val quickMenuView = PopUpQuickMenuView(context, isLeft)
        val displayedInnerTargets = ArrayList<QuickMenuTarget>()
        val displayedOuterTargets = ArrayList<QuickMenuTarget>()

        innerTargets.take(INNER_MAX_ICONS - 1).forEach { target ->
            createIconView(target)?.let { iconView ->
                quickMenuView.addView(iconView)
                displayedInnerTargets.add(target)
            }
        }

        // Keep the all-apps affordance available even when no quick apps are configured.
        quickMenuView.addView(
            ImageView(context).apply { setImageResource(R.drawable.ic_popup_more_apps) }
        )

        outerTargets.take(OUTER_MAX_ICONS).forEach { target ->
            createIconView(target)?.let { iconView ->
                quickMenuView.addView(iconView)
                displayedOuterTargets.add(target)
            }
        }

        quickMenuView.setOnIconLaunchListener { index ->
            when {
                index < displayedInnerTargets.size -> launchTarget(displayedInnerTargets[index])
                index == displayedInnerTargets.size -> launchAllApps()
                else -> displayedOuterTargets
                    .getOrNull(index - displayedInnerTargets.size - 1)
                    ?.let(::launchTarget)
            }
            hide()
        }
        quickMenuView.setOnDismissListener { hide() }
        if (initialTouchX >= 0f && initialTouchY >= 0f) {
            quickMenuView.setInitialTouchPoint(initialTouchX, initialTouchY)
        }

        try {
            windowManager.addView(quickMenuView, PopUpQuickMenuView.createLayoutParams())
            overlayView = quickMenuView
            while (touchCoordinatesQueue.isNotEmpty()) {
                touchCoordinatesQueue.poll()?.let { (x, y, isUp) ->
                    quickMenuView.dispatchTouchCoordinates(x, y, isUp)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add quick menu view", e)
            overlayView = null
            isGestureActive = false
            touchCoordinatesQueue.clear()
        }
    }

    fun onTouchCoordinates(x: Float, y: Float, isUp: Boolean) {
        if (!isGestureActive && !isUp) {
            isGestureActive = true
        }

        val quickMenuView = overlayView
        if (quickMenuView == null) {
            touchCoordinatesQueue.add(Triple(x, y, isUp))
        } else {
            quickMenuView.dispatchTouchCoordinates(x, y, isUp)
        }

        if (isUp) {
            isGestureActive = false
            touchCoordinatesQueue.clear()
        }
    }

    fun hide() {
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove quick menu view", e)
            }
        }
        overlayView = null
        isGestureActive = false
        touchCoordinatesQueue.clear()
    }

    private fun createIconView(target: QuickMenuTarget): ImageView? {
        val icon = runCatching { loadIcon(target) }
            .onFailure { Log.w(TAG, "Failed to load icon for $target", it) }
            .getOrNull()
            ?: return null
        return ImageView(context).apply { setImageDrawable(icon) }
    }

    private fun loadIcon(target: QuickMenuTarget): Drawable {
        return when (target) {
            is QuickMenuTarget.App -> {
                val appInfo = userTracker.userContext.packageManager.getApplicationInfo(
                    target.packageName,
                    0,
                )
                appInfo.loadIcon(userTracker.userContext.packageManager)
            }
            is QuickMenuTarget.Shortcut -> {
                val shortcutInfo = getShortcutInfo(target)
                    ?: return fallbackAppIcon(target.packageName)
                launcherApps.getShortcutBadgedIconDrawable(
                    shortcutInfo,
                    context.resources.displayMetrics.densityDpi,
                ) ?: fallbackAppIcon(target.packageName)
            }
        }
    }

    private fun fallbackAppIcon(packageName: String): Drawable =
        userTracker.userContext.packageManager.getApplicationInfo(packageName, 0)
            .loadIcon(userTracker.userContext.packageManager)

    private fun launchTarget(target: QuickMenuTarget) {
        when (target) {
            is QuickMenuTarget.App -> launchPackage(target.packageName)
            is QuickMenuTarget.Shortcut -> launchShortcut(target)
        }
    }

    private fun launchPackage(packageName: String) {
        val launchIntent = userTracker.userContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = ActivityOptions.makeBasic().apply {
            setLaunchWindowingMode(WindowConfiguration.WINDOWING_MODE_MINI_WINDOW_EXT)
            setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
        }
        runCatching {
            context.startActivityAsUser(launchIntent, options.toBundle(), userTracker.userHandle)
        }.onFailure {
            Log.w(TAG, "Failed to launch package $packageName", it)
        }
    }

    private fun launchShortcut(target: QuickMenuTarget.Shortcut) {
        val options = ActivityOptions.makeBasic().apply {
            setLaunchWindowingMode(WindowConfiguration.WINDOWING_MODE_MINI_WINDOW_EXT)
            setApplyMultipleTaskFlagForShortcut(true)
            setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
        }
        runCatching {
            launcherApps.startShortcut(
                target.packageName,
                target.shortcutId,
                null,
                options.toBundle(),
                UserHandle.of(target.userId),
            )
        }.onFailure {
            Log.w(TAG, "Failed to launch shortcut ${target.packageName}/${target.shortcutId}", it)
        }
    }

    private fun launchAllApps() {
        val intent = Intent().apply {
            setClassName(
                FREEFORM_SETTINGS_PACKAGE,
                FREEFORM_SETTINGS_ALL_APPS_ACTIVITY,
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        val options = ActivityOptions.makeBasic().apply {
            setLaunchWindowingMode(WindowConfiguration.WINDOWING_MODE_MINI_WINDOW_EXT)
            setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
        }
        runCatching {
            context.startActivityAsUser(intent, options.toBundle(), userTracker.userHandle)
        }.onFailure {
            Log.w(TAG, "Failed to launch all apps picker", it)
        }
    }

    private fun getInnerRingTargets(): List<QuickMenuTarget> {
        return readTargets(Settings.System.POP_UP_VIEW_QUICK_MENU_SELECTED_APPS)
    }

    private fun getOuterRingTargets(): List<QuickMenuTarget> {
        return readTargets(Settings.System.POP_UP_VIEW_QUICK_MENU_OUTER_RING_SELECTED_APPS)
    }

    private fun readTargets(key: String): List<QuickMenuTarget> {
        val selected = Settings.System.getStringForUser(
            context.contentResolver,
            key,
            userTracker.userId,
        ).orEmpty()
        return selected.split(ENTRY_SEPARATOR).mapNotNull(::parseTarget)
    }

    private fun parseTarget(rawEntry: String): QuickMenuTarget? {
        val entry = rawEntry.trim()
        if (entry.isBlank()) {
            return null
        }
        if (!entry.startsWith(ENTRY_PREFIX_APP) && !entry.startsWith(ENTRY_PREFIX_SHORTCUT)) {
            return QuickMenuTarget.App(entry)
        }

        return when {
            entry.startsWith(ENTRY_PREFIX_APP) -> {
                val packageName = Uri.decode(entry.removePrefix(ENTRY_PREFIX_APP)).trim()
                packageName.takeIf { it.isNotBlank() }?.let(QuickMenuTarget::App)
            }
            entry.startsWith(ENTRY_PREFIX_SHORTCUT) -> parseShortcutTarget(entry)
            else -> null
        }
    }

    private fun parseShortcutTarget(entry: String): QuickMenuTarget.Shortcut? {
        val parts = entry.split(ENTRY_FIELD_SEPARATOR, limit = 4)
        if (parts.size !in 3..4) {
            Log.w(TAG, "Ignoring malformed quick menu shortcut entry: $entry")
            return null
        }

        val hasExplicitUserId = parts.size == 4
        val userId =
            if (hasExplicitUserId) {
                parts[1].toIntOrNull()
            } else {
                userTracker.userId
            }
        val packageName = Uri.decode(parts[if (hasExplicitUserId) 2 else 1]).trim()
        val shortcutId = Uri.decode(parts[if (hasExplicitUserId) 3 else 2]).trim()
        if (userId == null || packageName.isBlank() || shortcutId.isBlank()) {
            Log.w(TAG, "Ignoring malformed quick menu shortcut entry: $entry")
            return null
        }
        return QuickMenuTarget.Shortcut(
            packageName = packageName,
            shortcutId = shortcutId,
            userId = userId,
        )
    }

    private fun getShortcutInfo(target: QuickMenuTarget.Shortcut): ShortcutInfo? {
        return queryShortcutInfo(
            target,
            LauncherApps.ShortcutQuery.FLAG_MATCH_ALL_KINDS_WITH_ALL_PINNED,
        ) ?: queryShortcutInfo(
            target,
            LauncherApps.ShortcutQuery.FLAG_MATCH_ALL_KINDS,
        )
    }

    private fun queryShortcutInfo(
        target: QuickMenuTarget.Shortcut,
        queryFlags: Int,
    ): ShortcutInfo? {
        return runCatching {
            launcherApps.getShortcuts(
                LauncherApps.ShortcutQuery()
                    .setPackage(target.packageName)
                    .setShortcutIds(listOf(target.shortcutId))
                    .setQueryFlags(queryFlags),
                UserHandle.of(target.userId),
            )
        }.onFailure {
            Log.w(
                TAG,
                "Failed to query shortcut ${target.packageName}/${target.shortcutId} with flags=$queryFlags",
                it,
            )
        }.getOrNull()?.firstOrNull()
    }

    companion object {
        private const val TAG = "PopUpQuickMenuController"
        private const val INNER_MAX_ICONS = 6
        private const val OUTER_MAX_ICONS = 7
        private const val ENTRY_SEPARATOR = "|"
        private const val ENTRY_PREFIX_APP = "app:"
        private const val ENTRY_PREFIX_SHORTCUT = "shortcut:"
        private const val ENTRY_FIELD_SEPARATOR = ":"
        private const val FREEFORM_SETTINGS_PACKAGE = "org.uwuaosp.settingsext"
        private const val FREEFORM_SETTINGS_ALL_APPS_ACTIVITY =
            "org.uwuaosp.settingsext.popup.AllAppsActivity"
    }

    private sealed interface QuickMenuTarget {
        data class App(val packageName: String) : QuickMenuTarget

        data class Shortcut(
            val packageName: String,
            val shortcutId: String,
            val userId: Int,
        ) : QuickMenuTarget
    }
}
