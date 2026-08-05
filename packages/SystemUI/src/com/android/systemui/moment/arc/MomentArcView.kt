/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MomentArcView(context: Context, private val isLeft: Boolean) : ViewGroup(context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private var hasAnimated = false
    private var selectedChildIndex = -1
    private var lastVibratedIndex = -1
    private var isTouching = false
    private var initialTouchX = -1f
    private var initialTouchY = -1f
    private var hasInitialTouchPoint = false
    private var iconLaunchListener: ((Int) -> Unit)? = null
    private var dismissListener: (() -> Unit)? = null
    private val pendingTouchCoordinates = ArrayDeque<TouchCoordinates>()

    init {
        setWillNotDraw(false)
        background = ColorDrawable(Color.BLACK).apply { alpha = 0 }
    }

    fun setOnIconLaunchListener(listener: (Int) -> Unit) { iconLaunchListener = listener }
    fun setOnDismissListener(listener: () -> Unit) { dismissListener = listener }

    fun setInitialTouchPoint(x: Float, y: Float) {
        initialTouchX = x
        initialTouchY = y
        hasInitialTouchPoint = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureChildren(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec),
        )
    }

    override fun generateDefaultLayoutParams() =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun checkLayoutParams(params: LayoutParams?) = params != null

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (childCount == 0) return
        val bounds = windowManager.currentWindowMetrics.bounds
        val screenWidth = bounds.width()
        val screenHeight = bounds.height()
        val navbarHeight = windowManager.currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.navigationBars()).bottom
        val centerX = screenWidth * if (isLeft) CIRCLE_X else 1f - CIRCLE_X
        val centerY = screenHeight * CIRCLE_Y - navbarHeight
        val dimension = min(screenWidth, screenHeight)
        val iconRadius = dimension * ICON_SIZE_RATIO / 2f

        for (index in 0 until childCount) {
            val inner = index < INNER_CHILD_COUNT
            val localIndex = if (inner) index else index - INNER_CHILD_COUNT
            val total = if (inner) INNER_CHILD_COUNT else OUTER_CHILD_COUNT
            val radius = dimension * if (inner) INNER_RADIUS_RATIO else OUTER_RADIUS_RATIO
            val spacing = if (inner) INNER_SPACING else OUTER_SPACING
            val start = if (inner) INNER_ANGLE_START else OUTER_ANGLE_START
            val end = if (inner) INNER_ANGLE_END else OUTER_ANGLE_END
            val angle = start + ((total + 1) / 2f - (localIndex + 1)) *
                ((end - start) / (total + 1)) * spacing
            val horizontal = radius * cos(Math.toRadians(angle.toDouble()))
            val vertical = radius * sin(Math.toRadians(angle.toDouble()))
            val x = if (isLeft) centerX + horizontal else centerX - horizontal
            val y = centerY - vertical
            getChildAt(index).layout(
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
        while (pendingTouchCoordinates.isNotEmpty()) {
            pendingTouchCoordinates.removeFirst().let {
                processTouchCoordinates(it.x, it.y, it.isUp, it.isCancelled)
            }
        }
    }

    fun dispatchTouchCoordinates(x: Float, y: Float, isUp: Boolean, isCancelled: Boolean) {
        if (isCancelled) {
            pendingTouchCoordinates.clear()
            cancelTouch()
            return
        }
        if (!isLaidOut) {
            pendingTouchCoordinates.addLast(TouchCoordinates(x, y, isUp, isCancelled))
            return
        }
        processTouchCoordinates(x, y, isUp, isCancelled)
    }

    private fun processTouchCoordinates(
        x: Float,
        y: Float,
        isUp: Boolean,
        isCancelled: Boolean,
    ) {
        if (isCancelled) {
            cancelTouch()
            return
        }
        when {
            !isTouching && !isUp -> {
                isTouching = true
                updateSelectedIcon(x, y)
                if (selectedChildIndex < 0 && hasInitialTouchPoint) {
                    checkIconAlongPath(initialTouchX, initialTouchY, x, y)
                }
            }
            isTouching && !isUp -> updateSelectedIcon(x, y)
            isUp -> finishTouch()
        }
    }

    private fun finishTouch() {
        if (selectedChildIndex in 0 until childCount) {
            iconLaunchListener?.invoke(selectedChildIndex)
        } else {
            dismissListener?.invoke()
        }
        resetTouchState()
    }

    private fun cancelTouch() {
        resetTouchState()
        dismissListener?.invoke()
    }

    private fun checkIconAlongPath(startX: Float, startY: Float, endX: Float, endY: Float) {
        for (step in 1 until PATH_STEPS) {
            val ratio = step.toFloat() / PATH_STEPS
            val x = startX + (endX - startX) * ratio
            val y = startY + (endY - startY) * ratio
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == View.VISIBLE && x >= child.left && x <= child.right &&
                    y >= child.top && y <= child.bottom
                ) {
                    select(index)
                    return
                }
            }
        }
    }

    private fun updateSelectedIcon(x: Float, y: Float) {
        var selected = -1
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.VISIBLE && x >= child.left && x <= child.right &&
                y >= child.top && y <= child.bottom
            ) {
                selected = index
                break
            }
        }
        select(selected)
    }

    private fun select(index: Int) {
        if (selectedChildIndex != index) {
            selectedChildIndex = index
            for (childIndex in 0 until childCount) {
                val scale = if (childIndex == index) 1.15f else 1f
                getChildAt(childIndex).animate().scaleX(scale).scaleY(scale).setDuration(100L).start()
            }
        }
        if (index >= 0 && index != lastVibratedIndex) {
            vibrator?.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
            lastVibratedIndex = index
        }
    }

    private fun startIntroAnimation() {
        ObjectAnimator.ofInt(background, "alpha", 0, 128).apply {
            duration = ANIMATION_DURATION_MS
            start()
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.alpha = 0f
            child.scaleX = 0.8f
            child.scaleY = 0.8f
            val delay = index * 15L + if (index >= INNER_CHILD_COUNT) 100L else 0L
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(child, "scaleX", 0.8f, 1f),
                    ObjectAnimator.ofFloat(child, "scaleY", 0.8f, 1f),
                    ObjectAnimator.ofFloat(child, "alpha", 0f, 1f),
                )
                interpolator = DecelerateInterpolator(1.5f)
                duration = ANIMATION_DURATION_MS + delay
                start()
            }
        }
    }

    private fun resetTouchState() {
        isTouching = false
        selectedChildIndex = -1
        lastVibratedIndex = -1
    }

    private data class TouchCoordinates(
        val x: Float,
        val y: Float,
        val isUp: Boolean,
        val isCancelled: Boolean,
    )

    companion object {
        private const val ANIMATION_DURATION_MS = 180L
        private const val CIRCLE_X = 0.1f
        private const val CIRCLE_Y = 0.95f
        private const val ICON_SIZE_RATIO = 0.1f
        private const val INNER_RADIUS_RATIO = 0.4f
        private const val OUTER_RADIUS_RATIO = 0.52f
        private const val INNER_SPACING = 1.5f
        private const val OUTER_SPACING = 1.2f
        private const val INNER_ANGLE_START = 45f
        private const val INNER_ANGLE_END = 135f
        private const val OUTER_ANGLE_START = 30f
        private const val OUTER_ANGLE_END = 150f
        private const val INNER_CHILD_COUNT = 6
        private const val OUTER_CHILD_COUNT = 7
        private const val PATH_STEPS = 5

        fun createLayoutParams() =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                title = "MomentArc"
                gravity = Gravity.TOP or Gravity.START
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                privateFlags =
                    WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                        WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION or
                        WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                accessibilityTitle = " "
            }
    }
}
