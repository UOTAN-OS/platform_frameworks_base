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

package com.android.systemui.prism

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.util.SparseArray
import android.view.Choreographer
import android.view.InputEvent
import android.view.MotionEvent
import androidx.core.content.FileProvider
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.screenshot.ImageCapture
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.system.InputChannelCompat
import com.android.systemui.shared.system.InputMonitorCompat
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class PrismGestureController
@Inject
constructor(
    private val context: Context,
    private val imageCapture: ImageCapture,
    private val displayTracker: DisplayTracker,
    private val userTracker: UserTracker,
    private val keyguardStateController: KeyguardStateController,
    private val powerManager: PowerManager,
    @Background private val backgroundScope: CoroutineScope,
    @Main private val mainExecutor: Executor,
) : CoreStartable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pointerStarts = SparseArray<PointerStart>()
    private var inputMonitor: InputMonitorCompat? = null
    private var inputReceiver: InputChannelCompat.InputEventReceiver? = null
    private var threeFingerReady = false
    private var gestureConsumed = false
    private var lastTriggerTime = 0L

    private val settingObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            updateListening()
        }
    }

    private val userCallback = object : UserTracker.Callback {
        override fun onUserChanged(newUser: Int, userContext: Context) {
            updateListening()
        }
    }

    override fun start() {
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(THREE_FINGER_SETTING),
            false,
            settingObserver,
            UserHandle.USER_ALL,
        )
        userTracker.addCallback(userCallback, mainExecutor)
        updateListening()
    }

    private fun updateListening() {
        val enabled = Settings.Secure.getIntForUser(
            context.contentResolver,
            THREE_FINGER_SETTING,
            0,
            userTracker.userId,
        ) != 0
        if (enabled && inputMonitor == null) {
            startListening()
        } else if (!enabled && inputMonitor != null) {
            stopListening()
        }
    }

    private fun startListening() {
        stopListening()
        val monitor = InputMonitorCompat(TAG, displayTracker.defaultDisplayId)
        inputMonitor = monitor
        inputReceiver = monitor.getInputReceiver(
            Looper.getMainLooper(),
            Choreographer.getInstance(),
            ::onInputEvent,
        )
        Log.i(TAG, "Three-finger gesture enabled")
    }

    private fun stopListening() {
        inputReceiver?.dispose()
        inputReceiver = null
        inputMonitor?.dispose()
        inputMonitor = null
        resetGesture()
    }

    private fun onInputEvent(event: InputEvent) {
        if (event !is MotionEvent || !event.isTouchEvent) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetGesture()
                recordPointer(event, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                recordPointer(event, event.actionIndex)
                if (pointerStarts.size() == REQUIRED_POINTERS) {
                    val earliest = (0 until pointerStarts.size())
                        .minOf { pointerStarts.valueAt(it).downTime }
                    val latest = (0 until pointerStarts.size())
                        .maxOf { pointerStarts.valueAt(it).downTime }
                    threeFingerReady = latest - earliest <= MAX_POINTER_DOWN_SPAN_MS
                } else if (pointerStarts.size() > REQUIRED_POINTERS) {
                    threeFingerReady = false
                }
            }
            MotionEvent.ACTION_MOVE -> maybeTrigger(event)
            MotionEvent.ACTION_POINTER_UP -> {
                if (!gestureConsumed) threeFingerReady = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> resetGesture()
        }
    }

    private fun recordPointer(event: MotionEvent, index: Int) {
        pointerStarts.put(
            event.getPointerId(index),
            PointerStart(
                x = event.getX(index),
                y = event.getY(index),
                downTime = event.eventTime,
            ),
        )
    }

    private fun maybeTrigger(event: MotionEvent) {
        if (!threeFingerReady || gestureConsumed || pointerStarts.size() != REQUIRED_POINTERS) {
            return
        }
        val earliestDown = (0 until pointerStarts.size())
            .minOf { pointerStarts.valueAt(it).downTime }
        if (event.eventTime - earliestDown > MAX_GESTURE_DURATION_MS) {
            threeFingerReady = false
            return
        }

        var totalDx = 0f
        var totalDy = 0f
        for (index in 0 until pointerStarts.size()) {
            val pointerId = pointerStarts.keyAt(index)
            val eventIndex = event.findPointerIndex(pointerId)
            if (eventIndex < 0) {
                threeFingerReady = false
                return
            }
            val start = pointerStarts.valueAt(index)
            val dx = event.getX(eventIndex) - start.x
            val dy = event.getY(eventIndex) - start.y
            if (
                dy > -swipeThresholdPx() * MIN_INDIVIDUAL_SWIPE_RATIO ||
                abs(dy) <= abs(dx) * VERTICAL_DOMINANCE_RATIO
            ) {
                return
            }
            totalDx += dx
            totalDy += dy
        }

        val averageDx = totalDx / REQUIRED_POINTERS
        val averageDy = totalDy / REQUIRED_POINTERS
        if (
            averageDy > -swipeThresholdPx() ||
            abs(averageDy) <= abs(averageDx) * VERTICAL_DOMINANCE_RATIO
        ) {
            return
        }

        gestureConsumed = true
        if (
            !powerManager.isInteractive ||
            !keyguardStateController.isUnlocked ||
            SystemClock.elapsedRealtime() - lastTriggerTime < TRIGGER_COOLDOWN_MS
        ) {
            return
        }

        lastTriggerTime = SystemClock.elapsedRealtime()
        inputMonitor?.pilferPointers()
        captureAndLaunch()
    }

    private fun captureAndLaunch() {
        val displayId = displayTracker.defaultDisplayId
        val userHandle = userTracker.userHandle
        backgroundScope.launch {
            val bitmap = imageCapture.captureDisplay(displayId)
            if (bitmap == null) {
                Log.e(TAG, "Display capture failed")
                return@launch
            }
            val uri = runCatching { saveCapture(bitmap) }
                .onFailure { Log.e(TAG, "Could not save temporary capture", it) }
                .getOrNull()
            bitmap.recycle()
            uri ?: return@launch

            mainExecutor.execute {
                val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                val intent = Intent().apply {
                    component = PRISM_CAPTURE_COMPONENT
                    data = uri
                    clipData = ClipData.newRawUri("uwuPrism screenshot", uri)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            grantFlags,
                    )
                }
                runCatching {
                    context.startActivityAsUser(intent, userHandle)
                    mainHandler.postDelayed(
                        {
                            context.revokeUriPermission(uri, grantFlags)
                            runCatching {
                                context.contentResolver.delete(uri, null, null)
                            }
                        },
                        CAPTURE_MAX_AGE_MS,
                    )
                }.onFailure {
                    context.revokeUriPermission(uri, grantFlags)
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    Log.e(TAG, "Could not launch uwuPrism", it)
                }
            }
        }
    }

    private fun saveCapture(bitmap: Bitmap): android.net.Uri {
        val directory = File(context.cacheDir, CAPTURE_DIRECTORY).apply { mkdirs() }
        val now = System.currentTimeMillis()
        directory.listFiles()?.forEach { file ->
            if (file.isFile && now - file.lastModified() > CAPTURE_MAX_AGE_MS) {
                file.delete()
            }
        }
        val file = File(directory, "capture-$now.png")
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            return FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun swipeThresholdPx(): Float {
        return SWIPE_DISTANCE_DP * context.resources.displayMetrics.density
    }

    private fun resetGesture() {
        pointerStarts.clear()
        threeFingerReady = false
        gestureConsumed = false
    }

    private data class PointerStart(
        val x: Float,
        val y: Float,
        val downTime: Long,
    )

    private companion object {
        const val TAG = "PrismGesture"
        const val THREE_FINGER_SETTING = "uwu_prism_three_finger_gesture"
        const val REQUIRED_POINTERS = 3
        const val MAX_POINTER_DOWN_SPAN_MS = 180L
        const val MAX_GESTURE_DURATION_MS = 700L
        const val TRIGGER_COOLDOWN_MS = 1_000L
        const val SWIPE_DISTANCE_DP = 72f
        const val MIN_INDIVIDUAL_SWIPE_RATIO = 0.45f
        const val VERTICAL_DOMINANCE_RATIO = 1.25f
        const val FILE_PROVIDER_AUTHORITY = "com.android.systemui.fileprovider"
        const val CAPTURE_DIRECTORY = "uwu_prism"
        const val CAPTURE_MAX_AGE_MS = 5 * 60 * 1_000L

        val PRISM_CAPTURE_COMPONENT = ComponentName(
            "org.uwuaosp.prism",
            "org.uwuaosp.prism.PrismCaptureActivity",
        )
    }
}
