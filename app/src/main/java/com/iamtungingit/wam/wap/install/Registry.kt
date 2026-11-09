package com.iamtungingit.wam.wap.install

import android.content.Context
import com.iamtungingit.wam.storage.AtomicJsonStore
import com.iamtungingit.wam.storage.Paths
import org.json.JSONObject

/** One installed WAP as persisted in config/registry.json (spec §7.2.4). */
data class RegistryEntry(
    val wapId: String,
    var name: String,
    var version: String,
    var versionCode: Long,
    var type: String,
    var sha256: String,
    var sizeBytes: Long,
    var installedAt: Long,
    var author: String = "",
    var sourceName: String = "",
    var networkPref: String = "inherit",
    var broken: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", wapId)
        put("name", name)
        put("version", version)
        put("versionCode", versionCode)
        put("type", type)
        put("sha256", sha256)
        put("sizeBytes", sizeBytes)
        put("installedAt", installedAt)
        put("author", author)
        put("sourceName", sourceName)
        put("networkPref", networkPref)
        put("broken", broken)
    }

    companion object {
        fun fromJson(id: String, o: JSONObject): RegistryEntry = RegistryEntry(
            wapId = id,
            name = o.optString("name", id),
            version = o.optString("version", "0.0.0"),
            versionCode = o.optLong("versionCode", 0L),
            type = o.optString("type", "offline"),
            sha256 = o.optString("sha256", ""),
            sizeBytes = o.optLong("sizeBytes", 0L),
            installedAt = o.optLong("installedAt", 0L),
            author = o.optString("author", ""),
            sourceName = o.optString("sourceName", ""),
            networkPref = o.optString("networkPref", "inherit"),
            broken = o.optBoolean("broken", false)
        )
    }
}

/** Registry backed by config/registry.json (spec D9). */
class Registry(context: Context) {

    private val store = AtomicJsonStore(Paths.registryFile(context))

    private fun readMap(): MutableMap<String, RegistryEntry> {
        val root = store.read() ?: return LinkedHashMap()
        val apps = root.optJSONObject("apps") ?: return LinkedHashMap()
        val out = LinkedHashMap<String, RegistryEntry>()
        for (key in apps.keys()) {
            out[key] = RegistryEntry.fromJson(key, apps.optJSONObject(key) ?: JSONObject())
        }
        return out
    }

    private fun writeMap(map: Map<String, RegistryEntry>): Boolean {
        val apps = JSONObject()
        for ((id, e) in map) apps.put(id, e.toJson())
        return store.write(JSONObject().put("apps", apps))
    }

    fun all(): List<RegistryEntry> = readMap().values.sortedBy { it.name.lowercase() }

    fun get(wapId: String): RegistryEntry? = readMap()[wapId]

    fun contains(wapId: String): Boolean = readMap().containsKey(wapId)

    fun put(entry: RegistryEntry): Boolean {
        val map = readMap()
        map[entry.wapId] = entry
        return writeMap(map)
    }

    fun remove(wapId: String): Boolean {
        val map = readMap()
        map.remove(wapId)
        return writeMap(map)
    }
}
