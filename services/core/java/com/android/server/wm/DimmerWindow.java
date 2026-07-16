/*
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * Copyright (C) 2026 The RisingOS Revived Project
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.content.pm.ActivityInfo.CONFIG_DENSITY;
import static android.content.pm.ActivityInfo.CONFIG_ORIENTATION;
import static android.graphics.PixelFormat.TRANSLUCENT;
import static android.view.View.SYSTEM_UI_FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.TYPE_MINI_WINDOW_DIMMER;

import static com.android.server.wm.PopUpWindowController.MOVE_TO_BACK_FROM_LEAVE_BUTTON;

import android.app.ActivityThread;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.util.Slog;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;

import com.android.server.UiThread;

/**
 * Pop-Up View decoration window.
 */
class DimmerWindow {

    private static final String TAG = "DimmerWindow";
    static final String WIN_TITLE = "MiniWindowDimmer";

    private static final float MAX_SCALE = 1.0f;
    private static final int WINDOW_STATE_EXPANDED = 0;
    private static final int WINDOW_STATE_MINIMIZED = 1;

    private static final int LEGACY_DISMISS_TARGET_WIDTH_DP = 132;
    private static final int LEGACY_DISMISS_TARGET_HEIGHT_DP = 36;
    private static final int LEGACY_DISMISS_TARGET_TOP_MARGIN_DP = 24;
    private static final float LEGACY_MINIMIZED_SCALE = 0.36f;
    private static final int DEFAULT_TOP_BAR_LIGHT_COLOR = 0xFFFFFFFF;
    private static final int DEFAULT_TOP_BAR_DARK_COLOR = 0xFF1C1C17;

    private final Context mUiContext = ActivityThread.currentActivityThread().getSystemUiContext();
    private final Handler mUiHandler = new Handler(UiThread.getHandler().getLooper());
    private final LayoutParams mWindowParams = new LayoutParams();

    private Configuration mOldConfig;
    private DimView mDimView;
    private final Task mTask;
    private WindowManager mWindowManager;

    private boolean mIsWindowAdded = false;
    private boolean mShowing = false;

    private float mCurrentScale = 1.0f;

    private int mVibrateThreadhold = 50;
    private int mPendingShowAttempts = 0;
    private boolean mPendingShow = false;

    private static final float MIN_SCALE = LEGACY_MINIMIZED_SCALE;

    private int mWindowState = WINDOW_STATE_EXPANDED;
    private final Point mMinimizedCenter = new Point();

    private static final int MAX_SHOW_RETRY = 40;
    private static final long SHOW_RETRY_DELAY_MS = 50L;

    DimmerWindow(Task task) {
        mTask = task;
    }

    private boolean isMinimizedState() {
        return mWindowState == WINDOW_STATE_MINIMIZED;
    }

    private boolean isExpandedState() {
        return mWindowState == WINDOW_STATE_EXPANDED;
    }

    private class DimView extends FrameLayout {

        final Rect mDrawingRect = new Rect();
        private View mTopBar;
        private View mTopBarTouchArea;
        private View mLegacyDismissTarget;

        private View mResizeHandleBottomLeft;
        private View mResizeHandleBottomRight;
        private View mResizeHandleTopLeft;
        private View mResizeHandleTopRight;

        private GestureDetector mGestureDetector;

        private int mTopBarHeight;
        private int mTopBarWidth;
        private static final int BASE_TOP_BAR_HEIGHT_DP = 6;
        private static final int BASE_TOP_BAR_WIDTH_DP = 120;

        private static final float FOCUSED_ALPHA = 1.0f;
        private static final float UNFOCUSED_ALPHA = 0.4f;

        private static final int TOUCH_AREA_HEIGHT_DP = 48;
        private static final int TOUCH_AREA_EXTRA_WIDTH_DP = 20;

        private boolean isOrientationChanged = false;
        private boolean mHasMoved = false;

        private final int mTouchSlop;

        private int mLegacyBarDownX;
        private int mLegacyBarDownY;
        private int mLegacyDragDownX;
        private int mLegacyDragDownY;
        private final Rect mLegacyDragStartBounds = new Rect();
        private boolean mLegacyDragging;
        private final Rect mDefaultDragStartBounds = new Rect();

        DimView(Context context, float initialScale) {
            super(context);
            setWillNotDraw(false);
            mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            mOldConfig = new Configuration(context.getResources().getConfiguration());
            initUI(initialScale);
        }

        private void initUI(float initialScale) {
            float uiScale = Math.max(0.3f, initialScale);
            mTopBarHeight = (int) (dpToPx(BASE_TOP_BAR_HEIGHT_DP) * uiScale);
            mTopBarWidth = (int) (dpToPx(BASE_TOP_BAR_WIDTH_DP) * uiScale);

            mTopBar = new View(getContext());
            GradientDrawable topBarDrawable = new GradientDrawable();
            topBarDrawable.setColor(0xFFFFFFFF);
            topBarDrawable.setCornerRadius(mTopBarHeight / 2f);
            mTopBar.setBackground(topBarDrawable);
            mTopBar.setAlpha(UNFOCUSED_ALPHA);

            mTopBarTouchArea = new View(getContext());
            mTopBarTouchArea.setBackgroundColor(Color.TRANSPARENT);

            mLegacyDismissTarget = new View(getContext());
            GradientDrawable dismissDrawable = new GradientDrawable();
            dismissDrawable.setColor(0xCCFFFFFF);
            dismissDrawable.setCornerRadius(dpToPx(LEGACY_DISMISS_TARGET_HEIGHT_DP) / 2f);
            mLegacyDismissTarget.setBackground(dismissDrawable);
            mLegacyDismissTarget.setVisibility(GONE);
            mLegacyDismissTarget.setAlpha(0.9f);

            mGestureDetector = new GestureDetector(getContext(),
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDown(MotionEvent e) {
                            return true;
                        }

                        @Override
                        public void onLongPress(MotionEvent e) {
                            if (isExpandedState() && !mHasMoved) {
                                moveActivityTaskToBack();
                            }
                        }

                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            if (isExpandedState() && !mHasMoved) {
                                PopUpWindowController.getInstance().exitMiniWindowingMode(mTask);
                                return true;
                            }
                            return false;
                        }
                    });

            mResizeHandleBottomLeft = new View(getContext());
            mResizeHandleBottomRight = new View(getContext());
            mResizeHandleTopLeft = new View(getContext());
            mResizeHandleTopRight = new View(getContext());

            setupResizeHandle(mResizeHandleBottomLeft, true, false);
            setupResizeHandle(mResizeHandleBottomRight, false, false);
            setupResizeHandle(mResizeHandleTopLeft, true, true);
            setupResizeHandle(mResizeHandleTopRight, false, true);

            addView(mTopBarTouchArea, new FrameLayout.LayoutParams(
                    dpToPx(BASE_TOP_BAR_WIDTH_DP + TOUCH_AREA_EXTRA_WIDTH_DP * 2),
                    dpToPx(TOUCH_AREA_HEIGHT_DP)));
            addView(mTopBar, new FrameLayout.LayoutParams(mTopBarWidth, mTopBarHeight));
            addView(mLegacyDismissTarget, new FrameLayout.LayoutParams(
                    dpToPx(LEGACY_DISMISS_TARGET_WIDTH_DP),
                    dpToPx(LEGACY_DISMISS_TARGET_HEIGHT_DP)));

            setupTopBarTouchListener();
            getViewTreeObserver().addOnComputeInternalInsetsListener(this::updateTouchableRegion);
        }

        private void updateTouchableRegion(ViewTreeObserver.InternalInsetsInfo info) {
            final Region region = new Region();
            if (isMinimizedState()) {
                addRectToRegion(region, mDrawingRect);
                addViewBoundsToRegion(region, mLegacyDismissTarget);
            } else if (mTopBar.getVisibility() == VISIBLE) {
                addViewBoundsToRegion(region, mTopBarTouchArea);
                addViewBoundsToRegion(region, mResizeHandleBottomLeft);
                addViewBoundsToRegion(region, mResizeHandleBottomRight);
                addViewBoundsToRegion(region, mResizeHandleTopLeft);
                addViewBoundsToRegion(region, mResizeHandleTopRight);
            }
            info.touchableRegion.set(region);
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        }

        private void addViewBoundsToRegion(Region region, View view) {
            if (view != null && view.getVisibility() == VISIBLE) {
                region.op(view.getLeft(), view.getTop(), view.getRight(), view.getBottom(),
                        Region.Op.UNION);
            }
        }

        private void addRectToRegion(Region region, Rect rect) {
            if (rect != null && !rect.isEmpty()) {
                region.op(rect.left, rect.top, rect.right, rect.bottom, Region.Op.UNION);
            }
        }

        private void setupTopBarTouchListener() {
            mTopBarTouchArea.setOnTouchListener((v, event) -> handleDefaultTopBarTouch(event));
        }

        private boolean handleDefaultTopBarTouch(MotionEvent event) {
            if (mGestureDetector != null) {
                mGestureDetector.onTouchEvent(event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    DimmerWindowManager.getInstance().setActiveTask(mTask);
                    mLegacyBarDownX = (int) event.getRawX();
                    mLegacyBarDownY = (int) event.getRawY();
                    mDefaultDragStartBounds.set(mDrawingRect);
                    mHasMoved = false;
                    PopUpWindowController.getInstance().triggerVibrate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    DimmerWindowManager.getInstance().hideMenus();
                    final int currentX = (int) event.getRawX();
                    final int currentY = (int) event.getRawY();
                    if (Math.abs(currentX - mLegacyBarDownX) > 10 || Math.abs(currentY - mLegacyBarDownY) > 10) {
                        mHasMoved = true;
                    }
                    if (mHasMoved) {
                        final Rect newBounds = new Rect(mDefaultDragStartBounds);
                        newBounds.offset(currentX - mLegacyBarDownX, currentY - mLegacyBarDownY);
                        updateLayout(newBounds);
                        moveTaskSurface(newBounds.centerX(), newBounds.centerY());
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mHasMoved = false;
                    return true;
                default:
                    return false;
            }
        }

        private void setupResizeHandle(View handle, boolean isLeft, boolean isTop) {
            handle.setBackgroundColor(Color.TRANSPARENT);

            handle.setOnTouchListener(new OnTouchListener() {
                private float initialDistance;
                private float initialScale;
                private int centerX;
                private int centerY;
                private float lastTouchX;
                private float lastTouchY;
                private float resizeDistance = 0;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (isMinimizedState()) {
                        return false;
                    }
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            DimmerWindowManager.getInstance().setActiveTask(mTask);
                            if (mTask != null) {
                                TaskWindowSurfaceInfo info = mTask.mWindowContainerExt
                                        .getTaskWindowSurfaceInfo();
                                if (info != null) {
                                    initialScale = info.getWindowSurfaceScale();
                                    mCurrentScale = initialScale;
                                } else {
                                    initialScale = mCurrentScale;
                                }
                            } else {
                                initialScale = mCurrentScale;
                            }
                            centerX = mDrawingRect.centerX();
                            centerY = mDrawingRect.centerY();
                            initialDistance = (float) Math.hypot(
                                    event.getRawX() - centerX,
                                    event.getRawY() - centerY);
                            lastTouchX = event.getRawX();
                            lastTouchY = event.getRawY();
                            resizeDistance = 0;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float currentTouchX = event.getRawX();
                            float currentTouchY = event.getRawY();
                            float dx = currentTouchX - lastTouchX;
                            float dy = currentTouchY - lastTouchY;
                            resizeDistance += (float) Math.sqrt(dx * dx + dy * dy);
                            if (resizeDistance >= mVibrateThreadhold) {
                                PopUpWindowController.getInstance().triggerVibrate();
                                resizeDistance = 0;
                            }
                            lastTouchX = currentTouchX;
                            lastTouchY = currentTouchY;
                            float currentDistance = (float) Math.hypot(
                                    currentTouchX - centerX,
                                    currentTouchY - centerY);
                            if (initialDistance > 0) {
                                float scaleFactor = currentDistance / initialDistance;
                                float newScale = initialScale * scaleFactor;
                                newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));
                                mCurrentScale = newScale;
                                resizeTask(newScale, isLeft, isTop);
                                updateBarScale(newScale);
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            resizeDistance = 0;
                            maybeSwitchToMinimizedState();
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            resizeDistance = 0;
                            return true;
                        default:
                            return false;
                    }
                }
            });

            addView(handle, new FrameLayout.LayoutParams(dpToPx(40), dpToPx(40)));
        }

        private void resizeTask(float scale, boolean isLeft, boolean isTop) {
            if (mTask == null) return;
            mTask.mWmService.mH.post(() -> {
                synchronized (mTask.mWmService.mGlobalLock) {
                    if (mTask == null) return;
                    TaskWindowSurfaceInfo info = mTask.mWindowContainerExt
                            .getTaskWindowSurfaceInfo();
                    if (info == null) {
                        return;
                    }
                    Rect displayBounds = new Rect();
                    mTask.getDisplayContent().getBounds(displayBounds);

                    float oldScale = info.getWindowSurfaceScale();
                    float scaleDiff = scale - oldScale;
                    if (Math.abs(scaleDiff) > 0.001f) {
                        Rect bounds = mTask.getBounds();
                        float dW = bounds.width() * scaleDiff;
                        float dH = bounds.height() * scaleDiff;
                        Point currentCenter = info.getWindowCenterPosition();
                        int dx;
                        int dy;
                        if (isOrientationChanged || mTask.getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                            dx = 0;
                            dy = 0;
                        } else {
                            dx = (int) ((isLeft ? -1 : 1) * (dW / 2.0f));
                            dy = (int) ((isTop ? -1 : 1) * (dH / 2.0f));
                        }
                        info.setWindowCenterPosition(new Point(currentCenter.x + dx, currentCenter.y + dy));
                    }
                    info.setWindowSurfaceScaleDrag(scale, displayBounds, isOrientationChanged);
                    android.view.SurfaceControl.Transaction t = mTask.getSyncTransaction();
                    PopUpWindowController.getInstance().onPrepareSurfaces(mTask, t);
                    t.apply();
                }
            });
        }

        private int dpToPx(int dp) {
            return (int) (dp * getResources().getDisplayMetrics().density);
        }

        void updateLayout(Rect taskBounds) {
            if (taskBounds == null || taskBounds.isEmpty()) return;
            mDrawingRect.set(taskBounds);

            FrameLayout.LayoutParams lpBar = (FrameLayout.LayoutParams) mTopBar.getLayoutParams();
            lpBar.width = mTopBarWidth;
            lpBar.height = mTopBarHeight;
            lpBar.leftMargin = taskBounds.centerX() - (mTopBarWidth / 2);
            lpBar.topMargin = taskBounds.bottom + dpToPx(4);
            mTopBar.setLayoutParams(lpBar);

            int touchHeight = dpToPx(TOUCH_AREA_HEIGHT_DP);
            int touchWidth = mTopBarWidth + dpToPx(TOUCH_AREA_EXTRA_WIDTH_DP * 2);
            FrameLayout.LayoutParams lpTouch = (FrameLayout.LayoutParams) mTopBarTouchArea.getLayoutParams();
            lpTouch.width = touchWidth;
            lpTouch.height = touchHeight;
            lpTouch.leftMargin = taskBounds.centerX() - (touchWidth / 2);
            lpTouch.topMargin = lpBar.topMargin - (touchHeight - mTopBarHeight) / 2;
            mTopBarTouchArea.setLayoutParams(lpTouch);

            FrameLayout.LayoutParams dismissLp = (FrameLayout.LayoutParams) mLegacyDismissTarget.getLayoutParams();
            dismissLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            dismissLp.topMargin = dpToPx(LEGACY_DISMISS_TARGET_TOP_MARGIN_DP);
            mLegacyDismissTarget.setLayoutParams(dismissLp);

            if (isMinimizedState()) {
                setMinimizedViewVisibility();
                requestLayout();
                invalidate();
                return;
            }

            int handleSize = dpToPx(40);
            FrameLayout.LayoutParams lpBottomLeft = (FrameLayout.LayoutParams) mResizeHandleBottomLeft.getLayoutParams();
            lpBottomLeft.leftMargin = taskBounds.left - (handleSize / 2);
            lpBottomLeft.topMargin = taskBounds.bottom - (handleSize / 2);
            mResizeHandleBottomLeft.setLayoutParams(lpBottomLeft);

            FrameLayout.LayoutParams lpBottomRight = (FrameLayout.LayoutParams) mResizeHandleBottomRight.getLayoutParams();
            lpBottomRight.leftMargin = taskBounds.right - (handleSize / 2);
            lpBottomRight.topMargin = taskBounds.bottom - (handleSize / 2);
            mResizeHandleBottomRight.setLayoutParams(lpBottomRight);

            FrameLayout.LayoutParams lpTopLeft = (FrameLayout.LayoutParams) mResizeHandleTopLeft.getLayoutParams();
            lpTopLeft.leftMargin = taskBounds.left - (handleSize / 2);
            lpTopLeft.topMargin = taskBounds.top - (handleSize / 2);
            mResizeHandleTopLeft.setLayoutParams(lpTopLeft);

            FrameLayout.LayoutParams lpTopRight = (FrameLayout.LayoutParams) mResizeHandleTopRight.getLayoutParams();
            lpTopRight.leftMargin = taskBounds.right - (handleSize / 2);
            lpTopRight.topMargin = taskBounds.top - (handleSize / 2);
            mResizeHandleTopRight.setLayoutParams(lpTopRight);

            mTopBar.setVisibility(VISIBLE);
            mTopBarTouchArea.setVisibility(VISIBLE);
            mResizeHandleBottomLeft.setVisibility(VISIBLE);
            mResizeHandleBottomRight.setVisibility(VISIBLE);
            mResizeHandleTopLeft.setVisibility(VISIBLE);
            mResizeHandleTopRight.setVisibility(VISIBLE);

            requestLayout();
        }

        private void setMinimizedViewVisibility() {
            mTopBar.setVisibility(GONE);
            mTopBarTouchArea.setVisibility(GONE);
            mResizeHandleBottomLeft.setVisibility(GONE);
            mResizeHandleBottomRight.setVisibility(GONE);
            mResizeHandleTopLeft.setVisibility(GONE);
            mResizeHandleTopRight.setVisibility(GONE);
        }

        void updateBarScale(float scale) {
            float uiScale = Math.max(0.6f, scale);
            mTopBarHeight = (int) (dpToPx(BASE_TOP_BAR_HEIGHT_DP) * uiScale);
            mTopBarWidth = (int) (dpToPx(BASE_TOP_BAR_WIDTH_DP) * uiScale);

            GradientDrawable topBarDrawable = new GradientDrawable();
            topBarDrawable.setColor(0xFFFFFFFF);
            topBarDrawable.setCornerRadius(mTopBarHeight / 2f);
            mTopBar.setBackground(topBarDrawable);

            FrameLayout.LayoutParams lpBar = (FrameLayout.LayoutParams) mTopBar.getLayoutParams();
            lpBar.width = mTopBarWidth;
            lpBar.height = mTopBarHeight;
            mTopBar.setLayoutParams(lpBar);

            int touchWidth = mTopBarWidth + dpToPx(TOUCH_AREA_EXTRA_WIDTH_DP * 2);
            FrameLayout.LayoutParams lpTouch = (FrameLayout.LayoutParams) mTopBarTouchArea.getLayoutParams();
            lpTouch.width = touchWidth;
            mTopBarTouchArea.setLayoutParams(lpTouch);

            if (mTask != null) {
                TaskWindowSurfaceInfo info = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
                if (info != null) {
                    updateLayout(info.getTaskWindowSurfaceBounds());
                }
            }
        }

        void updateTopBarFocus(boolean hasFocus) {
            if (mTopBar != null) {
                final float targetAlpha = hasFocus ? FOCUSED_ALPHA : UNFOCUSED_ALPHA;
                mTopBar.animate().alpha(targetAlpha).setDuration(150).start();
            }
        }

        void onWindowStateChanged() {
            if (isMinimizedState()) {
                setMinimizedViewVisibility();
            }
            setLegacyDismissTargetVisible(false, false);
            invalidate();
            requestLayout();
        }

        private void setLegacyDismissTargetVisible(boolean visible, boolean highlighted) {
            if (mLegacyDismissTarget == null) {
                return;
            }
            mLegacyDismissTarget.setVisibility(visible ? VISIBLE : GONE);
            GradientDrawable drawable = (GradientDrawable) mLegacyDismissTarget.getBackground();
            drawable.setColor(highlighted ? 0xFFE53935 : 0xCCFFFFFF);
            mLegacyDismissTarget.setAlpha(highlighted ? 1.0f : 0.9f);
            invalidate();
        }

        private boolean isInLegacyDismissTarget(float rawX, float rawY) {
            return mLegacyDismissTarget.getVisibility() == VISIBLE
                    && rawX >= mLegacyDismissTarget.getLeft()
                    && rawX <= mLegacyDismissTarget.getRight()
                    && rawY >= mLegacyDismissTarget.getTop()
                    && rawY <= mLegacyDismissTarget.getBottom();
        }

        private void maybeSwitchToMinimizedState() {
            if (mCurrentScale > MIN_SCALE + 0.001f || mDrawingRect.isEmpty()) {
                return;
            }
            rememberMinimizedCenter(mDrawingRect.centerX(), mDrawingRect.centerY());
            switchToWindowState(WINDOW_STATE_MINIMIZED);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (isExpandedState()) {
                return super.onTouchEvent(event);
            }
            return handleMinimizedTouch(event);
        }

        private boolean handleMinimizedTouch(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    DimmerWindowManager.getInstance().setActiveTask(mTask);
                    mLegacyDragDownX = (int) event.getRawX();
                    mLegacyDragDownY = (int) event.getRawY();
                    mLegacyDragStartBounds.set(mDrawingRect);
                    mLegacyDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    final int moveX = (int) event.getRawX();
                    final int moveY = (int) event.getRawY();
                    if (!mLegacyDragging) {
                        if (Math.abs(moveX - mLegacyDragDownX) > mTouchSlop
                                || Math.abs(moveY - mLegacyDragDownY) > mTouchSlop) {
                            mLegacyDragging = true;
                            setLegacyDismissTargetVisible(true, false);
                        }
                    }
                    if (mLegacyDragging) {
                        final Rect newBounds = new Rect(mLegacyDragStartBounds);
                        newBounds.offset(moveX - mLegacyDragDownX, moveY - mLegacyDragDownY);
                        updateLayout(newBounds);
                        moveTaskSurface(newBounds.centerX(), newBounds.centerY());
                        setLegacyDismissTargetVisible(true,
                                isInLegacyDismissTarget(event.getRawX(), event.getRawY()));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (mLegacyDragging) {
                        final boolean dismiss = isInLegacyDismissTarget(event.getRawX(), event.getRawY());
                        setLegacyDismissTargetVisible(false, false);
                        mLegacyDragging = false;
                        if (dismiss) {
                            moveActivityTaskToBack();
                        } else {
                            rememberMinimizedCenter(mDrawingRect.centerX(), mDrawingRect.centerY());
                            PopUpWindowController.getInstance().setMiniWindowInputFocus(false);
                        }
                    } else {
                        switchToWindowState(WINDOW_STATE_EXPANDED);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    setLegacyDismissTargetVisible(false, false);
                    mLegacyDragging = false;
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            int configChanges = newConfig.diff(mOldConfig);
            if ((configChanges & CONFIG_DENSITY) != 0) {
                onDensityChanged();
            }
            if ((configChanges & CONFIG_ORIENTATION) != 0) {
                onOrientationChanged();
            }
            mOldConfig.setTo(newConfig);
        }

        private void onOrientationChanged() {
            if (mTask == null) {
                return;
            }
            post(() -> {
                TaskWindowSurfaceInfo info = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
                if (info == null) {
                    return;
                }
                if (isMinimizedState()) {
                    applyWindowState(false);
                } else {
                    mCurrentScale = info.getWindowSurfaceScale();
                    updateLayout(info.getTaskWindowSurfaceBounds());
                }
                isOrientationChanged = !isOrientationChanged;
            });
        }
    }

    Task getTask() {
        return mTask;
    }

    boolean shouldHandleInput() {
        return isExpandedState();
    }

    void show() {
        if (!ensureBoundsReady()) {
            scheduleShowRetry();
            return;
        }
        mPendingShowAttempts = 0;
        if (mTask != null && mTask.mWindowContainerExt.getTaskWindowSurfaceInfo() != null) {
            mCurrentScale = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo().getWindowSurfaceScale();
        }
        updateWindowState(true);
    }

    void hide() {
        updateWindowState(false);
    }

    void destroy() {
        mUiHandler.post(() -> {
            if (mIsWindowAdded && mDimView != null && getWindowManager() != null) {
                getWindowManager().removeView(mDimView);
            }
            mIsWindowAdded = false;
            mShowing = false;
            mDimView = null;
        });
    }

    RectF getEdgeBarBounds() {
        return new RectF();
    }

    void moveActivityTaskToBack() {
        if (mTask == null) {
            return;
        }
        PopUpWindowController.getInstance().moveActivityTaskToBack(mTask, MOVE_TO_BACK_FROM_LEAVE_BUTTON);
    }

    private void updateWindowState(boolean show) {
        mUiHandler.post(() -> {
            if (show && mTask != null) {
                if (!ensureBoundsReady()) {
                    scheduleShowRetry();
                    return;
                }
                try {
                    TaskWindowSurfaceInfo info = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
                    if (info != null && mDimView != null) {
                        mDimView.updateLayout(info.getTaskWindowSurfaceBounds());
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Error updating window state layout", e);
                }
            }

            if (show && !mIsWindowAdded) {
                addDimmerWin();
            } else {
                updateDimmerWin(show);
            }
        });
    }

    private boolean ensureBoundsReady() {
        if (mTask == null) {
            return false;
        }
        TaskWindowSurfaceInfo info = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
        if (info == null) {
            return false;
        }
        Rect bounds = info.getTaskWindowSurfaceBounds();
        return bounds != null && !bounds.isEmpty();
    }

    private void scheduleShowRetry() {
        if (mPendingShow || mPendingShowAttempts >= MAX_SHOW_RETRY) {
            return;
        }
        mPendingShow = true;
        mUiHandler.postDelayed(() -> {
            mPendingShow = false;
            mPendingShowAttempts++;
            show();
        }, SHOW_RETRY_DELAY_MS);
    }

    void onDensityChanged() {
        PopUpWindowController.getInstance().findAndExitAllPopUp();
    }

    void onDragResizeChanged(float scale, Rect taskWindowSurfaceBound, boolean isLandscape) {
        mCurrentScale = scale;
        mUiHandler.post(() -> {
            if (mDimView != null) {
                mDimView.updateBarScale(scale);
                mDimView.updateLayout(taskWindowSurfaceBound);
            }
        });
    }

    void onResizeChanged() {
        if (mDimView == null || mTask == null) {
            return;
        }
        try {
            TaskWindowSurfaceInfo info = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
            if (info == null) {
                return;
            }
            if (isMinimizedState()) {
                applyWindowState(false);
            } else {
                mDimView.updateLayout(info.getTaskWindowSurfaceBounds());
            }
        } catch (Exception e) {
            Slog.w(TAG, "Error updating pop-up layout", e);
        }
    }

    private void addDimmerWin() {
        if (getWindowManager() == null) {
            return;
        }
        mWindowParams.type = TYPE_MINI_WINDOW_DIMMER;
        mWindowParams.format = TRANSLUCENT;
        mWindowParams.flags = LayoutParams.FLAG_NOT_TOUCH_MODAL
                | LayoutParams.FLAG_NOT_FOCUSABLE
                | LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | LayoutParams.FLAG_FULLSCREEN;
        mWindowParams.privateFlags = LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
        mWindowParams.layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        mWindowParams.setFitInsetsTypes(0);
        mWindowParams.dimAmount = 0;
        mWindowParams.gravity = Gravity.LEFT | Gravity.TOP;
        mWindowParams.x = 0;
        mWindowParams.y = 0;
        mWindowParams.setTitle(mTask != null ? WIN_TITLE + "#" + mTask.mTaskId : WIN_TITLE);
        mWindowParams.width = LayoutParams.MATCH_PARENT;
        mWindowParams.height = LayoutParams.MATCH_PARENT;
        mWindowParams.windowAnimations = 0;

        mDimView = new DimView(mUiContext.createWindowContext(TYPE_MINI_WINDOW_DIMMER, null), mCurrentScale);
        mDimView.setAlpha(1.0f);

        if (mTask != null) {
            try {
                TaskWindowSurfaceInfo info = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
                if (info != null) {
                    if (isMinimizedState()) {
                        applyWindowState(false);
                    } else {
                        mDimView.updateLayout(info.getTaskWindowSurfaceBounds());
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Error preparing pop-up decor", e);
            }
        }

        getWindowManager().addView(mDimView, mWindowParams);
        mIsWindowAdded = true;
        mShowing = true;
        mDimView.setSystemUiVisibility(SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void updateDimmerWin(boolean show) {
        if (getWindowManager() == null || mDimView == null || mShowing == show) {
            return;
        }
        if (show) {
            if (mTask != null) {
                try {
                    TaskWindowSurfaceInfo info = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
                    if (info != null) {
                        if (isMinimizedState()) {
                            applyWindowState(false);
                        } else {
                            mDimView.updateLayout(info.getTaskWindowSurfaceBounds());
                        }
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Error updating pop-up decor", e);
                }
            }
            mWindowParams.flags &= ~LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            mWindowParams.flags |= LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        mWindowManager.updateViewLayout(mDimView, mWindowParams);
        mDimView.setVisibility(show ? View.VISIBLE : View.GONE);
        mShowing = show;
    }

    private WindowManager getWindowManager() {
        if (mWindowManager == null) {
            mWindowManager = mUiContext.getSystemService(WindowManager.class);
        }
        return mWindowManager;
    }

    public Rect getBounds() {
        if (mDimView == null || mDimView.mDrawingRect.isEmpty()) {
            return null;
        }
        if (isMinimizedState()) {
            return new Rect(mDimView.mDrawingRect);
        }

        final Rect decoratedBounds = new Rect(mDimView.mDrawingRect);
        int barWidth = mDimView.mTopBarWidth;
        int barHeight = mDimView.mTopBarHeight;
        int touchHeight = dpToPx(DimView.TOUCH_AREA_HEIGHT_DP);
        int extraWidth = dpToPx(DimView.TOUCH_AREA_EXTRA_WIDTH_DP);
        int barVisualTop = mDimView.mDrawingRect.bottom + dpToPx(4);
        int touchTop = barVisualTop - (touchHeight - barHeight) / 2;
        int touchBottom = touchTop + touchHeight;
        decoratedBounds.bottom = Math.max(decoratedBounds.bottom, touchBottom);
        int taskCenterX = mDimView.mDrawingRect.centerX();
        int touchLeft = taskCenterX - (barWidth / 2) - extraWidth;
        int touchRight = taskCenterX + (barWidth / 2) + extraWidth;
        decoratedBounds.left = Math.min(decoratedBounds.left, touchLeft);
        decoratedBounds.right = Math.max(decoratedBounds.right, touchRight);
        return decoratedBounds;
    }

    void updateTopBarFocus(boolean hasFocus) {
        mUiHandler.post(() -> {
            if (mDimView != null) {
                mDimView.updateTopBarFocus(hasFocus);
            }
        });
    }

    void hideMenu() {
        // no-op: current Pop-Up View decoration no longer exposes a menu overlay.
    }

    private int dpToPx(int dp) {
        return (int) (dp * mUiContext.getResources().getDisplayMetrics().density);
    }

    private void switchToWindowState(int targetState) {
        mWindowState = targetState;
        applyWindowState(true);
    }

    private void applyWindowState(boolean animate) {
        if (mTask == null) {
            return;
        }
        final int targetState = mWindowState;
        mTask.mWmService.mH.post(() -> {
            synchronized (mTask.mWmService.mGlobalLock) {
                final TaskWindowSurfaceInfo info = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
                if (info == null || mTask.mDisplayContent == null) {
                    return;
                }
                final Rect displayBounds = new Rect();
                mTask.mDisplayContent.getBounds(displayBounds);
                final Point targetCenter = new Point(displayBounds.centerX(), displayBounds.centerY());
                final float targetScale;
                if (targetState == WINDOW_STATE_EXPANDED) {
                    targetScale = WindowResizingAlgorithm.getDefaultMiniWindowScale(
                            mTask.getConfiguration().orientation,
                            mTask.mDisplayContent.getRotation());
                } else {
                    targetScale = LEGACY_MINIMIZED_SCALE;
                    if (mMinimizedCenter.x != 0 || mMinimizedCenter.y != 0) {
                        targetCenter.set(mMinimizedCenter.x, mMinimizedCenter.y);
                    } else {
                        targetCenter.set(info.getWindowCenterPosition());
                    }
                }

                final Rect beforeBounds = info.getTaskWindowSurfaceBounds();
                final float beforeScale = info.getWindowSurfaceRealScale();

                info.setWindowCenterPosition(targetCenter);
                info.setWindowSurfaceScale(targetScale);
                mCurrentScale = targetScale;
                if (targetState == WINDOW_STATE_MINIMIZED) {
                    mMinimizedCenter.set(targetCenter.x, targetCenter.y);
                }
                final Rect afterBounds = info.getTaskWindowSurfaceBounds();
                final float afterScale = info.getWindowSurfaceRealScale();
                final android.view.SurfaceControl.Transaction t = mTask.getSyncTransaction();
                if (animate && beforeBounds != null && !beforeBounds.isEmpty()
                        && afterBounds != null && !afterBounds.isEmpty()) {
                    info.playToggleResizeWindowAnimation(
                            new Point(beforeBounds.left, beforeBounds.top),
                            new Point(afterBounds.left, afterBounds.top),
                            beforeScale,
                            afterScale,
                            () -> { });
                } else {
                    PopUpWindowController.getInstance().onPrepareSurfaces(mTask, t);
                    t.apply();
                }
                PopUpWindowController.getInstance().setMiniWindowInputFocus(
                        targetState == WINDOW_STATE_EXPANDED);
                mUiHandler.post(() -> {
                    if (mDimView != null) {
                        mDimView.onWindowStateChanged();
                        mDimView.updateLayout(afterBounds);
                    }
                });
            }
        });
    }

    private void rememberMinimizedCenter(int centerX, int centerY) {
        mMinimizedCenter.set(centerX, centerY);
    }

    private void moveTaskSurface(int centerX, int centerY) {
        if (mTask == null) return;
        rememberMinimizedCenter(centerX, centerY);
        mTask.mWmService.mH.post(() -> {
            synchronized (mTask.mWmService.mGlobalLock) {
                if (mTask == null) {
                    return;
                }
                TaskWindowSurfaceInfo info = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
                if (info == null) {
                    return;
                }
                info.setWindowCenterPosition(new Point(centerX, centerY));
                android.view.SurfaceControl.Transaction t = mTask.getSyncTransaction();
                PopUpWindowController.getInstance().onPrepareSurfaces(mTask, t);
                t.apply();
            }
        });
    }
}
