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
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.View.SYSTEM_UI_FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.TYPE_MINI_WINDOW_DIMMER;

import static com.android.server.wm.PopUpWindowController.MOVE_TO_BACK_TOUCH_OUTSIDE;

import static com.android.internal.util.android.DebugConstants.DEBUG_POP_UP;

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
import android.os.Looper;
import android.util.Slog;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;

import com.android.server.UiThread;

/**
 * Minimal decoration window with thin drag bar for Pop-Up View.
 */
class DimmerWindow {

    private static final String TAG = "DimmerWindow";
    static final String WIN_TITLE = "MiniWindowDimmer";

    private static final float MIN_SCALE = 0.3f;
    private static final float MAX_SCALE = 1.0f;

    private final Context mUiContext = ActivityThread.currentActivityThread().getSystemUiContext();
    private final Handler mUiHandler = new Handler(UiThread.getHandler().getLooper());
    private final LayoutParams mWindowParams = new LayoutParams();

    private Configuration mOldConfig;
    private DimView mDimView;
    private Task mLastDimmerTask;
    private WindowManager mWindowManager;

    private boolean mIsWindowAdded = false;
    private boolean mShowing = false;

    private float mCurrentScale = 1.0f;

    private int mVibrateThreadhold = 50;

    private static class InstanceHolder {
        private static final DimmerWindow INSTANCE = new DimmerWindow();
    }

    static DimmerWindow getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private DimmerWindow() {
    }

    private class DimView extends FrameLayout {

        final Rect mDrawingRect = new Rect();
        private View mTopBar;
        private View mTopBarTouchArea;

        // Four corner resize handles
        private View mResizeHandleBottomLeft;
        private View mResizeHandleBottomRight;
        private View mResizeHandleTopLeft;
        private View mResizeHandleTopRight;

        private GestureDetector mGestureDetector;

        private int mTopBarHeight;
        private int mTopBarWidth;
        // Constants
        private static final int BASE_TOP_BAR_HEIGHT_DP = 6;
        private static final int BASE_TOP_BAR_WIDTH_DP = 120;

        private static final float FOCUSED_ALPHA = 1.0f;
        private static final float UNFOCUSED_ALPHA = 0.4f;

        private static final int TOUCH_AREA_HEIGHT_DP = 48;
        private static final int TOUCH_AREA_EXTRA_WIDTH_DP = 20;

        private boolean isOrientationChanged = false;
        private boolean mHasMoved = false;


        DimView(Context context, float initialScale) {
            super(context);
            mOldConfig = new Configuration(context.getResources().getConfiguration());
            initUI(initialScale);
        }

        private void initUI(float initialScale) {
            float uiScale = Math.max(0.3f, initialScale);
            mTopBarHeight = (int) (dpToPx(BASE_TOP_BAR_HEIGHT_DP) * uiScale);
            mTopBarWidth = (int) (dpToPx(BASE_TOP_BAR_WIDTH_DP) * uiScale);

            // Create pill-shaped visual top bar (small)
            mTopBar = new View(getContext());
            GradientDrawable topBarDrawable = new GradientDrawable();
            topBarDrawable.setColor(0xFFFFFFFF);
            topBarDrawable.setCornerRadius(mTopBarHeight / 2f);
            mTopBar.setBackground(topBarDrawable);
            mTopBar.setAlpha(UNFOCUSED_ALPHA);

            // Create larger invisible touch area
            mTopBarTouchArea = new View(getContext());
            mTopBarTouchArea.setBackgroundColor(Color.TRANSPARENT);
            // Don't set background - completely invisible

            mGestureDetector = new GestureDetector(getContext(),
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDown(MotionEvent e) {
                            return true;
                        }

                        @Override
                        public void onLongPress(MotionEvent e) {
                            if (!mHasMoved) {
                                moveActivityTaskToBack();
                            }
                        }

                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            if (!mHasMoved) {
                                PopUpWindowController.getInstance().exitMiniWindowingMode();
                                return true;
                            }
                            return false;
                        }
                    });

            // Create 4 Resize Handles (all corners)
            mResizeHandleBottomLeft = new View(getContext());
            mResizeHandleBottomRight = new View(getContext());
            mResizeHandleTopLeft = new View(getContext());
            mResizeHandleTopRight = new View(getContext());

            setupResizeHandle(mResizeHandleBottomLeft, true, false);   // Bottom-left
            setupResizeHandle(mResizeHandleBottomRight, false, false); // Bottom-right
            setupResizeHandle(mResizeHandleTopLeft, true, true);       // Top-left
            setupResizeHandle(mResizeHandleTopRight, false, true);     // Top-right

            // Add views - touch area first (below visual bar in z-order)
            addView(mTopBarTouchArea, new FrameLayout.LayoutParams(
                    dpToPx(BASE_TOP_BAR_WIDTH_DP + TOUCH_AREA_EXTRA_WIDTH_DP * 2),
                    dpToPx(TOUCH_AREA_HEIGHT_DP)));
            addView(mTopBar, new FrameLayout.LayoutParams(mTopBarWidth, mTopBarHeight));

            // Setup touch handling on LARGER touch area
            setupTopBarTouchListener();

            // Update Touch Region
            getViewTreeObserver().addOnComputeInternalInsetsListener(info -> {
                if (mTopBar.getVisibility() == VISIBLE) {
                    Region region = new Region();

                    // Add expanded touch area
                    region.op(mTopBarTouchArea.getLeft(), mTopBarTouchArea.getTop(),
                            mTopBarTouchArea.getRight(), mTopBarTouchArea.getBottom(),
                            Region.Op.UNION);

                    // Add Resize Handles
                    addHandleToRegion(region, mResizeHandleBottomLeft);
                    addHandleToRegion(region, mResizeHandleBottomRight);
                    addHandleToRegion(region, mResizeHandleTopLeft);
                    addHandleToRegion(region, mResizeHandleTopRight);

                    info.touchableRegion.set(region);
                    info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
                } else {
                    info.touchableRegion.setEmpty();
                    info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
                }
            });
        }

        private void setupTopBarTouchListener() {
            mTopBarTouchArea.setOnTouchListener(new OnTouchListener() {
                private int initX, initY; // Initial touch position
                private int lastX, lastY; // Last touch position (to calculate movement)
                private Rect startBounds;
                private float moveDistance = 0; // Total moved distance in pixels

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (mGestureDetector != null) {
                        mGestureDetector.onTouchEvent(event);
                    }
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initX = (int) event.getRawX();
                            initY = (int) event.getRawY();
                            lastX = initX; // Initialize last touch position
                            lastY = initY;
                            startBounds = new Rect(mDrawingRect);
                            mHasMoved = false;
                            PopUpWindowController.getInstance().triggerVibrate();
                            moveDistance = 0; // Reset move distance
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            int dx = (int) event.getRawX() - lastX;
                            int dy = (int) event.getRawY() - lastY;

                            float distance = (float) Math.sqrt(dx * dx + dy * dy); // Calculate real move distance
                            moveDistance += distance; // Accumulate move distance

                            // Vibrate if total moved >= mVibrateThreadhold pixels
                            if (moveDistance >= mVibrateThreadhold) {
                                PopUpWindowController.getInstance().triggerVibrate();
                                moveDistance = 0; // Reset distance after vibration
                            }

                            lastX = (int) event.getRawX(); // Update last position
                            lastY = (int) event.getRawY();

                            if (Math.abs(lastX - initX) > 10 || Math.abs(lastY - initY) > 10) {
                                mHasMoved = true;
                            }

                            if (startBounds != null && mHasMoved) {
                                Rect newBounds = new Rect(startBounds);
                                newBounds.offset(lastX - initX, lastY - initY);
                                updateLayout(newBounds);
                                moveTaskSurface(newBounds.centerX(), newBounds.centerY());
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                            startBounds = null;
                            mHasMoved = false;
                            moveDistance = 0; // Reset distance after drag ends
                            return true;

                        case MotionEvent.ACTION_CANCEL:
                            startBounds = null;
                            mHasMoved = false;
                            moveDistance = 0; // Reset distance on cancel
                            return true;
                    }
                    return false;
                }
            });
        }

        private void addHandleToRegion(Region region, View handle) {
            if (handle.getVisibility() == VISIBLE) {
                region.op(handle.getLeft(), handle.getTop(),
                        handle.getRight(), handle.getBottom(),
                        Region.Op.UNION);
            }
        }

        private void setupResizeHandle(View handle, boolean isLeft, boolean isTop) {
            handle.setBackgroundColor(Color.TRANSPARENT);

            handle.setOnTouchListener(new OnTouchListener() {
                private float initialDistance;
                private float initialScale;
                private int centerX, centerY;
                private float lastTouchX, lastTouchY;
                private float resizeDistance = 0;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            // Get current scale from task
                            if (mLastDimmerTask != null) {
                                TaskWindowSurfaceInfo info = mLastDimmerTask.mWindowContainerExt
                                        .getTaskWindowSurfaceInfo();
                                if (info != null) {
                                    initialScale = info.getWindowSurfaceScale();
                                    mCurrentScale = initialScale;
                                    if (DEBUG_POP_UP) {
                                        Slog.d(TAG, "Resize started (" +
                                                (isTop ? "top" : "bottom") + "-" +
                                                (isLeft ? "left" : "right") +
                                                ") with fresh scale: " + initialScale);
                                    }
                                } else {
                                    initialScale = mCurrentScale;
                                }
                            } else {
                                initialScale = mCurrentScale;
                            }

                            // Store window center
                            centerX = mDrawingRect.centerX();
                            centerY = mDrawingRect.centerY();

                            // Calculate initial distance
                            initialDistance = (float) Math.hypot(
                                    event.getRawX() - centerX,
                                    event.getRawY() - centerY);

                            // Track touch position
                            lastTouchX = event.getRawX();
                            lastTouchY = event.getRawY();
                            resizeDistance = 0;
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            float currentTouchX = event.getRawX();
                            float currentTouchY = event.getRawY();

                            // Calculate touch movement distance for vibration
                            float dx = currentTouchX - lastTouchX;
                            float dy = currentTouchY - lastTouchY;
                            float touchMoveDistance = (float) Math.sqrt(dx * dx + dy * dy);
                            resizeDistance += touchMoveDistance;

                            // Vibrate every mVibrateThreadhold pixels
                            if (resizeDistance >= mVibrateThreadhold) {
                                PopUpWindowController.getInstance().triggerVibrate();
                                resizeDistance = 0;
                            }

                            // Update last touch position
                            lastTouchX = currentTouchX;
                            lastTouchY = currentTouchY;

                            // Calculate scale
                            float currentDistance = (float) Math.hypot(
                                    currentTouchX - centerX,
                                    currentTouchY - centerY);

                            if (initialDistance > 0) {
                                float scaleFactor = currentDistance / initialDistance;
                                float newScale = initialScale * scaleFactor;
                                newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));

                                // Pass corner information to resizeTask
                                resizeTask(newScale, isLeft, isTop);
                                updateBarScale(newScale);
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            resizeDistance = 0;
                            return true;
                    }
                    return false;
                }
            });

            addView(handle, new FrameLayout.LayoutParams(dpToPx(40), dpToPx(40)));
        }

        private void resizeTask(float scale, boolean isLeft, boolean isTop) {
            if (mLastDimmerTask == null) return;
            mLastDimmerTask.mWmService.mH.post(() -> {
                synchronized (mLastDimmerTask.mWmService.mGlobalLock) {
                    if (mLastDimmerTask == null) return;
                    TaskWindowSurfaceInfo info = mLastDimmerTask.mWindowContainerExt
                            .getTaskWindowSurfaceInfo();
                    if (info != null) {
                        Rect displayBounds = new Rect();
                        mLastDimmerTask.getDisplayContent().getBounds(displayBounds);

                        float oldScale = info.getWindowSurfaceScale();
                        float scaleDiff = scale - oldScale;

                        if (Math.abs(scaleDiff) > 0.001f) {
                            Rect bounds = mLastDimmerTask.getBounds();
                            float dW = bounds.width() * scaleDiff;
                            float dH = bounds.height() * scaleDiff;

                            Point currentCenter = info.getWindowCenterPosition();

                            int dx, dy;

                            if (isOrientationChanged) {
                                // Landscape: Scale from center (no shift)
                                dx = 0;
                                dy = 0;
                                if (DEBUG_POP_UP) {
                                    Slog.d(TAG, "Landscape display resize: scaling from center");
                                }
                            } else if (mLastDimmerTask.getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                // LANDSCAPE: Scale from center (no shift)
                                dx = 0;
                                dy = 0;
                                if (DEBUG_POP_UP) {
                                    Slog.d(TAG, "Landscape window resize: scaling from center");
                                }
                            } else {
                                // PORTRAIT: Scale from opposite corner
                                // Each handle anchors to its opposite corner

                                // Horizontal offset: opposite of dragged side
                                // If dragging left handle, anchor right
                                // If dragging right handle, anchor left
                                dx = (int) ((isLeft ? -1 : 1) * (dW / 2.0f));

                                // Vertical offset: opposite of dragged side
                                // If dragging top handle, anchor bottom
                                // If dragging bottom handle, anchor top
                                dy = (int) ((isTop ? -1 : 1) * (dH / 2.0f));
                                if (DEBUG_POP_UP) {
                                    Slog.d(TAG, "Portrait resize: anchor=" +
                                            (isTop ? "bottom" : "top") + "-" +
                                            (isLeft ? "right" : "left") +
                                            ", dx=" + dx + ", dy=" + dy);
                                }
                            }

                            info.setWindowCenterPosition(new Point(
                                    currentCenter.x + dx, currentCenter.y + dy));
                        }

                        info.setWindowSurfaceScaleDrag(scale, displayBounds, isOrientationChanged);

                        android.view.SurfaceControl.Transaction t =
                                mLastDimmerTask.getSyncTransaction();
                        PopUpWindowController.getInstance().onPrepareSurfaces(mLastDimmerTask, t);
                        t.apply();
                    }
                }
            });
        }

        private void moveTaskSurface(int centerX, int centerY) {
            if (mLastDimmerTask == null) return;

            mLastDimmerTask.mWmService.mH.post(() -> {
                synchronized (mLastDimmerTask.mWmService.mGlobalLock) {
                    if (mLastDimmerTask == null) return;
                    TaskWindowSurfaceInfo info = mLastDimmerTask.mWindowContainerExt
                            .getTaskWindowSurfaceInfo();
                    if (info != null) {
                        info.setWindowCenterPosition(new Point(centerX, centerY));

                        android.view.SurfaceControl.Transaction t =
                                mLastDimmerTask.getSyncTransaction();
                        PopUpWindowController.getInstance().onPrepareSurfaces(mLastDimmerTask, t);
                        t.apply();
                    }
                }
            });
        }

        private int dpToPx(int dp) {
            return (int) (dp * getResources().getDisplayMetrics().density);
        }

        void updateLayout(Rect taskBounds) {
            if (taskBounds == null || taskBounds.isEmpty()) return;
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "updateLayout called with bounds: " + taskBounds);
            }
            mDrawingRect.set(taskBounds);

            // Position visual bar
            FrameLayout.LayoutParams lpBar = (FrameLayout.LayoutParams) mTopBar.getLayoutParams();
            lpBar.width = mTopBarWidth;
            lpBar.height = mTopBarHeight;
            lpBar.leftMargin = taskBounds.centerX() - (mTopBarWidth / 2);
            lpBar.topMargin = taskBounds.bottom + dpToPx(4);
            mTopBar.setLayoutParams(lpBar);

            // Position touch area
            int touchHeight = dpToPx(TOUCH_AREA_HEIGHT_DP);
            int touchWidth = mTopBarWidth + dpToPx(TOUCH_AREA_EXTRA_WIDTH_DP * 2);

            FrameLayout.LayoutParams lpTouch = (FrameLayout.LayoutParams) mTopBarTouchArea.getLayoutParams();
            lpTouch.width = touchWidth;
            lpTouch.height = touchHeight;
            lpTouch.leftMargin = taskBounds.centerX() - (touchWidth / 2);
            lpTouch.topMargin = lpBar.topMargin - (touchHeight - mTopBarHeight) / 2;
            mTopBarTouchArea.setLayoutParams(lpTouch);

            // Position ALL FOUR Resize Handles
            int handleSize = dpToPx(40);

            // Bottom-Left Handle
            FrameLayout.LayoutParams lpBottomLeft =
                    (FrameLayout.LayoutParams) mResizeHandleBottomLeft.getLayoutParams();
            lpBottomLeft.leftMargin = taskBounds.left - (handleSize / 2);
            lpBottomLeft.topMargin = taskBounds.bottom - (handleSize / 2);
            mResizeHandleBottomLeft.setLayoutParams(lpBottomLeft);

            // Bottom-Right Handle
            FrameLayout.LayoutParams lpBottomRight =
                    (FrameLayout.LayoutParams) mResizeHandleBottomRight.getLayoutParams();
            lpBottomRight.leftMargin = taskBounds.right - (handleSize / 2);
            lpBottomRight.topMargin = taskBounds.bottom - (handleSize / 2);
            mResizeHandleBottomRight.setLayoutParams(lpBottomRight);

            // Top-Left Handle
            FrameLayout.LayoutParams lpTopLeft =
                    (FrameLayout.LayoutParams) mResizeHandleTopLeft.getLayoutParams();
            lpTopLeft.leftMargin = taskBounds.left - (handleSize / 2);
            lpTopLeft.topMargin = taskBounds.top - (handleSize / 2);
            mResizeHandleTopLeft.setLayoutParams(lpTopLeft);

            // Top-Right Handle
            FrameLayout.LayoutParams lpTopRight =
                    (FrameLayout.LayoutParams) mResizeHandleTopRight.getLayoutParams();
            lpTopRight.leftMargin = taskBounds.right - (handleSize / 2);
            lpTopRight.topMargin = taskBounds.top - (handleSize / 2);
            mResizeHandleTopRight.setLayoutParams(lpTopRight);

            // Set visibility
            mTopBar.setVisibility(VISIBLE);
            mTopBarTouchArea.setVisibility(VISIBLE);
            mResizeHandleBottomLeft.setVisibility(VISIBLE);
            mResizeHandleBottomRight.setVisibility(VISIBLE);
            mResizeHandleTopLeft.setVisibility(VISIBLE);
            mResizeHandleTopRight.setVisibility(VISIBLE);

            // Force views to update
            mTopBar.requestLayout();
            mTopBarTouchArea.requestLayout();
            mResizeHandleBottomLeft.requestLayout();
            mResizeHandleBottomRight.requestLayout();
            mResizeHandleTopLeft.requestLayout();
            mResizeHandleTopRight.requestLayout();

            requestLayout();
        }

        void updateBarScale(float scale) {
            float uiScale = Math.max(0.6f, scale);

            mTopBarHeight = (int) (dpToPx(BASE_TOP_BAR_HEIGHT_DP) * uiScale);
            mTopBarWidth = (int) (dpToPx(BASE_TOP_BAR_WIDTH_DP) * uiScale);

            // Update visual bar
            GradientDrawable topBarDrawable = new GradientDrawable();
            topBarDrawable.setColor(0xFFFFFFFF);
            topBarDrawable.setCornerRadius(mTopBarHeight / 2f);
            mTopBar.setBackground(topBarDrawable);

            float currentAlpha = mTopBar.getAlpha();
            mTopBar.setAlpha(currentAlpha);

            // Update layout dimensions
            FrameLayout.LayoutParams lpBar = (FrameLayout.LayoutParams) mTopBar.getLayoutParams();
            lpBar.width = mTopBarWidth;
            lpBar.height = mTopBarHeight;
            mTopBar.setLayoutParams(lpBar);

            // Update touch area dimensions
            int touchWidth = mTopBarWidth + dpToPx(TOUCH_AREA_EXTRA_WIDTH_DP * 2);
            FrameLayout.LayoutParams lpTouch = (FrameLayout.LayoutParams) mTopBarTouchArea.getLayoutParams();
            lpTouch.width = touchWidth;
            mTopBarTouchArea.setLayoutParams(lpTouch);

            if (mLastDimmerTask != null) {
                TaskWindowSurfaceInfo info = mLastDimmerTask.mWindowContainerExt
                        .getTaskWindowSurfaceInfo();
                if (info != null) {
                    updateLayout(info.getTaskWindowSurfaceBounds());
                }
            }
        }

        void updateTopBarFocus(boolean hasFocus) {
            if (mTopBar != null) {
                mTopBar.animate()
                        .alpha(hasFocus ? FOCUSED_ALPHA : UNFOCUSED_ALPHA)
                        .setDuration(150)
                        .start();
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
            // Force layout update with current task bounds
            if (mLastDimmerTask != null) {
                post(() -> {
                    TaskWindowSurfaceInfo info = mLastDimmerTask.mWindowContainerExt
                            .getTaskWindowSurfaceInfo();
                    if (info != null) {
                        if (DEBUG_POP_UP) {
                            Slog.d(TAG, "Updating layout after orientation change");
                        }
                        // Sync mCurrentScale
                        float taskScale = info.getWindowSurfaceScale();
                        mCurrentScale = taskScale;
                        if (DEBUG_POP_UP) {
                            Slog.d(TAG, "Synced mCurrentScale to: " + mCurrentScale);
                        }
                        updateLayout(info.getTaskWindowSurfaceBounds());

                        // Force touch region recalculation
                        requestLayout();
                        invalidate();

                        isOrientationChanged = !isOrientationChanged;
                    }
                });
            }
        }
    }

    Task getTask() {
        return mLastDimmerTask;
    }

    void setTask(Task task) {
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "setTask: " + (task != null ? task : "null"));
        }
        mLastDimmerTask = task;
        if (task != null && task.mWindowContainerExt.getTaskWindowSurfaceInfo() != null) {
             mCurrentScale = task.mWindowContainerExt.getTaskWindowSurfaceInfo()
                     .getWindowSurfaceScale();
        }
        updateWindowState(task != null);
    }

    RectF getEdgeBarBounds() {
        return new RectF();
    }

    void moveActivityTaskToBack() {
        if (mLastDimmerTask == null) {
            return;
        }
        PopUpWindowController.getInstance().moveActivityTaskToBack(
                mLastDimmerTask, MOVE_TO_BACK_TOUCH_OUTSIDE);
    }

    private void updateWindowState(boolean show) {
        mUiHandler.post(() -> {
            if (show && mLastDimmerTask != null) {
                try {
                    TaskWindowSurfaceInfo info = mLastDimmerTask.mWindowContainerExt
                            .getTaskWindowSurfaceInfo();
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
        if (mDimView != null && mLastDimmerTask != null) {
             try {
                 TaskWindowSurfaceInfo info = mLastDimmerTask.mWindowContainerExt
                         .getTaskWindowSurfaceInfo();
                 if (info != null) {
                     mDimView.updateLayout(info.getTaskWindowSurfaceBounds());
                 }
             } catch (Exception e) {}
        }
    }

    private void addDimmerWin() {
        if (getWindowManager() != null) {
            mWindowParams.type = TYPE_MINI_WINDOW_DIMMER;
            mWindowParams.format = TRANSLUCENT;
            mWindowParams.flags = LayoutParams.FLAG_NOT_TOUCH_MODAL |
                                  LayoutParams.FLAG_NOT_FOCUSABLE |
                                  LayoutParams.FLAG_ALT_FOCUSABLE_IM |
                                  LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
                                  LayoutParams.FLAG_FULLSCREEN;
            mWindowParams.privateFlags = LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS |
                                        LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
            mWindowParams.layoutInDisplayCutoutMode =
                    LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            mWindowParams.setFitInsetsTypes(0);
            mWindowParams.dimAmount = 0;
            mWindowParams.gravity = Gravity.LEFT | Gravity.TOP;
            mWindowParams.x = 0;
            mWindowParams.y = 0;
            mWindowParams.setTitle(WIN_TITLE);
            mWindowParams.width = LayoutParams.MATCH_PARENT;
            mWindowParams.height = LayoutParams.MATCH_PARENT;
            mWindowParams.windowAnimations = 0;

            mDimView = new DimView(
                    mUiContext.createWindowContext(TYPE_MINI_WINDOW_DIMMER, null), mCurrentScale);
            mDimView.setAlpha(1.0f);

            if (mLastDimmerTask != null) {
                 try {
                     TaskWindowSurfaceInfo info = mLastDimmerTask.mWindowContainerExt
                             .getTaskWindowSurfaceInfo();
                     if (info != null) {
                         mDimView.updateLayout(info.getTaskWindowSurfaceBounds());
                     }
                 } catch (Exception e) {}
            }

            mWindowManager.addView(mDimView, mWindowParams);
            mIsWindowAdded = true;
            mShowing = true;
            mDimView.setSystemUiVisibility(SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    private void updateDimmerWin(boolean show) {
        if (getWindowManager() != null && mDimView != null && mShowing != show) {
            if (show) {
                if (mLastDimmerTask != null) {
                     try {
                         TaskWindowSurfaceInfo info = mLastDimmerTask.mWindowContainerExt
                                 .getTaskWindowSurfaceInfo();
                         if (info != null) {
                             mDimView.updateLayout(info.getTaskWindowSurfaceBounds());
                         }
                     } catch (Exception e) {}
                }
                mWindowParams.flags &= ~LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                mWindowParams.flags |= LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            mWindowManager.updateViewLayout(mDimView, mWindowParams);
            mDimView.setVisibility(show ? View.VISIBLE : View.GONE);
            mShowing = show;
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "updateDimmerWin: show=" + show);
            }
        }
    }

    private WindowManager getWindowManager() {
        if (mWindowManager == null) {
            mWindowManager = mUiContext.getSystemService(WindowManager.class);
        }
        return mWindowManager;
    }

    /**
    * Get the full bounds of the DimmerWindow including expanded touch area and resize handles.
    */
    public Rect getBounds() {
        if (mDimView == null || mDimView.mDrawingRect.isEmpty()) {
            return null;
        }

        // Include expanded touch area for focus tracking
        final Rect decoratedBounds = new Rect(mDimView.mDrawingRect);

        // Get current bar dimensions (they scale with window)
        int barWidth = mDimView.mTopBarWidth;
        int barHeight = mDimView.mTopBarHeight;

        // Touch area dimensions (larger)
        int touchHeight = dpToPx(DimView.TOUCH_AREA_HEIGHT_DP);
        int extraWidth = dpToPx(DimView.TOUCH_AREA_EXTRA_WIDTH_DP);

        // Expand bottom to include the expanded touch area
        int barVisualTop = mDimView.mDrawingRect.bottom + dpToPx(4); // Visual bar position
        int touchTop = barVisualTop - (touchHeight - barHeight) / 2; // Center touch area on visual
        int touchBottom = touchTop + touchHeight;
        decoratedBounds.bottom = Math.max(decoratedBounds.bottom, touchBottom);

        // Expand left/right to include extra width
        int taskCenterX = mDimView.mDrawingRect.centerX();
        int touchLeft = taskCenterX - (barWidth / 2) - extraWidth;
        int touchRight = taskCenterX + (barWidth / 2) + extraWidth;

        decoratedBounds.left = Math.min(decoratedBounds.left, touchLeft);
        decoratedBounds.right = Math.max(decoratedBounds.right, touchRight);

        // Add 4 resize handles
        int handleSize = dpToPx(40);
        int handleRadius = handleSize / 2;

        Rect taskBounds = mDimView.mDrawingRect;

        // Bottom-Left Handle
        int bottomLeftHandleLeft = taskBounds.left - handleRadius;
        int bottomLeftHandleTop = taskBounds.bottom - handleRadius;
        int bottomLeftHandleRight = taskBounds.left + handleRadius;
        int bottomLeftHandleBottom = taskBounds.bottom + handleRadius;

        // Bottom-Right Handle
        int bottomRightHandleLeft = taskBounds.right - handleRadius;
        int bottomRightHandleTop = taskBounds.bottom - handleRadius;
        int bottomRightHandleRight = taskBounds.right + handleRadius;
        int bottomRightHandleBottom = taskBounds.bottom + handleRadius;

        // Top-Left Handle
        int topLeftHandleLeft = taskBounds.left - handleRadius;
        int topLeftHandleTop = taskBounds.top - handleRadius;
        int topLeftHandleRight = taskBounds.left + handleRadius;
        int topLeftHandleBottom = taskBounds.top + handleRadius;

        // Top-Right Handle
        int topRightHandleLeft = taskBounds.right - handleRadius;
        int topRightHandleTop = taskBounds.top - handleRadius;
        int topRightHandleRight = taskBounds.right + handleRadius;
        int topRightHandleBottom = taskBounds.top + handleRadius;

        // Expand bounds to include all handles
        decoratedBounds.left = Math.min(decoratedBounds.left,
                Math.min(bottomLeftHandleLeft, topLeftHandleLeft));
        decoratedBounds.right = Math.max(decoratedBounds.right,
                Math.max(bottomRightHandleRight, topRightHandleRight));
        decoratedBounds.top = Math.min(decoratedBounds.top,
                Math.min(topLeftHandleTop, topRightHandleTop));
        decoratedBounds.bottom = Math.max(decoratedBounds.bottom,
                Math.max(bottomLeftHandleBottom, bottomRightHandleBottom));

        return decoratedBounds;
    }

    public void notifyFocusChanged() {
        if (mDimView != null) {
            boolean hasFocus = PopUpWindowController.getInstance().shouldMiniWindowHandleInput();
            mDimView.updateTopBarFocus(hasFocus);
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * mUiContext.getResources().getDisplayMetrics().density);
    }
}
