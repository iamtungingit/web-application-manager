package com.iamtungingit.wam.settings

import android.content.Context
import com.iamtungingit.wam.storage.AtomicJsonStore
import com.iamtungingit.wam.storage.Paths
import com.iamtungingit.wam.util.JsonUtil
import org.json.JSONObject

/**
 * Global settings persisted in config/settings.json (spec D9, §15).
 */
class SettingsRepository(context: Context) {

    data class Settings(
        var defaultFullscreen: Boolean = false,
        var defaultOrientation: String = "unspecified",
        var keepScreenOn: Boolean = false,
        var confirmExit: Boolean = true,
        var networkDefault: String = "ask",
        var cleartextLocalhost: Boolean = false,
        var textZoom: Int = 100,
        var forceDark: Boolean = false,
        var safeBrowsing: Boolean = true,
        var webViewDebug: Boolean = false,
        var directZip: Boolean = false,
        var themeMode: String = "system",
        var appLayout: String = "grid",
        var devConsole: Boolean = false,
        var devWarnings: Boolean = false,
        var logLevel: String = "WARN",
        var maxQuotaBytes: Long = DEFAULT_MAX_QUOTA,
        /**
         * Preview engine (v1.5.0 user request: "Built-in browser as default").
         * "webview" = WAM's built-in browser (the secured in-app WebView viewer with
         * all the never-black hardening) — the DEFAULT: apps open inside WAM.
         * "browser" = hand the project to the device's external browser (Chrome/
         * Firefox) via the localhost preview server — kept as an option for devices
         * whose WebView engine is broken.
         */
        var previewEngine: String = "webview"
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("defaultFullscreen", defaultFullscreen)
            put("defaultOrientation", defaultOrientation)
            put("keepScreenOn", keepScreenOn)
            put("confirmExit", confirmExit)
            put("networkDefault", networkDefault)
            put("cleartextLocalhost", cleartextLocalhost)
            put("textZoom", textZoom)
            put("forceDark", forceDark)
            put("safeBrowsing", safeBrowsing)
            put("webViewDebug", webViewDebug)
            put("directZip", directZip)
            put("themeMode", themeMode)
            put("appLayout", appLayout)
            put("devConsole", devConsole)
            put("devWarnings", devWarnings)
            put("logLevel", logLevel)
            put("maxQuotaBytes", maxQuotaBytes)
            put("previewEngine", previewEngine)
        }

        companion object {
            fun fromJson(o: JSONObject): Settings = Settings().apply {
                defaultFullscreen = JsonUtil.optBoolean(o, "defaultFullscreen") ?: defaultFullscreen
                defaultOrientation = JsonUtil.optString(o, "defaultOrientation") ?: defaultOrientation
                keepScreenOn = JsonUtil.optBoolean(o, "keepScreenOn") ?: keepScreenOn
                confirmExit = JsonUtil.optBoolean(o, "confirmExit") ?: confirmExit
                networkDefault = (JsonUtil.optString(o, "networkDefault") ?: networkDefault).lowercase()
                cleartextLocalhost = JsonUtil.optBoolean(o, "cleartextLocalhost") ?: cleartextLocalhost
                textZoom = (JsonUtil.optInt(o, "textZoom") ?: textZoom).coerceIn(50, 200)
                forceDark = JsonUtil.optBoolean(o, "forceDark") ?: forceDark
                safeBrowsing = JsonUtil.optBoolean(o, "safeBrowsing") ?: safeBrowsing
                webViewDebug = JsonUtil.optBoolean(o, "webViewDebug") ?: webViewDebug
                directZip = JsonUtil.optBoolean(o, "directZip") ?: directZip
                themeMode = (JsonUtil.optString(o, "themeMode") ?: themeMode).lowercase()
                appLayout = (JsonUtil.optString(o, "appLayout") ?: appLayout).lowercase()
                devConsole = JsonUtil.optBoolean(o, "devConsole") ?: devConsole
                devWarnings = JsonUtil.optBoolean(o, "devWarnings") ?: devWarnings
                logLevel = (JsonUtil.optString(o, "logLevel") ?: logLevel).uppercase()
                maxQuotaBytes = JsonUtil.optLong(o, "maxQuotaBytes") ?: maxQuotaBytes
                previewEngine = (JsonUtil.optString(o, "previewEngine") ?: previewEngine).lowercase()
                    .let { if (it == "browser") "browser" else "webview" }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_QUOTA: Long = 100L * 1024 * 1024
    }

    private val store = AtomicJsonStore(Paths.settingsFile(context))

    @Volatile
    var current: Settings = Settings.fromJson(store.read() ?: JSONObject())
        private set

    fun save(s: Settings) {
        current = s
        store.write(s.toJson())
    }

    fun reload() {
        current = Settings.fromJson(store.read() ?: JSONObject())
    }
}
