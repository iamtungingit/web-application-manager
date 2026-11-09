package com.iamtungingit.wam.settings

import android.content.Context
import com.iamtungingit.wam.storage.AtomicJsonStore
import com.iamtungingit.wam.storage.Paths
import org.json.JSONObject

/**
 * Per-app (per installed WAP) permission settings (v1.5.0 user request #7):
 * camera, voice recorder, internet (stored via the registry networkPref), text to
 * speech and "requests to send data" — the last one is the DANGEROUS one (a WAP
 * with it enabled may POST/PUT user data such as photos or files to a server),
 * so the UI marks it with a red asterisk and a warning.
 *
 * Storage: config/app_prefs.json { "<wapId>": {camera, mic, tts, sendData} }.
 * Defaults are all OFF (least privilege) — an app must be granted each capability.
 */
class AppPrefsRepository(context: Context) {

    data class AppPrefs(
        var camera: Boolean = false,
        var mic: Boolean = false,
        var tts: Boolean = false,
        var sendData: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("camera", camera)
            .put("mic", mic)
            .put("tts", tts)
            .put("sendData", sendData)

        companion object {
            fun fromJson(o: JSONObject): AppPrefs = AppPrefs(
                camera = o.optBoolean("camera", false),
                mic = o.optBoolean("mic", false),
                tts = o.optBoolean("tts", false),
                sendData = o.optBoolean("sendData", false)
            )
        }
    }

    private val store = AtomicJsonStore(Paths.appPrefsFile(context))

    private fun readMap(): MutableMap<String, JSONObject> {
        val root = store.read() ?: return LinkedHashMap()
        val apps = root.optJSONObject("apps") ?: return LinkedHashMap()
        val out = LinkedHashMap<String, JSONObject>()
        for (key in apps.keys()) out[key] = apps.optJSONObject(key) ?: JSONObject()
        return out
    }

    private fun writeMap(map: Map<String, JSONObject>): Boolean {
        val apps = JSONObject()
        for ((k, v) in map) apps.put(k, v)
        return store.write(JSONObject().put("apps", apps))
    }

    fun get(wapId: String): AppPrefs =
        AppPrefs.fromJson(readMap()[wapId] ?: JSONObject())

    fun set(wapId: String, prefs: AppPrefs): Boolean {
        val map = readMap()
        map[wapId] = prefs.toJson()
        return writeMap(map)
    }
}
