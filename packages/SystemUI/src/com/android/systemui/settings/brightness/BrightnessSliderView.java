/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.settings.brightness;

import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.AttributeSet;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.SeekBar.OnSeekBarChangeListener;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.Gefingerpoken;
import com.android.systemui.res.R;
import com.android.settingslib.Utils;

import java.util.Collections;

/**
 * {@code FrameLayout} used to show and manipulate a {@link ToggleSeekBar}.
 *
 */
public class BrightnessSliderView extends FrameLayout {

    @NonNull
    protected ToggleSeekBar mSlider;
    private DispatchTouchEventListener mListener;
    private Gefingerpoken mOnInterceptListener;
    @Nullable
    protected Drawable mProgressDrawable;
    protected float mScale = 1f;
    private final Rect mSystemGestureExclusionRect = new Rect();
    private final Rect mSliderBounds = new Rect();
    private ImageButton mAutoBrightness;
    private final ContentObserver mBrightnessObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            updateAutoBrightnessButton();
        }
    };

    public BrightnessSliderView(Context context) {
        this(context, null);
    }

    public BrightnessSliderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    // Inflated from quick_settings_brightness_dialog
    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        setLayerType(LAYER_TYPE_HARDWARE, null);

        initBrightnessViewComponents();
    }

    protected void initBrightnessViewComponents() {
        mSlider = requireViewById(R.id.slider);
        mAutoBrightness = requireViewById(R.id.auto_brightness);
        mSlider.setAccessibilityLabel(getContentDescription().toString());
        // The expanded QS panel can detach/re-attach this view while finishing its expansion
        // animation. Do not post the toggle to the view: a queued callback can be dropped when
        // that happens, leaving the button looking as if it ignored the tap.
        mAutoBrightness.setOnClickListener(v -> toggleAutoBrightness());
        setBoundaryOffset();

        // Finds the progress drawable. Assumes brightness_progress_drawable.xml
        try {
            LayerDrawable progress = (LayerDrawable) mSlider.getProgressDrawable();
            DrawableWrapper progressSlider = (DrawableWrapper) progress
                    .findDrawableByLayerId(android.R.id.progress);
            LayerDrawable actualProgressSlider = (LayerDrawable) progressSlider.getDrawable();
            mProgressDrawable = actualProgressSlider.findDrawableByLayerId(R.id.slider_foreground);
        } catch (Exception e) {
            // Nothing to do, mProgressDrawable will be null.
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getContext().getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                false, mBrightnessObserver, UserHandle.USER_ALL);
        updateAutoBrightnessButton();
    }

    @Override
    protected void onDetachedFromWindow() {
        getContext().getContentResolver().unregisterContentObserver(mBrightnessObserver);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateAutoBrightnessButton();
    }

    private void toggleAutoBrightness() {
        boolean enabled = isAutoBrightnessEnabled();
        Settings.System.putIntForUser(getContext().getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                enabled ? Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        : Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                UserHandle.USER_CURRENT);
        updateAutoBrightnessButton();
    }

    private boolean isAutoBrightnessEnabled() {
        return Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                UserHandle.USER_CURRENT) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    }

    private void updateAutoBrightnessButton() {
        if (mAutoBrightness == null) return;
        boolean available = getResources().getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available);
        boolean enabled = isAutoBrightnessEnabled();
        mAutoBrightness.setEnabled(true);
        mAutoBrightness.setClickable(true);
        mAutoBrightness.setVisibility(available ? VISIBLE : GONE);
        mAutoBrightness.setImageResource(enabled
                ? R.drawable.ic_qs_brightness_auto_on
                : R.drawable.ic_brightness_medium);
        int backgroundColor = enabled
                ? Utils.getColorAttrDefaultColor(getContext(), R.attr.shadeActive)
                : isNightMode()
                        ? Utils.getColorAttrDefaultColor(getContext(), R.attr.shadeInactive)
                        : getContext().getColor(R.color.a11_qs_inactive_background);
        int iconColor = enabled
                ? Utils.getColorAttrDefaultColor(getContext(), R.attr.onShadeActive)
                : getResources().getColor(
                        com.android.internal.R.color.materialColorOnSurface, getContext().getTheme());
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(backgroundColor);
        mAutoBrightness.setBackground(background);
        mAutoBrightness.setColorFilter(iconColor);
        mAutoBrightness.setActivated(enabled);
    }

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    protected void setBoundaryOffset() {
         //  BrightnessSliderView uses hardware layer; if the background of its children exceed its
         //  boundary, it'll be cropped. We need to expand its boundary so that the background of
         //  ToggleSeekBar (i.e. the focus state) can be correctly rendered.
        int offset = getResources().getDimensionPixelSize(R.dimen.rounded_slider_boundary_offset);
        MarginLayoutParams lp = (MarginLayoutParams) getLayoutParams();
        lp.setMargins(-offset, -offset, -offset, -offset);
        setLayoutParams(lp);
        setPadding(offset,  offset, offset,  offset);
    }

    /**
     * Attaches a listener to relay touch events.
     * @param listener use {@code null} to remove listener
     */
    public void setOnDispatchTouchEventListener(
            DispatchTouchEventListener listener) {
        mListener = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Brightness mirroring is only intended for the seek bar. Forwarding touches from the
        // adjacent auto-brightness button also clicks the mirror's copy of that button, toggling
        // the setting twice while QS is fully expanded.
        mSlider.getHitRect(mSliderBounds);
        if (mListener != null && mSliderBounds.contains((int) ev.getX(), (int) ev.getY())) {
            mListener.onDispatchTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // We prevent disallowing on this view, but bubble it up to our parents.
        // We need interception to handle falsing.
        if (mParent != null) {
            mParent.requestDisallowInterceptTouchEvent(disallowIntercept);
        }
    }

    /**
     * Attaches a listener to the {@link ToggleSeekBar} in the view so changes can be observed
     * @param seekListener use {@code null} to remove listener
     */
    public void setOnSeekBarChangeListener(OnSeekBarChangeListener seekListener) {
        mSlider.setOnSeekBarChangeListener(seekListener);
    }

    /**
     * Enforces admin rules for toggling auto-brightness and changing value of brightness
     * @param admin
     * @see ToggleSeekBar#setEnforcedAdmin
     */
    protected void setAdminBlocker(ToggleSeekBar.AdminBlocker blocker) {
        mSlider.setAdminBlocker(blocker);
    }

    /**
     * Enables or disables the slider
     * @param enable
     */
    public void enableSlider(boolean enable) {
        mSlider.setEnabled(enable);
    }

    /**
     * @return the maximum value of the {@link ToggleSeekBar}.
     */
    public int getMax() {
        return mSlider.getMax();
    }

    /**
     * Sets the maximum value of the {@link ToggleSeekBar}.
     * @param max
     */
    public void setMax(int max) {
        mSlider.setMax(max);
    }

    /**
     * Sets the current value of the {@link ToggleSeekBar}.
     * @param value
     */
    public void setValue(int value) {
        mSlider.setProgress(value);
    }

    /**
     * @return the current value of the {@link ToggleSeekBar}
     */
    public int getValue() {
        return mSlider.getProgress();
    }

    public void setOnInterceptListener(Gefingerpoken onInterceptListener) {
        mOnInterceptListener = onInterceptListener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mOnInterceptListener != null) {
            return mOnInterceptListener.onInterceptTouchEvent(ev);
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        applySliderScale();
        int horizontalMargin =
                getResources().getDimensionPixelSize(R.dimen.notification_side_paddings);
        mSystemGestureExclusionRect.set(-horizontalMargin, 0, right - left + horizontalMargin,
                bottom - top);
        setSystemGestureExclusionRects(Collections.singletonList(mSystemGestureExclusionRect));
    }

    /**
     * Sets the scale for the progress bar (for brightness_progress_drawable.xml)
     *
     * This will only scale the thick progress bar and not the icon inside
     *
     * Used in {@link com.android.systemui.qs.QSAnimator}.
     */
    @Keep
    public void setSliderScaleY(float scale) {
        if (scale != mScale) {
            mScale = scale;
            applySliderScale();
        }
    }

    protected void applySliderScale() {
        if (mProgressDrawable != null) {
            final Rect r = mProgressDrawable.getBounds();
            int height = (int) (mProgressDrawable.getIntrinsicHeight() * mScale);
            int inset = (mProgressDrawable.getIntrinsicHeight() - height) / 2;
            mProgressDrawable.setBounds(r.left, inset, r.right, inset + height);
        }
    }

    @Keep
    public float getSliderScaleY() {
        return mScale;
    }

    /**
     * Interface to attach a listener for {@link View#dispatchTouchEvent}.
     */
    @FunctionalInterface
    public interface DispatchTouchEventListener {
        boolean onDispatchTouchEvent(MotionEvent ev);
    }
}
