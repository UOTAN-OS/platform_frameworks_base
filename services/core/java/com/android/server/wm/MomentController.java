/*
 * Copyright (C) 2026 The uwuAOSP Project
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

package com.android.server.wm;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MOMENT;

import static com.android.server.wm.MomentGeometry.COMPACT_DISMISS_TARGET_SIZE_DP;
import static com.android.server.wm.MomentGeometry.HANDLE_AREA_HEIGHT_DP;
import static com.android.server.wm.MomentGeometry.HANDLE_MENU_TOP_INSET_DP;
import static com.android.server.wm.MomentGeometry.HANDLE_MENU_WIDTH_DP;

import android.app.ActivityOptions;
import android.app.ActivityThread;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import java.io.PrintWriter;
import java.util.ArrayList;

final class MomentController {

    private static final String TAG = "Moment";
    private static final String DEBUG_PROPERTY = "persist.debug.wm.moment";

    private static final String SYSTEM_PACKAGE_NAME = "android";
    private static final float DEFAULT_SCALE = 0.55f;
    private static final float MIN_SCALE = 0.25f;
    private static final float MAX_SCALE = 1.0f;
    private static final float COMPACT_EPSILON = 0.001f;
    private static final float COMPACT_DISMISS_SCALE = 0.85f;
    private static final int MOVEMENT_SAFE_MARGIN_DP = 5;
    private static final long EXPAND_ANIMATION_DURATION_MS = 350;
    private static final long RESTORE_ANIMATION_DURATION_MS = 300;
    private static final long ENTER_COMPACT_ANIMATION_DURATION_MS = 220;
    private static final long OPEN_ANIMATION_DURATION_MS = 240;
    private static final long OPEN_REVEAL_TIMEOUT_MS = 3000;
    private static final long CLOSE_ANIMATION_DURATION_MS = 300;
    private static final int ANIMATION_EXPAND_FULLSCREEN = 1;
    private static final int ANIMATION_RESTORE_COMPACT = 2;
    private static final int ANIMATION_ENTER_COMPACT = 3;
    private static final int ANIMATION_CONVERT_FULLSCREEN = 4;
    private static final PathInterpolator TASK_TRANSFORM_INTERPOLATOR =
            new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final Interpolator TASK_TRANSFORM_REVERSE_INTERPOLATOR = input ->
            1f - TASK_TRANSFORM_INTERPOLATOR.getInterpolation(1f - input);

    private final WindowManagerService mService;
    private final ArrayMap<Integer, MomentTaskSurfaceState> mStates = new ArrayMap<>();
    private final ArrayMap<Integer, MomentHandleWindow> mHandleWindows = new ArrayMap<>();
    private final MomentTaskAnimationRunner mAnimationRunner;

    private boolean mEnabled;
    private float mDefaultScale = DEFAULT_SCALE;

    MomentController(WindowManagerService service) {
        mService = service;
        mAnimationRunner = new MomentTaskAnimationRunner(service);
    }

    void setEnabled(boolean enabled) {
        Settings.Secure.putIntForUser(mService.mContext.getContentResolver(),
                Settings.Secure.MOMENT_ENABLED, enabled ? 1 : 0, mService.mCurrentUserId);
        setEnabledFromSettings(enabled);
    }

    void setEnabledFromSettings(boolean enabled) {
        synchronized (mService.mGlobalLock) {
            mEnabled = enabled;
            if (!enabled) {
                exitAllForUserLocked(mService.mCurrentUserId);
            }
        }
    }

    boolean isEnabled() {
        synchronized (mService.mGlobalLock) {
            return mEnabled;
        }
    }

    boolean isEnabledForUser(int userId) {
        return Settings.Secure.getIntForUser(mService.mContext.getContentResolver(),
                Settings.Secure.MOMENT_ENABLED, 0, userId) != 0;
    }

    boolean shouldSuppressRelaunchForConversion(ActivityRecord activity) {
        synchronized (mService.mGlobalLock) {
            final Task task = activity != null ? activity.getTask() : null;
            final MomentTaskSurfaceState state = getStateLocked(task);
            return state != null && state.shouldSuppressConversionRelaunch();
        }
    }

    void setDefaultScale(float scale) {
        synchronized (mService.mGlobalLock) {
            mDefaultScale = clampScale(scale);
            forAllMomentTasksLocked(task -> {
                final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
                if (state.isMomentCompact() || state.isOpening() || state.isClosing()
                        || state.isTransformAnimating()) {
                    return;
                }
                state.setScale(clampScaleLocked(task, state, mDefaultScale));
                constrainMomentPositionLocked(task, state);
                updateHandleWindowLocked(task);
                scheduleMomentSurfaceUpdateLocked(task);
            });
        }
    }

    float getDefaultScale() {
        synchronized (mService.mGlobalLock) {
            return mDefaultScale;
        }
    }

    int startActivityInMoment(Intent intent, int userId) {
        if (!isEnabledForUser(userId)) {
            throw new IllegalStateException("Moment is disabled");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        final ActivityOptions options = ActivityOptions.makeCustomAnimation(
                ActivityThread.currentActivityThread().getSystemUiContext(),
                0 /* enterResId */, 0 /* exitResId */);
        options.setDisableStartingWindow(true);
        options.setLaunchWindowingMode(WINDOWING_MODE_MOMENT);
        synchronized (mService.mGlobalLock) {
            final DisplayContent displayContent = mService.getDefaultDisplayContentLocked();
            if (displayContent != null) {
                final Rect displayBounds = new Rect();
                displayContent.getBounds(displayBounds);
                if (displayBounds.width() > displayBounds.height()) {
                    options.setLaunchBounds(new Rect(0, 0,
                            displayBounds.height(), displayBounds.width()));
                }
            }
        }
        final Bundle bundle = options.toBundle();
        final long identity = Binder.clearCallingIdentity();
        try {
            return mService.mAtmService.startActivityAsUser(null, SYSTEM_PACKAGE_NAME, null,
                    intent, null, null, null, 0, 0, null, bundle, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    int convertTaskToMoment(int taskId) {
        if (!isEnabled()) {
            throw new IllegalStateException("Moment is disabled");
        }
        synchronized (mService.mGlobalLock) {
            final Task task = taskId == INVALID_TASK_ID
                    ? mService.mRoot.getTopDisplayFocusedRootTask()
                    : mService.mRoot.anyTaskForId(taskId);
            if (task == null) {
                throw new IllegalStateException("No target task");
            }
            final Task rootTask = task.getRootTask() != null ? task.getRootTask() : task;
            if (!rootTask.isRootTask() || !rootTask.isActivityTypeStandard()
                    || rootTask.getWindowingMode() != WINDOWING_MODE_FULLSCREEN
                    || !rootTask.isVisibleRequested()) {
                throw new IllegalStateException(
                        "Task must be a visible fullscreen standard task: " + rootTask.mTaskId);
            }
            final ActivityRecord topActivity = rootTask.topRunningActivity();
            final DisplayContent displayContent = rootTask.getDisplayContent();
            if (topActivity == null || displayContent == null || rootTask.mSurfaceControl == null
                    || !rootTask.mSurfaceControl.isValid()) {
                throw new IllegalStateException("Task is not ready: " + rootTask.mTaskId);
            }

            final Rect taskBounds = new Rect(rootTask.getBounds());
            if (taskBounds.isEmpty()) {
                throw new IllegalStateException("Task has no bounds: " + rootTask.mTaskId);
            }

            final MomentTaskSurfaceState state = new MomentTaskSurfaceState(rootTask,
                    mDefaultScale);
            state.prepareForFullscreenConversion();
            mStates.put(rootTask.mTaskId, state);
            ensureMomentTaskLayoutLocked(rootTask, state);
            constrainMomentPositionLocked(rootTask, state);
            final Rect targetBounds = state.getSurfaceBounds();
            final Rect fullscreenBounds = state.getFullscreenBounds();
            final float startScaleX = (float) fullscreenBounds.width() / taskBounds.width();
            final float startScaleY = (float) fullscreenBounds.height() / taskBounds.height();

            markNoAnimationLocked(rootTask);
            rootTask.setWindowingMode(WINDOWING_MODE_MOMENT);
            if (rootTask.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                mStates.remove(rootTask.mTaskId);
                state.destroy(rootTask.getSyncTransaction());
                throw new IllegalStateException(
                        "Task does not support Moment: " + rootTask.mTaskId);
            }
            rootTask.forAllActivities(activity -> {
                activity.ensureActivityConfiguration(true /* ignoreVisibility */);
            });
            startTaskTransformAnimationFromLocked(rootTask, state, startScaleX, startScaleY,
                    fullscreenBounds.exactCenterX(), fullscreenBounds.exactCenterY(), 1f, 0f,
                    state.getScale(), state.getScale(), targetBounds.exactCenterX(),
                    targetBounds.exactCenterY(), 1f, 1f, EXPAND_ANIMATION_DURATION_MS,
                    ANIMATION_CONVERT_FULLSCREEN, TASK_TRANSFORM_REVERSE_INTERPOLATOR);
            return rootTask.mTaskId;
        }
    }

    void exitMomentTask(int taskId) {
        synchronized (mService.mGlobalLock) {
            final Task task = taskId == INVALID_TASK_ID ? null
                    : mService.mRoot.anyTaskForId(taskId);
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            exitMomentTaskLocked(task);
        }
    }

    void closeMomentTask(int taskId) {
        synchronized (mService.mGlobalLock) {
            final Task task = taskId == INVALID_TASK_ID ? null
                    : mService.mRoot.anyTaskForId(taskId);
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (state.isMomentCompact()) {
                closeMomentTaskLocked(task);
            } else {
                startMomentCloseAnimationLocked(task, state);
            }
        }
    }

    void exitAll() {
        synchronized (mService.mGlobalLock) {
            exitAllLocked();
        }
    }

    void onTaskRemoved(Task task) {
        synchronized (mService.mGlobalLock) {
            removeHandleWindowLocked(task);
            if (task != null) {
                final MomentTaskSurfaceState state = mStates.remove(task.mTaskId);
                if (state != null) {
                    cancelTaskAnimationLocked(task, state);
                    state.destroy(task.getSyncTransaction());
                }
            }
        }
    }

    void onTaskLeftMomentWindowingMode(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task == null) {
                return;
            }
            removeHandleWindowLocked(task);
            final MomentTaskSurfaceState state = mStates.remove(task.mTaskId);
            if (state == null) {
                return;
            }
            final boolean wasClosing = state.isClosing();
            cancelTaskAnimationLocked(task, state);
            final SurfaceControl.Transaction t = task.getSyncTransaction();
            state.reset(t);
            if (task.mSurfaceControl != null && task.mSurfaceControl.isValid()) {
                if (!wasClosing && task.isVisibleRequested()) {
                    t.show(task.mSurfaceControl);
                } else {
                    t.hide(task.mSurfaceControl);
                }
            }
            state.destroy(t);
            scheduleMomentSurfaceUpdateLocked(task);
        }
    }

    void dumpStatus(PrintWriter pw) {
        synchronized (mService.mGlobalLock) {
            pw.println("Moment enabled=" + mEnabled + " defaultScale=" + mDefaultScale);
            dumpTasksLocked(pw);
        }
    }

    void dumpTasks(PrintWriter pw) {
        synchronized (mService.mGlobalLock) {
            dumpTasksLocked(pw);
        }
    }

    void applyMomentSurface(Task task, SurfaceControl.Transaction t) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            constrainMomentPositionLocked(task, state);
            final boolean visibilityChanged = state.ensureForceTranslucent();
            final ActivityRecord topActivity = task.topRunningActivity();
            if (!state.hasApplied() && task.isVisibleRequested() && topActivity != null
                    && topActivity.isVisibleRequested()) {
                startMomentOpenAnimationLocked(task, state, t);
            }
            final boolean showHandle = shouldShowHandleLocked(task, state);
            state.apply(t, showHandle);
            if (isDebugEnabled()) {
                Slog.d(TAG, "applySurface taskId=" + task.mTaskId
                        + " mode=" + task.getWindowingMode()
                        + " visibleRequested=" + task.isVisibleRequested()
                        + " visible=" + task.isVisible()
                        + " top=" + task.topRunningActivity()
                        + " taskBounds=" + task.getBounds()
                        + " surfaceBounds=" + state.getSurfaceBounds()
                        + " decorationBounds=" + state.getDecorationBounds()
                        + " handleBounds=" + state.getHandleBounds()
                        + " showHandle=" + showHandle
                        + " forceTranslucentChanged=" + visibilityChanged);
            }
            if (!state.isClosing() && shouldAttachInteractionWindowLocked(task)) {
                updateHandleWindowLocked(task);
            } else {
                removeHandleWindowLocked(task);
            }
            if (visibilityChanged) {
                mService.mRoot.ensureActivitiesVisible();
            }
        }
    }

    boolean shouldApplyMomentSurface(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return false;
            }
            // Moment does not have a separate Shell transition handler, so keep the task
            // surface constrained whenever the task stays in Moment.
            return true;
        }
    }

    Rect moveMomentTask(Task task, int centerX, int centerY) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return new Rect();
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            state.setCenter(centerX, centerY);
            constrainMomentPositionLocked(task, state);
            applyMomentStateLocked(task, state, true /* allowSurfaceHandle */);
            return state.getSurfaceBounds();
        }
    }

    void animateMomentHandlePress(Task task, boolean isTouchDown) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT
                    || !shouldShowHandleLocked(task, getOrCreateStateLocked(task))) {
                return;
            }
            getOrCreateStateLocked(task).animateHandlePress(isTouchDown);
        }
    }

    float getMomentTaskScale(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return mDefaultScale;
            }
            return getOrCreateStateLocked(task).getScale();
        }
    }

    boolean isMomentCompact(Task task) {
        synchronized (mService.mGlobalLock) {
            final MomentTaskSurfaceState state = getStateLocked(task);
            return task != null && task.getWindowingMode() == WINDOWING_MODE_MOMENT
                    && state != null && state.isMomentCompact();
        }
    }

    Rect resizeMomentTask(Task task, float scale, int fixedX, int fixedY,
            boolean resizeFromLeft, boolean resizeFromTop) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return new Rect();
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            state.setScaleFromCorner(clampScaleLocked(task, state, scale), fixedX, fixedY,
                    resizeFromLeft, resizeFromTop);
            constrainMomentPositionLocked(task, state);
            applyMomentStateLocked(task, state, true /* allowSurfaceHandle */);
            return state.getSurfaceBounds();
        }
    }

    void finishMomentResize(Task task, float restoreScale) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (state.getScale() > MIN_SCALE + COMPACT_EPSILON || state.isMomentCompact()) {
                return;
            }
            state.enterCompact(restoreScale > MIN_SCALE + COMPACT_EPSILON
                    ? clampScaleLocked(task, state, restoreScale)
                    : clampScaleLocked(task, state, mDefaultScale));
            constrainMomentCompactPositionLocked(task, state);
            applyMomentStateLocked(task, state, false /* allowSurfaceHandle */);
        }
    }

    void enterMomentCompact(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (state.isMomentCompact() || state.isOpening() || state.isClosing()
                    || state.isTransformAnimating()) {
                return;
            }
            final Rect bounds = state.getSurfaceBounds();
            startTaskTransformAnimationLocked(task, state,
                    clampScaleLocked(task, state, MIN_SCALE), bounds.exactCenterX(),
                    bounds.exactCenterY(), 1f, 1f, ENTER_COMPACT_ANIMATION_DURATION_MS,
                    ANIMATION_ENTER_COMPACT);
        }
    }

    Rect moveMomentCompactTask(Task task, float centerX, float centerY, float magnetProgress,
            boolean constrainToMovementBounds) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return new Rect();
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (!state.isMomentCompact()) {
                return new Rect();
            }
            final float dismissScale = getCompactDismissScaleLocked(task, state);
            final float scale = lerp(state.getCompactScale(), dismissScale, magnetProgress);
            state.setCompactDragTransform(scale, Math.round(centerX), Math.round(centerY));
            if (constrainToMovementBounds) {
                constrainMomentCompactPositionLocked(task, state);
            }
            applyMomentStateLocked(task, state, false /* allowSurfaceHandle */);
            return state.getSurfaceBounds();
        }
    }

    Rect getMomentCompactMovementBounds(Task task) {
        synchronized (mService.mGlobalLock) {
            final Rect movementBounds = new Rect();
            if (task != null && task.getWindowingMode() == WINDOWING_MODE_MOMENT) {
                final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
                if (state.isMomentCompact()) {
                    getMomentCompactMovementBoundsLocked(task, state, movementBounds);
                }
            }
            return movementBounds;
        }
    }

    int getMomentDisplayBottom(Task task) {
        synchronized (mService.mGlobalLock) {
            return task != null ? getDisplayBottomLocked(task) : 0;
        }
    }

    int getMomentCompactStashedSide(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return 0;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            return state.isMomentCompact() ? state.getCompactStashedSide() : 0;
        }
    }

    void setMomentCompactStashedSide(Task task, int side) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (state.isMomentCompact()) {
                state.setCompactStashedSide(side);
            }
        }
    }

    boolean canStashMomentCompact(Task task, int side) {
        synchronized (mService.mGlobalLock) {
            final DisplayContent displayContent = task != null ? task.getDisplayContent() : null;
            if (displayContent == null || displayContent.getDisplayInfo().displayCutout == null) {
                return true;
            }
            return side < 0
                    ? displayContent.getDisplayInfo().displayCutout.getSafeInsetLeft() == 0
                    : displayContent.getDisplayInfo().displayCutout.getSafeInsetRight() == 0;
        }
    }

    void dismissMomentCompactTask(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (state.isMomentCompact()) {
                closeMomentTaskLocked(task);
            }
        }
    }

    void finishMomentCompactDrag(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (!state.isMomentCompact()) {
                return;
            }
            state.setCompactDragTransform(state.getCompactScale(),
                    state.getSurfaceBounds().centerX(), state.getSurfaceBounds().centerY());
            constrainMomentCompactPositionLocked(task, state);
            applyMomentStateLocked(task, state, false /* allowSurfaceHandle */);
        }
    }

    void restoreMomentCompact(Task task) {
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (!state.isMomentCompact() || state.isOpening() || state.isClosing()
                    || state.isTransformAnimating()) {
                return;
            }
            final Rect bounds = state.getSurfaceBounds();
            startTaskTransformAnimationLocked(task, state,
                    clampScaleLocked(task, state, state.getCompactRestoreScale()),
                    bounds.exactCenterX(), bounds.exactCenterY(), 1f, 1f,
                    RESTORE_ANIMATION_DURATION_MS, ANIMATION_RESTORE_COMPACT);
        }
    }

    void expandMomentTaskAnimated(int taskId) {
        synchronized (mService.mGlobalLock) {
            final Task task = taskId == INVALID_TASK_ID ? null
                    : mService.mRoot.anyTaskForId(taskId);
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            if (state.isOpening() || state.isClosing() || state.isTransformAnimating()) {
                return;
            }
            final Rect taskBounds = task.getBounds();
            final Rect fullscreenBounds = state.getFullscreenBounds();
            final float endScaleX = (float) fullscreenBounds.width() / taskBounds.width();
            final float endScaleY = (float) fullscreenBounds.height() / taskBounds.height();
            startTaskTransformAnimationLocked(task, state, endScaleX, endScaleY,
                    fullscreenBounds.exactCenterX(), fullscreenBounds.exactCenterY(), 1f, 0f,
                    EXPAND_ANIMATION_DURATION_MS, ANIMATION_EXPAND_FULLSCREEN);
        }
    }

    void performMomentBack(Task task) {
        final int displayId;
        final int targetUid;
        synchronized (mService.mGlobalLock) {
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                return;
            }
            final ActivityRecord topActivity = task.topRunningActivity();
            if (topActivity == null) {
                return;
            }
            if (!task.isFocused()) {
                mService.mAtmService.setFocusedTask(task.mTaskId, topActivity);
                return;
            }
            displayId = task.getDisplayId();
            targetUid = topActivity.getUid();
        }
        mService.mH.post(() -> injectBackKey(displayId, targetUid));
    }

    private void injectBackKey(int displayId, int targetUid) {
        final long downTime = SystemClock.uptimeMillis();
        injectBackKeyEvent(KeyEvent.ACTION_DOWN, downTime, downTime, displayId, targetUid);
        injectBackKeyEvent(KeyEvent.ACTION_UP, downTime, SystemClock.uptimeMillis(), displayId,
                targetUid);
    }

    private void injectBackKeyEvent(int action, long downTime, long eventTime, int displayId,
            int targetUid) {
        final KeyEvent event = KeyEvent.obtain(downTime, eventTime, action, KeyEvent.KEYCODE_BACK,
                0 /* repeat */, 0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD,
                0 /* scanCode */, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD,
                displayId, null /* characters */);
        try {
            mService.mInputManager.injectInputEventToTarget(
                    event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC, targetUid);
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Unable to deliver BACK to Moment task uid=" + targetUid, e);
        } finally {
            event.recycle();
        }
    }

    private MomentTaskSurfaceState getOrCreateStateLocked(Task task) {
        final Task rootTask = task != null ? task.getRootTask() : null;
        final Task target = rootTask != null ? rootTask : task;
        MomentTaskSurfaceState state = target != null ? mStates.get(target.mTaskId) : null;
        if (state == null) {
            state = new MomentTaskSurfaceState(target, mDefaultScale);
            mStates.put(target.mTaskId, state);
        }
        ensureMomentTaskLayoutLocked(target, state);
        return state;
    }

    private MomentTaskSurfaceState getStateLocked(Task task) {
        final Task rootTask = task != null ? task.getRootTask() : null;
        final Task target = rootTask != null ? rootTask : task;
        return target != null ? mStates.get(target.mTaskId) : null;
    }

    private void ensureMomentTaskLayoutLocked(Task task, MomentTaskSurfaceState state) {
        final DisplayContent displayContent = task != null ? task.getDisplayContent() : null;
        if (displayContent == null) {
            return;
        }
        final Rect displayBounds = new Rect();
        displayContent.getBounds(displayBounds);
        final boolean landscape = displayBounds.width() > displayBounds.height();
        final Rect desiredBounds = new Rect(0, 0,
                landscape ? displayBounds.height() : displayBounds.width(),
                landscape ? displayBounds.width() : displayBounds.height());
        if (!state.shouldPreserveTaskBounds() && !task.getBounds().equals(desiredBounds)) {
            task.setBounds(desiredBounds);
        }
        final float maxScale = calculateMomentMaxScaleLocked(task);
        state.updateLandscapeLayout(landscape, maxScale);
        if (!landscape && state.getScale() > maxScale) {
            state.setScale(maxScale);
        }
    }

    private float calculateMomentMaxScaleLocked(Task task) {
        final Rect safeBounds = new Rect();
        getMomentSafeBoundsLocked(task, safeBounds);
        final int taskHeight = Math.max(1, task.getBounds().height());
        final float density = getDensityLocked(task);
        final float availableHeight = safeBounds.height()
                - (HANDLE_MENU_TOP_INSET_DP + HANDLE_AREA_HEIGHT_DP) * density;
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, availableHeight / taskHeight));
    }

    private float clampScaleLocked(Task task, MomentTaskSurfaceState state, float scale) {
        final float maxScale = calculateMomentMaxScaleLocked(task);
        return Math.max(MIN_SCALE, Math.min(maxScale, scale));
    }

    private void constrainMomentPositionLocked(Task task, MomentTaskSurfaceState state) {
        if (state.isMomentCompact() || state.isTransformAnimating()) {
            return;
        }
        final DisplayContent displayContent = task.getDisplayContent();
        if (displayContent == null) {
            return;
        }
        final Rect safeBounds = new Rect();
        getMomentSafeBoundsLocked(task, safeBounds);
        final Rect surfaceBounds = state.getSurfaceBounds();
        final float halfWidth = surfaceBounds.width() / 2f;
        final float halfHeight = surfaceBounds.height() / 2f;
        final float density = getDensityLocked(task);
        final float handleHeight = MomentGeometry.getBottomHandleHeight(
                surfaceBounds.width(), density);
        final float handleCenterOffset = HANDLE_AREA_HEIGHT_DP * density / 2f;
        final float minCenterY = safeBounds.top - handleCenterOffset
                + handleHeight / 2f - halfHeight;
        final boolean landscape = displayContent.getBounds().width()
                > displayContent.getBounds().height();

        final float minCenterX;
        final float maxCenterX;
        final float maxCenterY;
        if (landscape) {
            final float requiredHalfWidth = Math.max(halfWidth,
                    HANDLE_MENU_WIDTH_DP * density / 2f);
            minCenterX = safeBounds.left + requiredHalfWidth;
            maxCenterX = safeBounds.right - requiredHalfWidth;
            maxCenterY = safeBounds.bottom - handleCenterOffset - handleHeight / 2f - halfHeight;
        } else {
            final float topHandleHalfWidth = Math.max(HANDLE_MENU_WIDTH_DP * density / 2f,
                    surfaceBounds.width() / 4f);
            minCenterX = safeBounds.left + topHandleHalfWidth;
            maxCenterX = safeBounds.right - topHandleHalfWidth;
            final float maxTaskBottom = safeBounds.bottom - handleCenterOffset - handleHeight / 2f;
            maxCenterY = maxTaskBottom - halfHeight;
        }
        final int currentCenterX = state.getCenterX();
        final int currentCenterY = state.getCenterY();
        final int constrainedCenterX = Math.round(
                clampToRange(currentCenterX, minCenterX, maxCenterX));
        final int constrainedCenterY = Math.round(
                clampToRange(currentCenterY, minCenterY, maxCenterY));
        if (constrainedCenterX != currentCenterX || constrainedCenterY != currentCenterY) {
            state.setCenter(constrainedCenterX, constrainedCenterY);
        }
    }

    private void constrainMomentCompactPositionLocked(Task task,
            MomentTaskSurfaceState state) {
        final Rect movementBounds = new Rect();
        getMomentCompactMovementBoundsLocked(task, state, movementBounds);
        final int currentCenterX = state.getCenterX();
        final int currentCenterY = state.getCenterY();
        final int constrainedCenterX = Math.round(clampToRange(
                currentCenterX, movementBounds.left, movementBounds.right));
        final int constrainedCenterY = Math.round(clampToRange(
                currentCenterY, movementBounds.top, movementBounds.bottom));
        if (constrainedCenterX != currentCenterX || constrainedCenterY != currentCenterY) {
            state.setCenter(constrainedCenterX, constrainedCenterY);
        }
    }

    private void getMomentCompactMovementBoundsLocked(Task task,
            MomentTaskSurfaceState state, Rect outBounds) {
        getMomentSafeBoundsLocked(task, outBounds);
        final Rect taskBounds = task.getBounds();
        final int halfWidth = Math.round(taskBounds.width() * state.getCompactScale() / 2f);
        final int halfHeight = Math.round(taskBounds.height() * state.getCompactScale() / 2f);
        outBounds.inset(halfWidth, halfHeight);
        if (outBounds.left > outBounds.right) {
            final int centerX = outBounds.centerX();
            outBounds.left = centerX;
            outBounds.right = centerX;
        }
        if (outBounds.top > outBounds.bottom) {
            final int centerY = outBounds.centerY();
            outBounds.top = centerY;
            outBounds.bottom = centerY;
        }
    }

    private void getMomentSafeBoundsLocked(Task task, Rect outBounds) {
        final DisplayContent displayContent = task.getDisplayContent();
        if (displayContent == null) {
            outBounds.set(task.getBounds());
            return;
        }
        displayContent.getStableRect(outBounds);
        final int margin = Math.round(MOVEMENT_SAFE_MARGIN_DP * getDensityLocked(task));
        outBounds.inset(margin, margin);
    }

    private float getDensityLocked(Task task) {
        final DisplayContent displayContent = task.getDisplayContent();
        return displayContent != null ? displayContent.getDisplayMetrics().density : 1f;
    }

    private static float clampToRange(float value, float min, float max) {
        if (min > max) {
            return (min + max) / 2f;
        }
        return Math.max(min, Math.min(max, value));
    }

    private void startMomentCloseAnimationLocked(Task task, MomentTaskSurfaceState state) {
        if (state.isOpening() || state.isClosing() || state.isTransformAnimating()) {
            return;
        }
        cancelTaskAnimationLocked(task, state);
        final Rect startBounds = state.getSurfaceBounds();
        final float taskScale = state.getScale();
        final float density = getDensityLocked(task);
        final int color = mService.mContext.getColor(android.R.color.system_accent1_100);
        final int generation;
        try (SurfaceControl.Transaction t = mService.mTransactionFactory.get()) {
            generation = state.beginCloseAnimation(t, color,
                    MomentGeometry.getCornerRadius(density) * taskScale);
            if (generation < 0) {
                Slog.w(TAG, "Close animation unavailable for task " + task.mTaskId
                        + ", closing immediately");
                closeMomentTaskLocked(task);
                return;
            }
            t.apply();
        } catch (Surface.OutOfResourcesException e) {
            Slog.w(TAG, "Unable to create close animation layer for task " + task.mTaskId, e);
            closeMomentTaskLocked(task);
            return;
        }
        Slog.i(TAG, "Started close animation for task " + task.mTaskId
                + " bounds=" + startBounds);
        removeHandleWindowLocked(task);
        scheduleMomentSurfaceUpdateLocked(task);

        final MomentGeometry.MorphFrame frame = new MomentGeometry.MorphFrame();
        mAnimationRunner.start(state, CLOSE_ANIMATION_DURATION_MS, null,
                () -> state.isCloseAnimation(generation), progress -> {
                MomentGeometry.evaluateCloseMorph(startBounds, taskScale, density,
                        progress, frame);
                try (SurfaceControl.Transaction t = mService.mTransactionFactory.get()) {
                    if (state.applyCloseAnimationFrame(t, generation, frame.centerX, frame.centerY,
                            frame.width, frame.height, frame.cornerRadius, frame.alpha)) {
                        t.apply();
                    }
                }
                }, () -> mService.mH.post(() -> onMomentCloseAnimationFinished(
                        task.mTaskId, state, generation)));
    }

    private void startMomentOpenAnimationLocked(Task task, MomentTaskSurfaceState state,
            SurfaceControl.Transaction startTransaction) {
        if (state.isOpening() || state.isClosing()) {
            return;
        }
        final Rect endBounds = state.getSurfaceBounds();
        final float taskScale = state.getScale();
        final float density = getDensityLocked(task);
        final MomentGeometry.MorphFrame initialFrame = new MomentGeometry.MorphFrame();
        MomentGeometry.evaluateCloseMorph(endBounds, taskScale, density, 1f, initialFrame);
        final int color = mService.mContext.getColor(android.R.color.system_accent1_100);
        final int generation = state.beginOpenAnimation(startTransaction, color,
                initialFrame.centerX, initialFrame.centerY, initialFrame.width,
                initialFrame.height, initialFrame.cornerRadius);
        if (generation < 0) {
            Slog.w(TAG, "Open animation unavailable for task " + task.mTaskId);
            return;
        }
        final int taskId = task.mTaskId;
        startTransaction.addTransactionCommittedListener(Runnable::run,
                () -> startMomentOpenAnimator(taskId, state, generation, endBounds, taskScale,
                        density));
        removeHandleWindowLocked(task);
        Slog.i(TAG, "Prepared open animation for task " + taskId + " bounds=" + endBounds);
    }

    private void startMomentOpenAnimator(int taskId, MomentTaskSurfaceState state, int generation,
            Rect endBounds, float taskScale, float density) {
        final MomentGeometry.MorphFrame frame = new MomentGeometry.MorphFrame();
        mAnimationRunner.start(state, OPEN_ANIMATION_DURATION_MS, null,
                () -> state.isOpenAnimation(generation), progress -> {
            final float reverseProgress = 1f - progress;
            MomentGeometry.evaluateCloseMorph(endBounds, taskScale, density,
                    reverseProgress, frame);
            try (SurfaceControl.Transaction t = mService.mTransactionFactory.get()) {
                if (state.applyOpenAnimationFrame(t, generation, frame.centerX, frame.centerY,
                        frame.width, frame.height, frame.cornerRadius, frame.alpha)) {
                    t.apply();
                }
            }
        }, () -> {
            final long revealDeadline = SystemClock.uptimeMillis() + OPEN_REVEAL_TIMEOUT_MS;
            mService.mH.post(() -> finishMomentOpenAnimationWhenReady(taskId, state,
                    generation, revealDeadline));
        });
    }

    private void finishMomentOpenAnimationWhenReady(int taskId, MomentTaskSurfaceState state,
            int generation, long revealDeadline) {
        synchronized (mService.mGlobalLock) {
            final Task task = mService.mRoot.anyTaskForId(taskId);
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT
                    || getStateLocked(task) != state || !state.isOpenAnimation(generation)) {
                return;
            }
            final ActivityRecord topActivity = task.topRunningActivity();
            final WindowState mainWindow = topActivity != null
                    ? topActivity.findMainWindow(false /* includeStartingApp */) : null;
            if ((mainWindow == null || !mainWindow.isDrawn())
                    && SystemClock.uptimeMillis() < revealDeadline) {
                mService.mH.postDelayed(() -> finishMomentOpenAnimationWhenReady(taskId, state,
                        generation, revealDeadline), 16);
                return;
            }
            try (SurfaceControl.Transaction t = mService.mTransactionFactory.get()) {
                if (!state.finishOpenAnimation(t, generation)) {
                    return;
                }
                final boolean showHandle = shouldShowHandleLocked(task, state);
                state.apply(t, showHandle);
                t.apply();
            }
            if (shouldAttachInteractionWindowLocked(task)) {
                updateHandleWindowLocked(task);
            }
            scheduleMomentSurfaceUpdateLocked(task);
            Slog.i(TAG, "Finished open animation for task " + taskId);
        }
    }

    private void onMomentCloseAnimationFinished(int taskId, MomentTaskSurfaceState state,
            int generation) {
        synchronized (mService.mGlobalLock) {
            final Task task = mService.mRoot.anyTaskForId(taskId);
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT
                    || getStateLocked(task) != state) {
                cleanupStaleAnimationStateLocked(taskId, task, state);
                return;
            }
            try (SurfaceControl.Transaction t = mService.mTransactionFactory.get()) {
                if (!state.completeCloseAnimation(t, generation)) {
                    return;
                }
                t.apply();
            }
            Slog.i(TAG, "Finished close animation for task " + taskId);
            closeMomentTaskLocked(task);
        }
    }

    private void startTaskTransformAnimationLocked(Task task, MomentTaskSurfaceState state,
            float endScale, float endCenterX, float endCenterY, float endAlpha,
            float endCornerProgress, long duration, int endAction) {
        startTaskTransformAnimationLocked(task, state, endScale, endScale, endCenterX, endCenterY,
                endAlpha, endCornerProgress, duration, endAction);
    }

    private void startTaskTransformAnimationLocked(Task task, MomentTaskSurfaceState state,
            float endScaleX, float endScaleY, float endCenterX, float endCenterY, float endAlpha,
            float endCornerProgress, long duration, int endAction) {
        final Rect startBounds = state.getSurfaceBounds();
        final float startScale = state.getScale();
        startTaskTransformAnimationFromLocked(task, state, startScale, startScale,
                startBounds.exactCenterX(), startBounds.exactCenterY(), 1f, 1f,
                endScaleX, endScaleY, endCenterX, endCenterY, endAlpha, endCornerProgress,
                duration, endAction, TASK_TRANSFORM_INTERPOLATOR);
    }

    private void startTaskTransformAnimationFromLocked(Task task, MomentTaskSurfaceState state,
            float startScaleX, float startScaleY, float startCenterX, float startCenterY,
            float startAlpha, float startCornerProgress, float endScaleX, float endScaleY,
            float endCenterX, float endCenterY, float endAlpha, float endCornerProgress,
            long duration, int endAction, Interpolator interpolator) {
        final int generation = state.beginTransformAnimation(startScaleX, startScaleY,
                startCenterX, startCenterY, startAlpha, startCornerProgress);
        state.apply(task.getSyncTransaction(), false /* showHandle */);
        if (endAction == ANIMATION_RESTORE_COMPACT) {
            updateHandleWindowLocked(task);
        } else {
            removeHandleWindowLocked(task);
        }
        scheduleMomentSurfaceUpdateLocked(task);

        mAnimationRunner.start(state, duration, interpolator,
                () -> state.isTransformAnimation(generation), progress -> {
                final float scaleX = lerp(startScaleX, endScaleX, progress);
                final float scaleY = lerp(startScaleY, endScaleY, progress);
                final float centerX = lerp(startCenterX, endCenterX, progress);
                final float centerY = lerp(startCenterY, endCenterY, progress);
                final float alpha = lerp(startAlpha, endAlpha, progress);
                final float cornerProgress = lerp(startCornerProgress, endCornerProgress, progress);
                try (SurfaceControl.Transaction t = mService.mTransactionFactory.get()) {
                    if (state.applyAnimatedTransform(t, generation, scaleX, scaleY, centerX, centerY,
                            alpha, cornerProgress)) {
                        t.apply();
                    }
                }
                }, () -> mService.mH.post(() -> onTaskTransformAnimationFinished(task.mTaskId,
                        state, generation, endScaleX, endCenterX, endCenterY, endAction)));
    }

    private void onTaskTransformAnimationFinished(int taskId, MomentTaskSurfaceState state,
            int generation, float endScale, float endCenterX, float endCenterY, int endAction) {
        synchronized (mService.mGlobalLock) {
            final Task task = mService.mRoot.anyTaskForId(taskId);
            if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT
                    || getStateLocked(task) != state
                    || !state.finishTransformAnimation(generation)) {
                if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT) {
                    cleanupStaleAnimationStateLocked(taskId, task, state);
                }
                return;
            }
            switch (endAction) {
                case ANIMATION_EXPAND_FULLSCREEN:
                    exitMomentTaskLocked(task);
                    break;
                case ANIMATION_RESTORE_COMPACT:
                    state.setCenter(Math.round(endCenterX), Math.round(endCenterY));
                    state.finishCompactRestore(endScale);
                    constrainMomentPositionLocked(task, state);
                    applyMomentStateLocked(task, state, true /* allowSurfaceHandle */);
                    break;
                case ANIMATION_ENTER_COMPACT:
                    final float restoreScale = state.getScale();
                    state.setCenter(Math.round(endCenterX), Math.round(endCenterY));
                    state.setScale(endScale);
                    state.enterCompact(restoreScale);
                    constrainMomentCompactPositionLocked(task, state);
                    applyMomentStateLocked(task, state, false /* allowSurfaceHandle */);
                    break;
                case ANIMATION_CONVERT_FULLSCREEN:
                    state.finishFullscreenConversion();
                    state.setCenter(Math.round(endCenterX), Math.round(endCenterY));
                    state.setScale(endScale);
                    constrainMomentPositionLocked(task, state);
                    applyMomentStateLocked(task, state, true /* allowSurfaceHandle */);
                    break;
                default:
                    break;
            }
        }
    }

    private void cleanupStaleAnimationStateLocked(int taskId, Task task,
            MomentTaskSurfaceState state) {
        if (mStates.get(taskId) != state) {
            return;
        }
        mStates.remove(taskId);
        if (task != null) {
            removeHandleWindowLocked(task);
        }
        mAnimationRunner.cancel(state);
        if (task != null) {
            final SurfaceControl.Transaction t = task.getSyncTransaction();
            state.reset(t);
            if (task.mSurfaceControl != null && task.mSurfaceControl.isValid()) {
                if (task.isVisibleRequested()) {
                    t.show(task.mSurfaceControl);
                } else {
                    t.hide(task.mSurfaceControl);
                }
            }
            state.destroy(t);
            scheduleMomentSurfaceUpdateLocked(task);
        } else {
            try (SurfaceControl.Transaction t = mService.mTransactionFactory.get()) {
                state.destroy(t);
                t.apply();
            }
        }
    }

    private void cancelTaskAnimationLocked(Task task, MomentTaskSurfaceState state) {
        state.cancelTransformAnimation();
        mAnimationRunner.cancel(state);
    }

    private void closeMomentTaskLocked(Task task) {
        removeHandleWindowLocked(task);
        final MomentTaskSurfaceState state = mStates.get(task.mTaskId);
        if (state != null) {
            cancelTaskAnimationLocked(task, state);
            state.prepareForTaskRemoval(task.getSyncTransaction());
        }
        mService.mAtmService.removeTask(task, "close-moment");
    }

    private int getDisplayBottomLocked(Task task) {
        final DisplayContent displayContent = task.getDisplayContent();
        if (displayContent == null) {
            return task.getBounds().bottom;
        }
        final Rect displayBounds = new Rect();
        displayContent.getBounds(displayBounds);
        return displayBounds.bottom;
    }

    private float getCompactDismissScaleLocked(Task task, MomentTaskSurfaceState state) {
        final Rect taskBounds = task.getBounds();
        final float compactMaxDimension = Math.max(taskBounds.width(), taskBounds.height())
                * state.getCompactScale();
        final DisplayContent displayContent = task.getDisplayContent();
        final float density = displayContent != null
                ? displayContent.getDisplayMetrics().density : 1f;
        final float desiredWidth = COMPACT_DISMISS_TARGET_SIZE_DP * density
                * COMPACT_DISMISS_SCALE;
        return compactMaxDimension > 0f
                ? state.getCompactScale() * Math.min(1f, desiredWidth / compactMaxDimension)
                : state.getCompactScale();
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private void exitAllLocked() {
        final ArrayList<Task> tasks = new ArrayList<>();
        forAllMomentTasksLocked(tasks::add);
        for (int i = 0; i < tasks.size(); i++) {
            exitMomentTaskLocked(tasks.get(i));
        }
    }

    private void exitAllForUserLocked(int userId) {
        final ArrayList<Task> tasks = new ArrayList<>();
        forAllMomentTasksLocked(task -> {
            if (task.mUserId == userId) {
                tasks.add(task);
            }
        });
        for (int i = 0; i < tasks.size(); i++) {
            exitMomentTaskLocked(tasks.get(i));
        }
    }

    private void exitMomentTaskLocked(Task task) {
        final MomentTaskSurfaceState state = mStates.remove(task.mTaskId);
        if (state != null) {
            cancelTaskAnimationLocked(task, state);
            state.reset(task.getSyncTransaction());
            state.destroy(task.getSyncTransaction());
        }
        removeHandleWindowLocked(task);
        markNoAnimationLocked(task);
        task.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        task.setBounds(null);
        scheduleMomentSurfaceUpdateLocked(task);
    }

    private void removeHandleWindowLocked(Task task) {
        if (task == null) {
            return;
        }
        final MomentHandleWindow handleWindow = mHandleWindows.remove(task.mTaskId);
        if (handleWindow != null) {
            handleWindow.destroy();
        }
    }

    void onNotificationShadeExpandedLocked(DisplayContent displayContent, boolean expanded) {
        forAllMomentTasksLocked(task -> {
            if (task.getDisplayContent() != displayContent) {
                return;
            }
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            state.apply(task.getSyncTransaction(), !expanded && shouldShowHandleLocked(task, state));
            if (expanded) {
                removeHandleWindowLocked(task);
            } else {
                updateHandleWindowLocked(task);
            }
            scheduleMomentSurfaceUpdateLocked(task);
        });
    }

    void onNotificationShadeRemovedLocked(DisplayContent displayContent,
            WindowState removedShade, int shadeGeneration) {
        mService.mH.post(() -> finishNotificationShadeRemoval(
                displayContent, removedShade, shadeGeneration, 120 /* remainingFrames */));
    }

    private void finishNotificationShadeRemoval(DisplayContent displayContent,
            WindowState removedShade, int shadeGeneration, int remainingFrames) {
        synchronized (mService.mGlobalLock) {
            final DisplayPolicy displayPolicy = displayContent.getDisplayPolicy();
            if (displayPolicy.getNotificationShadeGeneration() != shadeGeneration
                    || !displayPolicy.isNotificationShadeExpanded()) {
                return;
            }
            if (removedShade.isOnScreen() || removedShade.isAnimating()) {
                if (remainingFrames <= 0) {
                    Slog.w(TAG, "Timed out waiting for notification shade removal on display "
                            + displayContent.getDisplayId());
                } else {
                    mService.mH.postDelayed(
                            () -> finishNotificationShadeRemoval(displayContent, removedShade,
                                    shadeGeneration, remainingFrames - 1), 16);
                    return;
                }
            }
            final WindowState currentShade = displayPolicy.getNotificationShade();
            if (currentShade != null && displayPolicy.hasNotificationShadeExpansionReport()
                    && displayPolicy.isNotificationShadeExpanded()) {
                return;
            }
            if (displayPolicy.setNotificationShadeExpanded(false)) {
                onNotificationShadeExpandedLocked(displayContent, false /* expanded */);
            }
        }
    }

    private void updateHandleWindowLocked(Task task) {
        if (!shouldAttachInteractionWindowLocked(task)) {
            removeHandleWindowLocked(task);
            return;
        }
        final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
        final Rect handleBounds = state.getHandleBounds();
        if (handleBounds.isEmpty()) {
            removeHandleWindowLocked(task);
            return;
        }
        MomentHandleWindow handleWindow = mHandleWindows.get(task.mTaskId);
        final DisplayContent displayContent = task.getDisplayContent();
        final int displayId = displayContent != null
                ? displayContent.getDisplayId() : android.view.Display.DEFAULT_DISPLAY;
        if (handleWindow != null && !handleWindow.isOnDisplay(displayId)) {
            mHandleWindows.remove(task.mTaskId);
            handleWindow.destroy();
            handleWindow = null;
        }
        if (handleWindow == null) {
            handleWindow = new MomentHandleWindow(mService, task, state.getHandleSurfaces());
            mHandleWindows.put(task.mTaskId, handleWindow);
        }
        handleWindow.showOrUpdate(handleBounds, state.isMomentCompact(),
                state.isTransformAnimating());
    }

    private void markNoAnimationLocked(Task task) {
        task.forAllActivities(activity -> {
            activity.intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            activity.mTransitionController.setNoAnimation(activity);
        });
    }

    private boolean shouldAttachInteractionWindowLocked(Task task) {
        if (task == null || task.getWindowingMode() != WINDOWING_MODE_MOMENT
                || !task.isVisibleRequested()) {
            return false;
        }
        final MomentTaskSurfaceState state = getStateLocked(task);
        if (state != null && (state.isOpening() || state.isClosing())) {
            return false;
        }
        final DisplayContent displayContent = task.getDisplayContent();
        if (displayContent == null
                || displayContent.getDisplayPolicy().isNotificationShadeExpanded()) {
            return false;
        }
        final ActivityRecord topActivity = task.topRunningActivity();
        if (topActivity == null || !topActivity.isVisibleRequested()) {
            return false;
        }
        return true;
    }

    private boolean shouldShowHandleLocked(Task task, MomentTaskSurfaceState state) {
        return shouldAttachInteractionWindowLocked(task) && !state.isMomentCompact()
                && !state.isClosing();
    }

    private void applyMomentStateLocked(Task task, MomentTaskSurfaceState state,
            boolean allowSurfaceHandle) {
        state.apply(task.getSyncTransaction(),
                allowSurfaceHandle && shouldShowHandleLocked(task, state));
        if (shouldAttachInteractionWindowLocked(task)) {
            updateHandleWindowLocked(task);
        } else {
            removeHandleWindowLocked(task);
        }
        scheduleMomentSurfaceUpdateLocked(task);
    }

    private void scheduleMomentSurfaceUpdateLocked(Task task) {
        final DisplayContent displayContent = task.getDisplayContent();
        if (displayContent != null) {
            displayContent.setLayoutNeeded();
        }
        mService.mWindowPlacerLocked.requestTraversal();
    }

    private void dumpTasksLocked(PrintWriter pw) {
        final ArrayList<Task> tasks = new ArrayList<>();
        forAllMomentTasksLocked(tasks::add);
        if (tasks.isEmpty()) {
            pw.println("No Moment tasks");
            return;
        }
        for (int i = 0; i < tasks.size(); i++) {
            final Task task = tasks.get(i);
            final MomentTaskSurfaceState state = getOrCreateStateLocked(task);
            final MomentHandleWindow handleWindow = mHandleWindows.get(task.mTaskId);
            pw.println("taskId=" + task.mTaskId + " userId=" + task.mUserId
                    + " scale=" + state.getScale()
                    + " compact=" + state.isMomentCompact()
                    + " bounds=" + state.getSurfaceBounds()
                    + " handleBounds=" + state.getHandleBounds()
                    + " decorationBounds=" + state.getDecorationBounds()
                    + " handleWindowBounds="
                    + (handleWindow != null ? handleWindow.getLastWindowBounds() : "null")
                    + " topActivity=" + task.topRunningActivity());
        }
    }

    private void forAllMomentTasksLocked(java.util.function.Consumer<Task> consumer) {
        mService.mRoot.forAllTasks(task -> {
            if (task.isRootTask() && task.getWindowingMode() == WINDOWING_MODE_MOMENT) {
                consumer.accept(task);
            }
        }, true);
    }

    private float clampScale(float scale) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    private static boolean isDebugEnabled() {
        return SystemProperties.getBoolean(DEBUG_PROPERTY, false);
    }
}
