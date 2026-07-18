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

import android.graphics.Color;
import android.view.Surface;
import android.view.SurfaceControl;

final class MomentMorphLayer {

    private final Task mTask;
    private SurfaceControl mSurface;

    MomentMorphLayer(Task task) {
        mTask = task;
    }

    void create(SurfaceControl parent, String name) throws Surface.OutOfResourcesException {
        if (mSurface != null) {
            throw new IllegalStateException("Morph layer already exists");
        }
        mSurface = mTask.mWmService.makeSurfaceBuilder()
                .setName(name)
                .setParent(parent)
                .setColorLayer()
                .setCallsite("MomentMorphLayer.create")
                .build();
    }

    boolean isValid() {
        return mSurface != null && mSurface.isValid();
    }

    void show(SurfaceControl.Transaction t, int color, float centerX, float centerY,
            float width, float height, float cornerRadius, float alpha) {
        if (!isValid()) {
            return;
        }
        t.setColor(mSurface, new float[] {
                Color.red(color) / 255f,
                Color.green(color) / 255f,
                Color.blue(color) / 255f})
                .setLayer(mSurface, Integer.MAX_VALUE)
                .show(mSurface);
        applyFrame(t, centerX, centerY, width, height, cornerRadius, alpha);
    }

    boolean applyFrame(SurfaceControl.Transaction t, float centerX, float centerY,
            float width, float height, float cornerRadius, float alpha) {
        if (!isValid()) {
            return false;
        }
        final int cropWidth = Math.max(1, Math.round(width));
        final int cropHeight = Math.max(1, Math.round(height));
        t.setWindowCrop(mSurface, cropWidth, cropHeight)
                .setPosition(mSurface, centerX - cropWidth / 2f,
                        centerY - cropHeight / 2f)
                .setCornerRadius(mSurface, cornerRadius)
                .setAlpha(mSurface, alpha);
        return true;
    }

    void hide(SurfaceControl.Transaction t) {
        if (t != null && isValid()) {
            t.hide(mSurface);
        }
    }

    void destroy(SurfaceControl.Transaction t) {
        if (mSurface == null) {
            return;
        }
        if (t != null && mSurface.isValid()) {
            t.remove(mSurface);
        }
        mSurface.release();
        mSurface = null;
    }
}
