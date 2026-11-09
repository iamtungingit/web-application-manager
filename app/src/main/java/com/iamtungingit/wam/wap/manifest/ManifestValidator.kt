package com.iamtungingit.wam.wap.manifest

import com.iamtungingit.wam.util.JsonUtil
import com.iamtungingit.wam.util.PathSanitizer
import org.json.JSONObject

/**
 * Manifest schema validation and defaults (spec §5.3). Pure JVM, unit tested.
 */
object ManifestValidator {

    private val KNOWN_FIELDS = setOf(
        "manifestVersion", "id", "name", "version", "versionCode", "description", "author",
        "type", "entry", "icon", "runtime", "display", "network", "widget", "launch", "data"
    )

    private val VERSION_REGEX = Regex("^\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?$")
    private val HOST_PATTERN = Regex("^(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")
    private val ICON_EXTS = setOf("png", "jpg", "jpeg", "webp")
    private val HTML_EXTS = setOf("html", "htm")

    fun validate(
        json: JSONObject,
        entryNames: Set<String>?,
        maxQuotaBytes: Long,
        extraWarnings: List<String> = emptyList()
    ): ManifestParser.Result {
        val warnings = mutableListOf<String>()
        warnings.addAll(extraWarnings)

        for (key in json.keys()) {
            if (key !in KNOWN_FIELDS) warnings.add("Unknown field '$key' ignored")
        }

        // manifestVersion
        val mvPresent = json.has("manifestVersion")
        val mv = JsonUtil.optInt(json, "manifestVersion")
        if (mvPresent && mv == null) {
            return fail(ErrorCodes.MANIFEST_MALFORMED, "manifestVersion", "must be an integer")
        }
        if (mv != null && mv != WapManifest.SUPPORTED_MANIFEST_VERSION) {
            return fail(
                ErrorCodes.RUNTIME_TOO_OLD, "manifestVersion",
                "manifest version $mv is not supported (supported: ${WapManifest.SUPPORTED_MANIFEST_VERSION})"
            )
        }

        // id
        val id = JsonUtil.optString(json, "id")
            ?: return fail(ErrorCodes.ID_INVALID, "id", "required")
        if (!PathSanitizer.isValidId(id)) {
            return fail(ErrorCodes.ID_INVALID, "id", "must match ${PathSanitizer.ID_REGEX.pattern} and be ≤ ${PathSanitizer.MAX_ID_LENGTH} chars")
        }

        // name
        val rawName = JsonUtil.optString(json, "name")
            ?: return fail(ErrorCodes.NAME_INVALID, "name", "required")
        val name = rawName.filter { it.code >= 0x20 && it.code != 0x7f }.trim()
        if (name.isEmpty() || name.length > 60) {
            return fail(ErrorCodes.NAME_INVALID, "name", "must be 1..60 characters after cleanup")
        }

        // version
        val version = JsonUtil.optString(json, "version")
            ?: return fail(ErrorCodes.VERSION_INVALID, "version", "required")
        if (version.length > 32 || !VERSION_REGEX.matches(version)) {
            return fail(ErrorCodes.VERSION_INVALID, "version", "must be x.y.z with optional -prerelease/+build, ≤ 32 chars")
        }
        val versionCode = JsonUtil.optLong(json, "versionCode")
        if (json.has("versionCode") && versionCode == null) {
            return fail(ErrorCodes.MANIFEST_MALFORMED, "versionCode", "must be an integer ≥ 0")
        }
        if (versionCode != null && versionCode < 0) {
            return fail(ErrorCodes.MANIFEST_MALFORMED, "versionCode", "must be ≥ 0")
        }

        val description = (JsonUtil.optString(json, "description") ?: "")
        if (description.length > 500) {
            return fail(ErrorCodes.MANIFEST_MALFORMED, "description", "must be ≤ 500 characters")
        }
        val author = (JsonUtil.optString(json, "author") ?: "")
        if (author.length > 80) {
            return fail(ErrorCodes.MANIFEST_MALFORMED, "author", "must be ≤ 80 characters")
        }

        // type
        val type = JsonUtil.optString(json, "type")
            ?: return fail(ErrorCodes.TYPE_INVALID, "type", "required (offline|online)")
        if (type != "offline" && type != "online") {
            return fail(ErrorCodes.TYPE_INVALID, "type", "must be 'offline' or 'online'")
        }
        val isOffline = type == "offline"

        // entry
        val rawEntry = JsonUtil.optString(json, "entry")
            ?: return fail(ErrorCodes.ENTRY_INVALID, "entry", "required")
        var entry: String
        if (isOffline) {
            val pathPart = rawEntry.substringBefore('?').substringBefore('#')
            val norm = PathSanitizer.sanitizeRelativePath(pathPart)
                ?: return fail(ErrorCodes.ENTRY_INVALID, "entry", "unsafe relative path")
            val ext = norm.substringAfterLast('.', "").lowercase()
            if (ext !in HTML_EXTS) {
                return fail(ErrorCodes.ENTRY_INVALID, "entry", "must point to an .html/.htm file")
            }
            if (entryNames != null && norm !in entryNames) {
                return fail(ErrorCodes.ENTRY_NOT_FOUND, "entry", "'$norm' is not in the archive")
            }
            entry = norm
            val qf = rawEntry.substring(pathPart.length)
            if (qf.length > 256 || qf.any { it.code < 0x20 }) {
                return fail(ErrorCodes.ENTRY_INVALID, "entry", "query/fragment too long or unsafe")
            }
            entry += qf
        } else {
            when (val r = UrlValidator.validateOnlineEntry(rawEntry)) {
                is UrlValidator.Result.Bad -> return fail(r.code, "entry", r.message)
                is UrlValidator.Result.Ok -> entry = r.uri.toString()
            }
        }

        // icon
        var icon: String? = null
        if (json.has("icon") && !json.isNull("icon")) {
            val rawIcon = JsonUtil.optString(json, "icon")
                ?: return fail(ErrorCodes.ICON_INVALID, "icon", "must be a string path")
            val norm = PathSanitizer.sanitizeRelativePath(rawIcon)
                ?: return fail(ErrorCodes.ICON_INVALID, "icon", "unsafe relative path")
            val ext = norm.substringAfterLast('.', "").lowercase()
            if (ext !in ICON_EXTS) {
                return fail(ErrorCodes.ICON_INVALID, "icon", "icon must be PNG/JPEG/WebP")
            }
            if (entryNames != null && norm !in entryNames) {
                return fail(ErrorCodes.ICON_INVALID, "icon", "'$norm' is not in the archive")
            }
            icon = norm
        }

        // runtime
        var minRuntime = 1
        JsonUtil.optObject(json, "runtime")?.let { rt ->
            val v = JsonUtil.optInt(rt, "minVersion") ?: 1
            if (v < 1) return fail(ErrorCodes.MANIFEST_MALFORMED, "runtime.minVersion", "must be ≥ 1")
            minRuntime = v
        }
        if (minRuntime > WapManifest.RUNTIME_VERSION) {
            return fail(
                ErrorCodes.RUNTIME_TOO_OLD, "runtime.minVersion",
                "requires runtime $minRuntime > ${WapManifest.RUNTIME_VERSION}"
            )
        }

        // display
        var fullscreen: Boolean? = null
        var orientation: String? = null
        var theme: String? = null
        var themeColor: String? = null
        var backgroundColor: String? = null
        var keepScreenOn: Boolean? = null
        JsonUtil.optObject(json, "display")?.let { d ->
            fullscreen = JsonUtil.optBoolean(d, "fullscreen")
            orientation = JsonUtil.optString(d, "orientation")?.lowercase()
            if (orientation != null && orientation !in setOf("unspecified", "portrait", "landscape", "sensor")) {
                return fail(ErrorCodes.MANIFEST_MALFORMED, "display.orientation", "unspecified|portrait|landscape|sensor")
            }
            theme = JsonUtil.optString(d, "theme")?.lowercase()
            if (theme != null && theme !in setOf("system", "light", "dark")) {
                return fail(ErrorCodes.MANIFEST_MALFORMED, "display.theme", "system|light|dark")
            }
            themeColor = JsonUtil.optString(d, "themeColor")
            if (themeColor != null && JsonUtil.parseColorHex(themeColor!!) == null) {
                return fail(ErrorCodes.MANIFEST_MALFORMED, "display.themeColor", "#RRGGBB or #AARRGGBB")
            }
            backgroundColor = JsonUtil.optString(d, "backgroundColor")
            if (backgroundColor != null && JsonUtil.parseColorHex(backgroundColor!!) == null) {
                return fail(ErrorCodes.MANIFEST_MALFORMED, "display.backgroundColor", "#RRGGBB or #AARRGGBB")
            }
            keepScreenOn = JsonUtil.optBoolean(d, "keepScreenOn")
        }

        // network
        var networkRequired: Boolean? = null
        var allowedHosts: List<String> = emptyList()
        JsonUtil.optObject(json, "network")?.let { n ->
            networkRequired = JsonUtil.optBoolean(n, "required")
            val hosts = JsonUtil.stringArray(n, "allowedHosts")
            if (hosts.size > 20) {
                return fail(ErrorCodes.MANIFEST_MALFORMED, "network.allowedHosts", "max 20 hosts")
            }
            for (h in hosts) {
                val lower = h.lowercase()
                if (lower.length > 253 || !HOST_PATTERN.matches(lower)) {
                    return fail(ErrorCodes.MANIFEST_MALFORMED, "network.allowedHosts", "invalid host '$h'")
                }
                val bare = lower.removePrefix("*.")
                if (bare.firstOrNull()?.isDigit() == true) {
                    // IP literals are not accepted in allowedHosts (spec §5.3).
                    return fail(ErrorCodes.MANIFEST_MALFORMED, "network.allowedHosts", "IP literal hosts are not allowed: '$h'")
                }
            }
            allowedHosts = hosts.map { it.lowercase() }.distinct()
        }

        // widget
        var widgetEnabled = true
        var widgetLabel: String? = null
        var widgetSubtitle = ""
        var widgetShape = "rounded"
        var widgetBg: String? = null
        var widgetFg: String? = null
        var widgetShowLabel = true
        JsonUtil.optObject(json, "widget")?.let { w ->
            widgetEnabled = JsonUtil.optBoolean(w, "enabled") ?: true
            widgetLabel = JsonUtil.optString(w, "label")
            if (widgetLabel != null && (widgetLabel!!.isEmpty() || widgetLabel!!.length > 24)) {
                return fail(ErrorCodes.MANIFEST_MALFORMED, "widget.label", "must be 1..24 characters")
            }
            widgetSubtitle = JsonUtil.optString(w, "subtitle") ?: ""
            if (widgetSubtitle.length > 40) {
                return fail(ErrorCodes.MANIFEST_MALFORMED, "widget.subtitle", "must be ≤ 40 characters")
            }
            JsonUtil.optObject(w, "style")?.let { st ->
                widgetShape = JsonUtil.optString(st, "shape") ?: "rounded"
                if (widgetShape !in setOf("square", "rounded", "circle")) {
                    return fail(ErrorCodes.MANIFEST_MALFORMED, "widget.style.shape", "square|rounded|circle")
                }
                widgetBg = JsonUtil.optString(st, "backgroundColor")
                if (widgetBg != null && JsonUtil.parseColorHex(widgetBg!!) == null) {
                    return fail(ErrorCodes.MANIFEST_MALFORMED, "widget.style.backgroundColor", "#RRGGBB or #AARRGGBB")
                }
                widgetFg = JsonUtil.optString(st, "textColor")
                if (widgetFg != null && JsonUtil.parseColorHex(widgetFg!!) == null) {
                    return fail(ErrorCodes.MANIFEST_MALFORMED, "widget.style.textColor", "#RRGGBB or #AARRGGBB")
                }
                widgetShowLabel = JsonUtil.optBoolean(st, "showLabel") ?: true
            }
        }

        // launch
        var allowedEntries: List<String>? = null
        var singleInstance = true
        JsonUtil.optObject(json, "launch")?.let { l ->
            singleInstance = JsonUtil.optBoolean(l, "singleInstance") ?: true
            val arr = JsonUtil.stringArray(l, "allowedEntries")
            if (arr.isNotEmpty()) {
                if (!isOffline) {
                    return fail(ErrorCodes.ENTRY_INVALID, "launch.allowedEntries", "only valid for offline apps")
                }
                for (p in arr) {
                    val norm = PathSanitizer.sanitizeRelativePath(p.substringBefore('?').substringBefore('#'))
                        ?: return fail(ErrorCodes.ENTRY_INVALID, "launch.allowedEntries", "unsafe path '$p'")
                    val ext = norm.substringAfterLast('.', "").lowercase()
                    if (ext !in HTML_EXTS) {
                        return fail(ErrorCodes.ENTRY_INVALID, "launch.allowedEntries", "'$norm' must be .html/.htm")
                    }
                    if (entryNames != null && norm !in entryNames) {
                        return fail(ErrorCodes.ENTRY_NOT_FOUND, "launch.allowedEntries", "'$norm' is not in the archive")
                    }
                }
                allowedEntries = arr
            }
        }

        // data
        var persistent = true
        var bridge: Boolean? = null
        var quota = WapManifest.DEFAULT_QUOTA
        JsonUtil.optObject(json, "data")?.let { dt ->
            persistent = JsonUtil.optBoolean(dt, "persistent") ?: true
            bridge = JsonUtil.optBoolean(dt, "bridge")
            JsonUtil.optLong(dt, "quotaBytes")?.let { q ->
                if (q < 0) return fail(ErrorCodes.MANIFEST_MALFORMED, "data.quotaBytes", "must be ≥ 0")
                if (q > maxQuotaBytes) {
                    warnings.add("data.quotaBytes $q exceeds the global maximum; capped to $maxQuotaBytes")
                }
                quota = q.coerceAtMost(maxQuotaBytes)
            }
        }

        val manifest = WapManifest(
            manifestVersion = mv ?: WapManifest.SUPPORTED_MANIFEST_VERSION,
            manifestVersionPresent = mvPresent,
            id = id,
            name = name,
            version = version,
            versionCode = versionCode ?: deriveVersionCode(version),
            description = description,
            author = author,
            type = type,
            entry = entry,
            icon = icon,
            minRuntime = minRuntime,
            fullscreen = fullscreen,
            orientation = orientation,
            theme = theme,
            themeColor = themeColor,
            backgroundColor = backgroundColor,
            keepScreenOn = keepScreenOn,
            networkRequired = networkRequired,
            allowedHosts = allowedHosts,
            widgetEnabled = widgetEnabled,
            widgetLabel = widgetLabel,
            widgetSubtitle = widgetSubtitle,
            widgetShape = widgetShape,
            widgetBackgroundColor = widgetBg,
            widgetTextColor = widgetFg,
            widgetShowLabel = widgetShowLabel,
            allowedEntries = allowedEntries,
            singleInstance = singleInstance,
            persistent = persistent,
            bridge = bridge,
            quotaBytes = quota,
            warnings = warnings.toList()
        )
        return ManifestParser.Result.Success(manifest, warnings.toList())
    }

    private fun fail(code: String, field: String, message: String): ManifestParser.Result =
        ManifestParser.Result.Failure(ValidationError(code, field, message))

    /** Deterministic fallback versionCode (spec §5.3 "derived from version"). */
    fun deriveVersionCode(version: String): Long {
        val core = version.split('-', '+').first()
        val parts = core.split('.')
        val major = parts.getOrNull(0)?.toLongOrNull()?.coerceIn(0, 9999) ?: 0
        val minor = parts.getOrNull(1)?.toLongOrNull()?.coerceIn(0, 999) ?: 0
        val patch = parts.getOrNull(2)?.toLongOrNull()?.coerceIn(0, 999) ?: 0
        return major * 1_000_000L + minor * 1_000L + patch
    }
}
