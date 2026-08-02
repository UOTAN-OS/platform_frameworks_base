/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.tileimpl

import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources.ID_NULL
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.os.Trace
import android.service.quicksettings.Tile
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import androidx.core.graphics.drawable.updateBounds
import com.android.app.animation.Interpolators
import com.android.app.tracing.traceSection
import com.android.settingslib.Utils
import com.android.systemui.Flags
import com.android.systemui.FontSizeUtils
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.LaunchableView
import com.android.systemui.animation.LaunchableViewDelegate
import com.android.systemui.animation.view.LaunchableLinearLayout
import com.android.systemui.haptics.qs.QSLongPressEffect
import com.android.systemui.plugins.qs.QSIconView
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.qs.QSTile.AdapterState
import com.android.systemui.plugins.qs.QSTileView
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSIconViewImpl.QS_ANIM_LENGTH
import com.android.systemui.res.R
import java.util.Objects

private const val TAG = "QSTileViewImpl"

open class QSTileViewImpl
@JvmOverloads
constructor(
    context: Context,
    private val collapsed: Boolean = false,
    private val longPressEffect: QSLongPressEffect? = null,
) : QSTileView(context), HeightOverrideable, LaunchableView {

    companion object {
        private const val INVALID = -1
        private const val BACKGROUND_NAME = "background"
        private const val LABEL_NAME = "label"
        private const val SECONDARY_LABEL_NAME = "secondaryLabel"
        private const val CHEVRON_NAME = "chevron"
        private const val OVERLAY_NAME = "overlay"
        const val UNAVAILABLE_ALPHA = 0.3f
        @VisibleForTesting internal const val TILE_STATE_RES_PREFIX = "tile_states_"
        @VisibleForTesting internal const val LONG_PRESS_EFFECT_WIDTH_SCALE = 1.1f
        @VisibleForTesting internal const val LONG_PRESS_EFFECT_HEIGHT_SCALE = 1.2f
        internal val EMPTY_RECT = Rect()
    }

    private val icon: QSIconViewImpl = QSIconViewImpl(context)
    private var position: Int = INVALID
    private var hasLongClickEffect: Boolean = true

    override fun setPosition(position: Int) {
        this.position = position
    }

    override var heightOverride: Int = HeightOverrideable.NO_OVERRIDE
        set(value) {
            if (field == value) return
            field = value
            if (longPressEffect?.state != QSLongPressEffect.State.RUNNING_BACKWARDS_FROM_CANCEL) {
                updateHeight()
            }
        }

    override var squishinessFraction: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            updateHeight()
        }

    private var colorActive =
        if (
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        ) {
            Utils.getColorAttrDefaultColor(context, R.attr.shadeActive)
        } else {
            context.getColor(R.color.a11_qs_active_background)
        }
    private var colorInactive =
        if (
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        ) {
            Utils.getColorAttrDefaultColor(context, R.attr.shadeInactive)
        } else {
            context.getColor(R.color.a11_qs_inactive_background)
        }
    private var colorUnavailable = Utils.applyAlpha(UNAVAILABLE_ALPHA, colorInactive)

    private val overlayColorActive =
        Utils.applyAlpha(
            /* alpha= */ 0.11f,
            Utils.getColorAttrDefaultColor(context, R.attr.onShadeActive),
        )
    private val overlayColorInactive =
        Utils.applyAlpha(
            /* alpha= */ 0.08f,
            Utils.getColorAttrDefaultColor(context, R.attr.onShadeInactive),
        )

    private var colorLabelActive = Color.BLACK
    private var colorLabelInactive = Color.BLACK
    private val colorLabelUnavailable = Utils.getColorAttrDefaultColor(context, R.attr.outline)

    private var colorSecondaryLabelActive = Color.BLACK
    private var colorSecondaryLabelInactive = Color.BLACK
    private val colorSecondaryLabelUnavailable =
        Utils.getColorAttrDefaultColor(context, R.attr.outline)

    private lateinit var iconContainer: LaunchableLinearLayout
    private lateinit var label: TextView
    protected lateinit var secondaryLabel: TextView
    private lateinit var labelContainer: IgnorableChildLinearLayout
    protected lateinit var sideView: ViewGroup
    private lateinit var customDrawableView: ImageView
    private lateinit var chevronView: ImageView
    private lateinit var a11DndButton: ImageView
    private lateinit var a11DndTrack: FrameLayout
    private lateinit var a11DndDots: Array<View>
    private var a11RingerReceiverRegistered = false
    private val a11RingerReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateA11DndButtonAppearance(animate = true)
            }
        }
    private var mQsLogger: QSLogger? = null

    /** Controls if tile background is set to a [RippleDrawable] see [setClickable] */
    protected var showRippleEffect = true

    private lateinit var qsTileBackground: RippleDrawable
    private lateinit var qsTileFocusBackground: Drawable
    private lateinit var backgroundDrawable: LayerDrawable
    private lateinit var backgroundBaseDrawable: Drawable
    private lateinit var backgroundOverlayDrawable: Drawable

    private var backgroundColor: Int = 0
    private var backgroundOverlayColor: Int = 0
    private var a11ColumnSpan = 1
    private var a11RowSpan = 1
    private var a11TileSpec: String? = null

    private var radiusActive: Float = 0f
    private var radiusInactive: Float = 0f
    private val shapeAnimator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = QS_ANIM_LENGTH
            interpolator = Interpolators.FAST_OUT_SLOW_IN
            addUpdateListener { changeCornerRadius(it.animatedValue as Float) }
        }

    private val singleAnimator: ValueAnimator =
        ValueAnimator().apply {
            setDuration(QS_ANIM_LENGTH)
            addUpdateListener { animation ->
                setAllColors(
                    // These casts will throw an exception if some property is missing. We should
                    // always have all properties.
                    animation.getAnimatedValue(BACKGROUND_NAME) as Int,
                    animation.getAnimatedValue(LABEL_NAME) as Int,
                    animation.getAnimatedValue(SECONDARY_LABEL_NAME) as Int,
                    animation.getAnimatedValue(CHEVRON_NAME) as Int,
                    animation.getAnimatedValue(OVERLAY_NAME) as Int,
                )
            }
        }

    private val tileAnimator = AnimatorSet().apply { playTogether(singleAnimator, shapeAnimator) }

    private var accessibilityClass: String? = null
    private var stateDescriptionDeltas: CharSequence? = null
    private var lastStateDescription: CharSequence? = null
    private var tileState = false
    private var lastState = INVALID
    private var lastIconTint = 0
    private val launchableViewDelegate =
        LaunchableViewDelegate(this, superSetVisibility = { super.setVisibility(it) })
    private var lastDisabledByPolicy = false

    private val locInScreen = IntArray(2)

    private val tapTimeoutMillis =
        if (android.companion.virtualdevice.flags.Flags.viewconfigurationApis()) {
                ViewConfiguration.get(context).tapTimeoutMillis
            } else {
                ViewConfiguration.getTapTimeout()
            }
            .toLong()

    /** Visuo-haptic long-press effects */
    private var longPressEffectAnimator: ValueAnimator? = null
    var haveLongPressPropertiesBeenReset = true
        private set

    private var paddingForLaunch = Rect()
    private var initialLongPressProperties: QSLongPressProperties? = null
    private var finalLongPressProperties: QSLongPressProperties? = null
    private val colorEvaluator = ArgbEvaluator.getInstance()
    val isLongPressEffectInitialized: Boolean
        get() = longPressEffect?.hasInitialized == true

    val areLongPressEffectPropertiesSet: Boolean
        get() = initialLongPressProperties != null && finalLongPressProperties != null

    init {
        updateLabelColorsForTheme()
        val typedValue = TypedValue()
        if (!getContext().theme.resolveAttribute(R.attr.isQsTheme, typedValue, true)) {
            throw IllegalStateException(
                "QSViewImpl must be inflated with a theme that contains " +
                    "Theme.SystemUI.QuickSettings"
            )
        }
        setId(generateViewId())
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        clipChildren = false
        clipToPadding = false

        val iconContainerSize = resources.getDimensionPixelSize(R.dimen.qs_quick_tile_size)
        radiusActive = iconContainerSize / 2f
        radiusInactive = iconContainerSize / 4f
        iconContainer =
            object : LaunchableLinearLayout(context) {
                override fun onActivityLaunchAnimationEnd() {
                    this@QSTileViewImpl.onActivityLaunchAnimationEnd()
                }
            }.apply {
                layoutParams = LayoutParams(iconContainerSize, iconContainerSize)
                clipChildren = false
                clipToPadding = false
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                background = createTileBackground()
            }
        setColor(getBackgroundColorForState(QSTile.State.DEFAULT_STATE))

        val padding = resources.getDimensionPixelSize(R.dimen.a11_qs_tile_padding)
        val iconSize = resources.getDimensionPixelSize(R.dimen.a11_qs_icon_size)
        iconContainer.setPaddingRelative(padding, padding, padding, padding)
        iconContainer.addView(icon, LayoutParams(iconSize, iconSize))
        changeCornerRadius(getCornerRadiusForState(QSTile.State.DEFAULT_STATE))
        addView(iconContainer)

        createAndAddLabels()
        createAndAddSideView()
        createA11DndButton()
        moveTileContentIntoContainer()
        applyA11Geometry()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateResources()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        Trace.traceBegin(Trace.TRACE_TAG_APP, "QSTileViewImpl#onMeasure")
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        Trace.endSection()
    }

    override fun resetOverride() {
        heightOverride = HeightOverrideable.NO_OVERRIDE
        updateHeight()
    }

    fun setQsLogger(qsLogger: QSLogger) {
        mQsLogger = qsLogger
    }

    fun updateResources() {
        updateBackgroundColorsForTheme()
        updateLabelColorsForTheme()
        FontSizeUtils.updateFontSize(label, R.dimen.qs_tile_text_size)
        FontSizeUtils.updateFontSize(secondaryLabel, R.dimen.qs_tile_text_size)

        val iconSize = context.resources.getDimensionPixelSize(R.dimen.a11_qs_icon_size)
        icon.layoutParams.apply {
            height = iconSize
            width = iconSize
        }

        val padding = resources.getDimensionPixelSize(R.dimen.a11_qs_tile_padding)
        iconContainer.setPaddingRelative(padding, padding, padding, padding)

        val labelMargin = resources.getDimensionPixelSize(R.dimen.qs_label_container_margin)
        (labelContainer.layoutParams as MarginLayoutParams).apply {
            marginStart = labelMargin
            topMargin = padding / 2
        }

        (sideView.layoutParams as MarginLayoutParams).apply { marginStart = labelMargin }
        val sideIconSize =
            if (isA11Pad()) {
                context.resources.getDimensionPixelSize(R.dimen.a11_qs_side_icon_size)
            } else {
                iconSize
            }
        (chevronView.layoutParams as MarginLayoutParams).apply {
            height = sideIconSize
            width = sideIconSize
        }
        updateA11DndButtonAppearance()

        val endMargin = resources.getDimensionPixelSize(R.dimen.qs_drawable_end_margin)
        (customDrawableView.layoutParams as MarginLayoutParams).apply {
            height = sideIconSize
            marginEnd = endMargin
        }

        iconContainer.background = createTileBackground()
        setColor(getBackgroundColorForState(lastState))
        setOverlayColor(backgroundOverlayColor)
        changeCornerRadius(getCornerRadiusForState(lastState))
        setLabelColor(getLabelColorForState(lastState, lastDisabledByPolicy))
        setSecondaryLabelColor(
            getSecondaryLabelColorForState(lastState, lastDisabledByPolicy)
        )
        lastIconTint = getA11IconColorForState(lastState, lastDisabledByPolicy)
        if (icon.mIcon is ImageView) {
            icon.setTintImmediately(icon.mIcon as ImageView, lastIconTint)
        }
        applyA11Geometry()
    }

    private fun updateBackgroundColorsForTheme() {
        val night =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        colorActive =
            if (night) {
                Utils.getColorAttrDefaultColor(context, R.attr.shadeActive)
            } else {
                context.getColor(R.color.a11_qs_active_background)
            }
        colorInactive =
            if (night) {
                Utils.getColorAttrDefaultColor(context, R.attr.shadeInactive)
            } else {
                context.getColor(R.color.a11_qs_inactive_background)
            }
        colorUnavailable = Utils.applyAlpha(UNAVAILABLE_ALPHA, colorInactive)
    }

    private fun updateLabelColorsForTheme() {
        val night =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        colorLabelActive =
            if (night) {
                Utils.getColorAttrDefaultColor(context, R.attr.onShadeActive)
            } else {
                context.getColor(R.color.a11_qs_active_foreground)
            }
        colorLabelInactive =
            if (night) {
                Utils.getColorAttrDefaultColor(context, R.attr.onShadeInactive)
            } else {
                Color.BLACK
            }
        colorSecondaryLabelActive =
            if (night) {
                Utils.getColorAttrDefaultColor(context, R.attr.onShadeActiveVariant)
            } else {
                Utils.applyAlpha(0.82f, Color.WHITE)
            }
        colorSecondaryLabelInactive =
            if (night) {
                Utils.getColorAttrDefaultColor(context, R.attr.onShadeInactiveVariant)
            } else {
                Utils.applyAlpha(0.72f, Color.BLACK)
            }
    }

    fun setA11ColumnSpan(columnSpan: Int) {
        val sanitized = if (columnSpan == 2) 2 else 1
        if (a11ColumnSpan == sanitized) return
        a11ColumnSpan = sanitized
        applyA11Geometry()
        requestLayout()
    }

    fun setA11RowSpan(rowSpan: Int) {
        val sanitized = if (rowSpan == 2) 2 else 1
        if (a11RowSpan == sanitized) return
        a11RowSpan = sanitized
        applyA11Geometry()
        requestLayout()
    }

    private fun moveTileContentIntoContainer() {
        removeView(labelContainer)
        removeView(sideView)
        iconContainer.addView(labelContainer)
        iconContainer.addView(sideView)
        iconContainer.addView(a11DndTrack)
    }

    private fun applyA11Geometry() {
        if (!::iconContainer.isInitialized || !::labelContainer.isInitialized) return
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val tileSize = resources.getDimensionPixelSize(R.dimen.a11_qs_tile_height)
        val a11Pad = isA11Pad()
        iconContainer.layoutParams =
            LayoutParams(
                if (a11Pad || a11ColumnSpan == 2) LayoutParams.MATCH_PARENT else tileSize,
                if (a11Pad || a11RowSpan == 2) LayoutParams.MATCH_PARENT else tileSize,
            )
        iconContainer.orientation =
            if (a11ColumnSpan == 2) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        iconContainer.gravity = Gravity.CENTER
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.isSelected = false
        secondaryLabel.maxLines = 1
        secondaryLabel.ellipsize = TextUtils.TruncateAt.END
        secondaryLabel.isSelected = false
        val radius =
            if (a11Pad) {
                resources.getDimensionPixelSize(R.dimen.qs_corner_radius).toFloat()
            } else {
                resources.getDimensionPixelSize(R.dimen.a11_qs_tile_height) / 2f
            }
        radiusActive = radius
        radiusInactive = radius
        val dnd = a11ColumnSpan == 2 && a11TileSpec == "dnd"
        val internet = a11ColumnSpan == 2 && a11TileSpec == "internet"
        val wide = a11ColumnSpan == 2 && !dnd
        iconContainer.isBaselineAligned = !wide
        icon.visibility = if (dnd) GONE else VISIBLE
        labelContainer.visibility = if (wide) VISIBLE else GONE
        sideView.visibility = if (a11ColumnSpan == 2 && !dnd && !internet) VISIBLE else GONE
        a11DndTrack.visibility = if (dnd) VISIBLE else GONE
        val labelParams = labelContainer.layoutParams as LayoutParams
        labelParams.width = if (a11Pad) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
        labelParams.height = LayoutParams.WRAP_CONTENT
        labelParams.weight = 0f
        labelParams.marginStart =
            if (wide) resources.getDimensionPixelSize(R.dimen.a11_qs_tile_gap)
            else resources.getDimensionPixelSize(R.dimen.qs_label_container_margin)
        labelParams.marginEnd = 0
        labelParams.topMargin = 0
        labelParams.gravity = Gravity.CENTER_VERTICAL
        labelContainer.layoutParams = labelParams
        labelContainer.gravity = if (wide) Gravity.CENTER else Gravity.NO_GRAVITY
        labelContainer.setPaddingRelative(0, 0, 0, 0)
        label.gravity = Gravity.CENTER
        secondaryLabel.gravity = Gravity.CENTER
        (sideView.layoutParams as LayoutParams).gravity = Gravity.CENTER_VERTICAL
        labelContainer.translationX = 0f
        changeCornerRadius(radius)
    }

    private fun isA11Pad(): Boolean =
        !com.android.systemui.qs.flags.QSComposeFragment.isEnabled &&
            resources.configuration.smallestScreenWidthDp >= 720

    private fun createA11DndButton() {
        val size = resources.getDimensionPixelSize(R.dimen.a11_qs_dnd_button_size)
        val dotSize = resources.getDimensionPixelSize(R.dimen.a11_qs_dnd_dot_size)
        val modes =
            intArrayOf(
                AudioManager.RINGER_MODE_VIBRATE,
                AudioManager.RINGER_MODE_NORMAL,
                AudioManager.RINGER_MODE_SILENT,
            )
        val hitRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
            }
        val dots = ArrayList<View>(3)
        modes.forEach { mode ->
            val zone =
                FrameLayout(context).apply {
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                    setOnClickListener { setA11RingerMode(mode) }
                }
            val dot =
                View(context).apply {
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(colorActive)
                        }
                }
            zone.addView(
                dot,
                FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER),
            )
            dots.add(dot)
            hitRow.addView(zone)
        }
        a11DndDots = dots.toTypedArray()
        a11DndButton =
            ImageView(context).apply {
                setImageResource(R.drawable.ic_notification_bell)
                setPadding(size / 4, size / 4, size / 4, size / 4)
                isClickable = false
                layoutParams =
                    FrameLayout.LayoutParams(size, size, Gravity.CENTER_VERTICAL)
            }
        a11DndTrack =
            FrameLayout(context).apply {
                visibility = GONE
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                addView(hitRow)
                addView(a11DndButton)
                addOnAttachStateChangeListener(
                    object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            if (!a11RingerReceiverRegistered) {
                                context.registerReceiver(
                                    a11RingerReceiver,
                                    IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
                                    Context.RECEIVER_NOT_EXPORTED,
                                )
                                a11RingerReceiverRegistered = true
                            }
                            updateA11DndButtonAppearance(animate = false)
                        }

                        override fun onViewDetachedFromWindow(v: View) {
                            if (a11RingerReceiverRegistered) {
                                context.unregisterReceiver(a11RingerReceiver)
                                a11RingerReceiverRegistered = false
                            }
                        }
                    }
                )
        }
        updateA11DndButtonAppearance(animate = false)
    }

    private fun setA11RingerMode(mode: Int) {
        context.getSystemService(AudioManager::class.java)?.ringerModeInternal = mode
        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        updateA11DndButtonAppearance(animate = true)
        announceForAccessibility(
            when (mode) {
                AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                AudioManager.RINGER_MODE_SILENT -> "Silent"
                else -> "Ring"
            }
        )
    }

    private fun updateA11DndButtonAppearance(animate: Boolean = false) {
        if (!::a11DndButton.isInitialized) return
        a11DndButton.background =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorActive)
            }
        a11DndButton.imageTintList = ColorStateList.valueOf(colorLabelActive)
        val dotColor =
            Color.argb(
                96,
                Color.red(colorActive),
                Color.green(colorActive),
                Color.blue(colorActive),
            )
        val mode =
            context.getSystemService(AudioManager::class.java)?.ringerModeInternal
                ?: AudioManager.RINGER_MODE_NORMAL
        fun updateDot(dot: View) {
            dot.background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dotColor)
                }
        }
        a11DndDots.forEach(::updateDot)
        val index =
            when (mode) {
                AudioManager.RINGER_MODE_VIBRATE -> 0
                AudioManager.RINGER_MODE_SILENT -> 2
                else -> 1
            }
        a11DndButton.setImageResource(
            when (mode) {
                AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_volume_ringer_vibrate
                AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_volume_ringer_mute
                else -> R.drawable.ic_notification_bell
            }
        )
        a11DndButton.contentDescription =
            when (mode) {
                AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                AudioManager.RINGER_MODE_SILENT -> "Silent"
                else -> "Ring"
            }
        a11DndTrack.post {
            if (a11DndTrack.width == 0) return@post
            val targetX =
                a11DndTrack.width * (index + 0.5f) / 3f - a11DndButton.width / 2f
            a11DndButton.animate().cancel()
            if (animate && a11DndButton.isLaidOut) {
                a11DndButton
                    .animate()
                    .x(targetX)
                    .setDuration(220L)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .start()
            } else {
                a11DndButton.x = targetX
            }
        }
    }

    private fun createAndAddLabels() {
        labelContainer =
            LayoutInflater.from(context).inflate(R.layout.qs_tile_label_vertical, this, false)
                as IgnorableChildLinearLayout
        label = labelContainer.requireViewById(R.id.tile_label)
        secondaryLabel = labelContainer.requireViewById(R.id.app_label)
        if (collapsed) {
            labelContainer.ignoreLastView = true
            // Ideally, it'd be great if the parent could set this up when measuring just this child
            // instead of the View class having to support this. However, due to the mysteries of
            // LinearLayout's double measure pass, we cannot overwrite `measureChild` or any of its
            // sibling methods to have special behavior for labelContainer.
            labelContainer.forceUnspecifiedMeasure = true
            secondaryLabel.alpha = 0f
        }
        setLabelColor(getLabelColorForState(QSTile.State.DEFAULT_STATE))
        setSecondaryLabelColor(getSecondaryLabelColorForState(QSTile.State.DEFAULT_STATE))
        addView(labelContainer)
    }

    private fun createAndAddSideView() {
        sideView =
            LayoutInflater.from(context).inflate(R.layout.qs_tile_side_icon_a11, this, false)
                as ViewGroup
        customDrawableView = sideView.requireViewById(R.id.customDrawable)
        chevronView = sideView.requireViewById(R.id.chevron)
        setChevronColor(getChevronColorForState(QSTile.State.DEFAULT_STATE))
        addView(sideView)
    }

    private fun createTileBackground(): Drawable {
        qsTileBackground =
            if (Flags.qsTileFocusState()) {
                mContext.getDrawable(R.drawable.qs_tile_background_flagged_no_mask) as RippleDrawable
            } else {
                mContext.getDrawable(R.drawable.qs_tile_background_no_mask) as RippleDrawable
            }
        qsTileFocusBackground = mContext.getDrawable(R.drawable.qs_tile_focused_background)!!
        backgroundDrawable =
            qsTileBackground.findDrawableByLayerId(R.id.background) as LayerDrawable
        backgroundBaseDrawable =
            backgroundDrawable.findDrawableByLayerId(R.id.qs_tile_background_base)
        backgroundOverlayDrawable =
            backgroundDrawable.findDrawableByLayerId(R.id.qs_tile_background_overlay)
        backgroundOverlayDrawable.mutate().setTintMode(PorterDuff.Mode.SRC)
        return qsTileBackground
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        updateHeight()
        maybeUpdateLongPressEffectWidth(iconContainer.measuredWidth.toFloat())
        refreshA11LongPressGeometryAfterLayout()
    }

    /**
     * Secondary ViewPager pages receive tile state before they are attached and measured. Their
     * long-press properties are consequently first initialized with a 0x0 container. Refresh the
     * complete geometry after layout, including the radius (not only width/height), so an
     * off-screen A11 tile cannot animate from a square.
     */
    private fun refreshA11LongPressGeometryAfterLayout() {
        if (
            com.android.systemui.qs.flags.QSComposeFragment.isEnabled ||
                !isLongClickable ||
                longPressEffect == null ||
                !hasLongClickEffect
        ) {
            return
        }
        val laidOutWidth = iconContainer.measuredWidth.toFloat()
        val laidOutHeight = iconContainer.measuredHeight.toFloat()
        if (laidOutWidth <= 0f || laidOutHeight <= 0f) return

        val initial = initialLongPressProperties ?: return
        val final = finalLongPressProperties ?: return
        initial.width = laidOutWidth
        initial.height = laidOutHeight
        initial.cornerRadius = getA11LongPressCornerRadius(laidOutWidth, laidOutHeight)
        final.width = LONG_PRESS_EFFECT_WIDTH_SCALE * laidOutWidth
        final.height = LONG_PRESS_EFFECT_HEIGHT_SCALE * laidOutHeight
        final.cornerRadius = getA11LongPressCornerRadius(final.width, final.height)
        prepareForLaunch()
        if (longPressEffect?.state == QSLongPressEffect.State.IDLE) {
            changeCornerRadius(initial.cornerRadius)
        }
    }

    private fun maybeUpdateLongPressEffectWidth(width: Float) {
        if (!isLongClickable || longPressEffect == null || !hasLongClickEffect) return

        initialLongPressProperties?.width = width
        finalLongPressProperties?.width = LONG_PRESS_EFFECT_WIDTH_SCALE * width

        val deltaW = (LONG_PRESS_EFFECT_WIDTH_SCALE - 1f) * width
        paddingForLaunch.left = -deltaW.toInt() / 2
        paddingForLaunch.right = deltaW.toInt() / 2
    }

    private fun maybeUpdateLongPressEffectHeight(height: Float) {
        if (!isLongClickable || longPressEffect == null || !hasLongClickEffect) return

        initialLongPressProperties?.height = height
        finalLongPressProperties?.height = LONG_PRESS_EFFECT_HEIGHT_SCALE * height

        val deltaH = (LONG_PRESS_EFFECT_HEIGHT_SCALE - 1f) * height
        paddingForLaunch.top = -deltaH.toInt() / 2
        paddingForLaunch.bottom = deltaH.toInt() / 2
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (Flags.qsTileFocusState()) {
            if (gainFocus) {
                qsTileFocusBackground.setBounds(0, 0, iconContainer.width, iconContainer.height)
                iconContainer.overlay.add(qsTileFocusBackground)
            } else {
                iconContainer.overlay.clear()
            }
        }
    }

    private fun updateHeight() {
        val actualHeight =
            if (heightOverride != HeightOverrideable.NO_OVERRIDE) {
                heightOverride
            } else {
                measuredHeight
            }
        // Limit how much we affect the height, so we don't have rounding artifacts when the tile
        // is too short.
        val constrainedSquishiness = constrainSquishiness(squishinessFraction)
        bottom = top + (actualHeight * constrainedSquishiness).toInt()
        scrollY = (actualHeight - height) / 2
        maybeUpdateLongPressEffectHeight(iconContainer.measuredHeight.toFloat())
    }

    override fun updateAccessibilityOrder(previousView: View?): View {
        accessibilityTraversalAfter = previousView?.id ?: ID_NULL
        return this
    }

    override fun getIcon(): QSIconView {
        return icon
    }

    override fun getIconWithBackground(): View {
        return iconContainer
    }

    override fun init(tile: QSTile) {
        if (longPressEffect != null) {
            isHapticFeedbackEnabled = false
            longPressEffect.qsTile = tile
            longPressEffect.createExpandableFromView(this)
            initLongPressEffectCallback()
            init(
                { _: View -> longPressEffect.onTileClick() },
                { _: View ->
                    longPressEffect.onTileLongClick()
                    true
                }, // Haptics and long-clicks are handled by [QSLongPressEffect]
            )
        } else {
            val expandable = Expandable.fromView(this)
            init(
                { _: View? -> tile.click(expandable) },
                { _: View? ->
                    tile.longClick(expandable)
                    true
                },
            )
        }
    }

    private fun initLongPressEffectCallback() {
        longPressEffect?.callback =
            object : QSLongPressEffect.Callback {

                override fun onResetProperties() {
                    resetLongPressEffectProperties()
                }

                override fun onEffectFinishedReversing() {
                    // The long-press effect properties finished at the same starting point.
                    // This is the same as if the properties were reset
                    haveLongPressPropertiesBeenReset = true
                }

                override fun onStartAnimator() {
                    if (longPressEffectAnimator?.isRunning != true) {
                        longPressEffectAnimator =
                            ValueAnimator.ofFloat(0f, 1f).apply {
                                this.duration = longPressEffect?.effectDuration?.toLong() ?: 0L
                                interpolator = AccelerateDecelerateInterpolator()

                                doOnStart { longPressEffect?.handleAnimationStart() }
                                addUpdateListener {
                                    val value = animatedValue as Float
                                    if (value == 0f) {
                                        bringToFront()
                                    } else {
                                        updateLongPressEffectProperties(value)
                                    }
                                }
                                doOnEnd { longPressEffect?.handleAnimationComplete() }
                                doOnCancel { longPressEffect?.handleAnimationCancel() }
                                start()
                            }
                    }
                }

                override fun onReverseAnimator(playHaptics: Boolean) {
                    longPressEffectAnimator?.let {
                        val pausedProgress = it.animatedFraction
                        if (playHaptics) longPressEffect?.playReverseHaptics(pausedProgress)
                        it.reverse()
                    }
                }

                override fun onCancelAnimator() {
                    resetLongPressEffectProperties()
                    longPressEffectAnimator?.cancel()
                }
            }
    }

    private fun init(click: OnClickListener?, longClick: OnLongClickListener?) {
        setOnClickListener(click)
        onLongClickListener = longClick
    }

    override fun onStateChanged(state: QSTile.State) {
        // We cannot use the handler here because sometimes, the views are not attached (if they
        // are in a page that the ViewPager hasn't attached). Instead, we use a runnable where
        // all its instances are `equal` to each other, so they can be used to remove them from the
        // queue.
        // This means that at any given time there's at most one enqueued runnable to change state.
        // However, as we only ever care about the last state posted, this is fine.
        val runnable = StateChangeRunnable(state.copy())
        removeCallbacks(runnable)
        post(runnable)
    }

    override fun getDetailY(): Int {
        return top + height / 2
    }

    override fun hasOverlappingRendering(): Boolean {
        // Avoid layers for this layout - we don't need them.
        return false
    }

    override fun setClickable(clickable: Boolean) {
        super.setClickable(clickable)
        if (!Flags.qsTileFocusState()) {
            iconContainer.background =
                if (clickable && showRippleEffect) {
                    qsTileBackground.also {
                        // In case that the colorBackgroundDrawable was used as the background, make
                        // sure
                        // it has the correct callback instead of null
                        backgroundDrawable.callback = it
                    }
                } else {
                    backgroundDrawable
                }
        }
    }

    override fun getLabelContainer(): View {
        return labelContainer
    }

    override fun getLabel(): View {
        return label
    }

    override fun getSecondaryLabel(): View {
        return secondaryLabel
    }

    override fun getSecondaryIcon(): View {
        return sideView
    }

    override fun setShouldBlockVisibilityChanges(block: Boolean) {
        launchableViewDelegate.setShouldBlockVisibilityChanges(block)
    }

    override fun getAnimatedView(): LaunchableView = iconContainer

    override fun setVisibility(visibility: Int) {
        launchableViewDelegate.setVisibility(visibility)
    }

    // Accessibility

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        if (!TextUtils.isEmpty(accessibilityClass)) {
            event.className = accessibilityClass
        }
        if (
            event.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION &&
                stateDescriptionDeltas != null
        ) {
            event.text.add(stateDescriptionDeltas)
            stateDescriptionDeltas = null
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // Clear selected state so it is not announce by talkback.
        info.isSelected = false
        info.text =
            if (TextUtils.isEmpty(secondaryLabel.text)) {
                "${label.text}"
            } else {
                "${label.text}, ${secondaryLabel.text}"
            }
        if (lastDisabledByPolicy) {
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id,
                    resources.getString(
                        R.string.accessibility_tile_disabled_by_policy_action_description
                    ),
                )
            )
        } else {
            if (isLongClickable) {
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.id,
                        resources.getString(R.string.accessibility_long_click_tile),
                    )
                )
            }
        }
        if (!TextUtils.isEmpty(accessibilityClass)) {
            info.className =
                if (lastDisabledByPolicy) {
                    Button::class.java.name
                } else {
                    accessibilityClass
                }
            if (Switch::class.java.name == accessibilityClass) {
                info.isChecked = tileState
                info.isCheckable = true
            }
        }
        if (position != INVALID) {
            info.collectionItemInfo =
                AccessibilityNodeInfo.CollectionItemInfo(position, 1, 0, 1, false)
        }
    }

    override fun toString(): String {
        val sb = StringBuilder(javaClass.simpleName).append('[')
        sb.append("locInScreen=(${locInScreen[0]}, ${locInScreen[1]})")
        sb.append(", iconView=$icon")
        sb.append(", tileState=$tileState")
        sb.append("]")
        return sb.toString()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // let the View run the onTouch logic for click and long-click detection
        val result = super.onTouchEvent(event)
        if (longPressEffect != null) {
            when (event?.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressEffect.handleActionDown()
                    if (isLongClickable) {
                        postDelayed({ longPressEffect.handleTimeoutComplete() }, tapTimeoutMillis)
                    }
                }
                MotionEvent.ACTION_UP -> longPressEffect.handleActionUp()
                MotionEvent.ACTION_CANCEL -> longPressEffect.handleActionCancel()
            }
        }
        return result
    }

    // HANDLE STATE CHANGES RELATED METHODS
    protected open fun handleStateChanged(state: QSTile.State) {
        a11TileSpec = state.spec
        val allowAnimations = animationsEnabled()
        isClickable = state.state != Tile.STATE_UNAVAILABLE
        isLongClickable = state.handlesLongClick
        hasLongClickEffect = (state.handlesLongClick && state.hasLongClickEffect)
        icon.setIcon(state, allowAnimations)
        contentDescription = state.contentDescription

        // State handling and description
        val stateDescription = StringBuilder()
        val arrayResId = SubtitleArrayMapping.getSubtitleId(state.spec)
        val stateText = state.getStateText(arrayResId, resources)
        state.secondaryLabel = state.getSecondaryLabel(stateText)
        if (!TextUtils.isEmpty(stateText)) {
            stateDescription.append(stateText)
        }
        if (state.disabledByPolicy && state.state != Tile.STATE_UNAVAILABLE) {
            stateDescription.append(", ")
            stateDescription.append(getUnavailableText(state.spec))
        }
        if (!TextUtils.isEmpty(state.stateDescription)) {
            stateDescription.append(", ")
            stateDescription.append(state.stateDescription)
            if (
                lastState != INVALID &&
                    state.state == lastState &&
                    state.stateDescription != lastStateDescription
            ) {
                stateDescriptionDeltas = state.stateDescription
            }
        }

        setStateDescription(stateDescription.toString())
        lastStateDescription = state.stateDescription

        accessibilityClass =
            if (state.state == Tile.STATE_UNAVAILABLE) {
                null
            } else {
                state.expandedAccessibilityClassName
            }

        if (state is AdapterState) {
            val newState = state.value
            if (tileState != newState) {
                tileState = newState
            }
        }

        // Labels
        val internetSingleLine =
            a11ColumnSpan == 2 &&
                state.spec == "internet" &&
                !TextUtils.isEmpty(state.secondaryLabel)
        val displayedLabel = if (internetSingleLine) state.secondaryLabel else state.label
        val displayedSecondaryLabel =
            if (internetSingleLine) null else state.secondaryLabel
        if (!Objects.equals(label.text, displayedLabel)) {
            label.text = displayedLabel
        }
        if (!Objects.equals(secondaryLabel.text, displayedSecondaryLabel)) {
            secondaryLabel.text = displayedSecondaryLabel
        }
        secondaryLabel.visibility =
            if (TextUtils.isEmpty(displayedSecondaryLabel)) {
                if (internetSingleLine) GONE else INVISIBLE
            } else {
                VISIBLE
            }
        applyA11Geometry()

        // Colors
        if (state.state != lastState || state.disabledByPolicy != lastDisabledByPolicy) {
            tileAnimator.cancel()
            mQsLogger?.logTileBackgroundColorUpdateIfInternetTile(
                state.spec,
                state.state,
                state.disabledByPolicy,
                getBackgroundColorForState(state.state, state.disabledByPolicy),
            )
            if (allowAnimations) {
                shapeAnimator.setFloatValues(
                    getCornerRadiusForState(lastState),
                    getCornerRadiusForState(state.state),
                )
                singleAnimator.setValues(
                    colorValuesHolder(
                        BACKGROUND_NAME,
                        backgroundColor,
                        getBackgroundColorForState(state.state, state.disabledByPolicy),
                    ),
                    colorValuesHolder(
                        LABEL_NAME,
                        label.currentTextColor,
                        getLabelColorForState(state.state, state.disabledByPolicy),
                    ),
                    colorValuesHolder(
                        SECONDARY_LABEL_NAME,
                        secondaryLabel.currentTextColor,
                        getSecondaryLabelColorForState(state.state, state.disabledByPolicy),
                    ),
                    colorValuesHolder(
                        CHEVRON_NAME,
                        chevronView.imageTintList?.defaultColor ?: 0,
                        getChevronColorForState(state.state, state.disabledByPolicy),
                    ),
                    colorValuesHolder(
                        OVERLAY_NAME,
                        backgroundOverlayColor,
                        getOverlayColorForState(state.state),
                    ),
                )
                tileAnimator.start()
            } else {
                setAllColors(
                    getBackgroundColorForState(state.state, state.disabledByPolicy),
                    getLabelColorForState(state.state, state.disabledByPolicy),
                    getSecondaryLabelColorForState(state.state, state.disabledByPolicy),
                    getChevronColorForState(state.state, state.disabledByPolicy),
                    getOverlayColorForState(state.state),
                )
                changeCornerRadius(getCornerRadiusForState(state.state))
            }
        }

        // Right side icon
        loadSideViewDrawableIfNecessary(state)

        label.isEnabled = !state.disabledByPolicy

        lastState = state.state
        lastDisabledByPolicy = state.disabledByPolicy
        lastIconTint = getA11IconColorForState(state.state, state.disabledByPolicy)
        if (icon.mIcon is ImageView) {
            icon.setTintImmediately(icon.mIcon as ImageView, lastIconTint)
        }
        val a11DndWide = a11ColumnSpan == 2 && state.spec == "dnd"
        labelContainer.visibility = if (a11ColumnSpan == 2 && !a11DndWide) VISIBLE else GONE
        sideView.visibility =
            if (a11ColumnSpan == 2 && !a11DndWide && state.spec != "internet") {
                sideView.visibility
            } else {
                GONE
            }

        // Long-press effects
        updateLongPressEffect(state.handlesLongClick)
    }

    private fun updateLongPressEffect(handlesLongClick: Boolean) {
        // The long press effect in the tile can't be updated if it is still running
        if (
            longPressEffect?.state != QSLongPressEffect.State.IDLE &&
                longPressEffect?.state != QSLongPressEffect.State.CLICKED
        )
            return

        longPressEffect.qsTile?.state?.handlesLongClick = handlesLongClick
        if (hasLongClickEffect && handlesLongClick &&
                longPressEffect.initializeEffect(longPressEffectDuration)) {
            showRippleEffect = false
            longPressEffect.qsTile?.state?.state = lastState // Store the tile's state
            longPressEffect.resetState()
            initializeLongPressProperties(
                iconContainer.measuredHeight,
                iconContainer.measuredWidth,
            )
        } else {
            // Long-press effects might have been enabled before but the new state does not
            // handle a long-press. In this case, we go back to the behaviour of a regular tile
            // and clean-up the resources
            showRippleEffect = isClickable
            initialLongPressProperties = null
            finalLongPressProperties = null
        }
    }

    private fun setAllColors(
        backgroundColor: Int,
        labelColor: Int,
        secondaryLabelColor: Int,
        chevronColor: Int,
        overlayColor: Int,
    ) {
        setColor(backgroundColor)
        setLabelColor(labelColor)
        setSecondaryLabelColor(secondaryLabelColor)
        setChevronColor(chevronColor)
        setOverlayColor(overlayColor)
    }

    private fun setColor(color: Int) {
        backgroundBaseDrawable.mutate().setTint(color)
        backgroundColor = color
    }

    private fun setLabelColor(color: Int) {
        label.setTextColor(color)
    }

    private fun setSecondaryLabelColor(color: Int) {
        secondaryLabel.setTextColor(color)
    }

    private fun setChevronColor(color: Int) {
        chevronView.imageTintList = ColorStateList.valueOf(color)
    }

    private fun setOverlayColor(overlayColor: Int) {
        backgroundOverlayDrawable.setTint(overlayColor)
        backgroundOverlayColor = overlayColor
    }

    private fun loadSideViewDrawableIfNecessary(state: QSTile.State) {
        if (state.sideViewCustomDrawable != null) {
            customDrawableView.setImageDrawable(state.sideViewCustomDrawable)
            customDrawableView.visibility = VISIBLE
            chevronView.visibility = GONE
        } else if (state !is AdapterState || state.forceExpandIcon) {
            customDrawableView.setImageDrawable(null)
            customDrawableView.visibility = GONE
            chevronView.visibility = VISIBLE
        } else {
            customDrawableView.setImageDrawable(null)
            customDrawableView.visibility = GONE
            chevronView.visibility = GONE
        }
    }

    private fun getUnavailableText(spec: String?): String {
        val arrayResId = SubtitleArrayMapping.getSubtitleId(spec)
        return resources.getStringArray(arrayResId)[Tile.STATE_UNAVAILABLE]
    }

    private fun getCornerRadiusForState(state: Int): Float = radiusActive

    /*
     * The view should not be animated if it's not on screen and no part of it is visible.
     */
    protected open fun animationsEnabled(): Boolean {
        if (!isShown) {
            return false
        }
        if (alpha != 1f) {
            return false
        }
        getLocationOnScreen(locInScreen)
        return locInScreen.get(1) >= -height
    }

    private fun getBackgroundColorForState(state: Int, disabledByPolicy: Boolean = false): Int {
        return when {
            state == Tile.STATE_UNAVAILABLE || disabledByPolicy -> colorUnavailable
            state == Tile.STATE_ACTIVE -> colorActive
            state == Tile.STATE_INACTIVE -> colorInactive
            else -> {
                Log.e(TAG, "Invalid state $state")
                0
            }
        }
    }

    private fun getLabelColorForState(state: Int, disabledByPolicy: Boolean = false): Int {
        return when {
            state == Tile.STATE_UNAVAILABLE || disabledByPolicy -> colorLabelUnavailable
            state == Tile.STATE_ACTIVE -> colorLabelActive
            state == Tile.STATE_INACTIVE -> colorLabelInactive
            else -> {
                Log.e(TAG, "Invalid state $state")
                0
            }
        }
    }

    private fun getSecondaryLabelColorForState(state: Int, disabledByPolicy: Boolean = false): Int {
        return when {
            state == Tile.STATE_UNAVAILABLE || disabledByPolicy -> colorSecondaryLabelUnavailable
            state == Tile.STATE_ACTIVE -> colorSecondaryLabelActive
            state == Tile.STATE_INACTIVE -> colorSecondaryLabelInactive
            else -> {
                Log.e(TAG, "Invalid state $state")
                0
            }
        }
    }

    private fun getChevronColorForState(state: Int, disabledByPolicy: Boolean = false): Int =
        getSecondaryLabelColorForState(state, disabledByPolicy)

    private fun getA11IconColorForState(state: Int, disabledByPolicy: Boolean = false): Int {
        val night =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        return when {
            state == Tile.STATE_UNAVAILABLE || disabledByPolicy ->
                if (night) Utils.getColorAttrDefaultColor(context, R.attr.outline)
                else Utils.applyAlpha(0.38f, Color.BLACK)
            state == Tile.STATE_INACTIVE ->
                if (night) {
                    Utils.getColorAttrDefaultColor(context, R.attr.onShadeInactiveVariant)
                } else {
                    Color.BLACK
                }
            state == Tile.STATE_ACTIVE ->
                if (night) {
                    Utils.getColorAttrDefaultColor(context, R.attr.onShadeActive)
                } else {
                    context.getColor(R.color.a11_qs_active_foreground)
                }
            else -> Color.TRANSPARENT
        }
    }

    private fun getOverlayColorForState(state: Int): Int {
        return when (state) {
            Tile.STATE_ACTIVE -> overlayColorActive
            Tile.STATE_INACTIVE -> overlayColorInactive
            else -> Color.TRANSPARENT
        }
    }

    override fun onActivityLaunchAnimationEnd() {
        longPressEffect?.resetState()
        if (longPressEffect != null && !haveLongPressPropertiesBeenReset) {
            resetLongPressEffectProperties()
        }
        // The launch animator temporarily changes the source drawable's corner radii. Its generic
        // restoration only tracks one GradientDrawable, while the A11 tile background is layered,
        // so explicitly restore the shape for the tile's current state before it is shown again.
        changeCornerRadius(getCornerRadiusForState(lastState))
        iconContainer.invalidate()
    }

    private fun prepareForLaunch() {
        val startingHeight = initialLongPressProperties?.height?.toInt() ?: 0
        val startingWidth = initialLongPressProperties?.width?.toInt() ?: 0
        val deltaH = finalLongPressProperties?.height?.minus(startingHeight)?.toInt() ?: 0
        val deltaW = finalLongPressProperties?.width?.minus(startingWidth)?.toInt() ?: 0
        paddingForLaunch.left = -deltaW / 2
        paddingForLaunch.top = -deltaH / 2
        paddingForLaunch.right = deltaW / 2
        paddingForLaunch.bottom = deltaH / 2
    }

    override fun getPaddingForLaunchAnimation(): Rect =
        if (longPressEffect?.state == QSLongPressEffect.State.LONG_CLICKED) {
            paddingForLaunch
        } else {
            EMPTY_RECT
        }

    fun updateLongPressEffectProperties(effectProgress: Float) {
        if (!isLongClickable || longPressEffect == null || !hasLongClickEffect) return

        if (haveLongPressPropertiesBeenReset) haveLongPressPropertiesBeenReset = false

        // Dimensions change
        val newHeight =
            interpolateFloat(
                    effectProgress,
                    initialLongPressProperties?.height ?: 0f,
                    finalLongPressProperties?.height ?: 0f,
                )
                .toInt()
        val newWidth =
            interpolateFloat(
                    effectProgress,
                    initialLongPressProperties?.width ?: 0f,
                    finalLongPressProperties?.width ?: 0f,
                )
                .toInt()

        val startingHeight = initialLongPressProperties?.height?.toInt() ?: 0
        val startingWidth = initialLongPressProperties?.width?.toInt() ?: 0
        val deltaH = (newHeight - startingHeight) / 2
        val deltaW = (newWidth - startingWidth) / 2

        iconContainer.background?.updateBounds(
            left = -deltaW,
            top = -deltaH,
            right = newWidth - deltaW,
            bottom = newHeight - deltaH,
        )

        val newRadius =
            interpolateFloat(
                effectProgress,
                initialLongPressProperties?.cornerRadius ?: 0f,
                finalLongPressProperties?.cornerRadius ?: 0f,
            )
        changeCornerRadius(newRadius)

        // Color change
        setAllColors(
            colorEvaluator.evaluate(
                effectProgress,
                initialLongPressProperties?.backgroundColor ?: 0,
                finalLongPressProperties?.backgroundColor ?: 0,
            ) as Int,
            colorEvaluator.evaluate(
                effectProgress,
                initialLongPressProperties?.labelColor ?: 0,
                finalLongPressProperties?.labelColor ?: 0,
            ) as Int,
            colorEvaluator.evaluate(
                effectProgress,
                initialLongPressProperties?.secondaryLabelColor ?: 0,
                finalLongPressProperties?.secondaryLabelColor ?: 0,
            ) as Int,
            colorEvaluator.evaluate(
                effectProgress,
                initialLongPressProperties?.chevronColor ?: 0,
                finalLongPressProperties?.chevronColor ?: 0,
            ) as Int,
            colorEvaluator.evaluate(
                effectProgress,
                initialLongPressProperties?.overlayColor ?: 0,
                finalLongPressProperties?.overlayColor ?: 0,
            ) as Int,
        )
        icon.setTint(
            icon.mIcon as ImageView,
            colorEvaluator.evaluate(
                effectProgress,
                initialLongPressProperties?.iconColor ?: 0,
                finalLongPressProperties?.iconColor ?: 0,
            ) as Int,
        )
    }

    private fun interpolateFloat(fraction: Float, start: Float, end: Float): Float =
        start + fraction * (end - start)

    fun resetLongPressEffectProperties() {
        iconContainer.background?.updateBounds(
            left = 0,
            top = 0,
            right = initialLongPressProperties?.width?.toInt() ?: iconContainer.measuredWidth,
            bottom = initialLongPressProperties?.height?.toInt() ?: iconContainer.measuredHeight,
        )
        changeCornerRadius(getCornerRadiusForState(lastState))
        setAllColors(
            getBackgroundColorForState(lastState, lastDisabledByPolicy),
            getLabelColorForState(lastState, lastDisabledByPolicy),
            getSecondaryLabelColorForState(lastState, lastDisabledByPolicy),
            getChevronColorForState(lastState, lastDisabledByPolicy),
            getOverlayColorForState(lastState),
        )
        icon.setTint(icon.mIcon as ImageView, lastIconTint)
        haveLongPressPropertiesBeenReset = true
    }

    @VisibleForTesting
    fun initializeLongPressProperties(startingHeight: Int, startingWidth: Int) {
        val a11 = !com.android.systemui.qs.flags.QSComposeFragment.isEnabled
        val startingRadius =
            if (a11) getA11LongPressCornerRadius(startingWidth.toFloat(), startingHeight.toFloat())
            else resources.getDimensionPixelSize(R.dimen.qs_corner_radius).toFloat()
        val finalWidth = LONG_PRESS_EFFECT_WIDTH_SCALE * startingWidth
        val finalHeight = LONG_PRESS_EFFECT_HEIGHT_SCALE * startingHeight
        val finalRadius =
            if (a11) getA11LongPressCornerRadius(finalWidth, finalHeight)
            else resources.getDimensionPixelSize(R.dimen.qs_corner_radius).toFloat() - 20
        initialLongPressProperties =
            QSLongPressProperties(
                height = startingHeight.toFloat(),
                width = startingWidth.toFloat(),
                startingRadius,
                getBackgroundColorForState(lastState),
                getLabelColorForState(lastState),
                getSecondaryLabelColorForState(lastState),
                getChevronColorForState(lastState),
                getOverlayColorForState(lastState),
                lastIconTint,
            )

        finalLongPressProperties =
            QSLongPressProperties(
                height = finalHeight,
                width = finalWidth,
                finalRadius,
                getBackgroundColorForState(Tile.STATE_ACTIVE),
                getLabelColorForState(Tile.STATE_ACTIVE),
                getSecondaryLabelColorForState(Tile.STATE_ACTIVE),
                getChevronColorForState(Tile.STATE_ACTIVE),
                getOverlayColorForState(Tile.STATE_ACTIVE),
                Utils.getColorAttrDefaultColor(context, R.attr.onShadeActive),
            )
        prepareForLaunch()
    }

    private fun changeCornerRadius(radius: Float) {
        updateDrawableCornerRadius(iconContainer.background, radius)
        iconContainer.invalidateOutline()
        iconContainer.invalidate()
    }

    private fun getA11LongPressCornerRadius(width: Float, height: Float): Float =
        if (isA11Pad()) {
            resources.getDimensionPixelSize(R.dimen.qs_corner_radius).toFloat()
        } else {
            minOf(width, height) / 2f
        }

    private fun updateDrawableCornerRadius(drawable: Drawable?, radius: Float) {
        when (drawable) {
            null -> return
            is GradientDrawable -> {
                drawable.cornerRadius = radius
                drawable.invalidateSelf()
            }
            is LayerDrawable -> {
                for (i in 0 until drawable.numberOfLayers) {
                    updateDrawableCornerRadius(drawable.getDrawable(i), radius)
                }
            }
            is DrawableContainer -> {
                val currentDrawable = drawable.current
                if (currentDrawable !== drawable) {
                    updateDrawableCornerRadius(currentDrawable, radius)
                }
            }
        }
    }

    @VisibleForTesting
    internal fun getCurrentColors(): List<Int> =
        listOf(
            backgroundColor,
            label.currentTextColor,
            secondaryLabel.currentTextColor,
            chevronView.imageTintList?.defaultColor ?: 0,
        )

    inner class StateChangeRunnable(private val state: QSTile.State) : Runnable {
        override fun run() {
            var traceTag = "QSTileViewImpl#handleStateChanged"
            if (!state.spec.isNullOrEmpty()) {
                traceTag += ":"
                traceTag += state.spec
            }
            traceSection(traceTag.take(Trace.MAX_SECTION_NAME_LEN)) { handleStateChanged(state) }
        }

        // We want all instances of this runnable to be equal to each other, so they can be used to
        // remove previous instances from the Handler/RunQueue of this view
        override fun equals(other: Any?): Boolean {
            return other is StateChangeRunnable
        }

        // This makes sure that all instances have the same hashcode (because they are `equal`)
        override fun hashCode(): Int {
            return StateChangeRunnable::class.hashCode()
        }
    }
}

fun constrainSquishiness(squish: Float): Float {
    return 0.1f + squish * 0.9f
}

private fun colorValuesHolder(name: String, vararg values: Int): PropertyValuesHolder {
    return PropertyValuesHolder.ofInt(name, *values).apply {
        setEvaluator(ArgbEvaluator.getInstance())
    }
}
