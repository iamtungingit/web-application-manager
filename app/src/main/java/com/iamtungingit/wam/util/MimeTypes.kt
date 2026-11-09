package com.iamtungingit.wam.util

/** Extension → MIME mapping for locally served WAP content (spec §9.3). */
object MimeTypes {

    private val map = mapOf(
        "html" to "text/html", "htm" to "text/html",
        "css" to "text/css",
        "js" to "text/javascript", "mjs" to "text/javascript",
        "json" to "application/json",
        "webmanifest" to "application/manifest+json",
        "svg" to "image/svg+xml",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "webp" to "image/webp", "gif" to "image/gif", "ico" to "image/x-icon",
        "bmp" to "image/bmp", "avif" to "image/avif",
        "woff" to "font/woff", "woff2" to "font/woff2",
        "ttf" to "font/ttf", "otf" to "font/otf",
        "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "wav" to "audio/wav",
        "m4a" to "audio/mp4", "flac" to "audio/flac",
        "mp4" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska",
        "txt" to "text/plain", "xml" to "application/xml",
        "pdf" to "application/pdf", "wasm" to "application/wasm",
        "zip" to "application/zip", "md" to "text/markdown", "csv" to "text/csv"
    )

    /** Served as UTF-8 (spec §9.3). */
    private val textTypes = setOf(
        "text/html", "text/css", "text/javascript", "application/json",
        "application/manifest+json", "image/svg+xml", "text/plain",
        "application/xml", "text/markdown", "text/csv"
    )

    fun fromPath(path: String): String {
        val clean = path.substringBefore('?').substringBefore('#')
        val ext = clean.substringAfterLast('.', "").lowercase()
        return map[ext] ?: "application/octet-stream"
    }

    fun isText(mime: String): Boolean = mime in textTypes
}
