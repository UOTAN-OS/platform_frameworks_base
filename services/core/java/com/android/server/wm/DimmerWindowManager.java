/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import android.graphics.Rect;
import android.util.ArrayMap;

import java.util.ArrayList;

/**
 * Manages Pop-Up View decoration windows per task.
 */
class DimmerWindowManager {

    private static final class InstanceHolder {
        private static final DimmerWindowManager INSTANCE = new DimmerWindowManager();
    }

    static DimmerWindowManager getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private final ArrayMap<Task, DimmerWindow> mWindows = new ArrayMap<>();
    private final ArrayList<Task> mWindowOrder = new ArrayList<>();

    private Task mActiveTask;

    private DimmerWindowManager() {
    }

    void attachTask(Task task) {
        if (task == null) {
            return;
        }
        if (PopUpSettingsConfig.getInstance().isLegacyUiMode()
                && mActiveTask != null && mActiveTask != task) {
            PopUpWindowController.getInstance().moveActivityTaskToBack(
                    mActiveTask, PopUpWindowController.MOVE_TO_BACK_NEW_MINI);
        }
        DimmerWindow window = getOrCreate(task);
        window.show();
        setActiveTask(task);
    }

    void detachTask(Task task) {
        if (task == null) {
            return;
        }
        DimmerWindow window = mWindows.remove(task);
        if (window != null) {
            window.destroy();
        }
        mWindowOrder.remove(task);
        if (task == mActiveTask) {
            mActiveTask = mWindowOrder.isEmpty() ? null : mWindowOrder.get(mWindowOrder.size() - 1);
        }
        updateFocusState();
    }

    void clearAll() {
        for (int i = mWindows.size() - 1; i >= 0; i--) {
            mWindows.valueAt(i).destroy();
        }
        mWindows.clear();
        mWindowOrder.clear();
        mActiveTask = null;
    }

    void setActiveTask(Task task) {
        if (task == null) {
            mActiveTask = null;
            updateFocusState();
            return;
        }
        DimmerWindow window = getOrCreate(task);
        window.show();
        mWindowOrder.remove(task);
        mWindowOrder.add(task);
        if (mActiveTask != task) {
            mActiveTask = task;
            updateFocusState();
        }
    }

    Task getActiveTask() {
        return mActiveTask;
    }

    boolean shouldTaskHandleInput(Task task) {
        final DimmerWindow window = task != null ? mWindows.get(task) : null;
        return window == null || window.shouldHandleInput();
    }

    Rect getActiveBounds() {
        DimmerWindow window = mActiveTask != null ? mWindows.get(mActiveTask) : null;
        return window != null ? window.getBounds() : null;
    }

    Task findTaskAt(int x, int y) {
        for (int i = mWindowOrder.size() - 1; i >= 0; i--) {
            Task task = mWindowOrder.get(i);
            DimmerWindow window = mWindows.get(task);
            if (window == null) {
                continue;
            }
            Rect bounds = window.getBounds();
            if (bounds != null && bounds.contains(x, y)) {
                return task;
            }
        }
        return null;
    }

    void onDragResizeChanged(Task task, float scale, Rect taskWindowSurfaceBound,
            boolean isLandscape) {
        DimmerWindow window = mWindows.get(task);
        if (window != null) {
            window.onDragResizeChanged(scale, taskWindowSurfaceBound, isLandscape);
        }
    }

    void onResizeChanged(Task task) {
        DimmerWindow window = mWindows.get(task);
        if (window != null) {
            window.onResizeChanged();
        }
    }

    void notifyFocusChanged() {
        updateFocusState();
    }

    void hideMenus() {
        for (int i = mWindows.size() - 1; i >= 0; i--) {
            mWindows.valueAt(i).hideMenu();
        }
    }

    ArrayList<Task> getTasksSnapshot() {
        return new ArrayList<>(mWindowOrder);
    }

    private DimmerWindow getOrCreate(Task task) {
        DimmerWindow window = mWindows.get(task);
        if (window == null) {
            window = new DimmerWindow(task);
            mWindows.put(task, window);
            mWindowOrder.add(task);
        }
        return window;
    }

    private void updateFocusState() {
        final boolean hasFocus = PopUpWindowController.getInstance().shouldMiniWindowHandleInput();
        for (int i = mWindows.size() - 1; i >= 0; i--) {
            Task task = mWindows.keyAt(i);
            DimmerWindow window = mWindows.valueAt(i);
            window.updateTopBarFocus(hasFocus && task == mActiveTask);
        }
    }
}
