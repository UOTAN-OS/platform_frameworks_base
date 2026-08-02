/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tileimpl;

import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.android.settingslib.RestrictedLockUtils;
import com.android.settingslib.Utils;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.res.R;
import com.android.systemui.settings.brightness.BrightnessController;
import com.android.systemui.settings.brightness.MirrorController;
import com.android.systemui.settings.brightness.ToggleSlider;

/** A11-only vertical control rendered as a single two-row capsule. */
public class A11VerticalSliderTileView extends QSTileView implements ToggleSlider {

    public enum Type {
        VOLUME,
        BRIGHTNESS,
    }

    private final Type mType;
    private final QSIconViewImpl mIcon;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mClipPath = new Path();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    @Nullable private final VolumeDialogController mVolumeController;
    @Nullable private final BrightnessController mBrightnessController;
    @Nullable private Listener mListener;
    private int mMax = 100;
    private int mValue = 50;
    private int mLevelMin;
    private int mPosition;
    private boolean mTracking;
    private int mLastAudibleVolume = 5;
    private final int mTouchSlop;
    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;
    private float mDownX;
    private float mDownY;
    private boolean mMoved;
    @Nullable private QSTile.State mLastState;

    private final ContentObserver mBrightnessModeObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            updateBrightnessModeIcon();
        }
    };

    private final VolumeDialogController.Callbacks mVolumeCallbacks =
            new VolumeDialogController.Callbacks() {
        @Override
        public void onStateChanged(VolumeDialogController.State state) {
            final VolumeDialogController.StreamState stream =
                    state.states.get(AudioManager.STREAM_MUSIC);
            if (stream == null) return;
            mLevelMin = stream.levelMin;
            mMax = Math.max(1, stream.levelMax - stream.levelMin);
            mValue = Math.max(0, stream.level - stream.levelMin);
            if (mValue > 0) mLastAudibleVolume = mValue;
            postInvalidateOnAnimation();
        }

        @Override public void onShowRequested(int reason, boolean keyguardLocked, int lockTaskMode) {}
        @Override public void onDismissRequested(int reason) {}
        @Override public void onLayoutDirectionChanged(int layoutDirection) {}
        @Override public void onConfigurationChanged() {}
        @Override public void onShowVibrateHint() {}
        @Override public void onShowSilentHint() {}
        @Override public void onScreenOff() {}
        @Override public void onShowSafetyWarning(int flags) {}
        @Override public void onAccessibilityModeChanged(Boolean showA11yStream) {}
        @Override public void onCaptionComponentStateChanged(Boolean enabled, Boolean fromTooltip) {}
        @Override public void onCaptionEnabledStateChanged(Boolean enabled, Boolean checkBefore) {}
        @Override public void onShowCsdWarning(int warning, int durationMs) {}
        @Override public void onVolumeChangedFromKey() {}
    };

    public A11VerticalSliderTileView(
            Context context,
            Type type,
            @Nullable BrightnessController.Factory brightnessControllerFactory,
            @Nullable VolumeDialogController volumeController) {
        super(context);
        mType = type;
        mVolumeController = volumeController;
        mBrightnessController = type == Type.BRIGHTNESS && brightnessControllerFactory != null
                ? brightnessControllerFactory.create(this)
                : null;
        setWillNotDraw(false);
        setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        setOrientation(VERTICAL);
        setPadding(0, 0, 0,
                getResources().getDimensionPixelSize(R.dimen.a11_qs_tile_padding));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mIcon = new QSIconViewImpl(context);
        final int iconSize = getResources().getDimensionPixelSize(R.dimen.a11_qs_icon_size);
        addView(mIcon, new LayoutParams(iconSize, iconSize));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mBrightnessController != null) {
            mBrightnessController.registerCallbacks();
            getContext().getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false, mBrightnessModeObserver, UserHandle.USER_ALL);
            updateBrightnessModeIcon();
        }
        if (mVolumeController != null) {
            mVolumeController.addCallback(mVolumeCallbacks, mHandler);
            mVolumeController.getState();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mBrightnessController != null) {
            mBrightnessController.unregisterCallbacks();
            getContext().getContentResolver().unregisterContentObserver(mBrightnessModeObserver);
        }
        if (mVolumeController != null) {
            mVolumeController.removeCallback(mVolumeCallbacks);
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float radius = getWidth() / 2f;
        final int inactive = isNightMode()
                ? Utils.getColorAttrDefaultColor(getContext(), R.attr.shadeInactive)
                : getContext().getColor(R.color.a11_qs_inactive_background);
        final int active = isNightMode()
                ? Utils.getColorAttrDefaultColor(getContext(), R.attr.shadeActive)
                : getContext().getColor(R.color.a11_qs_active_background);
        mPaint.setColor(inactive);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, mPaint);

        final float fraction = mMax == 0 ? 0f : Math.max(0f, Math.min(1f, mValue / (float) mMax));
        final float fillTop = getHeight() * (1f - fraction);
        updateIconTint(fillTop);
        mClipPath.reset();
        mClipPath.addRoundRect(0, 0, getWidth(), getHeight(), radius, radius,
                Path.Direction.CW);
        final int save = canvas.save();
        canvas.clipPath(mClipPath);
        mPaint.setColor(active);
        canvas.drawRect(0, fillTop, getWidth(), getHeight(), mPaint);
        canvas.restoreToCount(save);
        super.onDraw(canvas);
    }

    private void updateIconTint(float fillTop) {
        final View iconView = mIcon.getIconView();
        if (!(iconView instanceof ImageView)) return;
        final float iconCenterY = getHeight() - getPaddingBottom() - iconView.getHeight() / 2f;
        final boolean iconOnActiveFill = iconCenterY >= fillTop;
        final int color;
        if (isNightMode()) {
            color = Utils.getColorAttrDefaultColor(
                    getContext(),
                    iconOnActiveFill ? R.attr.onShadeActive : R.attr.onShadeInactiveVariant);
        } else {
            color = iconOnActiveFill
                    ? getContext().getColor(R.color.a11_qs_active_foreground)
                    : Color.BLACK;
        }
        mIcon.setTintImmediately((ImageView) iconView, color);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mActivePointerId = event.getPointerId(0);
                mDownX = event.getX();
                mDownY = event.getY();
                mMoved = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                mTracking = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                final int pointerIndex = event.findPointerIndex(mActivePointerId);
                if (pointerIndex < 0) return false;
                final float dx = event.getX(pointerIndex) - mDownX;
                final float dy = event.getY(pointerIndex) - mDownY;
                if (!mMoved && dx * dx + dy * dy > mTouchSlop * mTouchSlop) {
                    mMoved = true;
                }
                if (mMoved) updateFromTouch(event.getY(pointerIndex), false);
                return true;
            case MotionEvent.ACTION_UP:
                if (mActivePointerId == MotionEvent.INVALID_POINTER_ID) return true;
                final boolean iconTap = !mMoved
                        && isInBottomIconHitArea(mDownX, mDownY)
                        && isInBottomIconHitArea(event.getX(), event.getY());
                if (iconTap) {
                    toggleBottomAction();
                } else {
                    updateFromTouch(event.getY(), true);
                }
                mTracking = false;
                mActivePointerId = MotionEvent.INVALID_POINTER_ID;
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (mListener != null) mListener.onChanged(false, mValue, true);
                mTracking = false;
                mActivePointerId = MotionEvent.INVALID_POINTER_ID;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger cancels this gesture without changing the current value.
                if (mListener != null) mListener.onChanged(false, mValue, true);
                mTracking = false;
                mActivePointerId = MotionEvent.INVALID_POINTER_ID;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private boolean isInBottomIconHitArea(float x, float y) {
        final int minimumTarget = Math.max(
                getResources().getDimensionPixelSize(R.dimen.a11_qs_icon_touch_target),
                mIcon.getWidth());
        return x >= (getWidth() - minimumTarget) / 2f
                && x <= (getWidth() + minimumTarget) / 2f
                && y >= getHeight() - minimumTarget;
    }

    private void updateFromTouch(MotionEvent event, boolean stopTracking) {
        updateFromTouch(event.getY(), stopTracking);
    }

    private void updateFromTouch(float y, boolean stopTracking) {
        final int oldValue = mValue;
        final float fraction = 1f - Math.max(0f, Math.min(1f, y / getHeight()));
        mValue = Math.round(fraction * mMax);
        if (mType == Type.BRIGHTNESS) {
            if (mListener != null) mListener.onChanged(mTracking, mValue, stopTracking);
        } else if (mVolumeController != null && oldValue != mValue) {
            mVolumeController.setStreamVolume(
                    AudioManager.STREAM_MUSIC, mLevelMin + mValue, false);
        }
        postInvalidateOnAnimation();
    }

    private void toggleBottomAction() {
        if (mType == Type.BRIGHTNESS) {
            final boolean automatic = Settings.System.getIntForUser(
                    getContext().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    android.os.UserHandle.USER_CURRENT)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
            Settings.System.putIntForUser(
                    getContext().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    automatic
                            ? Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                            : Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                    android.os.UserHandle.USER_CURRENT);
            updateBrightnessModeIcon();
        } else if (mVolumeController != null) {
            final int target = mValue == 0 ? Math.max(1, mLastAudibleVolume) : 0;
            mVolumeController.setStreamVolume(
                    AudioManager.STREAM_MUSIC, mLevelMin + target, false);
        }
    }

    private void updateBrightnessModeIcon() {
        if (mType != Type.BRIGHTNESS || mLastState == null) return;
        final boolean automatic = Settings.System.getIntForUser(
                getContext().getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                UserHandle.USER_CURRENT) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        mLastState.icon = QSTileImpl.ResourceIcon.get(automatic
                ? R.drawable.ic_qs_brightness_auto_on
                : R.drawable.ic_qs_brightness_auto_off);
        mLastState.contentDescription = getContext().getString(automatic
                ? R.string.a11_qs_auto_brightness_on
                : R.string.a11_qs_auto_brightness_off);
        mIcon.setIcon(mLastState, true);
        setContentDescription(mLastState.contentDescription);
        invalidate();
    }

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    public View updateAccessibilityOrder(View previousView) {
        setAccessibilityTraversalAfter(previousView.getId());
        return this;
    }

    @Override
    public QSIconView getIcon() {
        return mIcon;
    }

    @Override
    public View getIconWithBackground() {
        return this;
    }

    @Override
    public void init(QSTile tile) {
        setOnLongClickListener(v -> {
            tile.longClick(null);
            return true;
        });
    }

    @Override
    public void onStateChanged(QSTile.State state) {
        mLastState = state;
        mIcon.setIcon(state, true);
        setContentDescription(state.contentDescription);
        setEnabled(state.state != android.service.quicksettings.Tile.STATE_UNAVAILABLE);
        updateBrightnessModeIcon();
    }

    @Override
    public int getDetailY() {
        return getTop() + getHeight() / 2;
    }

    @Override
    public void setPosition(int position) {
        mPosition = position;
    }

    @Override
    public void setEnforcedAdmin(RestrictedLockUtils.EnforcedAdmin admin) {
        setEnabled(admin == null);
    }

    @Override
    public void setMirrorControllerAndMirror(MirrorController controller) {
    }

    @Override
    public boolean mirrorTouchEvent(MotionEvent event) {
        return onTouchEvent(event);
    }

    @Override
    public void setOnChangedListener(Listener listener) {
        mListener = listener;
    }

    @Override
    public void setMax(int max) {
        mMax = Math.max(1, max);
    }

    @Override
    public int getMax() {
        return mMax;
    }

    @Override
    public void setValue(int value) {
        mValue = Math.max(0, Math.min(mMax, value));
        postInvalidateOnAnimation();
    }

    @Override
    public int getValue() {
        return mValue;
    }

    @Override
    public void showView() {
        setVisibility(VISIBLE);
    }

    @Override
    public void hideView() {
        setVisibility(GONE);
    }

    @Override
    public void showToast(int resId) {
        Toast.makeText(getContext(), resId, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean isVisible() {
        return getVisibility() == VISIBLE;
    }
}
