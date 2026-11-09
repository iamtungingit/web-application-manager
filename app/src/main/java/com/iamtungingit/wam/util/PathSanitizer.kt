package com.iamtungingit.wam.util

/**
 * Path and identifier sanitization (spec D10, §5.3, §7.1).
 * Pure JVM logic — unit tested without Android.
 */
object PathSanitizer {

    /** WAP package identifier (D10): max 64 chars total. */
    val ID_REGEX = Regex("^[a-z][a-z0-9_-]{0,31}(\\.[a-z][a-z0-9_-]{0,31}){0,4}$")

    const val MAX_ID_LENGTH = 64

    fun isValidId(id: String): Boolean = id.length <= MAX_ID_LENGTH && ID_REGEX.matches(id)

    /**
     * Normalizes and validates a relative archive/workspace path.
     * Returns the normalized path (slash separated) or null when unsafe.
     */
    fun sanitizeRelativePath(raw: String, maxSegments: Int = 32): String? {
        if (raw.isEmpty() || raw.length > 512) return null
        if (raw.any { it.code < 0x20 || it.code == 0x7f }) return null
        if (raw.contains('\\')) return null
        if (raw.contains(':')) return null
        if (raw.startsWith("/")) return null
        val segments = raw.split('/')
        if (segments.size > maxSegments) return null
        for (seg in segments) {
            if (seg.isEmpty() || seg == "." || seg == "..") return null
            if (seg.length > 255) return null
        }
        return segments.joinToString("/")
    }

    /** Single-segment file/folder name for editor create/rename. */
    fun sanitizeName(raw: String): String? {
        if (raw.contains('/')) return null
        return sanitizeRelativePath(raw)
    }

    /**
     * Developer project directory name.
     *
     * Rule change (user request): spaces are NOT allowed in project names — the user
     * explicitly asked for the "dashes or underscores" rule and for the warning to
     * appear WHILE typing, not after pressing Continue. Existing folders with spaces
     * still open fine; only newly typed names are restricted.
     */
    fun sanitizeProjectName(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.length > 48) return null
        if (t == "." || t == "..") return null
        if (!Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$").matches(t)) return null
        return t
    }

    /**
     * Live validation for the name input: returns a specific, human-readable reason
     * while the user types (empty string = still neutral, null = valid).
     */
    fun projectNameLiveError(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        if (t == "." || t == "..") return "“.” and “..” are reserved names"
        if (t.length > 48) return "Name is too long (max 48 characters)"
        val badSpace = t.contains(' ')
        val bad = t.firstOrNull { !it.isLetterOrDigit() && it != '-' && it != '_' && it != ' ' }
        return when {
            bad != null -> "“$bad” is not allowed — use letters, digits, dash (-) or underscore (_)"
            badSpace -> "Spaces are not allowed — use dash (-) or underscore (_), e.g. “${t.replace(' ', '_')}”"
            else -> null
        }
    }

    /** Directory-safe id for preview data isolation (spec §14). */
    fun previewDataId(projectName: String): String =
        "__preview__" + projectName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)

    /** Suggests a valid WAP id from an arbitrary app name. */
    fun suggestId(name: String): String {
        val cleaned = StringBuilder()
        name.lowercase().forEach { ch ->
            when {
                ch in 'a'..'z' -> cleaned.append(ch)
                ch in '0'..'9' -> cleaned.append(ch)
                ch == ' ' || ch == '-' || ch == '_' ->
                    if (cleaned.isNotEmpty() && cleaned.last() != '-') cleaned.append('-')
            }
        }
        var s = cleaned.toString().trimEnd('-')
        if (s.isEmpty()) return "app"
        if (s[0] in '0'..'9') s = "app-$s"
        return s.take(32)
    }
}
