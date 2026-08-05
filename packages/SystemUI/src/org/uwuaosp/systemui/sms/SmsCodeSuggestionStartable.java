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

package org.uwuaosp.systemui.sms;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;

import com.android.systemui.CoreStartable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.clipboardoverlay.ClipboardOverlayController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.settings.UserTracker;

import javax.inject.Inject;
import javax.inject.Provider;

@SysUISingleton
public final class SmsCodeSuggestionStartable implements CoreStartable {
    private static final String ACTION_SMS_CODE_RECEIVED =
            "org.uwuaosp.systemui.action.SMS_CODE_RECEIVED";
    private static final String EXTRA_CODE = "code";

    private final Context mContext;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Provider<ClipboardOverlayController> mOverlayProvider;
    private final UserTracker mUserTracker;

    private ClipboardOverlayController mOverlay;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_SMS_CODE_RECEIVED.equals(intent.getAction())) {
                return;
            }
            String code = intent.getStringExtra(EXTRA_CODE);
            if (TextUtils.isEmpty(code) || !canShowSuggestion()) {
                return;
            }

            if (mOverlay == null) {
                mOverlay = mOverlayProvider.get();
                mOverlay.setOnSessionCompleteListener(() -> mOverlay = null);
            }
            mOverlay.setVerificationCode(code);
        }
    };

    @Inject
    public SmsCodeSuggestionStartable(
            Context context,
            BroadcastDispatcher broadcastDispatcher,
            Provider<ClipboardOverlayController> overlayProvider,
            UserTracker userTracker) {
        mContext = context;
        mBroadcastDispatcher = broadcastDispatcher;
        mOverlayProvider = overlayProvider;
        mUserTracker = userTracker;
    }

    @Override
    public void start() {
        mBroadcastDispatcher.registerReceiver(
                mReceiver,
                new IntentFilter(ACTION_SMS_CODE_RECEIVED),
                null,
                UserHandle.ALL,
                Context.RECEIVER_EXPORTED,
                Manifest.permission.STATUS_BAR);
    }

    private boolean canShowSuggestion() {
        Context userContext = mUserTracker.getUserContext();
        KeyguardManager keyguardManager = userContext.getSystemService(KeyguardManager.class);
        if (keyguardManager != null && keyguardManager.isDeviceLocked()) {
            return false;
        }
        return Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE,
                0,
                mUserTracker.getUserHandle().getIdentifier()) == 1;
    }
}
