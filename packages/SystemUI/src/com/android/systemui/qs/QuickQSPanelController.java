/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs;

import static com.android.systemui.media.dagger.MediaModule.QUICK_QS_PANEL;
import static com.android.systemui.qs.dagger.QSScopeModule.QS_USING_COLLAPSED_LANDSCAPE_MEDIA;
import static com.android.systemui.qs.dagger.QSScopeModule.QS_USING_MEDIA_PLAYER;

import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.haptics.qs.QSLongPressEffect;
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor;
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager;
import com.android.systemui.media.controls.ui.view.MediaHost;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.qs.flags.QSComposeFragment;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.settings.brightness.BrightnessController;
import com.android.systemui.settings.brightness.BrightnessSliderController;
import com.android.systemui.shade.ShadeDisplayAware;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.SplitShadeStateController;
import com.android.systemui.util.leak.RotationUtils;

import kotlinx.coroutines.flow.StateFlow;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

/** Controller for {@link QuickQSPanel}. */
@QSScope
public class QuickQSPanelController extends QSPanelControllerBase<QuickQSPanel> {

    private final Provider<Boolean> mUsingCollapsedLandscapeMediaProvider;

    private final MediaCarouselInteractor mMediaCarouselInteractor;
    private final BrightnessSliderController mBrightnessSliderController;
    private final BrightnessController mBrightnessController;
    private boolean mListening;
    @Inject
    QuickQSPanelController(QuickQSPanel view, QSHost qsHost,
            QSCustomizerController qsCustomizerController,
            @Named(QS_USING_MEDIA_PLAYER) boolean usingMediaPlayer,
            @Named(QUICK_QS_PANEL) MediaHost mediaHost,
            @Named(QS_USING_COLLAPSED_LANDSCAPE_MEDIA)
                    Provider<Boolean> usingCollapsedLandscapeMediaProvider,
            MetricsLogger metricsLogger, UiEventLogger uiEventLogger, QSLogger qsLogger,
            DumpManager dumpManager, SplitShadeStateController splitShadeStateController,
            Provider<QSLongPressEffect> longPressEffectProvider,
            MediaCarouselInteractor mediaCarouselInteractor,
            BrightnessController.Factory brightnessControllerFactory,
            BrightnessSliderController.Factory brightnessSliderFactory,
            @ShadeDisplayAware ConfigurationController configurationController
    ) {
        super(view, qsHost, qsCustomizerController, usingMediaPlayer, mediaHost, metricsLogger,
                uiEventLogger, qsLogger, dumpManager, splitShadeStateController,
                longPressEffectProvider, configurationController);
        mUsingCollapsedLandscapeMediaProvider = usingCollapsedLandscapeMediaProvider;
        mMediaCarouselInteractor = mediaCarouselInteractor;
        mBrightnessSliderController = brightnessSliderFactory.create(getContext(), mView);
        mView.setBrightnessView(mBrightnessSliderController.getRootView());
        mBrightnessController = brightnessControllerFactory.create(mBrightnessSliderController);
    }

    @Override
    protected void onInit() {
        super.onInit();
        if (!SceneContainerFlag.isEnabled()) {
            updateMediaExpansion();
            mMediaHost.setShowsOnlyActiveMedia(true);
        }
        mMediaHost.init(MediaHierarchyManager.LOCATION_QQS);
        mBrightnessSliderController.init();
    }

    @Override
    StateFlow<Boolean> getMediaVisibleFlow() {
        return mMediaCarouselInteractor.getHasActiveMedia();
    }

    private void updateMediaExpansion() {
        int rotation = getRotation();
        boolean isLandscape = rotation == RotationUtils.ROTATION_LANDSCAPE
                || rotation == RotationUtils.ROTATION_SEASCAPE;
        boolean usingCollapsedLandscapeMedia = mUsingCollapsedLandscapeMediaProvider.get();
        if (!usingCollapsedLandscapeMedia || !isLandscape) {
            mMediaHost.setExpansion(MediaHost.EXPANDED);
        } else {
            mMediaHost.setExpansion(MediaHost.COLLAPSED);
        }
    }

    @VisibleForTesting
    protected int getRotation() {
        return RotationUtils.getRotation(getContext());
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();
        updateBrightnessSettings();
    }

    @Override
    protected void onViewDetached() {
        mBrightnessController.unregisterCallbacks();
        super.onViewDetached();
    }

    @Override
    void setListening(boolean listening) {
        super.setListening(listening);
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            mBrightnessController.registerCallbacks();
        } else {
            mBrightnessController.unregisterCallbacks();
        }
    }

    private void updateBrightnessSettings() {
        mView.updateBrightnessView(QSComposeFragment.isEnabled(), false);
    }

    private void setMaxTiles(int parseNumTiles) {
        mView.setMaxTiles(parseNumTiles);
        setTiles();
    }

    @Override
    protected void onConfigurationChanged() {
        int newMaxTiles = getResources().getInteger(R.integer.a11_qqs_max_cells);
        if (newMaxTiles != mView.getNumQuickTiles()) {
            setMaxTiles(newMaxTiles);
        }
        if (!SceneContainerFlag.isEnabled()) {
            updateMediaExpansion();
        }
    }

    @Override
    public void setTiles() {
        List<QSTile> tiles = new ArrayList<>();
        List<A11TileLayoutModel.Span> spans = new ArrayList<>();
        final int columns = getResources().getInteger(R.integer.a11_qs_num_columns);
        final int rows = getResources().getInteger(R.integer.a11_qqs_max_rows);
        for (QSTile tile : mHost.getTiles()) {
            final String spec = tile.getTileSpec();
            if (A11TileLayoutSpec.isSlider(spec)) {
                continue;
            }
            final ArrayList<A11TileLayoutModel.Span> candidate = new ArrayList<>(spans);
            candidate.add(new A11TileLayoutModel.Span(
                    A11TileLayoutSpec.getColumnSpan(getContext(), spec), 1));
            final List<A11TileLayoutModel.Placement> placements =
                    A11TileLayoutModel.pack(candidate, columns, rows);
            if (placements.get(placements.size() - 1).page != 0) {
                break;
            }
            spans = candidate;
            tiles.add(tile);
            if (tiles.size() == mView.getNumQuickTiles()) {
                break;
            }
        }
        super.setTiles(tiles, /* collapsedView */ true);
    }

    public void setContentMargins(int marginStart, int marginEnd) {
        mView.setContentMargins(marginStart, marginEnd, mMediaHost.getHostView());
    }

    public int getNumQuickTiles() {
        return mView.getNumQuickTiles();
    }
}
