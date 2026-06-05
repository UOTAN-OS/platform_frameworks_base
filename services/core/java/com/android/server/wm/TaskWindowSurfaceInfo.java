/*
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.server.wm.WindowResizingAlgorithm.BOUNDARY_GAP;

import static com.android.internal.util.android.DebugConstants.DEBUG_POP_UP;

import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Slog;
import android.util.TypedValue;
import android.view.DisplayInfo;
import android.view.IWindow;
import android.view.InsetsState;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowInsets;

import com.android.server.wm.Transition.ChangeInfo.PopUpViewInfo;

import java.util.Collections;
import java.util.List;

class TaskWindowSurfaceInfo {

    private static final String TAG = "TaskWindowSurfaceInfo";

    private static final int DENSITY_DEFAULT = 420;
    private static final float MINI_WINDOW_CORNER_RADIUS_DP = 28f;

    private final List<String> mForceUpdateDpiList;

    private final PopUpAnimationController mPopUpAnimationController;

    private final Configuration mConfiguration = new Configuration();
    private final TransitionInfoExt mTransitionInfoExt = new TransitionInfoExt();
    private final Point mWindowCenterPosition;
    private final Rect mWindowBoundaryGap;

    private float mCornerRadius;
    private float mMiniWindowCornerRadius;

    private boolean mMute = false;
    private boolean mIsExiting = false;

    private float mWindowSurfaceScale;
    private float mWindowSurfaceScaleFactor;

    final WindowManagerService mService;
    final Task mTask;

    int mFreezedWindowingMode = WINDOWING_MODE_UNDEFINED;

    TaskWindowSurfaceInfo(Task task) {
        mTask = task;
        mService = task.mWmService;

        mConfiguration.setTo(mTask.getConfiguration());

        mForceUpdateDpiList = Collections.emptyList();

        mWindowCenterPosition = new Point();
        setWindowSurfaceScale(1.0f);
        mWindowSurfaceScaleFactor = 1.0f;
        mWindowBoundaryGap = new Rect(0, BOUNDARY_GAP, BOUNDARY_GAP, 0);

        mMiniWindowCornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                MINI_WINDOW_CORNER_RADIUS_DP,
                mService.mContext.getResources().getDisplayMetrics());
        mCornerRadius = mMiniWindowCornerRadius;

        mPopUpAnimationController = new PopUpAnimationController(mService);
        mPopUpAnimationController.setTask(task);
    }

    private boolean ownsPopUpSurface() {
        return mTask != null && mTask.isRootTask();
    }

    TaskWindowSurfaceInfo(TaskWindowSurfaceInfo other, int preFreezedWindowingMode) {
        mTask = other.mTask;
        mService = other.mService;

        mFreezedWindowingMode = preFreezedWindowingMode;
        mConfiguration.setTo(mTask.getConfiguration());
        mMute = other.getMute();

        mForceUpdateDpiList = Collections.emptyList();

        mWindowCenterPosition = other.getWindowCenterPosition();
        mWindowSurfaceScale = other.getWindowSurfaceScale();
        mWindowSurfaceScaleFactor = other.getWindowSurfaceScaleFactor();
        mWindowBoundaryGap = other.getWindowBoundaryGap();

        mCornerRadius = other.getCornerRadius();

        mPopUpAnimationController = new PopUpAnimationController(mService);
        mPopUpAnimationController.setTask(mTask);
    }

    void toggleMute() {
        mMute = !mMute;
    }

    boolean getMute() {
        return mMute;
    }

    void setWindowCenterPosition(Point pos) {
        mWindowCenterPosition.set(pos.x, pos.y);
    }

    Point getWindowCenterPosition() {
        return new Point(mWindowCenterPosition.x, mWindowCenterPosition.y);
    }

    PopUpViewInfo getPopUpViewInfo() {
        return mTransitionInfoExt.getPopUpViewInfo();
    }

    void setWindowSurfaceScaleDrag(float scale, Rect displayBound, boolean isLandscape) {
        if (mWindowSurfaceScale != scale) {
            mWindowSurfaceScale = scale;
            if (ownsPopUpSurface()) {
                DimmerWindowManager.getInstance().onDragResizeChanged(mTask, scale,
                        getTaskWindowSurfaceBoundsOnDrag(displayBound), isLandscape);
            }
        }
    }

    void setWindowSurfaceScale(float scale) {
        if (mWindowSurfaceScale != scale) {
            mWindowSurfaceScale = scale;
            if (ownsPopUpSurface()) {
                DimmerWindowManager.getInstance().onResizeChanged(mTask);
            }
        }
    }

    float getWindowSurfaceScale() {
        return mWindowSurfaceScale;
    }

    float getWindowSurfaceRealScale() {
        return mWindowSurfaceScale * mWindowSurfaceScaleFactor;
    }

    float getWindowSurfaceRealScale(float scale) {
        return scale * mWindowSurfaceScaleFactor;
    }

    void setWindowSurfaceScaleFactor(float factor) {
        mWindowSurfaceScaleFactor = factor;
    }

    float getWindowSurfaceScaleFactor() {
        return mWindowSurfaceScaleFactor;
    }

    void resetWindowBoundaryGap() {
        mWindowBoundaryGap.setEmpty();
    }

    float getCornerRadius() {
        return mCornerRadius;
    }

    void resetWindowBoundaryGapToOrigin() {
        mWindowBoundaryGap.set(0, BOUNDARY_GAP, BOUNDARY_GAP, 0);
    }

    void setWindowBoundaryGap(int left, int top, int right, int bottom) {
        if (left > 0) {
            mWindowBoundaryGap.left = left;
        }
        if (top > 0) {
            mWindowBoundaryGap.top = top;
        }
        if (right > 0) {
            mWindowBoundaryGap.right = right;
        }
        if (bottom > 0) {
            mWindowBoundaryGap.bottom = bottom;
        }
    }

    Rect getWindowBoundaryGap() {
        return new Rect(mWindowBoundaryGap.left, mWindowBoundaryGap.top,
                mWindowBoundaryGap.right, mWindowBoundaryGap.bottom);
    }

    Rect getTaskWindowSurfaceBounds() {
        int windowingMode = mFreezedWindowingMode;
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            windowingMode = mTask.getConfiguration().windowConfiguration.getWindowingMode();
        }
        final Rect result = new Rect();
        if (WindowConfiguration.isPopUpWindowMode(windowingMode)) {
            final Rect displayBound = new Rect();
            if (mTask.mDisplayContent != null) {
                mTask.mDisplayContent.getBounds(displayBound);
            }
            final Point pos = new Point();
            final Rect bound = mTask.getBounds();
            mWindowSurfaceScaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                    bound, displayBound, mWindowCenterPosition, mWindowSurfaceScale,
                    false, pos);
            result.set(0, 0, bound.width(), bound.height());
            result.scale(getWindowSurfaceRealScale());
            result.offsetTo(pos.x, pos.y);
        }
        return result;
    }

    Rect getTaskWindowSurfaceBoundsOnDrag(Rect displayBound) {
        final Rect result = new Rect();
        final Point pos = new Point();
        final Rect bound = mTask.getBounds();
        mWindowSurfaceScaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                bound, displayBound, mWindowCenterPosition, mWindowSurfaceScale,
                false, pos);
        result.set(0, 0, bound.width(), bound.height());
        result.scale(getWindowSurfaceRealScale());
        result.offsetTo(pos.x, pos.y);
        return result;
    }

    void onWindowingModeChanged(int preWindowMode) {
        final WindowConfiguration winConfig = mTask.getConfiguration().windowConfiguration;
        final boolean isMiniWindow = winConfig.isMiniExtWindowMode();
        final boolean isPopUpWindow = winConfig.isPopUpWindowMode();
        final boolean isPrevMiniWindow = WindowConfiguration.isMiniExtWindowMode(preWindowMode);
        final boolean isPrevPopUpWindow = WindowConfiguration.isPopUpWindowMode(preWindowMode);
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "onWindowingModeChanged " + preWindowMode + "->"
                    + winConfig.getWindowingMode() + " mTask=" + mTask);
        }
        if (!isPopUpWindow && isPrevPopUpWindow) {
            mIsExiting = false;
            final IWindow window = getIWindow();
            if (window != null) {
                finishTaskPositioning(window);
            }
            if (cancelPopUpViewAnimation()) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "cancel PopUpViewAnimation when exit PopupView. mTask=" + mTask);
                }
            }
        }
        if (isMiniWindow) {
            final Rect displayBound = new Rect();
            if (mTask.mDisplayContent != null) {
                mTask.mDisplayContent.getBounds(displayBound);
                final Point pos = new Point(displayBound.width() / 2, displayBound.height() / 2);
                setWindowSurfaceScale(WindowResizingAlgorithm.getDefaultMiniWindowScale(
                        mTask.getConfiguration().orientation, mTask.mDisplayContent.getRotation()));
                setWindowCenterPosition(pos);
            }
            mCornerRadius = mMiniWindowCornerRadius;
            if (ownsPopUpSurface() && !isPrevMiniWindow) {
                DimmerWindowManager.getInstance().attachTask(mTask);
            }
        } else if (ownsPopUpSurface() && isPrevMiniWindow) {
            DimmerWindowManager.getInstance().detachTask(mTask);
        }
        if (isPopUpWindow || isPrevPopUpWindow) {
            final SurfaceControl surfaceControl = mTask.getSurfaceControl();
            if (surfaceControl != null && surfaceControl.isValid()) {
                mService.mTransactionFactory.get().setTrustedOverlay(surfaceControl, isPopUpWindow).apply();
            }
            updateDensityIfNeed(isPrevPopUpWindow && !isPopUpWindow);
        }
    }

    private void updateDensityIfNeed(boolean isExitPopUpView) {
        if (mTask.getWindowConfiguration().isPopUpWindowMode()) {
            final int initDensity = mTask.mDisplayContent == null ?
                    DENSITY_DEFAULT : mTask.mDisplayContent.mInitialDisplayDensity;
            if (mTask.getConfiguration().densityDpi < initDensity) {
                final ActivityRecord topActivity = mTask.topRunningActivityLocked();
                if (topActivity == null || !mForceUpdateDpiList.contains(topActivity.packageName)) {
                    return;
                }
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "force update density for " + mTask +
                            " densityDpi=" + mTask.getConfiguration().densityDpi);
                }
                mTask.getRequestedOverrideConfiguration().densityDpi = initDensity;
                mTask.onRequestedOverrideConfigurationChanged(mTask.getRequestedOverrideConfiguration());
            }
        } else if (isExitPopUpView && mTask.getRequestedOverrideConfiguration().densityDpi != 0) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "reset density for " + mTask +
                        " densityDpi=" + mTask.getRequestedOverrideConfiguration().densityDpi);
            }
            mTask.getRequestedOverrideConfiguration().densityDpi = 0;
            mTask.onRequestedOverrideConfigurationChanged(mTask.getRequestedOverrideConfiguration());
        }
    }

    void onRotationChanged() {
        if (mTask.getConfiguration().windowConfiguration.isMiniExtWindowMode()
                && mTask.mDisplayContent != null) {
            final Rect displayBound = new Rect();
            mTask.mDisplayContent.getBounds(displayBound);
            final Point pos = new Point(displayBound.width() / 2, displayBound.height() / 2);
            setWindowSurfaceScale(WindowResizingAlgorithm.getDefaultMiniWindowScale(
                    mTask.getConfiguration().orientation, mTask.mDisplayContent.getRotation()));
            setWindowCenterPosition(pos);
        }
    }

    void onConfigurationChanged() {
        final Configuration newConfig = mTask.getConfiguration();
        final long diff = mConfiguration.windowConfiguration.diff(newConfig.windowConfiguration, false);
        if ((WindowConfiguration.WINDOW_CONFIG_BOUNDS & diff) != 0
                || (WindowConfiguration.WINDOW_CONFIG_ROTATION & diff) != 0) {
            if (mTask.getWindowConfiguration().isMiniExtWindowMode()
                    && mTask.mDisplayContent != null) {
                setWindowSurfaceScale(WindowResizingAlgorithm.getDefaultMiniWindowScale(
                        mTask.getConfiguration().orientation, mTask.mDisplayContent.getRotation()));
            }
            if (ownsPopUpSurface()) {
                DimmerWindowManager.getInstance().onResizeChanged(mTask);
            }
        }
        if ((mConfiguration.diff(newConfig) & ActivityInfo.CONFIG_DENSITY) != 0) {
            updateDensityIfNeed(false);
        }
        mConfiguration.setTo(newConfig);
    }

    void onPrepareSurfaces(SurfaceControl.Transaction t) {
        final WindowConfiguration winConfig = mTask.getConfiguration().windowConfiguration;
        final boolean hasAnimationLeash = hasTaskSurfaceAnimationLeash() ||
                mPopUpAnimationController.isAnimating() ||
                mTask.mTransitionController.isPlaying();
        if (ownsPopUpSurface() && winConfig.isPopUpWindowMode() && !hasAnimationLeash &&
                !isWindowPositioningLocked() && !mIsExiting) {
            final Rect displayBound = new Rect();
            if (mTask.mDisplayContent != null) {
                mTask.mDisplayContent.getBounds(displayBound);
            }
            final Point pos = new Point();
            final Rect bound = mTask.getBounds();
            mWindowSurfaceScaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                    bound, displayBound, mWindowCenterPosition, mWindowSurfaceScale,
                    false, pos);
            t.setPosition(mTask.mSurfaceControl, pos.x, pos.y)
                    .setAlpha(mTask.mSurfaceControl, 1.0f)
                    .setWindowCrop(mTask.mSurfaceControl, bound.width(), bound.height())
                    .setCornerRadius(mTask.mSurfaceControl, mCornerRadius)
                    .setScale(mTask.mSurfaceControl, getWindowSurfaceRealScale(), getWindowSurfaceRealScale());
            t.show(mTask.mSurfaceControl);

        }
    }

    void scheduleTransition(TaskWindowSurfaceInfo freezeTaskWindowSurfaceInfo, DisplayInfo displayInfo) {
        mTransitionInfoExt.setupPopUpViewInfo(freezeTaskWindowSurfaceInfo, this, displayInfo);
    }

    boolean isCrossOverAnimating() {
        return mPopUpAnimationController.isCrossOverAnimating();
    }

    void playExitAnimation(boolean isFromLeaveButton, float startScale,
            PopUpAnimationController.OnAnimationEndCallback callback) {
        mIsExiting = true;
        mPopUpAnimationController.playExitAnimation(
                isFromLeaveButton, startScale, callback);
    }

    void resizeWindowWithAnimation(Point startPos, Point endPos, int boundWidth,
            int boundHeight, float startWinScale, float endWinScale,
            Rect displayBound, boolean isLandscape) {
        mPopUpAnimationController.playResizeAnimation(startPos, endPos,
                boundWidth, boundHeight, startWinScale, endWinScale,
                displayBound, isLandscape, this);
    }

    void playToggleResizeWindowAnimation(Point startPos, Point endPos, float startWinScale,
            float endWinScale, PopUpAnimationController.OnAnimationEndCallback callback) {
        mPopUpAnimationController.playToggleResizeWindowAnimation(
                startPos, endPos, startWinScale, endWinScale, callback);
    }

    void flingWindowToEdge(Point startPos, Point endPos, int boundWidth, int boundHeight,
            float winScale, float velX, float velY) {
        mPopUpAnimationController.playSpringAnimation(
                startPos, endPos, boundWidth, boundHeight, winScale, velX, velY);
    }

    boolean cancelPopUpViewAnimation() {
        return mPopUpAnimationController.cancelAnimation();
    }

    private void finishTaskPositioning(IWindow window) {
        try {
            if (mService != null && window != null) {
                synchronized (mService.mGlobalLock) {
                }
            }
        } catch (Exception e) {
            if (DEBUG_POP_UP) {
                Slog.w(TAG, "Failed to finish task positioning", e);
            }
        }
    }

    private boolean isWindowPositioningLocked() {
        try {
            synchronized (mService.mGlobalLock) {
                return false;
            }
        } catch (Exception e) {
            if (DEBUG_POP_UP) {
                Slog.w(TAG, "Failed to check window positioning lock state", e);
            }
            return false;
        }
    }

    private boolean hasTaskSurfaceAnimationLeash() {
        try {
            if (mTask.mSurfaceAnimator != null) {
                return mTask.mSurfaceAnimator.hasLeash();
            }
            return false;
        } catch (Exception e) {
            if (DEBUG_POP_UP) {
                Slog.w(TAG, "Failed to check animation leash state", e);
            }
            return false;
        }
    }

    private IWindow getIWindow() {
        synchronized (mService.mAtmService.mGlobalLock) {
            if (mTask != null && mTask.getTopVisibleAppMainWindow() != null) {
                final IWindow iWindow = mTask.getTopVisibleAppMainWindow().getIWindow();
                return iWindow;
            }
            return null;
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(1024);
        sb.append("Task=");
        sb.append(mTask);
        sb.append(" {mTask.windowingMode=");
        sb.append(mTask.getConfiguration().windowConfiguration.getWindowingMode());
        sb.append("}");
        sb.append(" {mFreezedWindowingMode=");
        sb.append(mFreezedWindowingMode);
        sb.append(" mMute=");
        sb.append(mMute);
        sb.append(" mWindowCenterPosition=");
        sb.append(mWindowCenterPosition);
        sb.append(" mWindowSurfaceScale=");
        sb.append(mWindowSurfaceScale);
        sb.append(" mWindowSurfaceScaleFactor=");
        sb.append(mWindowSurfaceScaleFactor);
        sb.append(" mWindowBoundaryGap=");
        sb.append(mWindowBoundaryGap);
        sb.append(" mCornerRadius=");
        sb.append(mCornerRadius);
        sb.append("}");
        return sb.toString();
    }
}
