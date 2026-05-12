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
    private static final String EXTRA_TICKER_ICON_SWITCH = "ticker_icon_switch";

    private static final int HIDE_LYRIC_DELAY = 1200;

    private final Context mContext;
    private final LyricViewHolder mOverlayLyricViewHolder;
    private final LyricViewHolder mInlineLyricViewHolder;

    private final ContrastColorUtil mNotificationColorUtil;

    private boolean mEnabled;
    private boolean mStarted;
    private boolean mShowOnClockRight;
    private boolean mHideIconOnClockRight;

    private String mCurrentNotificationPackage = null;
    private int mCurrentNotificationId;

    private ColorStateList mTintColorStateList;

    public LyricViewController(Context context, View statusBar) {
        mContext = context;
        mOverlayLyricViewHolder = createLyricViewHolder(
                statusBar, R.id.lyric_container, R.id.lyric_icon, R.id.lyric_text, true);
        mInlineLyricViewHolder = createLyricViewHolder(
                statusBar,
                R.id.lyric_inline_container,
                R.id.lyric_inline_icon,
                R.id.lyric_inline_text,
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
                && ((notification.flags & Notification.FLAG_ONLY_UPDATE_TICKER) != 0);

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
                int iconId = notification.extras.getInt(EXTRA_TICKER_ICON, -1);
                Drawable icon = iconId == -1 ? notification.getSmallIcon().loadDrawable(mContext) :
                        StatusBarIconView.getIcon(mContext, sbn.getPackageContext(mContext),
                                new StatusBarIcon(sbn.getPackageName(), sbn.getUser(),
                                    iconId, notification.iconLevel, 0, null, StatusBarIcon.Type.NotifSmallIcon));
                setIconForAllHolders(icon);
            }
            startLyric();
            setTextForAllHolders(notification.tickerText);
        }
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
        updateIconTint(mOverlayLyricViewHolder);
        if (mInlineLyricViewHolder != null) {
            updateIconTint(mInlineLyricViewHolder);
        }
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> area, float darkIntensity, int tint) {
        int tintColor = DarkIconDispatcher.getTint(area, getLyricView(), tint);

        updateTextTint(mOverlayLyricViewHolder, tintColor);
        if (mInlineLyricViewHolder != null) {
            updateTextTint(mInlineLyricViewHolder, tintColor);
        }

        mTintColorStateList = ColorStateList.valueOf(tintColor);
        updateIconTint();
    }

    private LyricViewHolder createLyricViewHolder(
            View statusBar, int containerId, int iconId, int textId, boolean required) {
        View lyricContainer = statusBar.findViewById(containerId);
        if (lyricContainer == null) {
            if (required) {
                throw new IllegalStateException("Missing lyric container: " + containerId);
            }
            return null;
        }
        return new LyricViewHolder(
                lyricContainer,
                lyricContainer.requireViewById(iconId),
                lyricContainer.requireViewById(textId));
    }

    private void setUpAnimations(
            LyricViewHolder lyricViewHolder, Animation animationIn, Animation animationOut) {
        lyricViewHolder.mTextSwitcher.setInAnimation(animationIn);
        lyricViewHolder.mTextSwitcher.setOutAnimation(animationOut);
        lyricViewHolder.mIconSwitcher.setInAnimation(animationIn);
        lyricViewHolder.mIconSwitcher.setOutAnimation(animationOut);
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
        updateIconTint(to);
    }

    private void setIconForAllHolders(Drawable icon) {
        mOverlayLyricViewHolder.mIconSwitcher.setImageDrawable(copyDrawable(icon));
        if (mInlineLyricViewHolder != null) {
            mInlineLyricViewHolder.mIconSwitcher.setImageDrawable(copyDrawable(icon));
        }
        updateIconTint();
        updateIconVisibility();
    }

    private void setTextForAllHolders(CharSequence text) {
        mOverlayLyricViewHolder.mTextSwitcher.setText(text);
        if (mInlineLyricViewHolder != null) {
            mInlineLyricViewHolder.mTextSwitcher.setText(text);
        }
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

    private void updateIconTint(LyricViewHolder lyricViewHolder) {
        Drawable drawable = ((ImageView) lyricViewHolder.mIconSwitcher.getCurrentView()).getDrawable();
        if (drawable == null) {
            return;
        }
        boolean isGrayscale = mNotificationColorUtil.isGrayscaleIcon(drawable);
        ImageView currentView = (ImageView) lyricViewHolder.mIconSwitcher.getCurrentView();
        ImageView nextView = (ImageView) lyricViewHolder.mIconSwitcher.getNextView();
        if (isGrayscale) {
            currentView.setImageTintList(mTintColorStateList);
            nextView.setImageTintList(mTintColorStateList);
        } else {
            currentView.setImageTintList(null);
            nextView.setImageTintList(null);
        }
    }

    private void updateTextTint(LyricViewHolder lyricViewHolder, int tintColor) {
        ((TextView) lyricViewHolder.mTextSwitcher.getCurrentView()).setTextColor(tintColor);
        ((TextView) lyricViewHolder.mTextSwitcher.getNextView()).setTextColor(tintColor);
    }

    private static final class LyricViewHolder {
        final View mLyricContainer;
        final ImageSwitcher mIconSwitcher;
        final TextSwitcher mTextSwitcher;

        LyricViewHolder(View lyricContainer, ImageSwitcher iconSwitcher, TextSwitcher textSwitcher) {
            mLyricContainer = lyricContainer;
            mIconSwitcher = iconSwitcher;
            mTextSwitcher = textSwitcher;
        }
    }
}
