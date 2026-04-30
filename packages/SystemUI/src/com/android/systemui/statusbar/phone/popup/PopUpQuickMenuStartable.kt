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

package com.android.systemui.statusbar.phone.popup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.UserHandle
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import javax.inject.Inject

@SysUISingleton
class PopUpQuickMenuStartable
@Inject
constructor(
    @Application private val context: Context,
    @Main private val mainHandler: Handler,
    private val controller: PopUpQuickMenuController,
) : CoreStartable {

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_SHOW_QUICK_MENU -> controller.show(
                        isLeft = intent.getBooleanExtra(EXTRA_IS_LEFT, true),
                        initialTouchX = intent.getFloatExtra(EXTRA_TOUCH_X, -1f),
                        initialTouchY = intent.getFloatExtra(EXTRA_TOUCH_Y, -1f),
                    )
                    ACTION_UPDATE_TOUCH -> controller.onTouchCoordinates(
                        x = intent.getFloatExtra(EXTRA_TOUCH_X, -1f),
                        y = intent.getFloatExtra(EXTRA_TOUCH_Y, -1f),
                        isUp = intent.getBooleanExtra(EXTRA_IS_UP, false),
                    )
                    ACTION_DISMISS_QUICK_MENU -> controller.hide()
                }
            }
        }

    override fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW_QUICK_MENU)
            addAction(ACTION_UPDATE_TOUCH)
            addAction(ACTION_DISMISS_QUICK_MENU)
        }
        context.registerReceiverAsUser(
            receiver,
            UserHandle.ALL,
            filter,
            null,
            mainHandler,
            Context.RECEIVER_EXPORTED,
        )
    }

    companion object {
        const val ACTION_SHOW_QUICK_MENU =
            "com.android.systemui.action.SHOW_POP_UP_QUICK_MENU"
        const val ACTION_UPDATE_TOUCH =
            "com.android.systemui.action.UPDATE_POP_UP_QUICK_MENU_TOUCH"
        const val ACTION_DISMISS_QUICK_MENU =
            "com.android.systemui.action.DISMISS_POP_UP_QUICK_MENU"
        const val EXTRA_IS_LEFT = "is_left"
        const val EXTRA_TOUCH_X = "touch_x"
        const val EXTRA_TOUCH_Y = "touch_y"
        const val EXTRA_IS_UP = "is_up"
    }
}
