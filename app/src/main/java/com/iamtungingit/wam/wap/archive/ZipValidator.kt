package com.iamtungingit.wam.wap.archive

import com.iamtungingit.wam.util.PathSanitizer
import com.iamtungingit.wam.wap.manifest.ErrorCodes
import com.iamtungingit.wam.wap.manifest.ValidationError
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Structural ZIP validation on a local copy of the archive (spec §7.1).
 * Sizes are counted on actual streamed bytes, never trusted from ZipEntry.
 * Pure JVM, unit tested.
 */
object ZipValidator {

    data class Report(
        val ok: Boolean,
        val entryNames: Set<String> = emptySet(),
        val totalUncompressed: Long = 0,
        val error: ValidationError? = null,
        val warnings: List<String> = emptyList()
    )

    fun validate(archive: File): Report {
        if (!archive.exists() || archive.length() == 0L) {
            return Report(false, error = ve(ErrorCodes.ARCHIVE_UNSUPPORTED, "archive", "empty or missing file"))
        }
        if (archive.length() > SizeLimits.MAX_ARCHIVE_BYTES) {
            return Report(false, error = ve(ErrorCodes.ARCHIVE_TOO_LARGE, "archive", "compressed size > ${SizeLimits.MAX_ARCHIVE_BYTES} bytes"))
        }
        val zf = try {
            ZipFile(archive)
        } catch (e: Exception) {
            return Report(false, error = ve(ErrorCodes.ARCHIVE_UNSUPPORTED, "archive", "not a readable ZIP archive"))
        }

        zf.use { zip ->
            val names = HashSet<String>()
            val lowerNames = HashSet<String>()
            val warnings = mutableListOf<String>()
            var count = 0
            var totalUncompressed = 0L

            // java.util.zip.ZipFile indexes entries by name and can silently hide
            // duplicate central-directory entries; scan the raw stream first so
            // duplicates/case-collisions can never slip through (spec §7.1.3).
            java.util.zip.ZipInputStream(archive.inputStream().buffered()).use { zis ->
                var seen = 0
                while (true) {
                    val e = zis.nextEntry ?: break
                    seen++
                    if (seen > SizeLimits.MAX_ENTRIES) {
                        return Report(false, error = ve(ErrorCodes.ARCHIVE_TOO_MANY_ENTRIES, "entries", "> ${SizeLimits.MAX_ENTRIES} entries"))
                    }
                    val rawName = e.name
                    val cleanName = if (rawName.endsWith("/")) rawName.dropLast(1) else rawName
                    if (cleanName.isEmpty()) continue
                    val norm = PathSanitizer.sanitizeRelativePath(cleanName)
                        ?: return Report(false, error = ve(
                            if (rawName.contains("..") || rawName.startsWith("/") || rawName.contains('\\'))
                                ErrorCodes.ARCHIVE_PATH_TRAVERSAL else ErrorCodes.ARCHIVE_UNSUPPORTED,
                            "entry", "unsafe entry name '$rawName'"
                        ))
                    if (!lowerNames.add(norm.lowercase())) {
                        return Report(false, error = ve(ErrorCodes.ARCHIVE_DUPLICATE_ENTRY, "entry", "duplicate entry '$rawName'"))
                    }
                }
            }

            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                count++
                if (count > SizeLimits.MAX_ENTRIES) {
                    return Report(false, error = ve(ErrorCodes.ARCHIVE_TOO_MANY_ENTRIES, "entries", "> ${SizeLimits.MAX_ENTRIES} entries"))
                }

                val rawName = e.name
                val isDir = e.isDirectory || rawName.endsWith("/")
                val cleanName = if (rawName.endsWith("/")) rawName.dropLast(1) else rawName
                if (cleanName.isEmpty()) continue // root entry

                val norm = PathSanitizer.sanitizeRelativePath(cleanName)
                    ?: return Report(false, error = ve(
                        if (rawName.contains("..") || rawName.startsWith("/") || rawName.contains('\\'))
                            ErrorCodes.ARCHIVE_PATH_TRAVERSAL else ErrorCodes.ARCHIVE_UNSUPPORTED,
                        "entry", "unsafe entry name '$rawName'"
                    ))

                if (norm != cleanName) warnings.add("Entry name normalized: '$rawName'")

                val lowerName = norm.lowercase()
                // Duplicate/case-collision detection already happened on the raw stream
                // above; here we only collect names for the manifest check.
                if (!names.add(norm)) {
                    return Report(false, error = ve(ErrorCodes.ARCHIVE_DUPLICATE_ENTRY, "entry", "duplicate entry '$rawName'"))
                }

                val method = e.method
                if (method != ZipEntry.DEFLATED && method != ZipEntry.STORED) {
                    return Report(false, error = ve(ErrorCodes.ARCHIVE_UNSUPPORTED, "entry", "unsupported compression method in '$rawName'"))
                }

                if (isDir) continue

                // Stream-count actual bytes; never trust e.size (may be -1 or forged).
                var entryBytes = 0L
                try {
                    zip.getInputStream(e).use { ins ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            entryBytes += n
                            totalUncompressed += n
                            if (entryBytes > SizeLimits.MAX_ENTRY_BYTES) {
                                return Report(false, error = ve(ErrorCodes.ARCHIVE_TOO_LARGE, "entry", "'$rawName' exceeds ${SizeLimits.MAX_ENTRY_BYTES} bytes"))
                            }
                            if (totalUncompressed > SizeLimits.MAX_TOTAL_UNCOMPRESSED) {
                                return Report(false, error = ve(ErrorCodes.ARCHIVE_TOO_LARGE, "total", "uncompressed total > ${SizeLimits.MAX_TOTAL_UNCOMPRESSED} bytes"))
                            }
                        }
                    }
                } catch (e2: ZipException) {
                    return Report(false, error = ve(ErrorCodes.ARCHIVE_UNSUPPORTED, "entry", "unreadable/encrypted entry '$rawName': ${e2.message}"))
                } catch (e2: Exception) {
                    return Report(false, error = ve(ErrorCodes.ARCHIVE_UNSUPPORTED, "entry", "cannot read '$rawName': ${e2.message}"))
                }

                val compressed = e.compressedSize
                if (compressed > 0 && entryBytes > SizeLimits.RATIO_FLOOR_BYTES &&
                    entryBytes / compressed > SizeLimits.MAX_RATIO
                ) {
                    return Report(false, error = ve(ErrorCodes.ARCHIVE_RATIO_EXCEEDED, "entry", "compression ratio > ${SizeLimits.MAX_RATIO}:1 in '$rawName'"))
                }
            }

            if ("manifest.json" !in names) {
                val wrapped = names.any { it.endsWith("/manifest.json") }
                val msg = if (wrapped) {
                    "manifest.json found in a subfolder — it must be at the archive root"
                } else {
                    "manifest.json is missing from the archive root"
                }
                return Report(false, entryNames = names, error = ve(ErrorCodes.MANIFEST_MISSING, "manifest.json", msg))
            }

            return Report(true, names.toSet(), totalUncompressed, warnings = warnings.toList())
        }
    }

    private fun ve(code: String, field: String, message: String) = ValidationError(code, field, message)
}
