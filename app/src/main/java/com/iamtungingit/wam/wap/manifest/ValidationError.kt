package com.iamtungingit.wam.wap.manifest

/** Validation error codes (spec §5.3). */
object ErrorCodes {
    const val MANIFEST_MISSING = "MANIFEST_MISSING"
    const val MANIFEST_TOO_LARGE = "MANIFEST_TOO_LARGE"
    const val MANIFEST_MALFORMED = "MANIFEST_MALFORMED"
    const val ID_INVALID = "ID_INVALID"
    const val NAME_INVALID = "NAME_INVALID"
    const val VERSION_INVALID = "VERSION_INVALID"
    const val TYPE_INVALID = "TYPE_INVALID"
    const val ENTRY_INVALID = "ENTRY_INVALID"
    const val ENTRY_NOT_FOUND = "ENTRY_NOT_FOUND"
    const val ICON_INVALID = "ICON_INVALID"
    const val RUNTIME_TOO_OLD = "RUNTIME_TOO_OLD"
    const val URL_SCHEME_FORBIDDEN = "URL_SCHEME_FORBIDDEN"
    const val URL_USERINFO_FORBIDDEN = "URL_USERINFO_FORBIDDEN"
    const val URL_TOO_LONG = "URL_TOO_LONG"
    const val ARCHIVE_PATH_TRAVERSAL = "ARCHIVE_PATH_TRAVERSAL"
    const val ARCHIVE_TOO_LARGE = "ARCHIVE_TOO_LARGE"
    const val ARCHIVE_TOO_MANY_ENTRIES = "ARCHIVE_TOO_MANY_ENTRIES"
    const val ARCHIVE_RATIO_EXCEEDED = "ARCHIVE_RATIO_EXCEEDED"
    const val ARCHIVE_DUPLICATE_ENTRY = "ARCHIVE_DUPLICATE_ENTRY"
    const val ARCHIVE_UNSUPPORTED = "ARCHIVE_UNSUPPORTED"

    // Packaging-only codes (custom packager v2): the packager must never report
    // ARCHIVE_UNSUPPORTED for problems that are not archive-format issues.
    const val PROJECT_INVALID = "PROJECT_INVALID"
    const val PROJECT_WRITE_FAILED = "PROJECT_WRITE_FAILED"
}

data class ValidationError(val code: String, val field: String, val message: String) {
    override fun toString(): String = "[$code] $field: $message"
}
