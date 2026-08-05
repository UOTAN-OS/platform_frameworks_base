/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import com.android.systemui.CoreStartable
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
abstract class MomentArcModule {
    @Binds
    @IntoMap
    @ClassKey(MomentArcStartable::class)
    abstract fun bindMomentArcStartable(startable: MomentArcStartable): CoreStartable
}
