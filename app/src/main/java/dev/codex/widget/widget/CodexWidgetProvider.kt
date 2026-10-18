package dev.codex.widget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import dev.codex.widget.data.SnapshotCodec
import dev.codex.widget.domain.WidgetMode
import dev.codex.widget.graph
import dev.codex.widget.sync.Scheduler

open class CodexWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        renderAll(context); Scheduler.install(context); Scheduler.refresh(context)
    }
    override fun onEnabled(context: Context) { Scheduler.install(context); Scheduler.refresh(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { renderAll(context) }
    companion object {
        val variants = listOf(
            CodexWidgetProvider::class.java to WidgetMode.SQUARE,
            SlimWidgetProvider::class.java to WidgetMode.SLIM,
            MiniWidgetProvider::class.java to WidgetMode.MINI,
            ExtendedWidgetProvider::class.java to WidgetMode.EXTENDED,
        )
        fun hasWidgets(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return variants.any { manager.getAppWidgetIds(ComponentName(context, it.first)).isNotEmpty() }
        }
        @Suppress("DEPRECATION")
        fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val snapshot = runCatching { SnapshotCodec.decode(context.graph.store.read().optJSONObject("snapshot")) }.getOrNull()
            val now = System.currentTimeMillis() / 1000
            variants.forEach { (provider, mode) ->
                manager.getAppWidgetIds(ComponentName(context, provider)).forEach { id ->
                    val options = manager.getAppWidgetOptions(id)
                    val sizes = if (Build.VERSION.SDK_INT >= 31) options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES).orEmpty()
                        .filter { it.width > 0 && it.height > 0 }.distinct().take(4) else emptyList()
                    val views = if (Build.VERSION.SDK_INT >= 31 && sizes.isNotEmpty()) RemoteViews(sizes.associateWith {
                        WidgetRenderer.views(context, snapshot, now, it.width, it.height, mode)
                    }) else {
                        // Legacy options describe two orientations: minHeight belongs to landscape.
                        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        val width = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 104).coerceAtLeast(48)
                        val height = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
                            when(mode) { WidgetMode.SQUARE -> 104; WidgetMode.SLIM -> 76; WidgetMode.MINI -> 58; WidgetMode.EXTENDED -> 208 }).coerceAtLeast(40)
                        WidgetRenderer.views(context, snapshot, now, width.toFloat(), height.toFloat(), mode)
                    }
                    manager.updateAppWidget(id, views)
                }
            }
        }
    }
}
class SlimWidgetProvider : CodexWidgetProvider()
class MiniWidgetProvider : CodexWidgetProvider()
class ExtendedWidgetProvider : CodexWidgetProvider()
