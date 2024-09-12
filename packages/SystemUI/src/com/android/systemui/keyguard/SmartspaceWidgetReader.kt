/*
 * Copyright (C) 2024 The LibreMobileOS foundation
 * SPDX-License-Identifer: Apache-2.0
 */

package com.android.systemui.keyguard

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PorterDuff.Mode
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.PatternMatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.descendants
import androidx.slice.builders.ListBuilder
import androidx.slice.builders.ListBuilder.RowBuilder
import androidx.slice.builders.SliceAction
import com.android.systemui.appwidget.HeadlessWidgetsManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class SmartspaceWidgetReader(private val context: Context) {

    private var appWidgetManager = AppWidgetManager.getInstance(context)
    private val widgetsManager = HeadlessWidgetsManager(context)

    private val _smartspaceRows = MutableSharedFlow<List<RowBuilder>>(replay = 1)
    val smartspaceRows: Flow<List<RowBuilder>>
        get() = _smartspaceRows.asSharedFlow()

    private var updateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO) + CoroutineName(TAG)
    private val isJobActive: Boolean
        get() = updateJob?.isActive ?: false

    private val gsaPackageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            dlog("gsaPackageReceiver: received ${intent.action}")
            updateGsaState()
        }
    }

    init {
        dlog("init")
        context.registerReceiver(
            gsaPackageReceiver,
            IntentFilter(Intent.ACTION_PACKAGE_CHANGED).apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
                addDataSchemeSpecificPart(GSA_PACKAGE, PatternMatcher.PATTERN_LITERAL)
            },
            Context.RECEIVER_EXPORTED
        )
        updateGsaState()
    }

    fun destroy() {
        dlog("destroying")
        scope.cancel()
        widgetsManager.destroy()
        context.unregisterReceiver(gsaPackageReceiver)
    }

    private fun updateGsaState() {
        val enabled = runCatching {
            context.packageManager.getApplicationInfo(GSA_PACKAGE, 0).enabled
        }.getOrDefault(false)
        dlog("updateGsaState: gsa package enabled=$enabled")
        if (enabled) {
            startUpdateJob()
        } else {
            cancelUpdateJob()
        }
    }

    private fun startUpdateJob() {
        if (isJobActive) {
            dlog("startUpdateJob: job already active")
            return
        }

        appWidgetManager.getInstalledProvidersForPackage(GSA_PACKAGE, null)
            .firstOrNull { it.provider.className == WIDGET_CLASS_NAME }
            ?.let { provider ->
                updateJob = scope.launch {
                    widgetsManager.subscribeUpdates(provider, WIDGET_KEY)
                        .collectLatest { _smartspaceRows.emit(extractWidgetLayout(it)) }
                }
                dlog("started update job")
            }
            ?: Log.e(TAG, "startUpdateJob: widget provider is unavailable!")
    }

    private fun cancelUpdateJob() {
        if (!isJobActive) {
            dlog("cancelUpdateJob: job not active")
            return
        }

        updateJob?.cancel()
        scope.launch { _smartspaceRows.emit(emptyList()) }
        widgetsManager.removeWidget(WIDGET_KEY)
        dlog("cancelled update job")
    }

    private fun extractWidgetLayout(appWidgetHostView: ViewGroup): List<RowBuilder> {
        val descendants = appWidgetHostView.descendants
        val texts = descendants.filterIsInstance<TextView>().filter {
            !it.text.isNullOrEmpty()
        }.toList()
        val images = descendants.filterIsInstance<ImageView>().filter {
            it.drawable != null && it.drawable is BitmapDrawable
        }.toList()
        var weatherIconView: ImageView? = null
        var cardIconView: ImageView? = null
        var title: TextView? = null
        var subtitle: TextView? = null
        var subtitle2: TextView? = null
        var temperatureText: TextView? = null
        dlog("extractWidgetLayout: texts=${texts.size} images=${images.size}")
        if (texts.isEmpty()) return emptyList()
        if (images.isNotEmpty()) {
            weatherIconView = images.firstOrNull()
            temperatureText = texts.last()

        }
        if (images.size > 1 && texts.size > 2) {
            cardIconView = images.first()
            title = texts[0]
            subtitle = texts[1]
            if (texts.size > 3) {
                subtitle2 = texts[2]
            }
        } else {
            // TNG widget has smartspace content in a list view
            descendants.filterIsInstance<ListView>().firstOrNull()?.let { listView ->
                val adapter = listView.adapter
                dlog("extractWidgetLayout: listView items=${adapter.count}")
                if (adapter.count == 0) return@let
                // take only the first item (higher importance) as we have limited space on keyguard
                val view = adapter.getView(0, null, listView)
                if (view !is ViewGroup) return@let
                val lvDescendants = view.descendants
                val lvTexts = lvDescendants.filterIsInstance<TextView>().filter {
                    !it.text.isNullOrEmpty()
                }.toList()
                val lvImages = lvDescendants.filterIsInstance<ImageView>().filter {
                    it.drawable != null && it.drawable is BitmapDrawable
                }.toList()
                dlog("extractWidgetLayout: listView item 0: descendants=${lvDescendants.count()}" +
                    " texts=${lvTexts.size} images=${lvImages.size}")
                cardIconView = lvImages.firstOrNull()
                title = lvTexts.getOrNull(0)
                subtitle = lvTexts.getOrNull(1)
            }
        }
        return parseData(
            weatherIconView?.extractIcon(),
            temperatureText,
            cardIconView?.extractIcon(),
            title,
            subtitle,
            subtitle2
        )
    }

    private fun parseData(
        weatherIcon: IconCompat?,
        temperature: TextView?,
        cardIcon: IconCompat?,
        title: TextView?,
        subtitle: TextView?,
        subtitle2: TextView?,
    ): List<RowBuilder> {
        val weatherRow = parseWeatherData(weatherIcon, temperature)
        dlog("parseData: cardIconPresent=${cardIcon != null} title=${title?.text}" +
            " subtitle=${subtitle?.text} subtitle2=${subtitle2?.text} weatherPresent=${weatherRow != null}")
        if (cardIcon == null || title == null || subtitle == null) {
            return listOfNotNull(weatherRow)
        }

        val ttl = title.text.toString() + if (subtitle2 != null) " ${subtitle.text}" else ""
        val sub = subtitle2 ?: subtitle
        dlog("parseData: title=$ttl")

        val titleRow = RowBuilder(SMARTSPACE_TITLE_URI)
            .setTitle(ttl)
        val subtitleRow = RowBuilder(SMARTSPACE_SUBTITLE_URI)
            .setTitle(sub.text.toString())
            .setTitleItem(cardIcon, ListBuilder.SMALL_IMAGE)
            .setEndOfSection(true)

        return listOfNotNull(weatherRow, titleRow, subtitleRow)
    }

    private fun parseWeatherData(weatherIcon: IconCompat?, temperatureText: TextView?): RowBuilder? {
        val temperature = temperatureText?.text?.toString()
        if (temperature == null || weatherIcon == null) return null

        val row = RowBuilder(SMARTSPACE_WEATHER_URI)
            .setTitle(temperature)
            .setTitleItem(weatherIcon, ListBuilder.SMALL_IMAGE)
            .setEndOfSection(true)

        return row
    }

    private fun ImageView.extractIcon(): IconCompat? {
        return (drawable as? BitmapDrawable)?.bitmap?.let {
            IconCompat.createWithBitmap(it).apply {
                // The only icons which have content description in the widget are weather related
                // and should not be tinted
                if (!contentDescription.isNullOrEmpty()) {
                    setTintMode(Mode.DST)
                }
            }
        }
    }

    companion object {
        private const val TAG = "SmartspaceWidgetReader"
        private const val GSA_PACKAGE = "com.google.android.googlequicksearchbox"
        private const val WIDGET_CLASS_NAME = "com.google.android.apps.gsa.staticplugins.smartspace.widget.SmartspaceWidgetProvider"
        private const val WIDGET_KEY = "smartspaceWidget"
        private val SMARTSPACE_TITLE_URI = Uri.parse("content://com.android.systemui.keyguard/smartspace/title")
        private val SMARTSPACE_SUBTITLE_URI = Uri.parse("content://com.android.systemui.keyguard/smartspace/subtitle")
        val SMARTSPACE_WEATHER_URI = Uri.parse("content://com.android.systemui.keyguard/smartspace/weather")

        private fun dlog(msg: String) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, msg)
            }
        }
    }
}
