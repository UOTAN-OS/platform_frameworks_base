/*
 * Copyright (C) 2024 The LibreMobileOS foundation
 * SPDX-License-Identifer: Apache-2.0
 */

package com.android.systemui.keyguard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.UserManager
import android.util.Log
import androidx.slice.Slice
import androidx.slice.builders.ListBuilder
import androidx.slice.builders.ListBuilder.RowBuilder
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.keyguard.KeyguardSliceProvider
import com.android.systemui.keyguard.SmartspaceWidgetReader.Companion.SMARTSPACE_WEATHER_URI
import javax.inject.Inject
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.plus

class CustomKeyguardSliceProvider : KeyguardSliceProvider() {

    private var smartspaceReader: SmartspaceWidgetReader? = null
    private var smartspaceRows: List<RowBuilder>? = null
    private val scope = CoroutineScope(Dispatchers.IO) + CoroutineName(TAG)
    @Inject lateinit var broadcastDispatcher: BroadcastDispatcher

    private fun initSmartspace() {
        dlog("initSmartspace")
        smartspaceReader = SmartspaceWidgetReader(context!!).also {
            it.smartspaceRows
                .onEach { rows ->
                    smartspaceRows = rows
                    dlog("received smartspace slice rows: ${rows.map { it.uri }}")
                    notifyChange()
                }
                .launchIn(scope)
        }
    }

    override fun onCreateSliceProvider(): Boolean {
        super.onCreateSliceProvider()
        dlog("onCreateSliceProvider()")

        // start smartspace only for the admin user since keyguard is created from admin user
        val userManager = context!!.getSystemService(UserManager::class.java)!!
        if (!userManager.isAdminUser()) {
            dlog("current user is not admin, skip init")
            return true
        }

        if (userManager.isUserUnlocked()) {
            dlog("user already unlocked")
            initSmartspace()
        } else {
            dlog("user not yet unlocked")
            broadcastDispatcher.broadcastFlow(
                filter = IntentFilter().apply {
                    addAction(Intent.ACTION_USER_UNLOCKED)
                }
            )
                .take(1)
                .onEach {
                    dlog("user unlocked")
                    initSmartspace()
                }
                .launchIn(scope)
        }

        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        dlog("onDestroy()")
        scope.cancel()
        smartspaceReader?.destroy()
    }

    override fun onBindSlice(uri: Uri): Slice? {
        dlog("onBindSlice")
        var slice: Slice? = null
        runCatching {
            synchronized (this) {
                val builder = ListBuilder(context!!, mSliceUri, ListBuilder.INFINITY)
                if (needsMediaLocked()) {
                    addMediaLocked(builder)
                } else {
                    addDateLocked(builder)
                }
                addSmartspaceRows(builder)
                addNextAlarmLocked(builder)
                addZenModeLocked(builder)
                addPrimaryActionLocked(builder)
                slice = builder.build()
            }
        }.onFailure { e ->
            Log.w(TAG, "Could not initialize slice", e)
        }
        return slice
    }

    private fun addSmartspaceRows(builder: ListBuilder) {
        smartspaceRows?.forEach { row ->
            if (row.uri == SMARTSPACE_WEATHER_URI && needsMediaLocked()) {
                dlog("addSmartspaceRows: skipping row ${row.uri}")
                return@forEach
            }
            dlog("addSmartspaceRows: adding row ${row.uri}")
            builder.addRow(row)
        } ?: dlog("addSmartspaceRows: no rows")
    }

    companion object {
        private const val TAG = "CustomKeyguardSliceProvider"

        private fun dlog(msg: String) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, msg)
            }
        }
    }
}
