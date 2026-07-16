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

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class PopUpQuickMenuView(
    context: Context,
    private val isLeft: Boolean,
) : ViewGroup(context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)

    private var hasAnimated = false
    private var selectedChildIndex = -1
    private var lastVibratedIndex = -1
    private var isTouching = false
    private var hasReceivedTouch = false
    private var isFirstTouchUp = true
    private var initialTouchX = -1f
    private var initialTouchY = -1f
    private var hasInitialTouchPoint = false

    private val iconLaunchListeners = mutableListOf<(Int) -> Unit>()
    private val dismissListeners = mutableListOf<() -> Unit>()

    init {
        setWillNotDraw(false)
        background = ColorDrawable(Color.BLACK).apply { alpha = 0 }
        isClickable = true
        isFocusable = true
    }

    fun setOnIconLaunchListener(listener: (Int) -> Unit) {
        iconLaunchListeners.add(listener)
    }

    fun setOnDismissListener(listener: () -> Unit) {
        dismissListeners.add(listener)
    }

    fun setInitialTouchPoint(x: Float, y: Float) {
        initialTouchX = x
        initialTouchY = y
        hasInitialTouchPoint = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        measureChildren(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(widthSize, heightSize)
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    override fun checkLayoutParams(p: LayoutParams?): Boolean {
        return p != null
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val count = childCount
        if (count == 0) return

        val bounds = windowManager.currentWindowMetrics.bounds
        val screenWidth = bounds.width()
        val screenHeight = bounds.height()
        val navbarHeight = getNavbarHeight()

        val iconRadius = min(screenWidth, screenHeight) * ICON_SIZE_RATIO / 2
        val circleXOffset = if (screenWidth > screenHeight) CIRCLE_OFFSET_X_LAND else CIRCLE_OFFSET_X_PORT
        val circleCenterY = if (screenWidth > screenHeight) CIRCLE_CENTER_Y_LAND else CIRCLE_CENTER_Y_PORT
        val radius = min(screenWidth, screenHeight) * CIRCLE_RADIUS_RATIO

        for (i in 0 until count) {
            val total = count
            val position = i + 1
            val angle = 45f + ((total + 1) / 2f - position) * (90f / (total + 1)) * ICON_SPACING_MULTIPLIER
            val x = if (isLeft) {
                (screenWidth * circleXOffset + radius * cos(Math.toRadians(angle.toDouble())) + iconRadius).toInt()
            } else {
                (screenWidth * (1f - circleXOffset) - radius * cos(Math.toRadians(angle.toDouble())) - iconRadius).toInt()
            }
            val y = (screenHeight * circleCenterY - radius * sin(Math.toRadians(angle.toDouble())) - iconRadius).toInt() - navbarHeight

            getChildAt(i).layout(
                (x - iconRadius).toInt(),
                (y - iconRadius).toInt(),
                (x + iconRadius).toInt(),
                (y + iconRadius).toInt(),
            )
        }

        if (!hasAnimated) {
            startIntroAnimation()
            hasAnimated = true
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isTouching = true
                hasReceivedTouch = true
                if (hasInitialTouchPoint) {
                    updateSelectedIcon(event.x, event.y)
                    if (selectedChildIndex < 0) {
                        checkIconAlongPath(initialTouchX, initialTouchY, event.x, event.y)
                    }
                } else {
                    updateSelectedIcon(event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTouching) {
                    updateSelectedIcon(event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isTouching) {
                    if (selectedChildIndex in 0 until childCount) {
                        iconLaunchListeners.forEach { it(selectedChildIndex) }
                    } else if (hasReceivedTouch && !isFirstTouchUp) {
                        dismissListeners.forEach { it() }
                    }
                    isFirstTouchUp = false
                    resetTouchState()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isTouching) {
                    resetTouchState()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun dispatchTouchCoordinates(x: Float, y: Float, isUp: Boolean) {
        when {
            !isTouching && !isUp -> {
                isTouching = true
                hasReceivedTouch = true
                updateSelectedIcon(x, y)
            }
            isTouching && !isUp -> updateSelectedIcon(x, y)
            isUp -> {
                if (selectedChildIndex in 0 until childCount) {
                    iconLaunchListeners.forEach { it(selectedChildIndex) }
                } else if (hasReceivedTouch && !isFirstTouchUp) {
                    dismissListeners.forEach { it() }
                }
                isFirstTouchUp = false
                resetTouchState()
            }
        }
    }

    private fun startIntroAnimation() {
        ObjectAnimator.ofInt(background, "alpha", 0, 128).apply {
            duration = ANIMATION_DURATION_MS
            start()
        }

        val bounds = windowManager.currentWindowMetrics.bounds
        val screenWidth = bounds.width()
        val screenHeight = bounds.height()
        val navbarHeight = getNavbarHeight()
        val circleXOffset = if (screenWidth > screenHeight) CIRCLE_OFFSET_X_LAND else CIRCLE_OFFSET_X_PORT
        val circleCenterY = if (screenWidth > screenHeight) CIRCLE_CENTER_Y_LAND else CIRCLE_CENTER_Y_PORT
        val centerX = if (isLeft) screenWidth * circleXOffset else screenWidth * (1f - circleXOffset)
        val centerY = screenHeight * circleCenterY - navbarHeight

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.alpha = 0f
            val startX = centerX - (child.left + child.width / 2)
            val startY = centerY - (child.top + child.height / 2)
            child.translationX = startX
            child.translationY = startY

            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(child, "translationX", startX, 0f),
                    ObjectAnimator.ofFloat(child, "translationY", startY, 0f),
                    ObjectAnimator.ofFloat(child, "alpha", 0f, 1f),
                )
                interpolator = DecelerateInterpolator(1.5f)
                duration = ANIMATION_DURATION_MS + i * 15L
                start()
            }
        }
    }

    private fun getNavbarHeight(): Int {
        return windowManager.currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.navigationBars()).bottom
    }

    private fun checkIconAlongPath(startX: Float, startY: Float, endX: Float, endY: Float) {
        val steps = 5
        for (i in 1 until steps) {
            val ratio = i.toFloat() / steps
            val checkX = startX + (endX - startX) * ratio
            val checkY = startY + (endY - startY) * ratio
            for (j in 0 until childCount) {
                val child = getChildAt(j)
                if (checkX >= child.left && checkX <= child.right && checkY >= child.top && checkY <= child.bottom) {
                    selectedChildIndex = j
                    updateIconScales()
                    performHapticFeedback()
                    lastVibratedIndex = j
                    return
                }
            }
        }
    }

    private fun updateSelectedIcon(x: Float, y: Float) {
        var newSelectedIndex = -1
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) {
                newSelectedIndex = i
                break
            }
        }

        if (newSelectedIndex != selectedChildIndex) {
            selectedChildIndex = newSelectedIndex
            updateIconScales()
        }

        if (selectedChildIndex >= 0 && selectedChildIndex != lastVibratedIndex) {
            performHapticFeedback()
            lastVibratedIndex = selectedChildIndex
        }
    }

    private fun updateIconScales() {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val targetScale = if (i == selectedChildIndex) 1.15f else 1f
            child.animate().scaleX(targetScale).scaleY(targetScale).setDuration(100).start()
        }
    }

    private fun performHapticFeedback() {
        vibrator?.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun resetTouchState() {
        isTouching = false
        selectedChildIndex = -1
        lastVibratedIndex = -1
    }

    companion object {
        private const val ANIMATION_DURATION_MS = 180L
        private const val CIRCLE_OFFSET_X_PORT = 0.1f
        private const val CIRCLE_CENTER_Y_PORT = 0.95f
        private const val CIRCLE_OFFSET_X_LAND = 0.1f
        private const val CIRCLE_CENTER_Y_LAND = 0.95f
        private const val ICON_SIZE_RATIO = 0.1f
        private const val ICON_SPACING_MULTIPLIER = 1.5f
        private const val CIRCLE_RADIUS_RATIO = 0.4f

        fun createLayoutParams(): WindowManager.LayoutParams {
            return WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                title = "Pop-Up Quick Menu"
                gravity = Gravity.TOP or Gravity.START
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                    WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION or
                    WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                accessibilityTitle = " "
            }
        }
    }
}
