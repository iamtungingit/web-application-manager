package com.iamtungingit.wam.settings

import android.content.Context
import com.iamtungingit.wam.storage.AtomicJsonStore
import com.iamtungingit.wam.storage.Paths
import org.json.JSONObject

/**
 * Per-app (per installed WAP) permission settings (v1.5.0 user request #7,
 * v1.7.0 user request "add more api permissions"):
 * camera, voice recorder, internet (stored via the registry networkPref), text to
 * speech, "requests to send data" (DANGEROUS — red asterisk) and the new v1.7.0
 * capabilities: vibrate, clipboard (DANGEROUS — read), notifications, device info,
 * share and export files. Every one is enforced in the WapFeatures bridge.
 *
 * Storage: config/app_prefs.json { "<wapId>": {camera, mic, tts, sendData, ...} }.
 * Defaults are all OFF (least privilege) — an app must be granted each capability.
 * Unknown JSON keys from older versions simply keep their false defaults.
 */
class AppPrefsRepository(context: Context) {

    data class AppPrefs(
        var camera: Boolean = false,
        var mic: Boolean = false,
        var tts: Boolean = false,
        var sendData: Boolean = false,
        var vibrate: Boolean = false,
        var clipboard: Boolean = false,
        var notifications: Boolean = false,
        var deviceInfo: Boolean = false,
        var share: Boolean = false,
        var exportFiles: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("camera", camera)
            .put("mic", mic)
            .put("tts", tts)
            .put("sendData", sendData)
            .put("vibrate", vibrate)
            .put("clipboard", clipboard)
            .put("notifications", notifications)
            .put("deviceInfo", deviceInfo)
            .put("share", share)
            .put("exportFiles", exportFiles)

        companion object {
            fun fromJson(o: JSONObject): AppPrefs = AppPrefs(
                camera = o.optBoolean("camera", false),
                mic = o.optBoolean("mic", false),
                tts = o.optBoolean("tts", false),
                sendData = o.optBoolean("sendData", false),
                vibrate = o.optBoolean("vibrate", false),
                clipboard = o.optBoolean("clipboard", false),
                notifications = o.optBoolean("notifications", false),
                deviceInfo = o.optBoolean("deviceInfo", false),
                share = o.optBoolean("share", false),
                exportFiles = o.optBoolean("exportFiles", false)
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
