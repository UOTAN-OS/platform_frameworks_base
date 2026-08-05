/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.clipboardoverlay;

import static android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.CLIPBOARD_OVERLAY_SHOW_ACTIONS;
import static com.android.systemui.Flags.clipboardOverlayMultiuser;
import static com.android.systemui.Flags.showClipboardIndication;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ACTION_SHOWN;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ACTION_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_DISMISSED_OTHER;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_EDIT_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_EXPANDED_FROM_MINIMIZED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_REMOTE_COPY_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_SHARE_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_SHOWN_EXPANDED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_SHOWN_MINIMIZED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_SWIPE_DISMISSED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_TAP_OUTSIDE;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_TIMED_OUT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.ActivityOptions;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.PersistableBundle;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.WindowInsets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.clipboardoverlay.dagger.ClipboardOverlayModule.OverlayWindowContext;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.plugins.ActivityStartOptions;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.screenshot.TimeoutHandler;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.FlashlightController;

import kotlin.Unit;

import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Controls state and UI for the overlay that appears when something is added to the clipboard
 */
public class ClipboardOverlayController implements ClipboardListener.ClipboardOverlay,
        ClipboardOverlayView.ClipboardOverlayCallbacks {
    private static final String TAG = "ClipboardOverlayCtrlr";
    private static final String SMS_CLIP_SOURCE = "uwuaosp_sms";
    private static final String TORCH_CLIP_SOURCE = "uwuaosp_torch";
    private static final String MUSIC_CLIP_SOURCE = "uwuaosp_music";

    /** Constants for screenshot/copy deconflicting */
    public static final String SCREENSHOT_ACTION = "com.android.systemui.SCREENSHOT";
    public static final String SELF_PERMISSION = "com.android.systemui.permission.SELF";
    public static final String COPY_OVERLAY_ACTION = "com.android.systemui.COPY";
    private static final Object ACTIVE_OVERLAY_LOCK = new Object();
    @Nullable private static ClipboardOverlayController sActiveOverlay;

    private static final int CLIPBOARD_DEFAULT_TIMEOUT_MILLIS = 6000;

    private final Context mContext;
    private final ClipboardLogger mClipboardLogger;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final ClipboardOverlayWindow mWindow;
    private final TimeoutHandler mTimeoutHandler;
    private final ClipboardOverlayUtils mClipboardUtils;
    private final ClipboardAppSuggestionUtils mClipboardAppSuggestionUtils;
    private final Executor mBgExecutor;
    private final ClipboardImageLoader mClipboardImageLoader;
    private final ClipboardTransitionExecutor mTransitionExecutor;
    private final ClipboardInputEventReceiver mClipboardInputEventReceiver;
    private final ActivityStarter mActivityStarter;
    private final UserTracker mUserTracker;
    private final IStatusBarService mStatusBarService;
    private final FlashlightController mFlashlightController;


    private final ClipboardOverlayView mView;
    private final ClipboardIndicationProvider mClipboardIndicationProvider;
    private final IntentCreator mIntentCreator;

    private Runnable mOnSessionCompleteListener;
    private boolean mSessionComplete;

    private BroadcastReceiver mCloseDialogsReceiver;
    private BroadcastReceiver mScreenshotReceiver;

    private Animator mExitAnimator;
    private Animator mEnterAnimator;

    private Runnable mOnUiUpdate;

    private boolean mShowingUi;
    private boolean mIsMinimized;
    private ClipboardModel mClipboardModel;
    @Nullable private String mVerificationCode;
    @Nullable private Drawable mMusicSuggestionIcon;
    @Nullable private Intent mMusicSuggestionIntent;
    @Nullable private CharSequence mMusicSuggestionDescription;
    private OverlayMode mOverlayMode = OverlayMode.CLIPBOARD;
    private ClipboardIndicationCallback mIndicationCallback = new ClipboardIndicationCallback() {
        @Override
        public void onIndicationTextChanged(@NonNull CharSequence text) {
            mView.setIndicationText(text);
        }
    };

    private enum OverlayMode {
        CLIPBOARD,
        VERIFICATION_CODE,
        TORCH_SUGGESTION,
        MUSIC_SUGGESTION,
    }

    @Inject
    public ClipboardOverlayController(@OverlayWindowContext Context context,
            ClipboardOverlayView clipboardOverlayView,
            ClipboardOverlayWindow clipboardOverlayWindow,
            BroadcastDispatcher broadcastDispatcher,
            BroadcastSender broadcastSender,
            TimeoutHandler timeoutHandler,
            ActivityStarter activityStarter,
            UserTracker userTracker,
            ClipboardOverlayUtils clipboardUtils,
            ClipboardAppSuggestionUtils clipboardAppSuggestionUtils,
            @Background Executor bgExecutor,
            ClipboardImageLoader clipboardImageLoader,
            ClipboardTransitionExecutor transitionExecutor,
            ClipboardInputEventReceiver clipboardInputEventReceiver,
            ClipboardIndicationProvider clipboardIndicationProvider,
            UiEventLogger uiEventLogger,
            IStatusBarService statusBarService,
            FlashlightController flashlightController,
            IntentCreator intentCreator) {
        mContext = context;
        mBroadcastDispatcher = broadcastDispatcher;
        mClipboardImageLoader = clipboardImageLoader;
        mTransitionExecutor = transitionExecutor;
        mClipboardInputEventReceiver = clipboardInputEventReceiver;
        mClipboardIndicationProvider = clipboardIndicationProvider;
        mActivityStarter = activityStarter;
        mUserTracker = userTracker;
        mStatusBarService = statusBarService;
        mFlashlightController = flashlightController;

        mClipboardLogger = new ClipboardLogger(uiEventLogger);
        mIntentCreator = intentCreator;

        mView = clipboardOverlayView;
        mWindow = clipboardOverlayWindow;
        mWindow.init(this::onInsetsChanged, () -> {
            mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_DISMISSED_OTHER);
            hideImmediate();
        });

        mTimeoutHandler = timeoutHandler;
        mTimeoutHandler.setDefaultTimeoutMillis(CLIPBOARD_DEFAULT_TIMEOUT_MILLIS);

        mClipboardUtils = clipboardUtils;
        mClipboardAppSuggestionUtils = clipboardAppSuggestionUtils;
        mBgExecutor = bgExecutor;

        mView.setCallbacks(this);

        mWindow.withWindowAttached(() -> {
            mWindow.setContentView(mView);
            mView.setInsets(mWindow.getWindowInsets(),
                    mContext.getResources().getConfiguration().orientation);
        });

        mTimeoutHandler.setOnTimeoutRunnable(() -> finish(CLIPBOARD_OVERLAY_TIMED_OUT));

        mCloseDialogsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())
                        || Intent.ACTION_SCREEN_OFF.equals(intent.getAction())
                        || Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                    finish(CLIPBOARD_OVERLAY_DISMISSED_OTHER);
                }
            }
        };

        IntentFilter closeFilter = new IntentFilter(ACTION_CLOSE_SYSTEM_DIALOGS);
        closeFilter.addAction(Intent.ACTION_SCREEN_OFF);
        closeFilter.addAction(Intent.ACTION_USER_SWITCHED);
        mBroadcastDispatcher.registerReceiver(mCloseDialogsReceiver, closeFilter, null, null,
                Context.RECEIVER_NOT_EXPORTED);

        mScreenshotReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (SCREENSHOT_ACTION.equals(intent.getAction())) {
                    finish(CLIPBOARD_OVERLAY_DISMISSED_OTHER);
                }
            }
        };

        mBroadcastDispatcher.registerReceiver(mScreenshotReceiver,
                new IntentFilter(SCREENSHOT_ACTION), null, null, Context.RECEIVER_EXPORTED,
                SELF_PERMISSION);

        ClipboardOverlayController previousOverlay;
        synchronized (ACTIVE_OVERLAY_LOCK) {
            previousOverlay = sActiveOverlay;
            sActiveOverlay = this;
        }
        if (previousOverlay != null) {
            previousOverlay.hideImmediate();
        }

        Intent copyIntent = new Intent(COPY_OVERLAY_ACTION);
        copyIntent.setPackage(mContext.getPackageName());
        broadcastSender.sendBroadcast(copyIntent, SELF_PERMISSION);
        monitorOutsideTouches();

    }

    @VisibleForTesting
    void onInsetsChanged(WindowInsets insets, int orientation) {
        mView.setInsets(insets, orientation);
        if (shouldShowMinimized(insets) && !mIsMinimized) {
            mIsMinimized = true;
            mView.setMinimized(true);
        }
    }

    @Override // ClipboardListener.ClipboardOverlay
    public void setClipData(ClipData data, String source) {
        mOverlayMode = OverlayMode.CLIPBOARD;
        mVerificationCode = null;
        mMusicSuggestionIcon = null;
        mMusicSuggestionIntent = null;
        mMusicSuggestionDescription = null;
        ClipboardModel model = ClipboardModel.fromClipData(mContext, mClipboardUtils, data, source);
        boolean wasExiting = (mExitAnimator != null && mExitAnimator.isRunning());
        if (wasExiting) {
            mExitAnimator.cancel();
        }
        boolean shouldAnimate = !model.dataMatches(mClipboardModel) || wasExiting;
        mClipboardModel = model;
        mClipboardLogger.setClipSource(mClipboardModel.getSource());
        if (showClipboardIndication()) {
            mClipboardIndicationProvider.getIndicationText(mIndicationCallback);
        }
        if (shouldAnimate) {
            reset();
            mClipboardLogger.setClipSource(mClipboardModel.getSource());
            if (shouldShowMinimized(mWindow.getWindowInsets())) {
                mClipboardLogger.logUnguarded(CLIPBOARD_OVERLAY_SHOWN_MINIMIZED);
                mIsMinimized = true;
                mView.setMinimized(true);
                animateInWithAnnouncement(mClipboardModel.getType());
            } else {
                mClipboardLogger.logUnguarded(CLIPBOARD_OVERLAY_SHOWN_EXPANDED);
                setExpandedView(() -> {
                    animateInWithAnnouncement(mClipboardModel.getType());
                });
            }
        } else if (!mIsMinimized) {
            setExpandedView(() -> {
            });
        }
        if (mClipboardModel.isRemote()) {
            mTimeoutHandler.cancelTimeout();
            mOnUiUpdate = null;
        } else {
            mOnUiUpdate = mTimeoutHandler::resetTimeout;
            mOnUiUpdate.run();
        }
    }

    public void setVerificationCode(String code) {
        mOverlayMode = OverlayMode.VERIFICATION_CODE;
        mClipboardModel = null;
        mMusicSuggestionIcon = null;
        mMusicSuggestionIntent = null;
        mMusicSuggestionDescription = null;
        boolean wasExiting = (mExitAnimator != null && mExitAnimator.isRunning());
        if (wasExiting) {
            mExitAnimator.cancel();
        }
        boolean shouldAnimate = !TextUtils.equals(code, mVerificationCode) || wasExiting;
        mVerificationCode = code;
        mClipboardLogger.setClipSource(SMS_CLIP_SOURCE);
        if (shouldAnimate) {
            reset();
            mClipboardLogger.setClipSource(SMS_CLIP_SOURCE);
            mClipboardLogger.logUnguarded(CLIPBOARD_OVERLAY_SHOWN_EXPANDED);
            setVerificationCodeView(() ->
                    animateInWithAnnouncement(
                            mContext.getString(R.string.uwu_sms_code_overlay_announcement)));
        } else {
            setVerificationCodeView(() -> { });
        }
        mTimeoutHandler.cancelTimeout();
        mOnUiUpdate = null;
    }

    public void setTorchSuggestion() {
        OverlayMode previousMode = mOverlayMode;
        mOverlayMode = OverlayMode.TORCH_SUGGESTION;
        mClipboardModel = null;
        mVerificationCode = null;
        boolean wasExiting = (mExitAnimator != null && mExitAnimator.isRunning());
        if (wasExiting) {
            mExitAnimator.cancel();
        }
        boolean shouldAnimate =
                previousMode != OverlayMode.TORCH_SUGGESTION || wasExiting || !mShowingUi;
        mClipboardLogger.setClipSource(TORCH_CLIP_SOURCE);
        if (shouldAnimate) {
            reset();
            mClipboardLogger.setClipSource(TORCH_CLIP_SOURCE);
            mClipboardLogger.logUnguarded(CLIPBOARD_OVERLAY_SHOWN_EXPANDED);
            setTorchSuggestionView(() ->
                    animateInWithAnnouncement(
                            mContext.getString(
                                    R.string.uwu_torch_suggestion_chip_content_description)));
        } else {
            setTorchSuggestionView(() -> { });
        }
        mTimeoutHandler.cancelTimeout();
        mOnUiUpdate = null;
    }

    public void setMusicSuggestion(
            Drawable icon, CharSequence description, Intent launchIntent) {
        OverlayMode previousMode = mOverlayMode;
        mOverlayMode = OverlayMode.MUSIC_SUGGESTION;
        mClipboardModel = null;
        mVerificationCode = null;
        mMusicSuggestionIcon = icon;
        mMusicSuggestionIntent = new Intent(launchIntent);
        mMusicSuggestionDescription = description;
        boolean wasExiting = (mExitAnimator != null && mExitAnimator.isRunning());
        if (wasExiting) {
            mExitAnimator.cancel();
        }
        boolean shouldAnimate =
                previousMode != OverlayMode.MUSIC_SUGGESTION || wasExiting || !mShowingUi;
        mClipboardLogger.setClipSource(MUSIC_CLIP_SOURCE);
        if (shouldAnimate) {
            reset();
            mClipboardLogger.setClipSource(MUSIC_CLIP_SOURCE);
            mClipboardLogger.logUnguarded(CLIPBOARD_OVERLAY_SHOWN_EXPANDED);
            setMusicSuggestionView(() ->
                    animateInWithAnnouncement(
                            mContext.getString(
                                    R.string.uwu_music_suggestion_chip_content_description)));
        } else {
            setMusicSuggestionView(() -> { });
        }
        mTimeoutHandler.cancelTimeout();
        mOnUiUpdate = null;
    }

    public void dismissSuggestion() {
        if (mShowingUi || (mEnterAnimator != null && mEnterAnimator.isRunning())) {
            finish(CLIPBOARD_OVERLAY_DISMISSED_OTHER);
        }
    }

    private void setExpandedView(Runnable onViewReady) {
        final ClipboardModel model = mClipboardModel;
        mView.setMinimized(false);
        switch (model.getType()) {
            case TEXT:
                maybeShowTextAction(model);
                if (model.isSensitive()) {
                    mView.showTextPreview(mContext.getString(R.string.clipboard_asterisks), true);
                } else {
                    mView.showTextPreview(model.getText().toString(), false);
                }
                mView.setEditAccessibilityAction(true);
                onViewReady.run();
                break;
            case IMAGE:
                mView.setEditAccessibilityAction(true);
                if (model.isSensitive()) {
                    mView.showImagePreview(null);
                    onViewReady.run();
                } else {
                    mClipboardImageLoader.loadAsync(model.getUri(), (bitmap) -> mView.post(() -> {
                        if (bitmap == null) {
                            mView.showDefaultTextPreview();
                        } else {
                            mView.showImagePreview(bitmap);
                        }
                        onViewReady.run();
                    }));
                }
                break;
            case URI:
            case OTHER:
                mView.showDefaultTextPreview();
                onViewReady.run();
                break;
        }
        if (!model.isRemote()) {
            maybeShowRemoteCopy(model.getClipData());
        }
        if (model.getType() != ClipboardModel.Type.OTHER) {
            mView.showShareChip();
        }
    }

    private void setVerificationCodeView(Runnable onViewReady) {
        final String code = mVerificationCode;
        if (TextUtils.isEmpty(code)) {
            return;
        }
        mView.setMinimized(false);
        mView.setPreviewVisible(true);
        mView.resetActionChips();
        mView.setRemoteCopyVisibility(false);
        mView.showTextPreview(code, false);
        mView.addActionChip(
                Icon.createWithResource(mContext, com.android.internal.R.drawable.ic_menu_copy_material)
                        .loadDrawable(mContext),
                null,
                mContext.getString(R.string.uwu_sms_code_copy),
                true,
                () -> {
                    ClipboardManager clipboardManager =
                            mContext.getSystemService(ClipboardManager.class);
                    if (clipboardManager != null) {
                        ClipData clipData = ClipData.newPlainText(null, code);
                        PersistableBundle extras = new PersistableBundle();
                        extras.putBoolean(ClipboardListener.EXTRA_SUPPRESS_OVERLAY, true);
                        clipData.getDescription().setExtras(extras);
                        clipboardManager.setPrimaryClipAsPackage(clipData, ClipboardListener.SHELL_PACKAGE);
                    }
                    finish(CLIPBOARD_OVERLAY_ACTION_TAPPED);
                });
        mView.addActionChip(
                Icon.createWithResource(mContext, R.drawable.ic_arrow_up_24dp)
                        .loadDrawable(mContext),
                null,
                mContext.getString(R.string.uwu_sms_code_fill_in),
                true,
                () -> {
                    try {
                        if (mStatusBarService.commitTextToFocusedInput(code, mContext.getDisplayId())) {
                            finish(CLIPBOARD_OVERLAY_ACTION_TAPPED);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to commit verification code", e);
                    }
                });
        onViewReady.run();
    }

    private void setTorchSuggestionView(Runnable onViewReady) {
        mView.setMinimized(false);
        mView.setPreviewVisible(false);
        mView.resetActionChips();
        mView.setRemoteCopyVisibility(false);
        mView.addActionChip(
                Icon.createWithResource(mContext, R.drawable.vd_flashlight_off).loadDrawable(mContext),
                null,
                mContext.getString(R.string.uwu_torch_suggestion_chip_content_description),
                true,
                () -> {
                    mFlashlightController.setFlashlight(false);
                    finish(CLIPBOARD_OVERLAY_ACTION_TAPPED);
                });
        onViewReady.run();
    }

    private void setMusicSuggestionView(Runnable onViewReady) {
        if (mMusicSuggestionIcon == null
                || mMusicSuggestionIntent == null
                || TextUtils.isEmpty(mMusicSuggestionDescription)) {
            return;
        }
        mView.setMinimized(false);
        mView.setPreviewVisible(false);
        mView.resetActionChips();
        mView.setRemoteCopyVisibility(false);
        mView.addActionChip(
                mMusicSuggestionIcon,
                null,
                mMusicSuggestionDescription,
                () -> finish(
                        CLIPBOARD_OVERLAY_ACTION_TAPPED,
                        new Intent(mMusicSuggestionIntent)));
        onViewReady.run();
    }

    private boolean shouldShowMinimized(WindowInsets insets) {
        return mOverlayMode == OverlayMode.CLIPBOARD
                && insets.getInsets(WindowInsets.Type.ime()).bottom > 0;
    }

    private void animateFromMinimized() {
        if (mEnterAnimator != null && mEnterAnimator.isRunning()) {
            mEnterAnimator.cancel();
        }
        mEnterAnimator = mView.getMinimizedFadeoutAnimation();
        mEnterAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (mIsMinimized) {
                    mClipboardLogger.logUnguarded(CLIPBOARD_OVERLAY_EXPANDED_FROM_MINIMIZED);
                    mIsMinimized = false;
                }
                if (mOverlayMode == OverlayMode.VERIFICATION_CODE) {
                    setVerificationCodeView(() -> animateIn());
                } else if (mOverlayMode == OverlayMode.TORCH_SUGGESTION) {
                    setTorchSuggestionView(() -> animateIn());
                } else if (mOverlayMode == OverlayMode.MUSIC_SUGGESTION) {
                    setMusicSuggestionView(() -> animateIn());
                } else {
                    setExpandedView(() -> animateIn());
                }
            }
        });
        mEnterAnimator.start();
    }

    private String getAccessibilityAnnouncement(ClipboardModel.Type type) {
        if (type == ClipboardModel.Type.TEXT) {
            return mContext.getString(R.string.clipboard_text_copied);
        } else if (type == ClipboardModel.Type.IMAGE) {
            return mContext.getString(R.string.clipboard_image_copied);
        } else {
            return mContext.getString(R.string.clipboard_content_copied);
        }
    }

    private void maybeShowTextAction(ClipboardModel model) {
        mBgExecutor.execute(() -> {
            Optional<RemoteAction> remoteAction = mClipboardAppSuggestionUtils.getAction(
                    model.getText(), model.getSource());
            if (remoteAction.isEmpty()
                    && model.getTextLinks() != null
                    && (model.isRemote() || DeviceConfig.getBoolean(
                            DeviceConfig.NAMESPACE_SYSTEMUI,
                            CLIPBOARD_OVERLAY_SHOW_ACTIONS,
                            false))) {
                remoteAction = mClipboardUtils.getAction(model.getTextLinks(), model.getSource());
            }
            if (model.equals(mClipboardModel)) {
                remoteAction.ifPresent(action -> {
                    mClipboardLogger.logUnguarded(CLIPBOARD_OVERLAY_ACTION_SHOWN);
                    mView.post(
                            () -> mView.setActionChip(action,
                                    () -> finish(CLIPBOARD_OVERLAY_ACTION_TAPPED)));
                });
            }
        });
    }

    private void maybeShowRemoteCopy(ClipData clipData) {
        Intent remoteCopyIntent = mIntentCreator.getRemoteCopyIntent(clipData, mContext);

        // Only show remote copy if it's available.
        PackageManager packageManager = mContext.getPackageManager();
        if (packageManager.resolveActivity(
                remoteCopyIntent, PackageManager.ResolveInfoFlags.of(0)) != null) {
            mView.setRemoteCopyVisibility(true);
        } else {
            mView.setRemoteCopyVisibility(false);
        }
    }

    @Override // ClipboardListener.ClipboardOverlay
    public void setOnSessionCompleteListener(Runnable runnable) {
        if (mSessionComplete) {
            runnable.run();
            return;
        }
        mOnSessionCompleteListener = runnable;
    }

    private void monitorOutsideTouches() {
        mClipboardInputEventReceiver.monitorOutsideTouches(event -> {
            if (mShowingUi && event instanceof MotionEvent motionEvent) {
                if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    if (shouldDismissOnOutsideTap()
                            && !mView.isInTouchRegion(
                            (int) motionEvent.getRawX(), (int) motionEvent.getRawY())) {
                        finish(CLIPBOARD_OVERLAY_TAP_OUTSIDE);
                    }
                }
            }
            return Unit.INSTANCE;
        });
    }

    private boolean shouldDismissOnOutsideTap() {
        return mOverlayMode != OverlayMode.VERIFICATION_CODE
                && mOverlayMode != OverlayMode.TORCH_SUGGESTION
                && mOverlayMode != OverlayMode.MUSIC_SUGGESTION;
    }

    private void animateInWithAnnouncement(ClipboardModel.Type type) {
        animateInWithAnnouncement(getAccessibilityAnnouncement(type));
    }

    private void animateInWithAnnouncement(CharSequence announcement) {
        Animator entrance = animateIn();
        entrance.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mView.announce(announcement);
            }
        });
    }

    private Animator animateIn() {
        if (mEnterAnimator != null && mEnterAnimator.isRunning()) {
            return mEnterAnimator;
        }
        mEnterAnimator = mView.getEnterAnimation();
        mEnterAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                super.onAnimationStart(animation);
                mShowingUi = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                // check again after animation to see if we should still be minimized
                if (mIsMinimized && !shouldShowMinimized(mWindow.getWindowInsets())) {
                    animateFromMinimized();
                }
                if (mOnUiUpdate != null) {
                    mOnUiUpdate.run();
                }
            }
        });
        mEnterAnimator.start();
        return mEnterAnimator;
    }

    private void finish(ClipboardOverlayEvent event) {
        finish(event, null);
    }

    private void finish(ClipboardOverlayEvent event, @Nullable Intent intent) {
        if (mExitAnimator != null && mExitAnimator.isRunning()) {
            return;
        }
        mExitAnimator = mView.getExitAnimation();
        mExitAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                super.onAnimationCancel(animation);
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (!mCancelled) {
                    mClipboardLogger.logSessionComplete(event);
                    if (intent != null) {
                        if (clipboardOverlayMultiuser()) {
                            mActivityStarter.startActivityDismissingKeyguard(
                                    new ActivityStartOptions(intent, false, false, null,
                                            intent.getFlags(), null, null, false,
                                            mUserTracker.getUserHandle(),
                                            ActivityOptions.makeBasic()
                                                    .setLaunchDisplayId(mContext.getDisplayId())));
                        } else {
                            mContext.startActivity(intent);
                        }
                    }
                    hideImmediate();
                }
            }
        });
        mExitAnimator.start();
    }

    private void finishWithSharedTransition(ClipboardOverlayEvent event, Intent intent) {
        if (mExitAnimator != null && mExitAnimator.isRunning()) {
            return;
        }
        mClipboardLogger.logSessionComplete(event);
        mExitAnimator = mView.getFadeOutAnimation();
        mExitAnimator.start();
        mTransitionExecutor.startSharedTransition(
                mWindow, mView.getPreview(), intent, this::hideImmediate);
    }

    void hideImmediate() {
        // Note this may be called multiple times if multiple dismissal events happen at the same
        // time.
        if (mSessionComplete) {
            return;
        }
        mSessionComplete = true;
        synchronized (ACTIVE_OVERLAY_LOCK) {
            if (sActiveOverlay == this) {
                sActiveOverlay = null;
            }
        }
        mTimeoutHandler.cancelTimeout();
        mWindow.remove();
        if (mCloseDialogsReceiver != null) {
            mBroadcastDispatcher.unregisterReceiver(mCloseDialogsReceiver);
            mCloseDialogsReceiver = null;
        }
        if (mScreenshotReceiver != null) {
            mBroadcastDispatcher.unregisterReceiver(mScreenshotReceiver);
            mScreenshotReceiver = null;
        }
        mClipboardInputEventReceiver.dispose();
        if (mOnSessionCompleteListener != null) {
            mOnSessionCompleteListener.run();
        }
    }

    private void reset() {
        mShowingUi = false;
        mIsMinimized = false;
        mView.reset();
        mTimeoutHandler.cancelTimeout();
        mClipboardLogger.reset();
    }

    @Override
    public void onRemoteCopyButtonTapped() {
        finish(CLIPBOARD_OVERLAY_REMOTE_COPY_TAPPED,
                mIntentCreator.getRemoteCopyIntent(
                        mClipboardModel.getClipData(), mContext));
    }

    @Override
    public void onShareButtonTapped() {
        Intent shareIntent =
                mIntentCreator.getShareIntent(
                        mClipboardModel.getClipData(), mContext);
        switch (mClipboardModel.getType()) {
            case TEXT:
            case URI:
                finish(CLIPBOARD_OVERLAY_SHARE_TAPPED, shareIntent);
                break;
            case IMAGE:
                finishWithSharedTransition(CLIPBOARD_OVERLAY_SHARE_TAPPED, shareIntent);
                break;
        }
    }

    @Override
    public void onPreviewTapped() {
        if (mOverlayMode != OverlayMode.CLIPBOARD || mClipboardModel == null) {
            return;
        }
        switch (mClipboardModel.getType()) {
            case TEXT:
                finish(CLIPBOARD_OVERLAY_EDIT_TAPPED,
                        mIntentCreator.getTextEditorIntent(mContext));
                break;
            case IMAGE:
                mIntentCreator.getImageEditIntentAsync(
                        mClipboardModel.getUri(), mContext,
                        intent -> finishWithSharedTransition(
                                CLIPBOARD_OVERLAY_EDIT_TAPPED, intent));
                break;
            default:
                Log.w(TAG, "Got preview tapped callback for non-editable type "
                        + mClipboardModel.getType());
        }
    }

    @Override
    public void onMinimizedViewTapped() {
        animateFromMinimized();
    }

    @Override
    public void onInteraction() {
        if (mOverlayMode == OverlayMode.VERIFICATION_CODE
                || mOverlayMode == OverlayMode.TORCH_SUGGESTION
                || mOverlayMode == OverlayMode.MUSIC_SUGGESTION) {
            return;
        }
        if (mOverlayMode != OverlayMode.CLIPBOARD || mClipboardModel == null) {
            mTimeoutHandler.resetTimeout();
            return;
        }
        if (!mClipboardModel.isRemote()) {
            mTimeoutHandler.resetTimeout();
        }
    }

    @Override
    public void onSwipeDismissInitiated(Animator animator) {
        if (mExitAnimator != null && mExitAnimator.isRunning()) {
            mExitAnimator.cancel();
        }
        mExitAnimator = animator;
        mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_SWIPE_DISMISSED);
    }

    @Override
    public void onDismissComplete() {
        hideImmediate();
    }

    static class ClipboardLogger {
        private final UiEventLogger mUiEventLogger;
        private String mClipSource;
        private boolean mGuarded = false;

        ClipboardLogger(UiEventLogger uiEventLogger) {
            mUiEventLogger = uiEventLogger;
        }

        void setClipSource(String clipSource) {
            mClipSource = clipSource;
        }

        void logUnguarded(@NonNull UiEventLogger.UiEventEnum event) {
            mUiEventLogger.log(event, 0, mClipSource);
        }

        void logSessionComplete(@NonNull UiEventLogger.UiEventEnum event) {
            if (!mGuarded) {
                mGuarded = true;
                mUiEventLogger.log(event, 0, mClipSource);
            }
        }

        void reset() {
            mGuarded = false;
            mClipSource = null;
        }
    }
}
