package com.iamtungingit.wam.wap.manifest

import org.json.JSONObject

/**
 * Parsed WAP manifest with defaults applied where present (spec §5.2/§5.3).
 * Nullable display fields mean "not declared in the manifest" so runtime
 * defaults from Settings can apply (spec §15 "Default runtime behavior").
 */
data class WapManifest(
    val manifestVersion: Int = 1,
    val manifestVersionPresent: Boolean = false,
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val versionCode: Long? = null,
    val description: String = "",
    val author: String = "",
    val type: String = "",
    val entry: String = "",
    val icon: String? = null,
    val minRuntime: Int = 1,
    val fullscreen: Boolean? = null,
    val orientation: String? = null,
    val theme: String? = null,
    val themeColor: String? = null,
    val backgroundColor: String? = null,
    val keepScreenOn: Boolean? = null,
    val networkRequired: Boolean? = null,
    val allowedHosts: List<String> = emptyList(),
    val widgetEnabled: Boolean = true,
    val widgetLabel: String? = null,
    val widgetSubtitle: String = "",
    val widgetShape: String = "rounded",
    val widgetBackgroundColor: String? = null,
    val widgetTextColor: String? = null,
    val widgetShowLabel: Boolean = true,
    val allowedEntries: List<String>? = null,
    val singleInstance: Boolean = true,
    val persistent: Boolean = true,
    val bridge: Boolean? = null,
    val quotaBytes: Long = DEFAULT_QUOTA,
    val warnings: List<String> = emptyList()
) {
    val isOffline: Boolean get() = type == "offline"

    /** Declaring network.required=true never grants access (spec §9.5.4); informational only. */
    val effectiveNetworkRequired: Boolean get() = networkRequired ?: !isOffline

    val effectiveBridge: Boolean get() = bridge ?: isOffline

    val effectiveAllowedEntries: List<String>
        get() = allowedEntries ?: listOf(entry)

    val effectiveWidgetLabel: String get() = widgetLabel ?: name

    fun toJSONObject(): JSONObject = JSONObject().apply {
        put("manifestVersion", manifestVersion)
        put("id", id)
        put("name", name)
        put("version", version)
        if (versionCode != null) put("versionCode", versionCode)
        if (description.isNotEmpty()) put("description", description)
        if (author.isNotEmpty()) put("author", author)
        put("type", type)
        put("entry", entry)
        if (icon != null) put("icon", icon)
        put("runtime", JSONObject().put("minVersion", minRuntime))
        val display = JSONObject()
        if (fullscreen != null) display.put("fullscreen", fullscreen)
        if (orientation != null) display.put("orientation", orientation)
        if (theme != null) display.put("theme", theme)
        if (themeColor != null) display.put("themeColor", themeColor)
        if (backgroundColor != null) display.put("backgroundColor", backgroundColor)
        if (keepScreenOn != null) display.put("keepScreenOn", keepScreenOn)
        if (display.length() > 0) put("display", display)
        val network = JSONObject()
        if (networkRequired != null) network.put("required", networkRequired)
        if (allowedHosts.isNotEmpty()) network.put("allowedHosts", org.json.JSONArray(allowedHosts))
        if (network.length() > 0) put("network", network)
        val widget = JSONObject()
        widget.put("enabled", widgetEnabled)
        if (widgetLabel != null) widget.put("label", widgetLabel)
        if (widgetSubtitle.isNotEmpty()) widget.put("subtitle", widgetSubtitle)
        val style = JSONObject()
        style.put("shape", widgetShape)
        if (widgetBackgroundColor != null) style.put("backgroundColor", widgetBackgroundColor)
        if (widgetTextColor != null) style.put("textColor", widgetTextColor)
        style.put("showLabel", widgetShowLabel)
        widget.put("style", style)
        put("widget", widget)
        val launch = JSONObject()
        if (allowedEntries != null) launch.put("allowedEntries", org.json.JSONArray(allowedEntries))
        launch.put("singleInstance", singleInstance)
        put("launch", launch)
        put("data", JSONObject()
            .put("persistent", persistent)
            .put("bridge", bridge ?: isOffline)
            .put("quotaBytes", quotaBytes))
    }

    companion object {
        const val RUNTIME_VERSION = 1
        const val SUPPORTED_MANIFEST_VERSION = 1
        const val DEFAULT_QUOTA: Long = 5L * 1024 * 1024
    }
}
