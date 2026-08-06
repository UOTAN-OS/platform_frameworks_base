/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.qs.shared.model.QsVisualStyle
import kotlinx.coroutines.flow.StateFlow

val LocalQsVisualStyle = compositionLocalOf { QsVisualStyle.DEFAULT_QS }

@Composable
fun ProvideQsVisualStyle(
    visualStyle: StateFlow<QsVisualStyle>,
    content: @Composable () -> Unit,
) {
    val style by visualStyle.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalQsVisualStyle provides style, content = content)
}
