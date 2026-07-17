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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.view.animation.Interpolator;

import java.util.function.BooleanSupplier;

final class MomentTaskAnimationRunner {

    interface FrameCallback {
        void onFrame(float progress);
    }

    private final WindowManagerService mService;
    private final ArrayMap<MomentTaskSurfaceState, ValueAnimator> mAnimators = new ArrayMap<>();

    MomentTaskAnimationRunner(WindowManagerService service) {
        mService = service;
    }

    void start(MomentTaskSurfaceState state, long duration, @Nullable Interpolator interpolator,
            BooleanSupplier isValid, FrameCallback frameCallback, Runnable endCallback) {
        mService.mAnimationHandler.post(() -> {
            if (!isValid.getAsBoolean()) {
                return;
            }
            final ValueAnimator previous = mAnimators.remove(state);
            if (previous != null) {
                previous.cancel();
            }
            final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            mAnimators.put(state, animator);
            animator.setDuration(duration);
            if (interpolator != null) {
                animator.setInterpolator(interpolator);
            }
            animator.addUpdateListener(animation ->
                    frameCallback.onFrame((float) animation.getAnimatedValue()));
            animator.addListener(new AnimatorListenerAdapter() {
                private boolean mCancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    mCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (mAnimators.get(state) == animation) {
                        mAnimators.remove(state);
                    }
                    if (!mCancelled) {
                        endCallback.run();
                    }
                }
            });
            animator.start();
        });
    }

    void cancel(MomentTaskSurfaceState state) {
        mService.mAnimationHandler.post(() -> {
            final ValueAnimator animator = mAnimators.remove(state);
            if (animator != null) {
                animator.cancel();
            }
        });
    }
}
