package com.iamtungingit.wam.widget

import android.content.Context
import com.iamtungingit.wam.storage.AtomicJsonStore
import com.iamtungingit.wam.storage.Paths
import org.json.JSONObject

/**
 * Per-widget record persisted in config/widgets.json (spec §11.2). Each widget stores
 * its own wapId; PendingIntents carry a unique data URI and request code (spec §3.8).
 */
data class WidgetRecord(
    val widgetId: Int,
    val wapId: String,
    val label: String,
    val subtitle: String,
    val entry: String?,
    val fullscreen: Boolean?,
    val theme: String?,
    val network: String,
    val shape: String,
    val backgroundColor: Int?,
    val textColor: Int?,
    val showLabel: Boolean,
    val createdAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("widgetId", widgetId)
        put("wapId", wapId)
        put("label", label)
        put("subtitle", subtitle)
        if (entry != null) put("entry", entry)
        if (fullscreen != null) put("fullscreen", fullscreen)
        if (theme != null) put("theme", theme)
        put("network", network)
        put("shape", shape)
        if (backgroundColor != null) put("backgroundColor", backgroundColor)
        if (textColor != null) put("textColor", textColor)
        put("showLabel", showLabel)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): WidgetRecord? {
            val id = o.optInt("widgetId", -1)
            val wapId = o.optString("wapId", "")
            if (id < 0 || wapId.isEmpty()) return null
            return WidgetRecord(
                widgetId = id,
                wapId = wapId,
                label = o.optString("label", ""),
                subtitle = o.optString("subtitle", ""),
                entry = if (o.has("entry") && !o.isNull("entry")) o.optString("entry") else null,
                fullscreen = if (o.has("fullscreen") && !o.isNull("fullscreen")) o.optBoolean("fullscreen") else null,
                theme = if (o.has("theme") && !o.isNull("theme")) o.optString("theme") else null,
                network = o.optString("network", "inherit"),
                shape = o.optString("shape", "rounded"),
                backgroundColor = if (o.has("backgroundColor") && !o.isNull("backgroundColor")) o.optInt("backgroundColor") else null,
                textColor = if (o.has("textColor") && !o.isNull("textColor")) o.optInt("textColor") else null,
                showLabel = o.optBoolean("showLabel", true),
                createdAt = o.optLong("createdAt", 0L)
            )
        }
    }
}

class WidgetStore(context: Context) {

    private val store = AtomicJsonStore(Paths.widgetsFile(context))

    private fun readMap(): MutableMap<Int, WidgetRecord> {
        val root = store.read() ?: return LinkedHashMap()
        val widgets = root.optJSONObject("widgets") ?: return LinkedHashMap()
        val out = LinkedHashMap<Int, WidgetRecord>()
        for (key in widgets.keys()) {
            val rec = widgets.optJSONObject(key)?.let { WidgetRecord.fromJson(it) }
            if (rec != null) out[rec.widgetId] = rec
        }
        return out
    }

    private fun writeMap(map: Map<Int, WidgetRecord>): Boolean {
        val widgets = JSONObject()
        for ((id, rec) in map) widgets.put(id.toString(), rec.toJson())
        return store.write(JSONObject().put("widgets", widgets))
    }

    fun all(): List<WidgetRecord> = readMap().values.sortedBy { it.widgetId }

    fun get(widgetId: Int): WidgetRecord? = readMap()[widgetId]

    fun put(rec: WidgetRecord): Boolean {
        val map = readMap()
        map[rec.widgetId] = rec
        return writeMap(map)
    }

    fun remove(widgetId: Int): Boolean {
        val map = readMap()
        map.remove(widgetId)
        return writeMap(map)
    }

    fun forWap(wapId: String): List<WidgetRecord> = readMap().values.filter { it.wapId == wapId }
}
