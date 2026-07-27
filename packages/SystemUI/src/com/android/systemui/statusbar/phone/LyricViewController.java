/*
 * Copyright (C) 2022 Project Kaleidoscope
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

package com.android.systemui.statusbar.phone;

import android.app.Notification;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Drawable.ConstantState;
import android.graphics.drawable.Icon;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageSwitcher;
import android.widget.ImageView;
import android.widget.TextSwitcher;
import android.widget.TextView;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Dependency;
import com.android.systemui.res.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.StatusBarIconView;

import java.util.ArrayList;

public abstract class LyricViewController implements
    DarkIconDispatcher.DarkReceiver,
    NotificationListener.NotificationHandler {
    public static final int LYRIC_POSITION_OVERLAY = 0;
    public static final int LYRIC_POSITION_CLOCK_RIGHT = 1;

    private static final String EXTRA_TICKER_ICON = "ticker_icon";
    private static final String EXTRA_TICKER_ICON_PACKAGE = "ticker_icon_package";
    private static final String EXTRA_TICKER_SMALL_ICON = "ticker_small_icon";
    private static final String EXTRA_TICKER_ICON_SWITCH = "ticker_icon_switch";
    private static final String EXTRA_TICKER_TRANSLATION = "ticker_translation";
    private static final String LYRIC_FETCH_PACKAGE = "cn.binbin323.statuslyricext";

    private static final int HIDE_LYRIC_DELAY = 1200;

    private final Context mContext;
    private final LyricViewHolder mOverlayLyricViewHolder;
    private final LyricViewHolder mInlineLyricViewHolder;
    private final View mTintReferenceView;

    private final ContrastColorUtil mNotificationColorUtil;

    private boolean mEnabled;
    private boolean mStarted;
    private boolean mShowOnClockRight;
    private boolean mShowTranslation;
    private boolean mHideIconOnClockRight;

    private String mCurrentNotificationPackage = null;
    private CharSequence mCurrentTranslatedText;
    private int mCurrentNotificationId;

    private int mOverlayTintColor = DarkIconDispatcher.DEFAULT_ICON_TINT;
    private int mInlineTintColor = DarkIconDispatcher.DEFAULT_ICON_TINT;

    public LyricViewController(Context context, View statusBar, View tintReferenceView) {
        mContext = context;
        mTintReferenceView = tintReferenceView;
        mOverlayLyricViewHolder = createLyricViewHolder(
                statusBar,
                R.id.lyric_container,
                R.id.lyric_icon,
                R.id.lyric_text,
                R.id.lyric_translation,
                true);
        mInlineLyricViewHolder = createLyricViewHolder(
                statusBar,
                R.id.lyric_inline_container,
                R.id.lyric_inline_icon,
                R.id.lyric_inline_text,
                R.id.lyric_inline_translation,
                false);

        mNotificationColorUtil = ContrastColorUtil.getInstance(mContext);

        Animation animationIn = AnimationUtils.loadAnimation(mContext,
                com.android.internal.R.anim.push_up_in);
        Animation animationOut = AnimationUtils.loadAnimation(mContext,
                com.android.internal.R.anim.push_up_out);

        setUpAnimations(mOverlayLyricViewHolder, animationIn, animationOut);
        if (mInlineLyricViewHolder != null) {
            setUpAnimations(mInlineLyricViewHolder, animationIn, animationOut);
        }

        View.OnTouchListener touchListener = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideLyricView(true);
                v.postDelayed(() -> showLyricView(true), HIDE_LYRIC_DELAY);
            }
            return false;
        };
        mOverlayLyricViewHolder.mLyricContainer.setOnTouchListener(touchListener);
        if (mInlineLyricViewHolder != null) {
            mInlineLyricViewHolder.mLyricContainer.setOnTouchListener(touchListener);
        }

        hideInactiveLyricViewsImmediately();

        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
        Dependency.get(NotificationListener.class).addNotificationHandler(this);
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        if (!mEnabled && mStarted) {
            stopLyric();
        }
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public void setLyricPosition(int position) {
        boolean showOnClockRight =
                position == LYRIC_POSITION_CLOCK_RIGHT && mInlineLyricViewHolder != null;
        if (mShowOnClockRight == showOnClockRight) {
            return;
        }

        LyricViewHolder previousHolder = getActiveLyricViewHolder();
        mShowOnClockRight = showOnClockRight;
        syncHolderContent(previousHolder, getActiveLyricViewHolder());
        updateIconVisibility();
        hideInactiveLyricViewsImmediately();
        postApplyTextTint();
        if (mStarted) {
            onLyricPositionChanged();
        }
    }

    public void setHideIconOnClockRight(boolean hideIconOnClockRight) {
        if (mHideIconOnClockRight == hideIconOnClockRight) {
            return;
        }
        mHideIconOnClockRight = hideIconOnClockRight;
        updateIconVisibility();
        postApplyTextTint();
    }

    public void setShowTranslation(boolean showTranslation) {
        if (mShowTranslation == showTranslation) {
            return;
        }
        mShowTranslation = showTranslation;
        CharSequence translatedText = getVisibleTranslatedText();
        setSubtitle(mOverlayLyricViewHolder, translatedText);
        if (mInlineLyricViewHolder != null) {
            setSubtitle(mInlineLyricViewHolder, translatedText);
        }
    }

    protected void onLyricPositionChanged() {
    }

    protected final boolean isClockRightMode() {
        return mShowOnClockRight && mInlineLyricViewHolder != null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        if (!mEnabled) return;

        Notification notification = sbn.getNotification();
        boolean isLyric = ((notification.flags & Notification.FLAG_ALWAYS_SHOW_TICKER) != 0)
                && ((notification.flags & Notification.FLAG_ONLY_UPDATE_TICKER) != 0)
                && isPackageAllowed(getLyricSourcePackage(sbn, notification));

        boolean isCurrentNotification = mCurrentNotificationId == sbn.getId() &&
                TextUtils.equals(sbn.getPackageName(), mCurrentNotificationPackage);
        if (!isLyric) {
            if (isCurrentNotification) {
                stopLyric();
            }
        } else {
            mCurrentNotificationPackage = sbn.getPackageName();
            mCurrentNotificationId = sbn.getId();

            if (notification.tickerText == null) {
                stopLyric();
                return;
            }
            if (!isCurrentNotification || !mStarted ||
                    notification.extras.getBoolean(EXTRA_TICKER_ICON_SWITCH, false)) {
                setIconForAllHolders(resolveLyricIcon(sbn, notification));
            }
            startLyric();
            setTextForAllHolders(
                    notification.tickerText,
                    notification.extras.getString(EXTRA_TICKER_TRANSLATION));
        }
    }

    private String getLyricSourcePackage(StatusBarNotification sbn, Notification notification) {
        String notificationPackage = sbn.getPackageName();
        if (!LYRIC_FETCH_PACKAGE.equals(notificationPackage)) {
            return notificationPackage;
        }
        return notification.extras.getString(EXTRA_TICKER_ICON_PACKAGE, notificationPackage);
    }

    private boolean isPackageAllowed(String packageName) {
        String value = Settings.Secure.getString(
                mContext.getContentResolver(), Settings.Secure.STATUS_BAR_LYRIC_ALLOWED_PACKAGES);
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        for (String allowedPackage : value.split(";")) {
            if (TextUtils.equals(packageName, allowedPackage.trim())) {
                return true;
            }
        }
        return false;
    }

    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        boolean isCurrentNotification = mCurrentNotificationId == sbn.getId() &&
                TextUtils.equals(sbn.getPackageName(), mCurrentNotificationPackage);
        if (isCurrentNotification) {
            stopLyric();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        onNotificationRemoved(sbn, rankingMap);
    }

    @Override
    public void onNotificationRankingUpdate(RankingMap rankingMap) {
    }

    @Override
    public void onNotificationsInitialized() {
    }

    public void startLyric() {
        if (!mStarted) {
            mStarted = true;
            showLyricView(true);
            postApplyTextTint();
        }
    }

    public void stopLyric() {
        if (mStarted) {
            mStarted = false;
            hideLyricView(true);
            mCurrentNotificationPackage = null;
            mCurrentNotificationId = 0;
        }
    }

    public abstract void showLyricView(boolean animate);

    public abstract void hideLyricView(boolean animate);

    public boolean isLyricStarted() {
        return mStarted;
    }

    protected final View getLyricView() {
        return getActiveLyricViewHolder().mLyricContainer;
    }

    protected final View getOverlayLyricView() {
        return mOverlayLyricViewHolder.mLyricContainer;
    }

    protected final View getInlineLyricView() {
        return mInlineLyricViewHolder == null ? null : mInlineLyricViewHolder.mLyricContainer;
    }

    private void updateIconTint() {
        updateIconTint(mOverlayLyricViewHolder, mOverlayTintColor);
        if (mInlineLyricViewHolder != null) {
            updateIconTint(mInlineLyricViewHolder, mInlineTintColor);
        }
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> area, float darkIntensity, int tint) {
        int textTint = mTintReferenceView != null
                ? DarkIconDispatcher.getTint(area, mTintReferenceView, tint)
                : tint;
        mOverlayTintColor = textTint;
        mInlineTintColor = textTint;
        applyTextTint();
    }

    private LyricViewHolder createLyricViewHolder(
            View statusBar,
            int containerId,
            int iconId,
            int textId,
            int subtitleTextId,
            boolean required) {
        View lyricContainer = statusBar.findViewById(containerId);
        if (lyricContainer == null) {
            if (required) {
                throw new IllegalStateException("Missing lyric container: " + containerId);
            }
            return null;
        }
        TextSwitcher subtitleTextSwitcher =
                subtitleTextId != View.NO_ID ? lyricContainer.findViewById(subtitleTextId) : null;
        return new LyricViewHolder(
                lyricContainer,
                lyricContainer.requireViewById(iconId),
                lyricContainer.requireViewById(textId),
                subtitleTextSwitcher);
    }

    private void setUpAnimations(
            LyricViewHolder lyricViewHolder, Animation animationIn, Animation animationOut) {
        lyricViewHolder.mTextSwitcher.setInAnimation(animationIn);
        lyricViewHolder.mTextSwitcher.setOutAnimation(animationOut);
        lyricViewHolder.mIconSwitcher.setInAnimation(animationIn);
        lyricViewHolder.mIconSwitcher.setOutAnimation(animationOut);
        if (lyricViewHolder.mSubtitleTextSwitcher != null) {
            lyricViewHolder.mSubtitleTextSwitcher.setInAnimation(null);
            lyricViewHolder.mSubtitleTextSwitcher.setOutAnimation(null);
        }
    }

    private LyricViewHolder getActiveLyricViewHolder() {
        return isClockRightMode() ? mInlineLyricViewHolder : mOverlayLyricViewHolder;
    }

    private void hideInactiveLyricViewsImmediately() {
        if (mOverlayLyricViewHolder != getActiveLyricViewHolder()) {
            hideViewImmediately(mOverlayLyricViewHolder.mLyricContainer);
        }
        if (mInlineLyricViewHolder != null && mInlineLyricViewHolder != getActiveLyricViewHolder()) {
            hideViewImmediately(mInlineLyricViewHolder.mLyricContainer);
        }
    }

    private void hideViewImmediately(View view) {
        view.animate().cancel();
        view.setAlpha(0f);
        view.setVisibility(View.GONE);
    }

    private void syncHolderContent(LyricViewHolder from, LyricViewHolder to) {
        if (from == null || to == null || from == to) {
            return;
        }

        Drawable currentDrawable = ((ImageView) from.mIconSwitcher.getCurrentView()).getDrawable();
        if (currentDrawable != null) {
            to.mIconSwitcher.setImageDrawable(copyDrawable(currentDrawable));
        }
        CharSequence currentText = ((TextView) from.mTextSwitcher.getCurrentView()).getText();
        if (!TextUtils.isEmpty(currentText)) {
            to.mTextSwitcher.setCurrentText(currentText);
        }
        syncSubtitle(from, to);
        if (to.mSubtitleTextSwitcher != null) {
            setSubtitle(to, getVisibleTranslatedText());
        }
        updateIconTint(to, getTintColorForHolder(to));
    }

    private void setIconForAllHolders(Drawable icon) {
        mOverlayLyricViewHolder.mIconSwitcher.setImageDrawable(copyDrawable(icon));
        if (mInlineLyricViewHolder != null) {
            mInlineLyricViewHolder.mIconSwitcher.setImageDrawable(copyDrawable(icon));
        }
        updateIconTint();
        updateIconVisibility();
    }

    private void setTextForAllHolders(CharSequence text, CharSequence translatedText) {
        mCurrentTranslatedText = translatedText;
        mOverlayLyricViewHolder.mTextSwitcher.setText(text);
        setSubtitle(mOverlayLyricViewHolder, getVisibleTranslatedText());
        if (mInlineLyricViewHolder != null) {
            mInlineLyricViewHolder.mTextSwitcher.setText(text);
            setSubtitle(mInlineLyricViewHolder, getVisibleTranslatedText());
        }
        postApplyTextTint();
    }

    private Drawable resolveLyricIcon(StatusBarNotification sbn, Notification notification) {
        Icon mediaSmallIcon = notification.extras.getParcelable(
                EXTRA_TICKER_SMALL_ICON, Icon.class);
        if (mediaSmallIcon != null) {
            Drawable drawable = mediaSmallIcon.loadDrawable(mContext);
            if (drawable != null) {
                return drawable;
            }
        }

        String iconPackage = notification.extras.getString(EXTRA_TICKER_ICON_PACKAGE);
        if (!TextUtils.isEmpty(iconPackage)) {
            try {
                return mContext.getPackageManager().getApplicationIcon(iconPackage);
            } catch (Exception ignored) {
            }
        }
        int iconId = notification.extras.getInt(EXTRA_TICKER_ICON, -1);
        return iconId == -1 ? notification.getSmallIcon().loadDrawable(mContext) :
                StatusBarIconView.getIcon(mContext, sbn.getPackageContext(mContext),
                        new StatusBarIcon(sbn.getPackageName(), sbn.getUser(),
                            iconId, notification.iconLevel, 0, null,
                            StatusBarIcon.Type.NotifSmallIcon));
    }

    private CharSequence getVisibleTranslatedText() {
        return mShowTranslation ? mCurrentTranslatedText : null;
    }

    private void updateIconVisibility() {
        if (mInlineLyricViewHolder != null) {
            mInlineLyricViewHolder.mIconSwitcher.setVisibility(
                    mHideIconOnClockRight && isClockRightMode() ? View.GONE : View.VISIBLE);
        }
    }

    private Drawable copyDrawable(Drawable drawable) {
        ConstantState constantState = drawable.getConstantState();
        return constantState != null ? constantState.newDrawable().mutate() : drawable;
    }

    private void updateIconTint(LyricViewHolder lyricViewHolder, int tintColor) {
        Drawable drawable = ((ImageView) lyricViewHolder.mIconSwitcher.getCurrentView()).getDrawable();
        if (drawable == null) {
            return;
        }
        boolean isGrayscale = mNotificationColorUtil.isGrayscaleIcon(drawable);
        ImageView currentView = (ImageView) lyricViewHolder.mIconSwitcher.getCurrentView();
        ImageView nextView = (ImageView) lyricViewHolder.mIconSwitcher.getNextView();
        ColorStateList tintList = ColorStateList.valueOf(tintColor);
        if (isGrayscale) {
            currentView.setImageTintList(tintList);
            nextView.setImageTintList(tintList);
        } else {
            currentView.setImageTintList(null);
            nextView.setImageTintList(null);
        }
    }

    private void updateTextTint(LyricViewHolder lyricViewHolder, int tintColor) {
        ((TextView) lyricViewHolder.mTextSwitcher.getCurrentView()).setTextColor(tintColor);
        ((TextView) lyricViewHolder.mTextSwitcher.getNextView()).setTextColor(tintColor);
        if (lyricViewHolder.mSubtitleTextSwitcher != null) {
            ((TextView) lyricViewHolder.mSubtitleTextSwitcher.getCurrentView()).setTextColor(tintColor);
            ((TextView) lyricViewHolder.mSubtitleTextSwitcher.getNextView()).setTextColor(tintColor);
        }
    }

    private void setSubtitle(LyricViewHolder lyricViewHolder, CharSequence translatedText) {
        if (lyricViewHolder.mSubtitleTextSwitcher == null) {
            return;
        }
        if (TextUtils.isEmpty(translatedText)) {
            lyricViewHolder.mSubtitleTextSwitcher.setCurrentText("");
            lyricViewHolder.mSubtitleTextSwitcher.setVisibility(View.GONE);
            return;
        }
        int tintColor = getTintColorForHolder(lyricViewHolder);
        ((TextView) lyricViewHolder.mSubtitleTextSwitcher.getCurrentView()).setTextColor(tintColor);
        ((TextView) lyricViewHolder.mSubtitleTextSwitcher.getNextView()).setTextColor(tintColor);
        lyricViewHolder.mSubtitleTextSwitcher.setVisibility(View.VISIBLE);
        lyricViewHolder.mSubtitleTextSwitcher.setText(translatedText);
    }

    private void syncSubtitle(LyricViewHolder from, LyricViewHolder to) {
        if (from.mSubtitleTextSwitcher == null || to.mSubtitleTextSwitcher == null) {
            return;
        }
        CharSequence currentText =
                ((TextView) from.mSubtitleTextSwitcher.getCurrentView()).getText();
        if (TextUtils.isEmpty(currentText)) {
            to.mSubtitleTextSwitcher.setCurrentText("");
            to.mSubtitleTextSwitcher.setVisibility(View.GONE);
            return;
        }
        to.mSubtitleTextSwitcher.setVisibility(View.VISIBLE);
        to.mSubtitleTextSwitcher.setCurrentText(currentText);
    }

    private void applyTextTint() {
        updateTextTint(mOverlayLyricViewHolder, mOverlayTintColor);
        if (mInlineLyricViewHolder != null) {
            updateTextTint(mInlineLyricViewHolder, mInlineTintColor);
        }
        updateIconTint();
    }

    private int getTintColorForHolder(LyricViewHolder lyricViewHolder) {
        return lyricViewHolder == mInlineLyricViewHolder ? mInlineTintColor : mOverlayTintColor;
    }

    private void postApplyTextTint() {
        mOverlayLyricViewHolder.mLyricContainer.post(this::applyTextTint);
    }

    private static final class LyricViewHolder {
        final View mLyricContainer;
        final ImageSwitcher mIconSwitcher;
        final TextSwitcher mTextSwitcher;
        final TextSwitcher mSubtitleTextSwitcher;

        LyricViewHolder(
                View lyricContainer,
                ImageSwitcher iconSwitcher,
                TextSwitcher textSwitcher,
                TextSwitcher subtitleTextSwitcher) {
            mLyricContainer = lyricContainer;
            mIconSwitcher = iconSwitcher;
            mTextSwitcher = textSwitcher;
            mSubtitleTextSwitcher = subtitleTextSwitcher;
        }
    }
}
