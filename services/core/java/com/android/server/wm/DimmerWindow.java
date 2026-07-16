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

import android.app.ActivityManager.TaskDescription;
import android.app.ActivityThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Slog;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.VelocityTracker;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.TextView;

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

    private static final int LEGACY_DISMISS_TARGET_SIZE_DP = 72;
    private static final int LEGACY_DISMISS_TARGET_BOTTOM_MARGIN_DP = 40;
    private static final int LEGACY_DISMISS_TARGET_MAGNET_RADIUS_DP = 120;
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
        private View mCornerHandleTouchArea;
        private View mCornerHandle;
        private LinearLayout mCornerChip;
        private ImageView mCornerChipIcon;
        private TextView mCornerChipLabel;

        private View mResizeHandleBottomLeft;
        private View mResizeHandleBottomRight;
        private View mResizeHandleTopLeft;
        private View mResizeHandleTopRight;

        private GestureDetector mGestureDetector;

        private int mTopBarHeight;
        private int mTopBarWidth;
        private static final int BASE_TOP_BAR_HEIGHT_DP = 6;
        private static final int BASE_TOP_BAR_WIDTH_DP = 120;
        private static final int BASE_CORNER_HANDLE_WIDTH_DP = 60;
        private static final int BASE_CORNER_CHIP_WIDTH_DP = 180;
        private static final int BASE_CORNER_CHIP_HEIGHT_DP = 38;
        private static final int CORNER_DECOR_SIDE_MARGIN_DP = 12;
        private static final int CORNER_DECOR_TOP_GAP_DP = 8;
        private static final int CORNER_DECOR_SCREEN_MARGIN_DP = 4;
        private static final int CORNER_HANDLE_FLOATING_GAP_DP = 4;
        private static final int CORNER_HANDLE_TOUCH_WIDTH_DP = 84;
        private static final int CORNER_HANDLE_TOUCH_HEIGHT_DP = 56;
        private static final int CORNER_HANDLE_TOUCH_SIDE_INSET_DP = 12;
        private static final int CORNER_HANDLE_TOUCH_TOP_INSET_DP = 8;
        private static final int CORNER_CHIP_ICON_SIZE_DP = 20;

        private int mCornerHandleWidth;
        private int mCornerChipWidth;
        private int mCornerChipHeight;
        private boolean mCornerChipExpanded;

        private static final float FOCUSED_ALPHA = 1.0f;
        private static final float UNFOCUSED_ALPHA = 0.4f;
        private float mDecorAlpha = UNFOCUSED_ALPHA;

        private static final int TOUCH_AREA_HEIGHT_DP = 48;
        private static final int TOUCH_AREA_EXTRA_WIDTH_DP = 20;

        private boolean isOrientationChanged = false;
        private boolean mHasMoved = false;

        private final int mTouchSlop;
        private final int mMinimumFlingVelocity;
        private final int mMaximumFlingVelocity;
        private final OverScroller mMiniFlingScroller;

        private int mLegacyBarDownX;
        private int mLegacyBarDownY;
        private int mLegacyDragDownX;
        private int mLegacyDragDownY;
        private final Rect mLegacyDragStartBounds = new Rect();
        private final Rect mMiniMovementBounds = new Rect();
        private boolean mLegacyDragging;
        private boolean mLegacyStuckToDismiss;
        private final Rect mDefaultDragStartBounds = new Rect();
        private VelocityTracker mMiniVelocityTracker;
        private boolean mDismissTargetVisible;
        private final Runnable mMiniFlingRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mMiniFlingScroller.computeScrollOffset()) {
                    finishMiniWindowFling();
                    return;
                }
                final Rect newBounds = new Rect(mDrawingRect);
                newBounds.offsetTo(mMiniFlingScroller.getCurrX(), mMiniFlingScroller.getCurrY());
                updateLayout(newBounds);
                moveTaskSurface(newBounds.centerX(), newBounds.centerY());
                postOnAnimation(this);
            }
        };

        DimView(Context context, float initialScale) {
            super(context);
            setWillNotDraw(false);
            final ViewConfiguration viewConfiguration = ViewConfiguration.get(context);
            mTouchSlop = viewConfiguration.getScaledTouchSlop();
            mMinimumFlingVelocity = viewConfiguration.getScaledMinimumFlingVelocity();
            mMaximumFlingVelocity = viewConfiguration.getScaledMaximumFlingVelocity();
            mMiniFlingScroller = new OverScroller(context);
            mOldConfig = new Configuration(context.getResources().getConfiguration());
            initUI(initialScale);
        }

        private void initUI(float initialScale) {
            float uiScale = Math.max(0.3f, initialScale);
            mTopBarHeight = (int) (dpToPx(BASE_TOP_BAR_HEIGHT_DP) * uiScale);
            mTopBarWidth = (int) (dpToPx(BASE_TOP_BAR_WIDTH_DP) * uiScale);
            updateCornerDecorSize(uiScale);

            mTopBar = new View(getContext());
            updateTopBarDrawable();
            mTopBar.setAlpha(UNFOCUSED_ALPHA);

            mTopBarTouchArea = new View(getContext());
            mTopBarTouchArea.setBackgroundColor(Color.TRANSPARENT);

            mLegacyDismissTarget = new View(getContext());
            GradientDrawable dismissDrawable = new GradientDrawable();
            dismissDrawable.setColor(0xCCFFFFFF);
            dismissDrawable.setCornerRadius(dpToPx(LEGACY_DISMISS_TARGET_SIZE_DP) / 2f);
            dismissDrawable.setStroke(dpToPx(2), 0x26FFFFFF);
            mLegacyDismissTarget.setBackground(dismissDrawable);
            mLegacyDismissTarget.setVisibility(GONE);
            mLegacyDismissTarget.setAlpha(0.0f);
            mLegacyDismissTarget.setScaleX(0.85f);
            mLegacyDismissTarget.setScaleY(0.85f);

            initCornerDecor();

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
                    dpToPx(LEGACY_DISMISS_TARGET_SIZE_DP),
                    dpToPx(LEGACY_DISMISS_TARGET_SIZE_DP)));
            addView(mCornerHandleTouchArea, new FrameLayout.LayoutParams(
                    dpToPx(CORNER_HANDLE_TOUCH_WIDTH_DP),
                    dpToPx(CORNER_HANDLE_TOUCH_HEIGHT_DP)));
            addView(mCornerHandle, new FrameLayout.LayoutParams(
                    mCornerHandleWidth,
                    mTopBarHeight));
            addView(mCornerChip, new FrameLayout.LayoutParams(
                    mCornerChipWidth,
                    mCornerChipHeight));

            setupTopBarTouchListener();
            getViewTreeObserver().addOnComputeInternalInsetsListener(this::updateTouchableRegion);
        }

        private void initCornerDecor() {
            mCornerHandleTouchArea = new View(getContext());
            mCornerHandleTouchArea.setBackgroundColor(Color.TRANSPARENT);
            mCornerHandleTouchArea.setClickable(true);
            mCornerHandleTouchArea.setOnClickListener(v -> setCornerChipExpanded(true, true));

            mCornerHandle = new View(getContext());
            final GradientDrawable handleDrawable = new GradientDrawable();
            handleDrawable.setColor(DEFAULT_TOP_BAR_LIGHT_COLOR);
            handleDrawable.setCornerRadius(mTopBarHeight / 2f);
            mCornerHandle.setBackground(handleDrawable);
            mCornerHandle.setAlpha(UNFOCUSED_ALPHA);
            mCornerHandle.setOnClickListener(v -> setCornerChipExpanded(true, true));

            mCornerChip = new LinearLayout(getContext());
            mCornerChip.setOrientation(LinearLayout.HORIZONTAL);
            mCornerChip.setGravity(Gravity.CENTER_VERTICAL);
            mCornerChip.setClipToPadding(false);
            mCornerChip.setPadding(dpToPx(10), 0, dpToPx(14), 0);
            final GradientDrawable chipDrawable = new GradientDrawable();
            chipDrawable.setColor(0xF2FFFFFF);
            chipDrawable.setCornerRadius(dpToPx(BASE_CORNER_CHIP_HEIGHT_DP) / 2f);
            mCornerChip.setBackground(chipDrawable);
            mCornerChip.setAlpha(UNFOCUSED_ALPHA);
            mCornerChip.setVisibility(GONE);
            mCornerChip.setScaleX(0.84f);
            mCornerChip.setScaleY(0.84f);
            mCornerChip.setClickable(true);
            mCornerChip.setOnClickListener(v -> { });

            mCornerChipIcon = new ImageView(getContext());
            final LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                    dpToPx(CORNER_CHIP_ICON_SIZE_DP),
                    dpToPx(CORNER_CHIP_ICON_SIZE_DP));
            mCornerChipIcon.setLayoutParams(iconLp);

            mCornerChipLabel = new TextView(getContext());
            final LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    0, LayoutParams.WRAP_CONTENT, 1.0f);
            labelLp.leftMargin = dpToPx(8);
            mCornerChipLabel.setLayoutParams(labelLp);
            mCornerChipLabel.setSingleLine(true);
            mCornerChipLabel.setEllipsize(TextUtils.TruncateAt.END);
            mCornerChipLabel.setTextColor(0xFF1A1A1A);
            mCornerChipLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);

            mCornerChip.addView(mCornerChipIcon);
            mCornerChip.addView(mCornerChipLabel);
            populateCornerChipInfo();
        }

        private void updateTouchableRegion(ViewTreeObserver.InternalInsetsInfo info) {
            final Region region = new Region();
            if (isMinimizedState()) {
                addRectToRegion(region, mDrawingRect);
                addViewBoundsToRegion(region, mLegacyDismissTarget);
            } else if (mTopBar.getVisibility() == VISIBLE) {
                addViewBoundsToRegion(region, mTopBarTouchArea);
                addViewBoundsToRegion(region, mCornerHandleTouchArea);
                addViewBoundsToRegion(region, mCornerChip);
                addViewBoundsToRegion(region, mResizeHandleBottomLeft);
                addViewBoundsToRegion(region, mResizeHandleBottomRight);
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

        private void updateCornerDecorSize(float uiScale) {
            mCornerHandleWidth = Math.max(dpToPx(44),
                    (int) (dpToPx(BASE_CORNER_HANDLE_WIDTH_DP) * Math.max(0.6f, uiScale)));
            mCornerChipWidth = Math.max(dpToPx(144),
                    (int) (dpToPx(BASE_CORNER_CHIP_WIDTH_DP) * Math.max(0.85f, uiScale)));
            mCornerChipHeight = Math.max(dpToPx(32),
                    (int) (dpToPx(BASE_CORNER_CHIP_HEIGHT_DP) * Math.max(0.85f, uiScale)));
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
            dismissLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            dismissLp.bottomMargin = dpToPx(LEGACY_DISMISS_TARGET_BOTTOM_MARGIN_DP);
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

            updateCornerDecorLayout(taskBounds);

            mTopBar.setVisibility(VISIBLE);
            mTopBarTouchArea.setVisibility(VISIBLE);
            mResizeHandleBottomLeft.setVisibility(VISIBLE);
            mResizeHandleBottomRight.setVisibility(VISIBLE);
            mResizeHandleTopLeft.setVisibility(GONE);
            mResizeHandleTopRight.setVisibility(VISIBLE);
            updateCornerDecorVisibility();

            requestLayout();
        }

        private void updateCornerDecorLayout(Rect taskBounds) {
            final int screenMargin = dpToPx(CORNER_DECOR_SCREEN_MARGIN_DP);
            final int sideMargin = dpToPx(CORNER_DECOR_SIDE_MARGIN_DP);
            final int topGap = dpToPx(CORNER_DECOR_TOP_GAP_DP);
            final int floatingGap = dpToPx(CORNER_HANDLE_FLOATING_GAP_DP);
            final int rootWidth = getWidth() > 0
                    ? getWidth()
                    : getResources().getDisplayMetrics().widthPixels;

            final int chipLeft = clamp(taskBounds.left + sideMargin, screenMargin,
                    Math.max(screenMargin, rootWidth - mCornerChipWidth - screenMargin));
            final int chipTop = Math.max(screenMargin, taskBounds.top - mCornerChipHeight - topGap);
            final FrameLayout.LayoutParams chipLp =
                    (FrameLayout.LayoutParams) mCornerChip.getLayoutParams();
            chipLp.width = mCornerChipWidth;
            chipLp.height = mCornerChipHeight;
            chipLp.leftMargin = chipLeft;
            chipLp.topMargin = chipTop;
            mCornerChip.setLayoutParams(chipLp);
            mCornerChip.setPivotX(0f);
            mCornerChip.setPivotY(mCornerChipHeight);

            final int handleLeft = clamp(taskBounds.left + sideMargin + dpToPx(2), screenMargin,
                    Math.max(screenMargin, rootWidth - mCornerHandleWidth - screenMargin));
            final int handleTop = Math.max(screenMargin,
                    taskBounds.top - mTopBarHeight - floatingGap);
            final FrameLayout.LayoutParams handleLp =
                    (FrameLayout.LayoutParams) mCornerHandle.getLayoutParams();
            handleLp.width = mCornerHandleWidth;
            handleLp.height = mTopBarHeight;
            handleLp.leftMargin = handleLeft;
            handleLp.topMargin = handleTop;
            mCornerHandle.setLayoutParams(handleLp);

            final int handleTouchWidth = Math.max(dpToPx(CORNER_HANDLE_TOUCH_WIDTH_DP),
                    mCornerHandleWidth + dpToPx(CORNER_HANDLE_TOUCH_SIDE_INSET_DP * 2));
            final int handleTouchHeight = Math.max(dpToPx(CORNER_HANDLE_TOUCH_HEIGHT_DP),
                    mTopBarHeight + floatingGap + dpToPx(CORNER_HANDLE_TOUCH_TOP_INSET_DP));
            final int handleTouchLeft = clamp(
                    handleLeft - dpToPx(CORNER_HANDLE_TOUCH_SIDE_INSET_DP),
                    0,
                    Math.max(0, rootWidth - handleTouchWidth));
            final int handleTouchTop = Math.max(0,
                    handleTop - dpToPx(CORNER_HANDLE_TOUCH_TOP_INSET_DP));
            final FrameLayout.LayoutParams handleTouchLp =
                    (FrameLayout.LayoutParams) mCornerHandleTouchArea.getLayoutParams();
            handleTouchLp.width = handleTouchWidth;
            handleTouchLp.height = handleTouchHeight;
            handleTouchLp.leftMargin = handleTouchLeft;
            handleTouchLp.topMargin = handleTouchTop;
            mCornerHandleTouchArea.setLayoutParams(handleTouchLp);
        }

        private void updateCornerHintLayout(Rect taskBounds) {
            final FrameLayout.LayoutParams topRightLp =
                    (FrameLayout.LayoutParams) mCornerHintTopRight.getLayoutParams();
            topRightLp.width = mCornerHintSize;
            topRightLp.height = mCornerHintSize;
            topRightLp.leftMargin = taskBounds.right - mCornerHintSize;
            topRightLp.topMargin = taskBounds.top;
            mCornerHintTopRight.setLayoutParams(topRightLp);

            final FrameLayout.LayoutParams bottomLeftLp =
                    (FrameLayout.LayoutParams) mCornerHintBottomLeft.getLayoutParams();
            bottomLeftLp.width = mCornerHintSize;
            bottomLeftLp.height = mCornerHintSize;
            bottomLeftLp.leftMargin = taskBounds.left;
            bottomLeftLp.topMargin = taskBounds.bottom - mCornerHintSize;
            mCornerHintBottomLeft.setLayoutParams(bottomLeftLp);

            final FrameLayout.LayoutParams bottomRightLp =
                    (FrameLayout.LayoutParams) mCornerHintBottomRight.getLayoutParams();
            bottomRightLp.width = mCornerHintSize;
            bottomRightLp.height = mCornerHintSize;
            bottomRightLp.leftMargin = taskBounds.right - mCornerHintSize;
            bottomRightLp.topMargin = taskBounds.bottom - mCornerHintSize;
            mCornerHintBottomRight.setLayoutParams(bottomRightLp);
        }
        private void setMinimizedViewVisibility() {
            mTopBar.setVisibility(GONE);
            mTopBarTouchArea.setVisibility(GONE);
            mResizeHandleBottomLeft.setVisibility(GONE);
            mResizeHandleBottomRight.setVisibility(GONE);
            mResizeHandleTopLeft.setVisibility(GONE);
            mResizeHandleTopRight.setVisibility(GONE);
            mCornerHandleTouchArea.setVisibility(GONE);
            mCornerHandle.setVisibility(GONE);
            mCornerChip.setVisibility(GONE);
        }

        void updateBarScale(float scale) {
            float uiScale = Math.max(0.6f, scale);
            mTopBarHeight = (int) (dpToPx(BASE_TOP_BAR_HEIGHT_DP) * uiScale);
            mTopBarWidth = (int) (dpToPx(BASE_TOP_BAR_WIDTH_DP) * uiScale);
            updateCornerDecorSize(uiScale);

            updateTopBarDrawable();
            updateCornerHandleDrawable();
            updateCornerChipDrawable();

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

        private void updateTopBarDrawable() {
            final GradientDrawable topBarDrawable = new GradientDrawable();
            topBarDrawable.setColor(resolveDefaultTopBarColor());
            topBarDrawable.setCornerRadius(mTopBarHeight / 2f);
            mTopBar.setBackground(topBarDrawable);
        }

        private void updateCornerHandleDrawable() {
            if (!(mCornerHandle.getBackground() instanceof GradientDrawable)) {
                return;
            }
            final GradientDrawable handleDrawable = (GradientDrawable) mCornerHandle.getBackground();
            handleDrawable.setCornerRadius(mTopBarHeight / 2f);
        }

        private void updateCornerChipDrawable() {
            if (!(mCornerChip.getBackground() instanceof GradientDrawable)) {
                return;
            }
            final GradientDrawable chipDrawable = (GradientDrawable) mCornerChip.getBackground();
            chipDrawable.setCornerRadius(mCornerChipHeight / 2f);
        }

        private int resolveDefaultTopBarColor() {
            if (mTask == null) {
                return DEFAULT_TOP_BAR_LIGHT_COLOR;
            }
            final TaskDescription taskDescription = mTask.getTaskDescription();
            if (taskDescription == null) {
                return DEFAULT_TOP_BAR_LIGHT_COLOR;
            }
            final int appearance = taskDescription.getSystemBarsAppearance();
            if ((appearance & WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS) != 0
                    || (appearance & WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) != 0) {
                return DEFAULT_TOP_BAR_DARK_COLOR;
            }
            int sampleColor = taskDescription.getNavigationBarColor();
            if (sampleColor == 0) {
                sampleColor = taskDescription.getStatusBarColor();
            }
            if (sampleColor == 0) {
                sampleColor = taskDescription.getBackgroundColor();
            }
            if (sampleColor == 0) {
                return DEFAULT_TOP_BAR_LIGHT_COLOR;
            }
            return Color.luminance(sampleColor) > 0.5f
                    ? DEFAULT_TOP_BAR_DARK_COLOR
                    : DEFAULT_TOP_BAR_LIGHT_COLOR;
        }

        void updateTopBarFocus(boolean hasFocus) {
            mDecorAlpha = hasFocus ? FOCUSED_ALPHA : UNFOCUSED_ALPHA;
            animateDecorAlpha(mTopBar, mDecorAlpha);
            animateDecorAlpha(mCornerHandle, mDecorAlpha);
            animateDecorAlpha(mCornerChip, mDecorAlpha);
        }

        private void animateDecorAlpha(View view, float targetAlpha) {
            if (view != null) {
                view.animate().alpha(targetAlpha).setDuration(150).start();
            }
        }

        void onWindowStateChanged() {
            if (isMinimizedState()) {
                setMinimizedViewVisibility();
            }
            if (isMinimizedState() && mCornerChipExpanded) {
                setCornerChipExpanded(false, false);
            }
            setLegacyDismissTargetVisible(false, false);
            invalidate();
            requestLayout();
        }

        private void setLegacyDismissTargetVisible(boolean visible, boolean highlighted) {
            if (mLegacyDismissTarget == null) {
                return;
            }
            final GradientDrawable drawable = (GradientDrawable) mLegacyDismissTarget.getBackground();
            drawable.setColor(highlighted ? 0xFFE53935 : 0xCCFFFFFF);
            drawable.setStroke(dpToPx(2), highlighted ? 0x44FFFFFF : 0x26FFFFFF);
            if (visible && !mDismissTargetVisible) {
                mLegacyDismissTarget.animate().cancel();
                mLegacyDismissTarget.setVisibility(VISIBLE);
                mLegacyDismissTarget.setScaleX(0.85f);
                mLegacyDismissTarget.setScaleY(0.85f);
                mLegacyDismissTarget.setAlpha(0.0f);
                mLegacyDismissTarget.animate()
                        .alpha(1.0f)
                        .scaleX(highlighted ? 1.08f : 1.0f)
                        .scaleY(highlighted ? 1.08f : 1.0f)
                        .setDuration(180)
                        .start();
            } else if (visible) {
                mLegacyDismissTarget.animate().cancel();
                mLegacyDismissTarget.setVisibility(VISIBLE);
                mLegacyDismissTarget.animate()
                        .alpha(1.0f)
                        .scaleX(highlighted ? 1.08f : 1.0f)
                        .scaleY(highlighted ? 1.08f : 1.0f)
                        .setDuration(120)
                        .start();
            } else if (mDismissTargetVisible) {
                mLegacyDismissTarget.animate().cancel();
                mLegacyDismissTarget.animate()
                        .alpha(0.0f)
                        .scaleX(0.85f)
                        .scaleY(0.85f)
                        .setDuration(140)
                        .withEndAction(() -> {
                            if (mLegacyDismissTarget != null) {
                                mLegacyDismissTarget.setVisibility(GONE);
                            }
                        })
                        .start();
            } else {
                mLegacyDismissTarget.setVisibility(GONE);
                mLegacyDismissTarget.setAlpha(0.0f);
                mLegacyDismissTarget.setScaleX(0.85f);
                mLegacyDismissTarget.setScaleY(0.85f);
            }
            mDismissTargetVisible = visible;
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
                    stopMiniWindowFling();
                    ensureMiniVelocityTracker().clear();
                    mMiniVelocityTracker.addMovement(event);
                    DimmerWindowManager.getInstance().setActiveTask(mTask);
                    mLegacyDragDownX = (int) event.getRawX();
                    mLegacyDragDownY = (int) event.getRawY();
                    mLegacyDragStartBounds.set(mDrawingRect);
                    mLegacyDragging = false;
                    mLegacyStuckToDismiss = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    ensureMiniVelocityTracker().addMovement(event);
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
                        final Rect newBounds = computeDraggedMiniBounds(moveX, moveY);
                        updateLayout(newBounds);
                        moveTaskSurface(newBounds.centerX(), newBounds.centerY());
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    ensureMiniVelocityTracker().addMovement(event);
                    if (mLegacyDragging) {
                        final boolean dismiss = mLegacyStuckToDismiss
                                || isInLegacyDismissTarget(event.getRawX(), event.getRawY());
                        setLegacyDismissTargetVisible(false, false);
                        mLegacyDragging = false;
                        mLegacyStuckToDismiss = false;
                        if (dismiss) {
                            recycleMiniVelocityTracker();
                            moveActivityTaskToBack();
                        } else {
                            rememberMinimizedCenter(mDrawingRect.centerX(), mDrawingRect.centerY());
                            PopUpWindowController.getInstance().setMiniWindowInputFocus(false);
                            startMiniWindowFlingIfNeeded();
                        }
                    } else {
                        recycleMiniVelocityTracker();
                        switchToWindowState(WINDOW_STATE_EXPANDED);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    ensureMiniVelocityTracker().addMovement(event);
                    setLegacyDismissTargetVisible(false, false);
                    mLegacyDragging = false;
                    mLegacyStuckToDismiss = false;
                    recycleMiniVelocityTracker();
                    return true;
                default:
                    return false;
            }
        }

        private Rect computeDraggedMiniBounds(int moveX, int moveY) {
            final Rect newBounds = new Rect(mLegacyDragStartBounds);
            newBounds.offset(moveX - mLegacyDragDownX, moveY - mLegacyDragDownY);
            if (!isNearLegacyDismissTarget(newBounds.centerX(), newBounds.centerY())) {
                mLegacyStuckToDismiss = false;
                setLegacyDismissTargetVisible(true, false);
                return clampMiniBounds(newBounds);
            }

            mLegacyStuckToDismiss = true;
            setLegacyDismissTargetVisible(true, true);
            final int targetCenterX = mLegacyDismissTarget.getLeft() + (mLegacyDismissTarget.getWidth() / 2);
            final int targetCenterY = mLegacyDismissTarget.getTop() + (mLegacyDismissTarget.getHeight() / 2);
            newBounds.offsetTo(targetCenterX - (newBounds.width() / 2),
                    targetCenterY - (newBounds.height() / 2));
            return clampMiniBounds(newBounds);
        }

        private Rect clampMiniBounds(Rect bounds) {
            if (bounds == null || bounds.isEmpty()) {
                return bounds;
            }
            updateMiniMovementBounds(bounds.width(), bounds.height());
            bounds.offsetTo(
                    clamp(bounds.left, mMiniMovementBounds.left, mMiniMovementBounds.right),
                    clamp(bounds.top, mMiniMovementBounds.top, mMiniMovementBounds.bottom));
            return bounds;
        }

        private void updateMiniMovementBounds(int width, int height) {
            final int availableWidth = Math.max(0, getWidth() - width);
            final int availableHeight = Math.max(0, getHeight() - height);
            mMiniMovementBounds.set(0, 0, availableWidth, availableHeight);
        }

        private boolean isNearLegacyDismissTarget(int centerX, int centerY) {
            if (mLegacyDismissTarget.getVisibility() != VISIBLE) {
                return false;
            }
            final int targetCenterX = mLegacyDismissTarget.getLeft() + (mLegacyDismissTarget.getWidth() / 2);
            final int targetCenterY = mLegacyDismissTarget.getTop() + (mLegacyDismissTarget.getHeight() / 2);
            final float distance = (float) Math.hypot(centerX - targetCenterX, centerY - targetCenterY);
            return distance <= dpToPx(LEGACY_DISMISS_TARGET_MAGNET_RADIUS_DP);
        }

        private void startMiniWindowFlingIfNeeded() {
            if (mDrawingRect.isEmpty()) {
                recycleMiniVelocityTracker();
                return;
            }
            final VelocityTracker velocityTracker = mMiniVelocityTracker;
            if (velocityTracker == null) {
                return;
            }
            velocityTracker.computeCurrentVelocity(1000, mMaximumFlingVelocity);
            final int velocityX = Math.round(velocityTracker.getXVelocity());
            final int velocityY = Math.round(velocityTracker.getYVelocity());
            recycleMiniVelocityTracker();
            if (Math.max(Math.abs(velocityX), Math.abs(velocityY)) < mMinimumFlingVelocity) {
                return;
            }
            updateMiniMovementBounds(mDrawingRect.width(), mDrawingRect.height());
            mMiniFlingScroller.fling(
                    mDrawingRect.left,
                    mDrawingRect.top,
                    velocityX,
                    velocityY,
                    mMiniMovementBounds.left,
                    mMiniMovementBounds.right,
                    mMiniMovementBounds.top,
                    mMiniMovementBounds.bottom);
            postOnAnimation(mMiniFlingRunnable);
        }

        private void stopMiniWindowFling() {
            if (!mMiniFlingScroller.isFinished()) {
                mMiniFlingScroller.abortAnimation();
            }
            removeCallbacks(mMiniFlingRunnable);
        }

        private void finishMiniWindowFling() {
            removeCallbacks(mMiniFlingRunnable);
            if (!mDrawingRect.isEmpty()) {
                rememberMinimizedCenter(mDrawingRect.centerX(), mDrawingRect.centerY());
            }
        }

        private VelocityTracker ensureMiniVelocityTracker() {
            if (mMiniVelocityTracker == null) {
                mMiniVelocityTracker = VelocityTracker.obtain();
            }
            return mMiniVelocityTracker;
        }

        private void recycleMiniVelocityTracker() {
            if (mMiniVelocityTracker != null) {
                mMiniVelocityTracker.recycle();
                mMiniVelocityTracker = null;
            }
        }

        private int clamp(int value, int min, int max) {
            if (value < min) {
                return min;
            }
            if (value > max) {
                return max;
            }
            return value;
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

        private void updateCornerDecorVisibility() {
            mCornerHandleTouchArea.setVisibility(mCornerChipExpanded ? GONE : VISIBLE);
            mCornerHandle.setVisibility(mCornerChipExpanded ? GONE : VISIBLE);
            mCornerChip.setVisibility(mCornerChipExpanded ? VISIBLE : GONE);
        }

        private void setCornerChipExpanded(boolean expanded, boolean animate) {
            if (mCornerChipExpanded == expanded && mCornerChip.getVisibility() == (expanded ? VISIBLE : GONE)) {
                return;
            }
            mCornerChipExpanded = expanded;
            updateCornerDecorVisibility();
            if (!animate) {
                mCornerHandle.animate().cancel();
                mCornerChip.animate().cancel();
                mCornerHandle.setAlpha(expanded ? 0f : mDecorAlpha);
                mCornerChip.setAlpha(expanded ? mDecorAlpha : 0f);
                mCornerChip.setScaleX(expanded ? 1f : 0.84f);
                mCornerChip.setScaleY(expanded ? 1f : 0.84f);
                mCornerChip.setTranslationY(expanded ? 0f : dpToPx(10));
                return;
            }
            if (expanded) {
                mCornerHandle.animate().cancel();
                mCornerChip.animate().cancel();
                mCornerChip.setVisibility(VISIBLE);
                mCornerChip.setAlpha(0f);
                mCornerChip.setScaleX(0.84f);
                mCornerChip.setScaleY(0.84f);
                mCornerChip.setTranslationY(dpToPx(10));
                mCornerHandle.animate()
                        .alpha(0f)
                        .scaleX(0.88f)
                        .setDuration(120)
                        .withEndAction(() -> {
                            if (mCornerChipExpanded) {
                                mCornerHandle.setVisibility(GONE);
                                mCornerHandle.setScaleX(1f);
                            }
                        })
                        .start();
                mCornerChip.animate()
                        .alpha(mDecorAlpha)
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .setDuration(180)
                        .start();
            } else {
                mCornerHandle.animate().cancel();
                mCornerChip.animate().cancel();
                mCornerHandle.setVisibility(VISIBLE);
                mCornerHandle.setAlpha(0f);
                mCornerHandle.setScaleX(0.88f);
                mCornerHandle.animate()
                        .alpha(mDecorAlpha)
                        .scaleX(1f)
                        .setDuration(120)
                        .start();
                mCornerChip.animate()
                        .alpha(0f)
                        .scaleX(0.84f)
                        .scaleY(0.84f)
                        .translationY(dpToPx(10))
                        .setDuration(140)
                        .withEndAction(() -> {
                            if (!mCornerChipExpanded) {
                                mCornerChip.setVisibility(GONE);
                            }
                        })
                        .start();
            }
            invalidate();
        }

        private void populateCornerChipInfo() {
            CharSequence appLabel = "";
            Drawable appIcon = null;
            if (mTask != null) {
                final PackageManager packageManager = getContext().getPackageManager();
                try {
                    final ActivityRecord topActivity = mTask.getTopNonFinishingActivity();
                    final ComponentName component = topActivity != null && topActivity.info != null
                            ? topActivity.info.getComponentName()
                            : mTask.realActivity;
                    final ApplicationInfo appInfo;
                    if (topActivity != null && topActivity.info != null) {
                        appInfo = topActivity.info.applicationInfo;
                    } else if (component != null) {
                        appInfo = packageManager.getApplicationInfoAsUser(
                                component.getPackageName(), 0, mTask.mUserId);
                    } else {
                        appInfo = null;
                    }
                    if (appInfo != null) {
                        appLabel = appInfo.loadLabel(packageManager);
                        appIcon = appInfo.loadIcon(packageManager);
                    } else if (component != null) {
                        appLabel = component.getPackageName();
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.w(TAG, "Unable to load app chip resources for task " + mTask.mTaskId, e);
                }
            }
            if (TextUtils.isEmpty(appLabel)) {
                appLabel = mTask != null ? "Task " + mTask.mTaskId : "";
            }
            if (appIcon == null) {
                appIcon = getContext().getPackageManager().getDefaultActivityIcon();
            }
            mCornerChipLabel.setText(appLabel);
            mCornerChipIcon.setImageDrawable(appIcon);
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
        if (mDimView != null) {
            mDimView.stopMiniWindowFling();
        }
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
        unionDecorBounds(decoratedBounds, mDimView.mTopBarTouchArea);
        unionDecorBounds(decoratedBounds, mDimView.mCornerHandle);
        unionDecorBounds(decoratedBounds, mDimView.mCornerChip);
        unionDecorBounds(decoratedBounds, mDimView.mResizeHandleBottomLeft);
        unionDecorBounds(decoratedBounds, mDimView.mResizeHandleBottomRight);
        unionDecorBounds(decoratedBounds, mDimView.mResizeHandleTopRight);
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
        if (mDimView == null) {
            return;
        }
        mUiHandler.post(() -> {
            if (mDimView != null) {
                mDimView.setCornerChipExpanded(false, true);
            }
        });
    }

    private int dpToPx(int dp) {
        return (int) (dp * mUiContext.getResources().getDisplayMetrics().density);
    }

    private void unionDecorBounds(Rect outBounds, View view) {
        if (outBounds == null || view == null || view.getVisibility() != View.VISIBLE) {
            return;
        }
        outBounds.union(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
    }

    private void switchToWindowState(int targetState) {
        if (mDimView != null) {
            mDimView.stopMiniWindowFling();
        }
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
