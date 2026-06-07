/*
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.provider.Settings.System.POP_UP_NOTIFICATION_BLACKLIST;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.util.android.PopUpViewManager;

class PopUpSettingsConfig {

    private static final String TAG = "PopUpSettingsConfig";

    private static class InstanceHolder {
        private static final PopUpSettingsConfig INSTANCE = new PopUpSettingsConfig();
    }

    static PopUpSettingsConfig getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private final ArraySet<String> mUserNotificationBlacklist = new ArraySet<>();

    private Context mContext;
    private Handler mHandler;
    private SettingsObserver mObserver;

    void init(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mObserver = new SettingsObserver(handler);
        mObserver.observe();
        updateAll();
    }

    private void updateNotificationBlacklist() {
        mUserNotificationBlacklist.clear();
        if (mContext == null) {
            Log.w(TAG, "Context is null, cannot update notification blacklist");
            return;
        }
        final String blacklist = Settings.System.getStringForUser(
                mContext.getContentResolver(),
                POP_UP_NOTIFICATION_BLACKLIST,
                UserHandle.USER_CURRENT);
        if (TextUtils.isEmpty(blacklist)) {
            return;
        }
        final String[] apps = blacklist.split(";");
        for (String app : apps) {
            mUserNotificationBlacklist.add(app);
        }
    }

    boolean inNotificationBlacklist(String packageName) {
        return PopUpViewManager.inSystemNotificationBlacklist(packageName) ||
                mUserNotificationBlacklist.contains(packageName);
    }

    void updateAll() {
        if (mHandler != null) {
            mHandler.post(() -> {
                updateNotificationBlacklist();
            });
        } else {
            Log.w(TAG, "Handler is null, updating settings synchronously");
            updateNotificationBlacklist();
        }
    }

    private final class SettingsObserver extends ContentObserver {

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            if (mContext == null) {
                Log.e(TAG, "Context is null, cannot register content observer");
                return;
            }
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.System.getUriFor(POP_UP_NOTIFICATION_BLACKLIST),
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            switch (uri.getLastPathSegment()) {
                case POP_UP_NOTIFICATION_BLACKLIST:
                    updateNotificationBlacklist();
                    break;
            }
        }
    }
}
