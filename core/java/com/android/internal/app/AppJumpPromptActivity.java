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

package com.android.internal.app;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;

import java.util.ArrayList;

/**
 * A dialog-like activity shown before a user app launches another user app.
 */
public class AppJumpPromptActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "AppJumpPromptActivity";
    private static final String PACKAGE_NAME = "com.android.internal.app";

    private static final String EXTRA_MODE = PACKAGE_NAME + ".extra.MODE";
    private static final String EXTRA_SOURCE_PACKAGE = PACKAGE_NAME + ".extra.SOURCE_PACKAGE";
    private static final String EXTRA_TARGET_PACKAGE = PACKAGE_NAME + ".extra.TARGET_PACKAGE";

    private static final int MODE_CONFIRM = 1;
    private static final int MODE_BLOCKED = 2;

    private int mUserId;
    private String mSourcePackage;
    private String mTargetPackage;
    private IntentSender mTarget;
    private CheckBox mRememberChoiceView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        setFinishOnTouchOutside(true);

        final Intent intent = getIntent();
        final int mode = intent.getIntExtra(EXTRA_MODE, 0);
        mUserId = intent.getIntExtra(Intent.EXTRA_USER_ID, -1);
        mSourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE);
        mTargetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE);
        mTarget = intent.getParcelableExtra(Intent.EXTRA_INTENT, IntentSender.class);

        if ((mode != MODE_CONFIRM && mode != MODE_BLOCKED)
                || mUserId < 0 || mSourcePackage == null || mTargetPackage == null
                || (mode == MODE_CONFIRM && mTarget == null)) {
            Log.wtf(TAG, "Invalid app jump prompt intent: " + intent);
            finish();
            return;
        }

        setContentView(R.layout.app_jump_prompt_dialog);
        bindViews(mode);
    }

    private void bindViews(int mode) {
        final TextView titleView = requireViewById(R.id.app_jump_title);
        final TextView messageView = requireViewById(R.id.app_jump_message);
        final ImageView sourceIconView = requireViewById(R.id.app_jump_source_icon);
        final ImageView targetIconView = requireViewById(R.id.app_jump_target_icon);
        final TextView sourceLabelView = requireViewById(R.id.app_jump_source_label);
        final TextView targetLabelView = requireViewById(R.id.app_jump_target_label);
        final Button allowButton = requireViewById(R.id.app_jump_allow_button);
        final Button denyButton = requireViewById(R.id.app_jump_deny_button);
        final Button allowOnceButton = requireViewById(R.id.app_jump_allow_once_button);
        final Button okButton = requireViewById(R.id.app_jump_ok_button);
        mRememberChoiceView = requireViewById(R.id.app_jump_remember_choice);
        final TextView footerView = requireViewById(R.id.app_jump_footer);
        final ViewGroup buttonGroup = requireViewById(R.id.app_jump_button_group);

        final CharSequence sourceLabel = loadAppLabel(mSourcePackage);
        final CharSequence targetLabel = loadAppLabel(mTargetPackage);
        final boolean isConfirm = mode == MODE_CONFIRM;
        sourceIconView.setImageDrawable(loadAppIcon(mSourcePackage));
        targetIconView.setImageDrawable(loadAppIcon(mTargetPackage));
        sourceLabelView.setText(sourceLabel);
        targetLabelView.setText(targetLabel);

        titleView.setText(isConfirm
                ? R.string.app_jump_prompt_title
                : R.string.app_jump_blocked_title);
        messageView.setText(isConfirm
                ? getString(R.string.app_jump_prompt_message, sourceLabel, targetLabel)
                : getString(R.string.app_jump_blocked_message, sourceLabel, targetLabel));

        allowButton.setVisibility(isConfirm ? View.VISIBLE : View.GONE);
        denyButton.setVisibility(isConfirm ? View.VISIBLE : View.GONE);
        allowOnceButton.setVisibility(!isConfirm && mTarget != null ? View.VISIBLE : View.GONE);
        okButton.setVisibility(isConfirm ? View.GONE : View.VISIBLE);
        mRememberChoiceView.setVisibility(isConfirm ? View.VISIBLE : View.GONE);
        mRememberChoiceView.setChecked(false);
        footerView.setText(R.string.app_jump_footer_message);
        updateActionButtonBackgrounds(buttonGroup, allowButton, denyButton, allowOnceButton,
                okButton);

        allowButton.setOnClickListener(this);
        denyButton.setOnClickListener(this);
        allowOnceButton.setOnClickListener(this);
        okButton.setOnClickListener(this);
    }

    private void updateActionButtonBackgrounds(ViewGroup buttonGroup, Button... buttons) {
        final ArrayList<Button> visibleButtons = new ArrayList<>();
        for (Button button : buttons) {
            if (button.getVisibility() == View.VISIBLE) {
                visibleButtons.add(button);
            }
        }
        buttonGroup.setVisibility(visibleButtons.isEmpty() ? View.GONE : View.VISIBLE);
        final int topMargin = getResources().getDimensionPixelOffset(
                R.dimen.app_jump_action_button_margin_top);
        final int bottomMargin = getResources().getDimensionPixelOffset(
                R.dimen.app_jump_action_button_margin_bottom);
        final int buttonCount = visibleButtons.size();
        for (int i = 0; i < buttonCount; i++) {
            final Button button = visibleButtons.get(i);
            button.setBackgroundResource(resolveActionButtonBackground(buttonCount, i));
            final ViewGroup.LayoutParams layoutParams = button.getLayoutParams();
            if (layoutParams instanceof ViewGroup.MarginLayoutParams marginLayoutParams) {
                marginLayoutParams.topMargin = i == 0 ? 0 : topMargin;
                marginLayoutParams.bottomMargin = i == buttonCount - 1 ? bottomMargin : 0;
                button.setLayoutParams(marginLayoutParams);
            }
        }
    }

    private int resolveActionButtonBackground(int buttonCount, int buttonIndex) {
        if (buttonCount <= 1) {
            return R.drawable.app_jump_action_background_single;
        }
        if (buttonIndex == 0) {
            return R.drawable.app_jump_action_background_top;
        }
        if (buttonIndex == buttonCount - 1) {
            return R.drawable.app_jump_action_background_bottom;
        }
        return R.drawable.app_jump_action_background_middle;
    }

    private CharSequence loadAppLabel(String packageName) {
        try {
            final ApplicationInfo appInfo =
                    getPackageManager().getApplicationInfoAsUser(packageName, 0, mUserId);
            return appInfo.loadSafeLabel(getPackageManager(),
                    PackageItemInfo.DEFAULT_MAX_LABEL_SIZE_PX,
                    PackageItemInfo.SAFE_LABEL_FLAG_FIRST_LINE
                            | PackageItemInfo.SAFE_LABEL_FLAG_TRIM);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Unable to resolve app label for " + packageName, e);
            return packageName;
        }
    }

    private Drawable loadAppIcon(String packageName) {
        try {
            final ApplicationInfo appInfo =
                    getPackageManager().getApplicationInfoAsUser(packageName, 0, mUserId);
            return appInfo.loadIcon(getPackageManager());
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Unable to resolve app icon for " + packageName, e);
            return getPackageManager().getDefaultActivityIcon();
        }
    }

    @Override
    public void onClick(View v) {
        final int id = v.getId();
        if (id == R.id.app_jump_allow_button) {
            if (shouldRememberChoice()) {
                persistPairMode(ActivityTaskManager.APP_JUMP_SOURCE_MODE_ALLOW);
            }
            launchOriginalIntent();
            return;
        }
        if (id == R.id.app_jump_allow_once_button) {
            launchOriginalIntent();
            return;
        }
        if (id == R.id.app_jump_deny_button) {
            if (shouldRememberChoice()) {
                persistPairMode(ActivityTaskManager.APP_JUMP_SOURCE_MODE_BLOCK);
            }
            finish();
            return;
        }
        finish();
    }

    private void launchOriginalIntent() {
        if (mTarget == null) {
            Log.w(TAG, "No target intent sender, finishing");
            finish();
            return;
        }
        final Bundle activityOptions = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle();
        try {
            startIntentSenderForResult(mTarget, -1, null, 0, 0, 0, activityOptions);
        } catch (IntentSender.SendIntentException | ActivityNotFoundException e) {
            Log.e(TAG, "Unable to continue app jump", e);
        }
        finish();
    }

    private boolean shouldRememberChoice() {
        return mRememberChoiceView != null
                && mRememberChoiceView.getVisibility() == View.VISIBLE
                && mRememberChoiceView.isChecked();
    }

    private void persistPairMode(int mode) {
        try {
            ActivityTaskManager.getService().setAppJumpPairMode(
                    mSourcePackage, mTargetPackage, mUserId, mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public static Intent createConfirmIntent(Context context, int userId, String sourcePackage,
            String targetPackage, IntentSender target) {
        return new Intent()
                .setClass(context, AppJumpPromptActivity.class)
                .putExtra(EXTRA_MODE, MODE_CONFIRM)
                .putExtra(Intent.EXTRA_USER_ID, userId)
                .putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
                .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                .putExtra(Intent.EXTRA_INTENT, target)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }

    public static Intent createBlockedIntent(Context context, int userId, String sourcePackage,
            String targetPackage, IntentSender target) {
        return new Intent()
                .setClass(context, AppJumpPromptActivity.class)
                .putExtra(EXTRA_MODE, MODE_BLOCKED)
                .putExtra(Intent.EXTRA_USER_ID, userId)
                .putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
                .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                .putExtra(Intent.EXTRA_INTENT, target)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }
}
