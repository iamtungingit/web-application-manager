package com.iamtungingit.wam.ui.main

import com.iamtungingit.wam.R

/**
 * Per-extension file type icons (v1.5.0 user request: "Each file type with own icon
 * image for png, jpeg, gif; movie for mp4, mov; document for txt, xml, html, css and
 * js with own icons"). Returns a drawable + a tint color resource so every type is
 * recognizable at a glance in the file manager.
 */
object FileIcons {

    data class TypeIcon(val drawable: Int, val tint: Int?)

    private val NO_TINT: Int? = null

    /** Extension (lowercase, without dot) → icon + tint. */
    private val byExtension = mapOf<String, TypeIcon>(
        // Images
        "png" to TypeIcon(R.drawable.ic_file_image, R.color.file_image),
        "jpg" to TypeIcon(R.drawable.ic_file_image, R.color.file_image),
        "jpeg" to TypeIcon(R.drawable.ic_file_image, R.color.file_image),
        "gif" to TypeIcon(R.drawable.ic_file_image, R.color.file_image),
        "webp" to TypeIcon(R.drawable.ic_file_image, R.color.file_image),
        "svg" to TypeIcon(R.drawable.ic_file_image, R.color.file_image),
        "bmp" to TypeIcon(R.drawable.ic_file_image, R.color.file_image),
        "avif" to TypeIcon(R.drawable.ic_file_image, R.color.file_image),
        "ico" to TypeIcon(R.drawable.ic_file_image, R.color.file_image),
        // Movies
        "mp4" to TypeIcon(R.drawable.ic_file_video, R.color.file_video),
        "mov" to TypeIcon(R.drawable.ic_file_video, R.color.file_video),
        "webm" to TypeIcon(R.drawable.ic_file_video, R.color.file_video),
        "mkv" to TypeIcon(R.drawable.ic_file_video, R.color.file_video),
        // Audio
        "mp3" to TypeIcon(R.drawable.ic_file_audio, R.color.file_audio),
        "wav" to TypeIcon(R.drawable.ic_file_audio, R.color.file_audio),
        "ogg" to TypeIcon(R.drawable.ic_file_audio, R.color.file_audio),
        "flac" to TypeIcon(R.drawable.ic_file_audio, R.color.file_audio),
        "m4a" to TypeIcon(R.drawable.ic_file_audio, R.color.file_audio),
        // Web documents
        "html" to TypeIcon(R.drawable.ic_file_html, R.color.file_html),
        "htm" to TypeIcon(R.drawable.ic_file_html, R.color.file_html),
        "css" to TypeIcon(R.drawable.ic_file_css, R.color.file_css),
        "js" to TypeIcon(R.drawable.ic_file_js, R.color.file_js),
        "mjs" to TypeIcon(R.drawable.ic_file_js, R.color.file_js),
        "json" to TypeIcon(R.drawable.ic_file_json, R.color.file_json),
        "webmanifest" to TypeIcon(R.drawable.ic_file_json, R.color.file_json),
        "xml" to TypeIcon(R.drawable.ic_file_xml, R.color.file_xml),
        // Plain text documents
        "txt" to TypeIcon(R.drawable.ic_new_file, R.color.file_txt),
        "md" to TypeIcon(R.drawable.ic_new_file, R.color.file_txt),
        "csv" to TypeIcon(R.drawable.ic_new_file, R.color.file_txt),
        "log" to TypeIcon(R.drawable.ic_new_file, R.color.file_txt),
        // Fonts
        "woff" to TypeIcon(R.drawable.ic_file_xml, R.color.file_xml),
        "woff2" to TypeIcon(R.drawable.ic_file_xml, R.color.file_xml),
        "ttf" to TypeIcon(R.drawable.ic_file_xml, R.color.file_xml),
        "otf" to TypeIcon(R.drawable.ic_file_xml, R.color.file_xml),
        // Archives
        "zip" to TypeIcon(R.drawable.ic_archive, R.color.file_archive),
        "wap" to TypeIcon(R.drawable.ic_archive, R.color.file_archive)
    )

    fun iconFor(name: String): TypeIcon {
        val ext = name.substringAfterLast('.', "").lowercase()
        return byExtension[ext]
            ?: TypeIcon(R.drawable.ic_new_file, NO_TINT) // generic document
    }
}
