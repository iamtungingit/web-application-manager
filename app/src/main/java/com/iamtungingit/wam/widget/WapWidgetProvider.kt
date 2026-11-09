package com.iamtungingit.wam.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/**
 * Home-screen widget provider (spec §11). Configuration lives on disk, so widgets
 * survive restarts and reboots; onUpdate re-renders from the store.
 */
class WapWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) WidgetRenderer.renderFor(context, manager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        WidgetRenderer.renderFor(context, manager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = WidgetStore(context)
        for (id in appWidgetIds) store.remove(id)
    }

    override fun onDisabled(context: Context) {
        // Last widget removed; records are already gone via onDeleted.
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        // Remap ids after backup/restore or launcher migration (spec §11.4).
        val store = WidgetStore(context)
        for (i in oldWidgetIds.indices) {
            val oldId = oldWidgetIds[i]
            val newId = newWidgetIds.getOrElse(i) { oldId }
            val rec = store.get(oldId) ?: continue
            store.remove(oldId)
            store.put(rec.copy(widgetId = newId))
        }
    }
}
