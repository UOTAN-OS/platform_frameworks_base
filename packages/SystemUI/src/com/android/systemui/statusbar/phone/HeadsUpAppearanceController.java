/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.util.MathUtils;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.qualifiers.RootView;
import com.android.systemui.shade.ShadeHeadsUpTracker;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.SourceType;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager;
import com.android.systemui.statusbar.notification.headsup.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.stack.NotificationRoundnessManager;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.fragment.dagger.HomeStatusBarScope;
import com.android.systemui.util.ViewController;

import java.util.function.BiConsumer;

import javax.inject.Inject;

/** Controls the roundness of heads-up and pulsing notifications. */
@HomeStatusBarScope
public class HeadsUpAppearanceController extends ViewController<PhoneStatusBarView>
        implements OnHeadsUpChangedListener,
        NotificationWakeUpCoordinator.WakeUpListener {
    private static final SourceType HEADS_UP = SourceType.from("HeadsUp");
    private static final SourceType PULSING = SourceType.from("Pulsing");
    private final HeadsUpManager mHeadsUpManager;
    private final NotificationStackScrollLayoutController mStackScrollerController;
    private final ShadeViewController mShadeViewController;
    private final NotificationRoundnessManager mNotificationRoundnessManager;
    private final BiConsumer<ExpandableNotificationRow, String>
            mSetTrackingHeadsUp = this::setTrackingHeadsUp;
    private final BiConsumer<Float, Float> mSetExpandedHeight = this::setAppearFraction;
    private final PhoneStatusBarTransitions mPhoneStatusBarTransitions;

    @VisibleForTesting
    float mExpandedHeight;
    @VisibleForTesting
    float mAppearFraction;
    private ExpandableNotificationRow mTrackedChild;

    @VisibleForTesting
    @Inject
    public HeadsUpAppearanceController(
            HeadsUpManager headsUpManager,
            PhoneStatusBarTransitions phoneStatusBarTransitions,
            NotificationStackScrollLayoutController stackScrollerController,
            ShadeViewController shadeViewController,
            NotificationRoundnessManager notificationRoundnessManager,
            @RootView PhoneStatusBarView phoneStatusBarView) {
        super(phoneStatusBarView);
        mNotificationRoundnessManager = notificationRoundnessManager;
        mHeadsUpManager = headsUpManager;
        mTrackedChild = shadeViewController.getShadeHeadsUpTracker()
                .getTrackedHeadsUpNotification();
        mAppearFraction = stackScrollerController.getAppearFraction();
        mExpandedHeight = stackScrollerController.getExpandedHeight();
        mStackScrollerController = stackScrollerController;
        mShadeViewController = shadeViewController;
        mStackScrollerController.setHeadsUpAppearanceController(this);
        mPhoneStatusBarTransitions = phoneStatusBarTransitions;
    }

    @Override
    protected void onViewAttached() {
        mHeadsUpManager.addListener(this);
        getShadeHeadsUpTracker().addTrackingHeadsUpListener(mSetTrackingHeadsUp);
        getShadeHeadsUpTracker().setHeadsUpAppearanceController(this);
        mStackScrollerController.addOnExpandedHeightChangedListener(mSetExpandedHeight);
    }

    private ShadeHeadsUpTracker getShadeHeadsUpTracker() {
        return mShadeViewController.getShadeHeadsUpTracker();
    }

    @Override
    protected void onViewDetached() {
        mHeadsUpManager.removeListener(this);
        getShadeHeadsUpTracker().removeTrackingHeadsUpListener(mSetTrackingHeadsUp);
        getShadeHeadsUpTracker().setHeadsUpAppearanceController(null);
        mStackScrollerController.removeOnExpandedHeightChangedListener(mSetExpandedHeight);
    }

    @Override
    public void onHeadsUpPinned(NotificationEntry entry) {
        updateHeader(entry.getRow());
        updateHeadsUpAndPulsingRoundness(entry.getRow());
    }

    @Override
    public void onHeadsUpStateChanged(@NonNull NotificationEntry entry, boolean isHeadsUp) {
        updateHeadsUpAndPulsingRoundness(entry.getRow());
        mPhoneStatusBarTransitions.onHeadsUpStateChanged(isHeadsUp);
    }

    @Override
    public void onHeadsUpUnPinned(NotificationEntry entry) {
        updateHeader(entry.getRow());
        updateHeadsUpAndPulsingRoundness(entry.getRow());
    }

    public void setAppearFraction(float expandedHeight, float appearFraction) {
        boolean changed = expandedHeight != mExpandedHeight;
        mExpandedHeight = expandedHeight;
        mAppearFraction = appearFraction;
        if (changed) {
            updateHeadsUpHeaders();
        }
    }

    public void setTrackingHeadsUp(ExpandableNotificationRow trackedChild, String reason) {
        ExpandableNotificationRow previousTracked = mTrackedChild;
        mTrackedChild = trackedChild;
        if (previousTracked != null) {
            updateHeader(previousTracked);
            updateHeadsUpAndPulsingRoundness(previousTracked);
        }
    }

    private void updateHeadsUpHeaders() {
        mHeadsUpManager.getAllEntries().forEach(entry -> {
            updateHeader(entry.getRow());
            updateHeadsUpAndPulsingRoundness(entry.getRow());
        });
    }

    public void updateHeader(ExpandableNotificationRow row) {
        float headerVisibleAmount = 1.0f;
        row.setHeaderVisibleAmount(headerVisibleAmount);
    }

    public void updateHeadsUpAndPulsingRoundness(ExpandableNotificationRow row) {
        boolean isTrackedChild = row == mTrackedChild;
        if (row.isPinned() || row.isHeadsUpAnimatingAway() || isTrackedChild) {
            row.requestRoundness(1f, 1f, HEADS_UP);
        } else {
            row.requestRoundnessReset(HEADS_UP);
        }
        if (mNotificationRoundnessManager.shouldRoundNotificationPulsing()) {
            if (row.showingPulsing()) {
                row.requestRoundness(1f, 1f, PULSING);
            } else {
                row.requestRoundnessReset(PULSING);
            }
        }
    }
}
