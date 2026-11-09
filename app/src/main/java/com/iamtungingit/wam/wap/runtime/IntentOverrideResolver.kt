package com.iamtungingit.wam.wap.runtime

import com.iamtungingit.wam.util.PathSanitizer

/**
 * Pure validation of runtime Intent extras (spec §12). Exhaustively unit tested.
 * Extras never bypass package isolation, path validation, network policy or
 * entry allow-lists; invalid overrides are dropped, an invalid WAP_ID is fatal.
 */
object IntentOverrideResolver {

    const val EXTRA_ID = "WAP_ID"
    const val EXTRA_ENTRY = "WAP_ENTRY"
    const val EXTRA_MODE = "WAP_MODE"
    const val EXTRA_FULLSCREEN = "WAP_FULLSCREEN"
    const val EXTRA_THEME = "WAP_THEME"
    const val EXTRA_ALLOW_NETWORK = "WAP_ALLOW_NETWORK"
    const val EXTRA_WIDGET_ID = "WAP_WIDGET_ID"
    const val EXTRA_PREVIEW_PATH = "WAP_PREVIEW_PATH"

    data class Resolved(
        val id: String?,
        val idValid: Boolean,
        val entry: String?,
        val mode: String?,
        val fullscreen: Boolean?,
        val theme: String?,
        val networkRequest: String?,
        val widgetId: Int?,
        val widgetUsable: Boolean,
        val dropped: List<String>
    )

    /**
     * @param isOffline            manifest type of the target WAP (mode must match)
     * @param entryAllowed         true when the override entry is permitted
     *                             (in launch.allowedEntries or equals entry, for offline)
     */
    fun resolve(
        id: String?,
        entry: String?,
        mode: String?,
        fullscreen: Any?,
        theme: String?,
        network: String?,
        widgetId: Int?,
        widgetWapId: String?,
        isOffline: Boolean,
        entryAllowed: (String) -> Boolean
    ): Resolved {
        val dropped = mutableListOf<String>()

        val idOk = id != null && PathSanitizer.isValidId(id)

        var e: String? = null
        if (entry != null) {
            if (isOffline) {
                val pathPart = entry.substringBefore('?').substringBefore('#')
                val suffix = entry.substring(pathPart.length)
                val norm = PathSanitizer.sanitizeRelativePath(pathPart)
                val ext = pathPart.substringAfterLast('.', "").lowercase()
                val safeQuery = suffix.length <= 256 && suffix.none { it.code < 0x20 }
                when {
                    norm == null || (ext != "html" && ext != "htm") ->
                        dropped.add("WAP_ENTRY invalid: '$entry'")
                    !entryAllowed(norm) ->
                        dropped.add("WAP_ENTRY not allowed: '$entry'")
                    !safeQuery ->
                        dropped.add("WAP_ENTRY query/fragment unsafe")
                    else -> e = norm + suffix
                }
            } else {
                // Online: same-origin path/query/fragment overrides only.
                val norm = entry.trim()
                if (norm.isEmpty() || norm.contains("//") || norm.startsWith("/") && norm.contains("..")) {
                    dropped.add("WAP_ENTRY invalid for online: '$entry'")
                } else if (norm.length > 1024) {
                    dropped.add("WAP_ENTRY too long")
                } else {
                    e = if (norm.startsWith("/")) norm else "/$norm"
                }
            }
        }

        var m: String? = null
        if (mode != null) {
            val lower = mode.lowercase()
            if (lower == "offline" || lower == "online") {
                val matches = (lower == "offline") == isOffline
                if (matches) m = lower
                else dropped.add("WAP_MODE mismatch with manifest type")
            } else {
                dropped.add("WAP_MODE invalid: '$mode'")
            }
        }

        var fs: Boolean? = null
        if (fullscreen != null) {
            if (fullscreen is Boolean) fs = fullscreen
            else dropped.add("WAP_FULLSCREEN must be Boolean")
        }

        var t: String? = null
        if (theme != null) {
            when (theme.lowercase()) {
                "system" -> t = "system"
                "light" -> t = "light"
                "dark" -> t = "dark"
                else -> dropped.add("WAP_THEME invalid: '$theme'")
            }
        }

        var n: String? = null
        if (network != null) {
            val norm = NetworkPolicy.normalize(network)
            if (norm == null || norm == NetworkPolicy.INHERIT) {
                dropped.add("WAP_ALLOW_NETWORK invalid: '$network'")
            } else {
                n = norm
            }
        }

        var wid: Int? = null
        var widgetUsable = false
        if (widgetId != null) {
            if (widgetId >= 0) {
                wid = widgetId
                widgetUsable = widgetWapId == id && idOk
                if (!widgetUsable) dropped.add("WAP_WIDGET_ID does not match WAP_ID")
            } else {
                dropped.add("WAP_WIDGET_ID negative")
            }
        }

        return Resolved(id, idOk, e, m, fs, t, n, wid, widgetUsable, dropped)
    }
}
