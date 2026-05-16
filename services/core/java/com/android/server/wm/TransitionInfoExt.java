/*
 * Copyright (C) 2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.internal.util.android.DebugConstants.DEBUG_POP_UP;

import android.app.WindowConfiguration;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.InsetsState;
import android.view.WindowInsets;

import com.android.server.wm.Transition.ChangeInfo.PopUpViewInfo;

public class TransitionInfoExt {

    private static final String TAG = "TransitionInfoExt";

    private final PopUpViewInfo mPopUpViewInfo = new PopUpViewInfo();

    private float getPositionAndScaleFormInfo(TaskWindowSurfaceInfo info, Point out) {
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "getPositionAndScaleFormInfo, info=" + info);
        }
        out.set(0, 0);
        final Task task = info.mTask;
        final int windowingMode = info.mFreezedWindowingMode != WINDOWING_MODE_UNDEFINED
                ? info.mFreezedWindowingMode
                : task.getConfiguration().windowConfiguration.getWindowingMode();

        if (WindowConfiguration.isPopUpWindowMode(windowingMode)) {
            final Rect displayBound = new Rect();
            if (task.mDisplayContent != null) {
                task.mDisplayContent.getBounds(displayBound);
            }
            final Rect bound = task.getBounds();
            info.setWindowSurfaceScaleFactor(WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                    bound, displayBound, info.getWindowCenterPosition(), info.getWindowSurfaceScale(),
                    false, out));
            return info.getWindowSurfaceRealScale();
        }
        return 1.0f;
    }

    private float getCornerRadiusFromInfo(TaskWindowSurfaceInfo info) {
        final int windowingMode = info.mFreezedWindowingMode != 0 ? info.mFreezedWindowingMode
                : info.mTask.getConfiguration().windowConfiguration.getWindowingMode();
        if (WindowConfiguration.isPopUpWindowMode(windowingMode)) {
            return info.getCornerRadius();
        }
        return 0.0f;
    }

    PopUpViewInfo getPopUpViewInfo() {
        return mPopUpViewInfo;
    }

    void setupPopUpViewInfo(TaskWindowSurfaceInfo freezeInfo, TaskWindowSurfaceInfo info, DisplayInfo displayInfo) {
        mPopUpViewInfo.mEndScale = getPositionAndScaleFormInfo(info, mPopUpViewInfo.mEndPos);
        mPopUpViewInfo.mEndCornerRadius = getCornerRadiusFromInfo(info);
        mPopUpViewInfo.mAppBounds.set(0, 0, displayInfo.appWidth, displayInfo.appHeight);
        mPopUpViewInfo.mStartScale = getPositionAndScaleFormInfo(freezeInfo, mPopUpViewInfo.mStartPos);
        mPopUpViewInfo.mStartCornerRadius = getCornerRadiusFromInfo(freezeInfo);
        if (info.mTask != null) {
            info.mTask.getBounds(mPopUpViewInfo.mWindowCrop);

            try {
                java.lang.reflect.Field freezerField = info.mTask.getClass().getDeclaredField("mSurfaceFreezer");
                freezerField.setAccessible(true);
                Object surfaceFreezer = freezerField.get(info.mTask);

                if (surfaceFreezer != null) {
                    java.lang.reflect.Field boundsField = surfaceFreezer.getClass().getDeclaredField("mFreezeBounds");
                    boundsField.setAccessible(true);
                    Rect freezeBounds = (Rect) boundsField.get(surfaceFreezer);

                    if (freezeBounds != null) {
                        mPopUpViewInfo.mStartDragBounds.set(freezeBounds);
                    } else {
                        info.mTask.getBounds(mPopUpViewInfo.mStartDragBounds);
                    }
                } else {
                    info.mTask.getBounds(mPopUpViewInfo.mStartDragBounds);
                }
            } catch (Exception e) {
                if (DEBUG_POP_UP) {
                    Slog.w(TAG, "Unable to access surface freezer bounds, using task bounds as fallback", e);
                }
                info.mTask.getBounds(mPopUpViewInfo.mStartDragBounds);
            }
        }
        if (info.mTask != null
                && PopUpWindowController.getInstance().hasPendingPopUpViewLaunchPoint()
                && isPopUpWindowModeChange(freezeInfo, info)) {
            overrideStartStateForGestureLaunch(info, displayInfo);
        }
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "setupPopUpViewInfo, info=" + mPopUpViewInfo);
        }
    }

    private boolean isPopUpWindowModeChange(TaskWindowSurfaceInfo freezeInfo,
            TaskWindowSurfaceInfo info) {
        final int startWindowingMode = freezeInfo.mFreezedWindowingMode != WINDOWING_MODE_UNDEFINED
                ? freezeInfo.mFreezedWindowingMode
                : freezeInfo.mTask.getConfiguration().windowConfiguration.getWindowingMode();
        final int endWindowingMode = info.mFreezedWindowingMode != WINDOWING_MODE_UNDEFINED
                ? info.mFreezedWindowingMode
                : info.mTask.getConfiguration().windowConfiguration.getWindowingMode();
        return PopUpWindowController.getInstance().shouldStartChangeTransition(
                startWindowingMode, endWindowingMode);
    }

    private void overrideStartStateForGestureLaunch(TaskWindowSurfaceInfo info, DisplayInfo displayInfo) {
        final Task task = info.mTask;
        if (task == null) {
            return;
        }
        final Rect endBounds = new Rect();
        task.getBounds(endBounds);
        if (endBounds.isEmpty()) {
            return;
        }
        final Rect displayBounds = new Rect(0, 0, displayInfo.appWidth, displayInfo.appHeight);
        final float progress = Math.max(0f, Math.min(1f,
                PopUpWindowController.getInstance().getNextPopUpViewLaunchProgress()));
        final int sourceWidth = Math.max(endBounds.width(),
                Math.round(lerp(displayBounds.width(), endBounds.width(), progress)));
        final int sourceHeight = Math.round(
                sourceWidth * (endBounds.height() / (float) endBounds.width()));
        final int centerX = PopUpWindowController.getInstance().getNextPopUpViewLaunchPointX();
        final int bottomY = PopUpWindowController.getInstance().getNextPopUpViewLaunchPointY();
        final int clampedLeft = Math.max(displayBounds.left,
                Math.min(displayBounds.right - sourceWidth, centerX - (sourceWidth / 2)));
        final int clampedBottom = Math.max(displayBounds.top + sourceHeight / 2,
                Math.min(displayBounds.bottom, bottomY));
        final Rect startBounds = mPopUpViewInfo.mStartDragBounds;
        startBounds.set(clampedLeft, clampedBottom - sourceHeight,
                clampedLeft + sourceWidth, clampedBottom);
        mPopUpViewInfo.mStartPos.set(startBounds.left, startBounds.top);
        mPopUpViewInfo.mStartScale = sourceWidth / (float) endBounds.width();
        mPopUpViewInfo.mStartCornerRadius = mPopUpViewInfo.mEndCornerRadius;
    }

    private static float lerp(float start, float end, float progress) {
        return start + ((end - start) * progress);
    }
}
