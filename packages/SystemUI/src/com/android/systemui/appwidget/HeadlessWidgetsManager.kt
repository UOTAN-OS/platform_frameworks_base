/*
 * Copyright (C) 2024 The LibreMobileOS foundation
 * SPDX-License-Identifer: Apache-2.0
 */

package com.android.systemui.appwidget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.plus

private const val TAG = "HeadlessWidgetsManager";

class HeadlessWidgetsManager(private val context: Context) {

    private val widgetManager = AppWidgetManager.getInstance(context)
    private val host = HeadlessAppWidgetHost(context)
    private val widgetsMap = mutableMapOf<String, Widget>()

    init {
        dlog("init")
        // clear any stale records
        host.deleteHost()
    }

    fun destroy() {
        dlog("destroying")
        host.stopListening()
        host.deleteHost()
    }

    fun getWidget(info: AppWidgetProviderInfo, key: String): Widget {
        val widget = widgetsMap.getOrPut(key) { Widget(info) }
        check(info.provider == widget.info.provider) {
            "widget $key was created with a different provider"
        }
        return widget
    }

    fun removeWidget(key: String) {
        widgetsMap.remove(key)?.run { unbind() }
            ?: Log.e(TAG, "cannot removeWidget: $key not found in widgets map!")

        if (widgetsMap.isEmpty()) {
            host.stopListening()
        }
    }

    fun subscribeUpdates(info: AppWidgetProviderInfo, key: String): Flow<AppWidgetHostView> {
        val widget = getWidget(info, key)
        dlog("subscribeUpdates: key=$key widget=$widget")
        if (!widget.isBound) {
            Log.e(TAG, "cannot subscribeUpdates: key=$key widget not bound!")
            return emptyFlow()
        }
        host.startListening()
        return widget.updates
    }

    companion object {
        private const val TAG = "HeadlessWidgetsManager"

        private fun dlog(msg: String) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, msg)
            }
        }
    }

    private class HeadlessAppWidgetHost(context: Context) : AppWidgetHost(context, 1028) {

        override fun onCreateView(
            context: Context,
            appWidgetId: Int,
            appWidget: AppWidgetProviderInfo?,
        ): AppWidgetHostView {
            return HeadlessAppWidgetHostView(context)
        }
    }

    @SuppressLint("ViewConstructor")
    private class HeadlessAppWidgetHostView(context: Context) :
        AppWidgetHostView(context) {

        var updateCallback: ((view: AppWidgetHostView) -> Unit)? = null

        override fun updateAppWidget(remoteViews: RemoteViews?) {
            super.updateAppWidget(remoteViews)
            updateCallback?.invoke(this)
        }
    }

    inner class Widget internal constructor(val info: AppWidgetProviderInfo) {
        private var widgetId = -1
        private val view by lazy {
            dlog("creating view for $this")
            host.createView(context, widgetId, info) as HeadlessAppWidgetHostView
        }

        val isBound: Boolean
            get() = widgetManager.getAppWidgetInfo(widgetId)?.provider == info.provider

        val updates = callbackFlow {
            trySend(view)
            view.updateCallback = {
                dlog("widget view updated")
                trySend(it)
            }
            awaitClose()
        }
            .onStart { if (!isBound) throw WidgetNotBoundException() }

        init {
            bind()
        }

        fun bind() {
            if (!isBound) {
                Log.i(TAG, "binding $this")
                if (widgetId > -1) {
                    host.deleteAppWidgetId(widgetId)
                }
                widgetId = host.allocateAppWidgetId()
                widgetManager.bindAppWidgetIdIfAllowed(
                    widgetId,
                    info.profile,
                    info.provider,
                    null,
                )
            }
        }

        fun unbind() {
            if (isBound && widgetId > -1) {
                host.deleteAppWidgetId(widgetId)
            }
        }

        override fun toString(): String {
            return "Widget{widgetId=$widgetId, isBound=$isBound provider=${info.provider}}"
        }
    }

    private class WidgetNotBoundException : RuntimeException()
}
