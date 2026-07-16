/*
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * Copyright (C) 2026 The RisingOS Revived Project
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MINI_WINDOW_EXT;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.os.Process.THREAD_PRIORITY_DEFAULT;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_MINI_WINDOW_DIMMER;
import static android.window.TransitionInfo.FLAG_EXIT_POP_UP_VIEW_BY_DRAG;
import static android.window.TransitionInfo.FLAG_EXIT_POP_UP_VIEW_DISPLAY_ROTATION;
import static android.window.TransitionInfo.FLAG_LAUNCH_POP_UP_VIEW_FROM_GESTURE;
import static android.window.TransitionInfo.FLAG_LAUNCH_POP_UP_VIEW_FROM_RECENTS;
import static android.window.TransitionInfo.FLAG_SCHEDULE_POP_UP_VIEW;

import static com.android.server.wm.Transition.ChangeInfo.FLAG_CHANGE_SHOULD_SKIP_TRANSITIONS;

import static com.android.internal.util.android.DebugConstants.DEBUG_POP_UP;
import static com.android.internal.util.android.PopUpViewManager.FEATURE_SUPPORTED;

import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.om.IOverlayManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.util.ArrayMap;
import android.util.SparseArray;
import android.util.ArraySet;
import android.util.Slog;
import android.view.IWindow;
import android.view.InsetsSource;
import android.annotation.IntDef;
import java.lang.annotation.Retention;
import android.view.InsetsState;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowInsets.Type;
import android.view.WindowManagerPolicyConstants.PointerEventListener;
import android.window.TransitionInfo;
import android.window.TransitionInfo.Change;

import com.android.server.ServiceThread;
import com.android.server.wm.ActivityStarter.Request;
import com.android.server.wm.LaunchParamsController.LaunchParams;
import com.android.server.wm.Transition.ChangeInfo;

import com.google.android.collect.Sets;
import java.lang.annotation.RetentionPolicy;

import java.util.ArrayList;

import com.android.internal.util.android.PopUpViewManager;


public class PopUpWindowController {

    private static final String TAG = "PopUpWindowController";
    private static final String PACKAGE_NAME_PIXEL_LAUNCHER_OVERLAY =
            "com.google.android.apps.nexuslauncher.pop_up.overlay";

    public static final int REORDER_KEEP_IN_PLACE = 0;
    public static final int REORDER_MOVE_TO_TOP = 1;
    public static final int REORDER_MOVE_TO_ORIGINAL_POSITION = 2;

    @IntDef(value = { REORDER_KEEP_IN_PLACE, REORDER_MOVE_TO_TOP, REORDER_MOVE_TO_ORIGINAL_POSITION })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ReorderMode {}

    static final int MOVE_TO_BACK_TOUCH_OUTSIDE = 0;
    static final int MOVE_TO_BACK_FROM_LEAVE_BUTTON = 1;
    static final int MOVE_TO_BACK_NEW_MINI = 2;
    static final int MOVE_TO_BACK_NEW_PIN = 3;
    static final int MOVE_TO_BACK_NON_USER = 4;
    static final int MOVE_TO_BACK_SINGLE_POP_UP_POLICY = 5;

    private static final long EXIT_POP_UP_DELAY = 200L;

    private static final int ID_DISPLAY_CUTOUT_LEFT = InsetsSource.createId(null, 0, Type.displayCutout());
    private static final int ID_DISPLAY_CUTOUT_TOP = InsetsSource.createId(null, 1, Type.displayCutout());
    private static final int ID_DISPLAY_CUTOUT_RIGHT = InsetsSource.createId(null, 2, Type.displayCutout());
    private static final int ID_DISPLAY_CUTOUT_BOTTOM = InsetsSource.createId(null, 3, Type.displayCutout());

    private final Handler mHandler;
    private final ServiceThread mServiceThread;

    private ActivityTaskManagerService mAtmService;
    private Context mContext;
    private Vibrator mVibrator;
    private WindowManagerService mService;

    private SurfaceControl.Transaction mTransaction;

    private boolean mSkipNextTransitionFreeze;
    private boolean mTryExitWindowingMode;
    private boolean mTryExitWindowingModeByDrag;
    private boolean mLaunchPopUpViewFromRecents;
    private boolean mLaunchPopUpViewFromGesture;
    private int mNextPopUpViewLaunchPointX = -1;
    private int mNextPopUpViewLaunchPointY = -1;
    private float mNextPopUpViewLaunchProgress = Float.NaN;
    private boolean mNextRecentIsPin;

    private WindowState mDimWinState = null;
    private final SparseArray<WindowToken> mDimmerTokensByTaskId = new SparseArray<>();
    private final ArrayMap<WindowToken, Integer> mTaskIdByDimmerToken = new ArrayMap<>();

    private PointerEventListener mMiniWindowPointerListener;

    private static class InstanceHolder {
        private static final PopUpWindowController INSTANCE = new PopUpWindowController();
    }

    public static PopUpWindowController getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private PopUpWindowController() {
        mServiceThread = new ServiceThread(TAG, THREAD_PRIORITY_DEFAULT, false);
        mServiceThread.start();
        mHandler = new Handler(mServiceThread.getLooper());
    }

    public void init(Context context, WindowManagerService wms) {
        mContext = context;
        mService = wms;
        mAtmService = mService.mAtmService;
        mTransaction = mService.mTransactionFactory.get();
        mVibrator = mContext.getSystemService(Vibrator.class);
    }

    void systemReady() {
        PopUpSettingsConfig.getInstance().init(mContext, mHandler);
        PopUpAppStarter.getInstance().init(mContext);

        setupMiniWindowPointerListener();
    }

    void onWindowAdd(ConfigurationContainer newParent, WindowState win) {
        if (newParent == null) {
            return;
        }
        final DisplayContent displayContent = win.getDisplayContent();
        if (displayContent.getDisplayId() == DEFAULT_DISPLAY &&
                win.mAttrs.type == TYPE_MINI_WINDOW_DIMMER) {
            mDimWinState = win;
            final int taskId = parseDimmerTaskId(win);
            if (taskId != -1) {
                mDimmerTokensByTaskId.put(taskId, win.mToken);
                mTaskIdByDimmerToken.put(win.mToken, taskId);
            }
            displayContent.assignWindowLayers(false);
        }
    }

    void onWindowRemove(WindowState win) {
        if (win.mAttrs.type == TYPE_MINI_WINDOW_DIMMER) {
            mDimWinState = null;
            final Integer taskId = mTaskIdByDimmerToken.remove(win.mToken);
            if (taskId != null) {
                mDimmerTokensByTaskId.remove(taskId);
            }
        }
    }

    boolean onWindowTokenAssignLayer(WindowToken token, SurfaceControl.Transaction t, int layer) {
        if (token.windowType != TYPE_MINI_WINDOW_DIMMER) {
            return false;
        }
        if (token.mSurfaceControl == null) {
            return true;
        }
        if (mAtmService.isSleepingOrShuttingDownLocked()) {
            t.hide(token.mSurfaceControl);
        } else {
            t.show(token.mSurfaceControl);
            final DisplayContent displayContent = token.getDisplayContent();
            if (displayContent != null) {
                final Task targetTask = getTaskForDimmerToken(token);
                if (targetTask != null && targetTask.mSurfaceControl != null) {
                    token.assignRelativeLayer(t, targetTask.mSurfaceControl, 1, true);
                } else {
                    // Fallback to default layer if task lookup fails.
                    t.setLayer(token.mSurfaceControl, layer);
                }
            }
        }
        return true;
    }

    private Task getTaskForDimmerToken(WindowToken token) {
        if (token == null) {
            return null;
        }
        final Integer taskId = mTaskIdByDimmerToken.get(token);
        if (taskId == null) {
            return null;
        }
        return mAtmService.mRootWindowContainer.anyTaskForId(taskId);
    }

    private int parseDimmerTaskId(WindowState win) {
        if (win == null || win.mAttrs == null) {
            return -1;
        }
        final CharSequence titleSeq = win.mAttrs.getTitle();
        if (titleSeq == null) {
            return -1;
        }
        final String title = titleSeq.toString();
        final String prefix = DimmerWindow.WIN_TITLE + "#";
        if (!title.startsWith(prefix)) {
            return -1;
        }
        final String idPart = title.substring(prefix.length());
        try {
            return Integer.parseInt(idPart);
        } catch (NumberFormatException e) {
            return -1;
        }
    }


    void onRotationChanged(Task task) {
        if (task.mWindowContainerExt.getTaskWindowSurfaceInfo() != null) {
            task.mWindowContainerExt.getTaskWindowSurfaceInfo().onRotationChanged();
        }
    }

    void onPrepareSurfaces(Task task, SurfaceControl.Transaction t) {
        if (task.mWindowContainerExt.getTaskWindowSurfaceInfo() != null) {
            task.mWindowContainerExt.getTaskWindowSurfaceInfo().onPrepareSurfaces(t);
        }
        final WindowToken dimmerToken = mDimmerTokensByTaskId.get(task.mTaskId);
        if (dimmerToken != null && dimmerToken.mSurfaceControl != null
                && task.mSurfaceControl != null && task.mSurfaceControl.isValid()
                && task.getWindowConfiguration().isMiniExtWindowMode()) {
            t.show(dimmerToken.mSurfaceControl);
            dimmerToken.assignRelativeLayer(t, task.mSurfaceControl, 1, true);
        }
    }

    void onUserSwitched() {
        PopUpSettingsConfig.getInstance().updateAll();
        // Only proceed if service is properly initialized
        if (mService != null && mService.mSystemReady) {
            findAndExitAllPopUp();
        }
    }

    int getChangeFlags(ChangeInfo info, int flags) {
        final int curMode = info.mContainer.getWindowingMode();
        if (shouldStartChangeTransition(info.mWindowingMode, curMode)
                || WindowConfiguration.isPopUpWindowMode(curMode)) {
            flags |= FLAG_SCHEDULE_POP_UP_VIEW;
            if (mLaunchPopUpViewFromGesture) {
                flags |= FLAG_LAUNCH_POP_UP_VIEW_FROM_GESTURE;
            } else if (mLaunchPopUpViewFromRecents) {
                flags |= FLAG_LAUNCH_POP_UP_VIEW_FROM_RECENTS;
            }
            if (mTryExitWindowingModeByDrag) {
                flags |= FLAG_EXIT_POP_UP_VIEW_BY_DRAG;
            }
        }
        if (WindowConfiguration.isPopUpWindowMode(info.mContainer.getWindowingMode())
                && (info.mIsKeyguardGoingAway || info.mIsMoveTaskToBack)) {
            info.mFlags |= FLAG_CHANGE_SHOULD_SKIP_TRANSITIONS;
        }
        return flags;
    }

    DisplayContent getDefaultDisplayContent() {
        if (mService == null) {
            Slog.w(TAG, "getDefaultDisplayContent: mService is null");
            return null;
        }
        return mService.getDefaultDisplayContentLocked();
    }

    WindowState getDimWinState() {
        return mDimWinState;
    }

    void getPopUpViewTouchOffset(Session session, IWindow window, float[] offsets) {
        synchronized (mService.mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                final WindowState win = mService.windowForClient(session, window);
                if (offsets != null && offsets.length == 4) {
                    offsets[0] = 0.0f;
                    offsets[1] = 0.0f;
                    offsets[2] = 1.0f;
                    offsets[3] = 1.0f;
                    if (win != null && win.getWindowConfiguration().isPopUpWindowMode() &&
                            win.mActivityRecord != null) {
                        final Task rootTask = win.mActivityRecord.getRootTask(task -> task != null);
                        if (rootTask != null && rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo() != null) {
                            final TaskWindowSurfaceInfo info = rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
                            final Rect rect = info.getTaskWindowSurfaceBounds();
                            offsets[0] = rect.left;
                            offsets[1] = rect.top;
                            final float scale = info.getWindowSurfaceRealScale();
                            offsets[2] = scale;
                            offsets[3] = scale;
                        }
                    }
                }
            } catch (IllegalStateException | NullPointerException e) {
                Slog.e(TAG, "Failed to get popup-view touch offset: ", e);
                return;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    void resetBounds(Task task, int currentWindowingMode, int preferredWindowingMode) {
        if (WindowConfiguration.isPopUpWindowMode(currentWindowingMode) &&
                !WindowConfiguration.isPopUpWindowMode(preferredWindowingMode)) {
            task.setBounds(null);
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "setBounds to null for windowing mode change: currentMode="
                        + currentWindowingMode + "->" + preferredWindowingMode);
            }
        }
    }

    void removeChild(Task task) {
        if (tryExitPopUpView(task, true, true, true)) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "removeChild: exit PopUpView window");
            }
        }
    }

    void anyTaskForId(Task targetRootTask, Task task) {
        return;
    }

    void ensureActivityConfiguration(ActivityRecord r) {
        return;
    }

    boolean shouldSkipAppFocusChanged(Task newTask) {
        if (newTask != null && !newTask.getWindowConfiguration().isPopUpWindowMode()
                && TopActivityRecorder.getInstance().hasMiniWindow()) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "skip fullscreen app focus change due to mini-window showing");
            }
            return true;
        }
        if (newTask != null && newTask.mWindowContainerExt.getFreezerSkipAnim()) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "task is under NoWindowModeAnim, should not be focusable");
            }
            return true;
        }
        return false;
    }

    boolean shouldSkipRemoteAnimation(boolean isChanging) {
        return isChanging && mTryExitWindowingMode;
    }

    void onAppFocusChanged(ActivityRecord newFocus, Task newTask) {
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "onAppFocusChanged: newTask=" + newTask);
        }
        if (newFocus != null && newTask != null &&
                newTask.getWindowConfiguration().isPopUpWindowMode()) {
            final Task rootTask = newTask.getRootTask();
            if (rootTask != null) {
                newFocus.mWindowContainerExt.setOrientation(rootTask);
            }
        }
        if (newTask != null && newTask.getWindowConfiguration().isMiniExtWindowMode()) {
            newTask.mWindowContainerExt.setFinishTopTask(false);
        }
    }

    void findAndExitAllPopUp() {
        final ArrayList<Task> miniTasks = DimmerWindowManager.getInstance().getTasksSnapshot();
        for (int i = miniTasks.size() - 1; i >= 0; i--) {
            moveActivityTaskToBack(miniTasks.get(i), MOVE_TO_BACK_TOUCH_OUTSIDE);
        }
    }

    void enforceSinglePopUpPolicy() {
        final Task activeTask = DimmerWindowManager.getInstance().getActiveTask();
        DimmerWindowManager.getInstance().moveOtherTasksToBack(
                activeTask, MOVE_TO_BACK_SINGLE_POP_UP_POLICY);
    }

    private void moveActivityTaskToBackInner(Task task, Task fullTask) {
        ActivityRecord fullTaskActivity = fullTask.getResumedActivity();
        if (fullTaskActivity == null) {
            fullTaskActivity = fullTask.getTopActivity(true, true);
        }
        ActivityRecord taskActivity = task.getResumedActivity();
        if (taskActivity == null) {
            taskActivity = task.getTopActivity(true, true);
        }
        task.startPausing(true, false, fullTaskActivity, "PopUpWindowController.moveActivityTaskToBackInner");
        if (taskActivity != null && task.getDisplayContent() != null) {
            if (taskActivity.getTask() != null &&
                    (taskActivity.getTask() == task || taskActivity.getTask().getParent() == task)) {
                task.moveTaskToBack(taskActivity.getTask());
            }
            final ActivityRecord resumedActivity = task.mRootWindowContainer.getTopResumedActivity();
            if (resumedActivity != null && !resumedActivity.isSleeping()) {
                mAtmService.setLastResumedActivityUncheckLocked(
                        resumedActivity, "PopUpWindowController.moveActivityTaskToBackInner");
            }
        }
    }

    void moveActivityTaskToBack(Task task, int reason) {
        synchronized (mAtmService.mGlobalLock) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "moveActivityTaskToBack, task=" + (task != null ? task : "null")
                        + ", reason=" + reasonToString(reason));
            }
            if (task != null && task.mWindowContainerExt.getTaskWindowSurfaceInfo() != null) {
                // Reset mini-window input focus so the fullscreen app can regain input.
                // Without this, mMiniWindowHasInputFocus stays true after dismissal,
                // causing the fullscreen app to appear frozen (no touch response).
                setMiniWindowInputFocus(false);
                if (reason == MOVE_TO_BACK_TOUCH_OUTSIDE) {
                    TopActivityRecorder.getInstance().clearMiniWindow();
                }
                final TaskWindowSurfaceInfo info = task.mWindowContainerExt.getTaskWindowSurfaceInfo();
                // Hide the DimmerWindow frame immediately to prevent flash after exit animation
                DimmerWindowManager.getInstance().detachTask(task);
                // Remove from TopActivityRecorder so hasMiniWindow() returns false
                // immediately, allowing shouldSkipAppFocusChanged to let the fullscreen
                // app regain focus without waiting for tryExitPopUpView (200ms delay).
                TopActivityRecorder.getInstance().removeMiniWindowTaskFromList(task);
                info.playExitAnimation(reason == MOVE_TO_BACK_FROM_LEAVE_BUTTON,
                        info.getWindowSurfaceRealScale(),
                        () -> {
                            synchronized (mAtmService.mGlobalLock) {
                                if (task.mDisplayContent == null) {
                                    task.mDisplayContent = mService.getDefaultDisplayContentLocked();
                                }
                                final Task fullTask = TopActivityRecorder.getInstance().getTopFullscreenTask();
                                if (fullTask != null) {
                                    task.setAlwaysOnTop(false);
                                    task.mDisplayContent.assignWindowLayers(true);
                                    moveActivityTaskToBackInner(task, fullTask);
                                }
                                mHandler.postDelayed(()-> {
                                    tryExitPopUpView(task, true, reason != MOVE_TO_BACK_NEW_MINI, reason != MOVE_TO_BACK_NEW_PIN);
                                }, EXIT_POP_UP_DELAY);
                            }
                        }
                );
            }
        }
    }

    boolean getOrCreateRootTask(Task candidateTask, DisplayContent displayContent, int windowingMode) {
        if (!WindowConfiguration.isPopUpWindowMode(windowingMode)) {
            return false;
        }
        if (candidateTask == null || displayContent == null) {
            return false;
        }

        final TaskDisplayArea tda = displayContent.getDefaultTaskDisplayArea();
        if (tda == null) {
            return false;
        }

        final Task newRoot = tda.createRootTask(
                windowingMode, candidateTask.getActivityType(), true /* onTop */);
        if (candidateTask.getParent() != newRoot) {
            candidateTask.reparent(newRoot, WindowContainer.POSITION_TOP);
        }
        setWindowingModePopUpView(candidateTask, windowingMode);
        return true;
    }

    void setUpRootTask(Task rootTask, DisplayContent displayContent, int windowingMode) {
        if (!WindowConfiguration.isPopUpWindowMode(windowingMode)) {
            return;
        }
        setWindowingModePopUpView(rootTask, windowingMode);
    }

    boolean startActivityFromRecents(Task task, ActivityOptions activityOptions) {
        if (activityOptions != null && WindowConfiguration.isPopUpWindowMode(
                activityOptions.getLaunchWindowingMode())) {
            updatePendingPopUpLaunchConfig(activityOptions);
        }
        return false;
    }

    void startLockTaskMode(Task task) {
        if (task.getWindowConfiguration().isPopUpWindowMode()) {
            tryExitPopUpView(task, false, true, true);
        }
    }

    void notifyNextRecentIsPin() {
        mNextRecentIsPin = true;
    }

    boolean tryExitPopUpView(Task task, boolean skipAnim, boolean removeMini, boolean removePin) {
        if (task != null && task.getWindowConfiguration().isPopUpWindowMode()) {
            synchronized (mAtmService.mGlobalLock) {
                mAtmService.deferWindowLayout();
                try {
                    final boolean wasMiniWindow = task.getWindowConfiguration().isMiniExtWindowMode();
                    final Task rootTask = task.getRootTask();
                    if (rootTask != null) {
                        if (DEBUG_POP_UP) {
                            Slog.d(TAG, "tryExitPopUpView task=" + task +
                                    " skipAnim=" + skipAnim +
                                    " removeMini=" + removeMini +
                                    ", removePin=" + removePin +
                                    ", wasMiniWindow=" + wasMiniWindow);
                        }
                        rootTask.mWindowContainerExt.setFreezerSkipAnim(skipAnim);
                        if (!skipAnim) {
                            rootTask.mWindowContainerExt.prepareTransition();
                        }
                        rootTask.setAlwaysOnTop(false);
                        rootTask.setWindowingMode(WINDOWING_MODE_UNDEFINED);
                        rootTask.setBounds(null);
                        rootTask.mWindowContainerExt.setFreezerSkipAnim(false);
                        // Update recents ordering so the expanded task appears at the top.
                        // Without this, the task stays at its old position in the recents list,
                        // causing gesture bar quick switch to go to the wrong app.
                        // Note: 'task' may be the root task, not the actual app task.
                        // Find the real task from the root task's top activity.
                        final Task recentsTask;
                        final ActivityRecord topActivity = rootTask.getTopNonFinishingActivity();
                        if (topActivity != null && topActivity.getTask() != null) {
                            recentsTask = topActivity.getTask();
                        } else {
                            recentsTask = task;
                        }
                        // Clear the recents freeze first — it may have been set by a prior
                        // Recents operation and never cleared, preventing the task from moving
                        // to the top of the recents list.
                        mAtmService.getRecentTasks().resetFreezeTaskListReordering(task);
                        mAtmService.getRecentTasks().add(recentsTask);
                        if (skipAnim) {
                            rootTask.mTaskSupervisor.mNoAnimActivities.clear();
                            rootTask.resetSurfaceControlTransforms();
                        }
                        if (removeMini && wasMiniWindow) {
                            TopActivityRecorder.getInstance().removeMiniWindowTask(task);
                        }
                        if (!skipAnim) {
                            rootTask.mWindowContainerExt.scheduleTransition();
                        }
                        return true;
                    }
                } catch (IllegalStateException | NullPointerException e) {
                    Slog.e(TAG, "Failed exit pop-up window: ", e);
                    return false;
                } finally {
                    mAtmService.continueWindowLayout();
                }
            }
        }
        return false;
    }

    void updateFocusedApp() {
        final DisplayContent defaultDisplay = mService.getDefaultDisplayContentLocked();
        final ActivityRecord previousFocusedApp = defaultDisplay.mFocusedApp;
        defaultDisplay.mFocusedApp = null;
        final WindowState win = defaultDisplay.findFocusedWindow();
        defaultDisplay.mFocusedApp = previousFocusedApp;
        if (win != null && win.getActivityRecord() != null) {
            defaultDisplay.setFocusedApp(win.getActivityRecord());
        } else if (win != null && win.getTask() != null) {
            mAtmService.setFocusedTask(win.getTask().mTaskId);
        }
    }

    void enterMiniWindowingMode(WindowState win) {
        synchronized (mAtmService.mGlobalLock) {
            if (win != null) {
                final Task task = win.getTask();
                final Task rootTask = task != null ? task.getRootTask() : null;
                if (rootTask == null) {
                    Slog.e(TAG, "enterMiniWindowingMode: the windowState doesn't have a root task. rootTask=" + rootTask);
                    return;
                }
                rootTask.setWindowingMode(WINDOWING_MODE_MINI_WINDOW_EXT);
                mAtmService.setFocusedTask(rootTask.mTaskId);
                //rootTask.mWindowContainerExt.scheduleTransition();
            }
        }
    }

    private void capturePopUpViewTaskSnapshot(Task task) {
        if (task != null) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "captureTaskSnapshot for task=" + task);
            }
            final ArraySet<Task> tasks = Sets.newArraySet(new Task[] {task});
            mService.mTaskSnapshotController.snapshotTasks(tasks);
        }
    }

    private void setWindowingModePopUpView(Task task, int windowingMode) {
        if (task == null) {
            return;
        }

        final Task rootTask = task.getRootTask();
        final Task targetTask = rootTask != null ? rootTask : task;

        if (!targetTask.getWindowConfiguration().isPopUpWindowMode()) {
            capturePopUpViewTaskSnapshot(targetTask);
            targetTask.mWindowContainerExt.prepareTransition();
            targetTask.setWindowingMode(windowingMode);
        }

        final Task boundsTask = rootTask != null ? rootTask : task;
        final Rect bounds = new Rect();
        boundsTask.mWindowContainerExt.getTaskWindowSurfaceInfo().resetWindowBoundaryGapToOrigin();
        // Use display bounds as the source instead of task's current bounds,
        // which may be from a previous freeform/small-window state and too small.
        if (boundsTask.mDisplayContent != null) {
            boundsTask.mDisplayContent.getBounds(bounds);
        } else {
            boundsTask.getBounds(bounds);
        }
        WindowResizingAlgorithm.getPopUpViewDefalutBounds(bounds);
        boundsTask.setAlwaysOnTop(true);
        boundsTask.setBounds(bounds);

        targetTask.mWindowContainerExt.scheduleTransition();
        boundsTask.moveToFront("popUpView");
        boundsTask.resumeNextFocusAfterReparent();
    }

    void triggerVibrate() {
        triggerVibrationEffect(VibrationEffect.EFFECT_CLICK,
                VibrationEffect.EFFECT_STRENGTH_STRONG);
    }

    void triggerTickVibrate() {
        triggerVibrationEffect(VibrationEffect.EFFECT_TICK,
                VibrationEffect.EFFECT_STRENGTH_MEDIUM);
    }

    void triggerHeavyVibrate() {
        triggerVibrationEffect(VibrationEffect.EFFECT_HEAVY_CLICK,
                VibrationEffect.EFFECT_STRENGTH_STRONG);
    }

    private void triggerVibrationEffect(int effectId, int strength) {
        Slog.d(TAG, "Triggering vibrate");
        mHandler.post(() -> {
            if (mVibrator != null) {
                VibrationEffect effect = VibrationEffect.createPredefined(effectId);
                effect = effect.applyEffectStrength(strength);
                mVibrator.vibrate(effect);
            }
        });
    }

    boolean isTryExitWindowingModeByDrag() {
        return mTryExitWindowingModeByDrag;
    }

    void setTryExitWindowingModeByDrag(boolean isExit) {
        if (mAtmService.getTransitionController().isShellTransitionsEnabled() && !isExit) {
            return;
        }
        mTryExitWindowingModeByDrag = isExit;
    }

    boolean isTryExitWindowingMode() {
        return mTryExitWindowingMode;
    }

    void setTryExitWindowingMode(boolean isExit) {
        mTryExitWindowingMode = isExit;
    }

    boolean isLaunchPopUpViewFromRecents() {
        return mLaunchPopUpViewFromRecents;
    }

    boolean isLaunchPopUpViewFromGesture() {
        return mLaunchPopUpViewFromGesture;
    }

    boolean hasPendingPopUpViewLaunchPoint() {
        return mLaunchPopUpViewFromGesture
                && mNextPopUpViewLaunchPointX >= 0
                && mNextPopUpViewLaunchPointY >= 0
                && !Float.isNaN(mNextPopUpViewLaunchProgress);
    }

    int getNextPopUpViewLaunchPointX() {
        return mNextPopUpViewLaunchPointX;
    }

    int getNextPopUpViewLaunchPointY() {
        return mNextPopUpViewLaunchPointY;
    }

    float getNextPopUpViewLaunchProgress() {
        return mNextPopUpViewLaunchProgress;
    }

    boolean shouldInitializeChangeTransition(Task task, int prevWinMode) {
        if (task.mWindowContainerExt.setPreFreezedWindowingMode(prevWinMode)) {
            if (task.mWindowContainerExt.getFreezerSkipAnim()) {
                return false;
            }
            if (mTryExitWindowingModeByDrag) {
                final TaskWindowSurfaceInfo info = new TaskWindowSurfaceInfo(
                        task.mWindowContainerExt.getTaskWindowSurfaceInfo(), prevWinMode);
                task.mTmpPrevBounds.set(info.getTaskWindowSurfaceBounds());
            }
        }
        return true;
    }

    boolean shouldSkipNextTransitionFreeze() {
        if (!mSkipNextTransitionFreeze) {
            return false;
        }
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "entering multi-window, skip transition freeze");
        }
        mSkipNextTransitionFreeze = false;
        return true;
    }

    boolean shouldStartChangeTransition(int prevWinMode, int newWinMode) {
        if (WindowConfiguration.isMiniExtWindowMode(prevWinMode) !=
                WindowConfiguration.isMiniExtWindowMode(newWinMode)) {
            return true;
        }
        return false;
    }

    void notifyFinishTransition() {
        mTryExitWindowingModeByDrag = false;
        mLaunchPopUpViewFromRecents = false;
        mLaunchPopUpViewFromGesture = false;
        mNextPopUpViewLaunchPointX = -1;
        mNextPopUpViewLaunchPointY = -1;
        mNextPopUpViewLaunchProgress = Float.NaN;
    }

    InsetsState adjustInsetsForWindow(WindowState target, InsetsState state) {
        if (target != null && target.mActivityRecord != null &&
                target.mActivityRecord.getWindowConfiguration().isPopUpWindowMode()) {
            state = new InsetsState(state);
            state.removeSource(ID_DISPLAY_CUTOUT_LEFT);
            state.removeSource(ID_DISPLAY_CUTOUT_TOP);
            state.removeSource(ID_DISPLAY_CUTOUT_RIGHT);
            state.removeSource(ID_DISPLAY_CUTOUT_BOTTOM);
            handleImeInsetsForPopUpView(target, state);
        }
        return state;
    }

    private void handleImeInsetsForPopUpView(WindowState target, InsetsState state) {
        final InsetsSource imeSource = state.peekSource(InsetsSource.ID_IME);
        if (imeSource == null) {
            return;
        }
        final Task task = target.getTask();
        final Task rootTask = task != null ? task.getRootTask() : null;
        final InsetsSource newImeSource = new InsetsSource(imeSource);
        if (rootTask != null && rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo() != null) {
            final TaskWindowSurfaceInfo windowInfo = rootTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
            final Rect displayFrame = target.getDisplayFrame();
            final Rect displayRect = windowInfo.getTaskWindowSurfaceBounds();
            final Rect frame = newImeSource.getFrame();
            final Rect visibleFrame = newImeSource.getVisibleFrame();
            final float scale = windowInfo.getWindowSurfaceRealScale();
            if (frame != null && !frame.isEmpty()) {
                final int frameHeight = Math.max(0, displayRect.bottom - frame.top);
                newImeSource.setFrame(displayFrame.left, displayFrame.bottom - Math.round(
                        (frameHeight * 1.0f) / scale), displayFrame.right, displayFrame.bottom);
            }
            if (visibleFrame != null && !visibleFrame.isEmpty()) {
                final int vfHeight = Math.max(0, displayRect.bottom - visibleFrame.top);
                newImeSource.setVisibleFrame(new Rect(displayFrame.left, displayFrame.bottom - Math.round(
                        (vfHeight * 1.0f) / scale), displayFrame.right, displayFrame.bottom));
            }
        } else {
            newImeSource.setVisible(false);
            newImeSource.setFrame(0, 0, 0, 0);
        }
        state.addSource(newImeSource);
    }

    void calculateTransitionInfo(ArrayList<ChangeInfo> sortedTargets, TransitionInfo out) {
        boolean rotated = false;
        for (int i = 0; i < sortedTargets.size(); i++) {
            final ChangeInfo info = sortedTargets.get(i);
            if (info.mContainer instanceof DisplayContent &&
                    info.mRotation != info.mContainer.getWindowConfiguration().getRotation()) {
                rotated = true;
            }
        }
        if (!rotated) {
            return;
        }
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "Abort pop-up view flags for display ratated");
        }
        for (int i = 0; i < out.getChanges().size(); i++) {
            final Change change = out.getChanges().get(i);
            change.setFlags(change.getFlags() | FLAG_EXIT_POP_UP_VIEW_DISPLAY_ROTATION);
        }
        for (int i = 0; i < sortedTargets.size(); i++) {
            final ChangeInfo info = sortedTargets.get(i);
            info.mReadyFlags = info.mReadyFlags | FLAG_EXIT_POP_UP_VIEW_DISPLAY_ROTATION;
        }
    }

    void computeLaunchParams(LaunchParams params, ActivityOptions options, Task task) {
        if (options == null || !WindowConfiguration.isPopUpWindowMode(
                options.getLaunchWindowingMode())) {
            return;
        }
        updatePendingPopUpLaunchConfig(options);
        return;
    }

    private void updatePendingPopUpLaunchConfig(ActivityOptions options) {
        if (options == null) {
            return;
        }
        mLaunchPopUpViewFromRecents = options.getLaunchTaskId() != -1;
        mNextPopUpViewLaunchPointX = options.getPopUpViewLaunchPointX();
        mNextPopUpViewLaunchPointY = options.getPopUpViewLaunchPointY();
        mNextPopUpViewLaunchProgress = options.getPopUpViewLaunchProgress();
        mLaunchPopUpViewFromGesture = mNextPopUpViewLaunchPointX >= 0
                && mNextPopUpViewLaunchPointY >= 0
                && !Float.isNaN(mNextPopUpViewLaunchProgress);
    }

    void computeBeforeExecuteRequest(Request request) {
        if (!FEATURE_SUPPORTED) {
            if (request.activityOptions != null && request.activityOptions.isPopUpWindowMode()) {
                request.activityOptions.setLaunchWindowingMode(WINDOWING_MODE_UNDEFINED);
            }
            return;
        }

        if (DEBUG_POP_UP) {
            Slog.d(TAG, "computeBeforeExecuteRequest, caller=" + request.callingPackage
                    + ", intent=" + request.intent);
        }

        if ((request.intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "computeBeforeExecuteRequest, skip: original intent requires clear task");
            }
            return;
        }

        final String callerPackage = request.callingPackage;
        final ComponentName component = request.intent.getComponent();
        final String targetPackage = component != null ? component.getPackageName() : "";

        final String currentTopFullscreenPackage = TopActivityRecorder.getInstance().getTopFullscreenPackage();
        final String currentTopMiniPackage = TopActivityRecorder.getInstance().getTopMiniWindowPackage();

        if (request.activityOptions != null && request.activityOptions.isFromNotification()
                && request.activityOptions.isMiniWindowingMode()) {
            // We are jumping to notification. SystemUI already set mini-window options.
            // Do more checks before actually enter mini-window.

            if (currentTopMiniPackage.equals(targetPackage)) {
                // Target package is already in mini-window. Do nothing here.
                return;
            }

            if (currentTopFullscreenPackage.equals(targetPackage)) {
                // Target package is in top fullscreen.
                // Don't reset windowing mode — the caller explicitly requested PopUp mode.
                // Resetting to UNDEFINED would prevent the activity from opening in mini window.
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "computeBeforeExecuteRequest: target is fullscreen, "
                            + "keeping PopUp mode for notification launch");
                }
                return;
            }

            // Reset to fullscreen windowing mode for blacklist targets.
            if (PopUpSettingsConfig.getInstance().inNotificationBlacklist(targetPackage)) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "computeBeforeExecuteRequest, skip: in Notification target blacklist");
                }
                request.activityOptions.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);
                return;
            }

            // All done. Here we go.
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "computeBeforeExecuteRequest, configure: enter Notification");
            }
            return;
        }

        if (currentTopMiniPackage.equals(callerPackage)) {
            // Caller app is in top mini-window. Let's start other packages in mini-window as well.
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "computeBeforeExecuteRequest, configure: starting outside activity from mini-window");
            }
            if (request.activityOptions == null) {
                request.activityOptions = SafeActivityOptions.fromBundle(
                    ActivityOptions.makeBasic().toBundle(), Binder.getCallingPid(), Binder.getCallingUid());
            }
            request.activityOptions.setLaunchWindowingMode(WINDOWING_MODE_MINI_WINDOW_EXT);
            return;
        }
    }

    private String reasonToString(int reason) {
        switch (reason) {
            case MOVE_TO_BACK_TOUCH_OUTSIDE:
                return "TOUCH_OUTSIDE";
            case MOVE_TO_BACK_FROM_LEAVE_BUTTON:
                return "FROM_LEAVY_BUTTON";
            case MOVE_TO_BACK_NEW_MINI:
                return "NEW_MINI";
            case MOVE_TO_BACK_NEW_PIN:
                return "NEW_PIN";
            case MOVE_TO_BACK_NON_USER:
                return "NON_USER";
            case MOVE_TO_BACK_SINGLE_POP_UP_POLICY:
                return "SINGLE_POP_UP_POLICY";
            default:
                return "UNKNOWN";
        }
    }

    /**
    * Check if the mini window currently has input focus.
    * @return true if mini window has focus, false otherwise
    */
    boolean isMiniWindowFocused() {
        final Task miniTask = DimmerWindowManager.getInstance().getActiveTask();
        if (miniTask == null || !miniTask.getWindowConfiguration().isMiniExtWindowMode()) {
            return false;
        }

        synchronized (mService.mGlobalLock) {
            // Method 1: Check window focus
            final DisplayContent dc = getDefaultDisplayContent();
            if (dc != null && dc.mCurrentFocus != null) {
                final Task focusedWindowTask = dc.mCurrentFocus.getTask();
                if (focusedWindowTask == miniTask || focusedWindowTask == miniTask.getRootTask()) {
                    return true;
                }
            }

            // Method 2: Check task focus
            final Task focusedTask = mAtmService.getTopDisplayFocusedRootTask();
            if (focusedTask != null) {
                if (focusedTask == miniTask || focusedTask == miniTask.getRootTask()) {
                    return true;
                }
            }

            // Method 3: Check via FocusedApp
            if (dc != null && dc.mFocusedApp != null) {
                final Task focusedAppTask = dc.mFocusedApp.getTask();
                if (focusedAppTask == miniTask || focusedAppTask == miniTask.getRootTask()) {
                    return true;
                }
            }

            return false;
        }
    }

    /**
    * Get the mini window task, or null if none exists.
    */
    Task getMiniWindowTask() {
        return DimmerWindowManager.getInstance().getActiveTask();
    }

    /**
    * Get the top fullscreen task that is below the mini window.
    * Used for back gesture routing when mini window doesn't have focus.
    */
    Task getTopFullscreenTaskBelowMini() {
        synchronized (mService.mGlobalLock) {
            return TopActivityRecorder.getInstance().getTopFullscreenTask();
        }
    }

    /*
     * Added method to handle Maximize button action.
     * Exits Pop-Up mode, effectively restoring the task to Fullscreen.
     */
    public void exitMiniWindowingMode() {
        exitMiniWindowingMode(DimmerWindowManager.getInstance().getActiveTask());
    }

    public void exitMiniWindowingMode(Task task) {
        if (task == null) {
            return;
        }
        // Flag to indicate we are exiting manually, possibly affecting transition logic
        setTryExitWindowingMode(true);
        // tryExitPopUpView(Task, skipAnim, removeMini, removePin)
        // removeMini=true ensures it clears from TopActivityRecorder tracking
        tryExitPopUpView(task, false, true, true);
        setTryExitWindowingMode(false);
    }

    /**
    * Tracks whether mini window should receive input (back gestures, etc).
    * Different from Android's focus which always gives focus to alwaysOnTop windows.
    */
    private boolean mMiniWindowHasInputFocus = false;

    /**
    * Check if mini window should handle input events like back gestures.
    * This is different from Android's window focus - we track this based on user taps.
    */
    boolean shouldMiniWindowHandleInput() {
        final Task miniTask = DimmerWindowManager.getInstance().getActiveTask();
        if (miniTask == null || !miniTask.getWindowConfiguration().isMiniExtWindowMode()) {
            return false;
        }
        return mMiniWindowHasInputFocus;
    }

    /**
    * Mark mini window as having input focus (user tapped on it).
    */
    void setMiniWindowInputFocus(boolean hasFocus) {
        if (mMiniWindowHasInputFocus != hasFocus) {
            mMiniWindowHasInputFocus = hasFocus;
            Slog.e(TAG, "Mini window input focus changed: " + hasFocus);

            // Notify DimmerWindow to update visuals
            DimmerWindowManager.getInstance().notifyFocusChanged();
        }
    }

    private void setupMiniWindowPointerListener() {
        mMiniWindowPointerListener = event -> {
            if (event.getActionMasked() != android.view.MotionEvent.ACTION_DOWN) {
                return;
            }

            final Task miniTask = DimmerWindowManager.getInstance().getActiveTask();
            if (miniTask == null || !miniTask.getWindowConfiguration().isMiniExtWindowMode()) {
                return;
            }

            final DisplayContent dc = miniTask.getDisplayContent();
            if (dc == null) return;

            final int x = (int) event.getRawX();
            final int y = (int) event.getRawY();

            // Check gesture areas
            final DisplayPolicy policy = dc.getDisplayPolicy();
            final int leftGestureInset = policy.getLeftGestureInset();
            final int rightGestureInset = policy.getRightGestureInset();
            final int displayWidth = dc.mBaseDisplayWidth;

            final boolean inLeftGestureArea = x < leftGestureInset;
            final boolean inRightGestureArea = x > (displayWidth - rightGestureInset);

            if (inLeftGestureArea || inRightGestureArea) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "Touch in gesture area at (" + x + "," + y + "), ignoring");
                }
                return;
            }

            // NEW: Check if Quick Settings or notification shade is expanded
            if (isShadeExpanded(dc)) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "Shade/QuickSettings expanded, ignoring touch");
                }
                return;
            }

            // Check system UI
            if (isInExcludeArea(dc, x, y)) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "Touch in system UI area at (" + x + "," + y + "), ignoring");
                }
                return;
            }

            final Task touchedTask = DimmerWindowManager.getInstance().findTaskAt(x, y);
            final boolean inMiniWindow = touchedTask != null;

            if (DEBUG_POP_UP) {
                Slog.d(TAG, "Touch at (" + x + "," + y + "), inMiniWindow=" + inMiniWindow);
            }

            DimmerWindowManager.getInstance().hideMenus();

            if (inMiniWindow) {
                DimmerWindowManager.getInstance().setActiveTask(touchedTask);
                setMiniWindowInputFocus(
                        DimmerWindowManager.getInstance().shouldTaskHandleInput(touchedTask));
            } else {
                setMiniWindowInputFocus(false);
            }
        };

        mService.registerPointerEventListener(mMiniWindowPointerListener, DEFAULT_DISPLAY);
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "Registered mini window pointer listener");
        }
    }

    private boolean isInExcludeArea(DisplayContent dc, int x, int y) {
        final DisplayPolicy policy = dc.getDisplayPolicy();

        // Check status bar area (top)
        final WindowState statusBar = policy.getStatusBar();
        if (statusBar != null && statusBar.getFrame().contains(x, y)) {
            return true;
        }

        // Check navigation bar area (bottom or sides)
        final WindowState navBar = policy.getNavigationBar();
        if (navBar != null && navBar.getFrame().contains(x, y)) {
            return true;
        }

        return false;
    }

    private boolean isShadeExpanded(DisplayContent dc) {
        // Check if NotificationShade window is visible
        final WindowState shadeWindow = dc.getWindow(w -> {
            final String windowName = w.toString();
            return windowName.contains("NotificationShade");
        });

        if (shadeWindow != null) {
            final boolean visible = shadeWindow.isVisible();
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "NotificationShade window found, isVisible=" + visible);
            }
            return visible;
        }
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "NotificationShade window not found");
        }
        return false;
    }
}
