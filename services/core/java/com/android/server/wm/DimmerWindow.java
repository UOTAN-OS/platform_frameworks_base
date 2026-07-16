/*
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * Copyright (C) 2026 The RisingOS Revived Project
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

import static org.rising.DebugConstants.DEBUG_POP_UP;

import android.animation.TimeInterpolator;
import android.app.ActivityThread;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Slog;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.android.internal.R;
import com.android.server.UiThread;

/**
 * Minimal decoration window with thin top bar and menu for Pop-Up View.
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

        private FrameLayout mMenuOverlay;

        private int mTopBarHeight;
        private int mTopBarWidth;
        private int mCornerRadius;

        // Constants
        private static final int BASE_TOP_BAR_HEIGHT_DP = 6;
        private static final int BASE_TOP_BAR_WIDTH_DP = 120;
        private static final int BASE_CORNER_RADIUS_DP = 12;

        private static final float FOCUSED_ALPHA = 1.0f;
        private static final float UNFOCUSED_ALPHA = 0.4f;
        private static final int LONG_PRESS_TIMEOUT_MS = 500;

        private static final int TOUCH_AREA_HEIGHT_DP = 48;
        private static final int TOUCH_AREA_EXTRA_WIDTH_DP = 20;

        private boolean isOrientationChanged = false;


        DimView(Context context, float initialScale) {
            super(context);
            mOldConfig = new Configuration(context.getResources().getConfiguration());
            initUI(initialScale);
        }

        private void initUI(float initialScale) {
            float uiScale = Math.max(0.3f, initialScale);
            mTopBarHeight = (int) (dpToPx(BASE_TOP_BAR_HEIGHT_DP) * uiScale);
            mTopBarWidth = (int) (dpToPx(BASE_TOP_BAR_WIDTH_DP) * uiScale);
            mCornerRadius = (int) (dpToPx(BASE_CORNER_RADIUS_DP) * uiScale);

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

            // Create menu overlay
            mMenuOverlay = new FrameLayout(getContext());
            mMenuOverlay.setVisibility(GONE);

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
            addView(mMenuOverlay, new FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

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

                    // Add Menu Overlay if visible
                    if (mMenuOverlay.getVisibility() == VISIBLE) {
                        region.op(mMenuOverlay.getLeft(), mMenuOverlay.getTop(),
                                mMenuOverlay.getRight(), mMenuOverlay.getBottom(),
                                Region.Op.UNION);
                    }

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
                private boolean hasMoved = false;
                private float moveDistance = 0; // Total moved distance in pixels

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initX = (int) event.getRawX();
                            initY = (int) event.getRawY();
                            lastX = initX; // Initialize last touch position
                            lastY = initY;
                            startBounds = new Rect(mDrawingRect);
                            hasMoved = false;
                            PopUpWindowController.getInstance().triggerVibrate();
                            moveDistance = 0; // Reset move distance
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            DimmerWindow.getInstance().hideMenu();
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
                                hasMoved = true;
                            }

                            if (startBounds != null && hasMoved) {
                                Rect newBounds = new Rect(startBounds);
                                newBounds.offset(lastX - initX, lastY - initY);
                                updateLayout(newBounds);
                                moveTaskSurface(newBounds.centerX(), newBounds.centerY());
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                            if (!hasMoved) {
                                if (DEBUG_POP_UP) {
                                    Slog.d(TAG, "Top bar clicked, showing menu");
                                }
                                PopUpWindowController.getInstance().triggerVibrate();

                                post(() -> {
                                    if (DEBUG_POP_UP) {
                                        Slog.d(TAG, "Executing showCustomMenu on UI thread");
                                    }
                                    showCustomMenu();
                                });
                            }
                            startBounds = null;
                            hasMoved = false;
                            moveDistance = 0; // Reset distance after drag ends
                            return true;

                        case MotionEvent.ACTION_CANCEL:
                            startBounds = null;
                            hasMoved = false;
                            moveDistance = 0; // Reset distance on cancel
                            return true;
                    }
                    return false;
                }
            });
        }

        private static class OutExpoInterpolator implements TimeInterpolator {
            @Override
            public float getInterpolation(float input) {
                // Exponential out: fast at start, slow at end
                // Formula: 1 - 2^(-10 * x)
                return input == 1.0f ? 1.0f : 1.0f - (float) Math.pow(2, -10 * input);
            }
        }

        private void showCustomMenu() {
            mMenuOverlay.removeAllViews();

            // Container for menu + triangle
            LinearLayout menuContainer = new LinearLayout(getContext());
            menuContainer.setOrientation(LinearLayout.VERTICAL);
            menuContainer.setGravity(Gravity.CENTER_HORIZONTAL);

            // Menu items container - HORIZONTAL layout for side-by-side buttons
            LinearLayout menuItems = new LinearLayout(getContext());
            menuItems.setOrientation(LinearLayout.HORIZONTAL);
            menuItems.setGravity(Gravity.CENTER);

            GradientDrawable menuBg = new GradientDrawable();
            menuBg.setColor(0xE0222222);
            menuBg.setCornerRadius(dpToPx(12));
            menuItems.setBackground(menuBg);
            menuItems.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));

            // Force minimum size for menuItems
            menuItems.setMinimumWidth(dpToPx(140));
            menuItems.setMinimumHeight(dpToPx(72));

            // Add menu buttons
            View btnMaximize = createMenuButton("Maximize",
                    com.android.internal.R.drawable.popup_view_maximize, () -> {
                hideCustomMenu();
                PopUpWindowController.getInstance().exitMiniWindowingMode();
            });

            View btnMinimize = createMenuButton("Minimize",
                    com.android.internal.R.drawable.popup_view_minimize, () -> {
                hideCustomMenu();
                moveActivityTaskToBack();
            });

            menuItems.addView(btnMaximize);
            menuItems.addView(btnMinimize);

            // Triangle pointer
            TriangleView triangle = new TriangleView(getContext());
            triangle.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(20), dpToPx(10)));

            // Add to container
            menuContainer.addView(menuItems, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            menuContainer.addView(triangle);

            // Add to overlay with temporary position for measurement
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);

            mMenuOverlay.addView(menuContainer, lp);

            int widthSpec = View.MeasureSpec.makeMeasureSpec(dpToPx(200), View.MeasureSpec.AT_MOST);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(dpToPx(150), View.MeasureSpec.AT_MOST);
            menuContainer.measure(widthSpec, heightSpec);
            menuContainer.layout(0, 0, menuContainer.getMeasuredWidth(), menuContainer.getMeasuredHeight());

            int menuWidth = menuContainer.getMeasuredWidth();
            int menuHeight = menuContainer.getMeasuredHeight();

            // Get bar's center position in DimView coordinates
            int barCenterX = mTopBar.getLeft() + (mTopBar.getWidth() / 2);
            int barTop = mTopBar.getTop();

            // Center menu horizontally on the bar
            lp.leftMargin = barCenterX - (menuWidth / 2);

            // Position above the bar with gap
            lp.topMargin = barTop - menuHeight - dpToPx(8);

            menuContainer.setLayoutParams(lp);

            mMenuOverlay.setVisibility(VISIBLE);

            // ANIMATION: Fade in + Swipe up
            int swipeDistance = dpToPx(20); // Swipe up 20dp

            mMenuOverlay.setAlpha(0f);
            mMenuOverlay.setTranslationY(swipeDistance); // Start below final position

            mMenuOverlay.animate()
                    .alpha(1f)
                    .translationY(0f) // End at final position
                    .setDuration(300)
                    .setInterpolator(new OutExpoInterpolator())
                    .start();

            // Tap outside to dismiss
            mMenuOverlay.setOnClickListener(v -> {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "Menu overlay clicked, hiding");
                }
                hideCustomMenu();
            });

            requestLayout();
        }

        private View createMenuButton(String contentDescription, int drawableRes, Runnable onClick) {
            FrameLayout button = new FrameLayout(getContext());
            button.setClickable(true);
            button.setFocusable(true);

            int buttonSize = dpToPx(56);

            // Explicit layout params for LinearLayout parent
            LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(buttonSize, buttonSize);
            button.setLayoutParams(buttonLp);

            // Ripple effect
            android.content.res.TypedArray ta = getContext().obtainStyledAttributes(
                    new int[]{android.R.attr.selectableItemBackgroundBorderless});
            button.setBackground(ta.getDrawable(0));
            ta.recycle();

            // Icon with explicit size and visibility
            ImageView icon = new ImageView(getContext());
            icon.setContentDescription(contentDescription);
            icon.setImageResource(drawableRes);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setVisibility(View.VISIBLE); // Force visible

            // Get the drawable
            android.graphics.drawable.Drawable drawable = icon.getDrawable();

            // Explicit size for icon
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                    dpToPx(40), dpToPx(40));
            iconLp.gravity = Gravity.CENTER;
            icon.setLayoutParams(iconLp);

            button.addView(icon);

            button.setOnClickListener(v -> {
                PopUpWindowController.getInstance().triggerVibrate();
                onClick.run();
            });
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "Created button: " + contentDescription +
                            ", buttonSize=" + buttonSize +
                            ", layoutParams=" + button.getLayoutParams());
            }
            return button;
        }

        private void hideCustomMenu() {
            if (mMenuOverlay.getVisibility() != VISIBLE) {
                return;
            }

            // ANIMATION: Fade out + Swipe down
            int swipeDistance = dpToPx(20); // Swipe down 20dp

            mMenuOverlay.animate()
                    .alpha(0f)
                    .translationY(swipeDistance) // Move down from current position
                    .setDuration(250)
                    .setInterpolator(new OutExpoInterpolator())
                    .withEndAction(() -> {
                        // Hide and reset after animation completes
                        mMenuOverlay.setVisibility(GONE);
                        mMenuOverlay.removeAllViews();
                        mMenuOverlay.setTranslationY(0f); // Reset translation
                        mMenuOverlay.setOnClickListener(null);
                    })
                    .start();
        }

        // Custom triangle view for pointer
        private class TriangleView extends View {
            private Paint paint;

            public TriangleView(Context context) {
                super(context);
                paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(0xE0222222); // Match menu background
                paint.setStyle(Paint.Style.FILL);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);

                int width = getWidth();
                int height = getHeight();

                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(width / 2f, height); // Bottom point (pointing down)
                path.lineTo(0, 0); // Top left
                path.lineTo(width, 0); // Top right
                path.close();

                canvas.drawPath(path, paint);
            }
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
                            hideCustomMenu();

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
            lpBar.topMargin = taskBounds.top - mTopBarHeight - dpToPx(4);
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

            if (mMenuOverlay.getVisibility() == VISIBLE) {
                hideCustomMenu();
            }

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
            mCornerRadius = (int) (dpToPx(BASE_CORNER_RADIUS_DP) * uiScale);

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

        public void hideMenuIfVisible() {
            if (mMenuOverlay != null && mMenuOverlay.getVisibility() == VISIBLE) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "Hiding menu");
                }
                hideCustomMenu();
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

        // Expand top to include the expanded touch area
        int barVisualTop = mDimView.mDrawingRect.top - barHeight - dpToPx(4); // Visual bar position
        int touchTop = barVisualTop - (touchHeight - barHeight) / 2; // Center touch area on visual
        decoratedBounds.top = touchTop;

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

    public void hideMenu() {
        mUiHandler.post(() -> {
            if (mDimView != null) {
                mDimView.hideMenuIfVisible();
            }
        });
    }

    private int dpToPx(int dp) {
        return (int) (dp * mUiContext.getResources().getDisplayMetrics().density);
    }
}
