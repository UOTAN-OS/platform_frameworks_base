/*
 * Copyright (C) 2026 The LineageOS-Sado Project
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

package com.android.systemui.volume;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import com.android.systemui.plugins.VolumeDialogSafetyController;
import com.android.systemui.dagger.SysUISingleton;

import java.util.Collections;
import java.util.Optional;

import javax.inject.Inject;

/** Keeps hearing-protection dialogs in SystemUI while allowing the volume UI to be a plugin. */
@SysUISingleton
public final class VolumeDialogSafetyControllerImpl
        implements VolumeDialogSafetyController {
    private final Object mLock = new Object();
    private final Context mContext;
    private final AudioManager mAudioManager;
    private final CsdWarningDialog.Factory mCsdWarningDialogFactory;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private SafetyWarningDialog mSafetyWarning;
    private CsdWarningDialog mCsdWarning;
    private Runnable mCsdTimeout;

    @Inject
    public VolumeDialogSafetyControllerImpl(
            Context context,
            AudioManager audioManager,
            CsdWarningDialog.Factory csdWarningDialogFactory) {
        mContext = context;
        mAudioManager = audioManager;
        mCsdWarningDialogFactory = csdWarningDialogFactory;
    }

    @Override
    public void showSafetyWarning(int flags) {
        if ((flags & (AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_SHOW_UI_WARNINGS)) == 0) {
            return;
        }
        mHandler.post(() -> {
            synchronized (mLock) {
                if (mSafetyWarning != null) {
                    return;
                }
                mSafetyWarning = new SafetyWarningDialog(mContext, mAudioManager) {
                    @Override
                    protected void cleanUp() {
                        synchronized (mLock) {
                            mSafetyWarning = null;
                        }
                    }
                };
                mSafetyWarning.show();
            }
        });
    }

    @Override
    public void showCsdWarning(int warning, int durationMs) {
        mHandler.post(() -> {
            synchronized (mLock) {
                if (mCsdWarning != null) {
                    return;
                }
                mCsdWarning = mCsdWarningDialogFactory.create(
                        warning,
                        () -> {
                            synchronized (mLock) {
                                mCsdWarning = null;
                                cancelCsdTimeoutLocked();
                            }
                        },
                        Optional.of(Collections.emptyList()));
                mCsdWarning.show();
                if (durationMs > 0) {
                    mCsdTimeout = () -> {
                        synchronized (mLock) {
                            if (mCsdWarning != null) {
                                mCsdWarning.dismiss();
                            }
                        }
                    };
                    mHandler.postDelayed(mCsdTimeout, durationMs);
                }
            }
        });
    }

    @Override
    public void dismissWarnings() {
        mHandler.post(() -> {
            synchronized (mLock) {
                if (mSafetyWarning != null) {
                    mSafetyWarning.dismiss();
                }
                if (mCsdWarning != null) {
                    mCsdWarning.dismiss();
                }
                cancelCsdTimeoutLocked();
            }
        });
    }

    private void cancelCsdTimeoutLocked() {
        if (mCsdTimeout != null) {
            mHandler.removeCallbacks(mCsdTimeout);
            mCsdTimeout = null;
        }
    }
}
