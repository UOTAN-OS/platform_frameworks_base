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
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val touchCoordinatesQueue = ConcurrentLinkedQueue<Triple<Float, Float, Boolean>>()

    private var overlayView: PopUpQuickMenuView? = null
    private var isGestureActive = false

    fun show(isLeft: Boolean, initialTouchX: Float = -1f, initialTouchY: Float = -1f) {
        hide()

        val selectedApps = getSelectedApps()
        if (selectedApps.isEmpty()) {
            return
        }

        val quickMenuView = PopUpQuickMenuView(context, isLeft)
        val packageManager = userTracker.userContext.packageManager
        val displayedApps = ArrayList<String>()

        selectedApps.take(MAX_ICONS - 1).forEach { packageName ->
            runCatching {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                val icon = appInfo.loadIcon(packageManager)
                ImageView(context).apply { setImageDrawable(icon) }
            }.onSuccess {
                quickMenuView.addView(it)
                displayedApps.add(packageName)
            }
        }

        quickMenuView.addView(
            ImageView(context).apply { setImageResource(R.drawable.ic_popup_more_apps) }
        )

        quickMenuView.setOnIconLaunchListener { index ->
            if (index < displayedApps.size) {
                launchPackage(displayedApps[index])
            } else {
                launchAllApps()
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

    private fun getSelectedApps(): List<String> {
        val selected = Settings.System.getStringForUser(
            context.contentResolver,
            Settings.System.POP_UP_VIEW_QUICK_MENU_SELECTED_APPS,
            userTracker.userId,
        ).orEmpty()
        return selected.split("|").filter { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "PopUpQuickMenuController"
        private const val MAX_ICONS = 6
        private const val FREEFORM_SETTINGS_PACKAGE = "org.uwuaosp.settingsext"
        private const val FREEFORM_SETTINGS_ALL_APPS_ACTIVITY =
            "org.uwuaosp.settingsext.popup.AllAppsActivity"
    }
}
