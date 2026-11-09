package com.iamtungingit.wam.wap.archive

import com.iamtungingit.wam.util.PathSanitizer
import com.iamtungingit.wam.wap.manifest.ErrorCodes
import com.iamtungingit.wam.wap.manifest.ValidationError
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipFile

/**
 * Canonical-path-checked extraction (spec §7.1.7, §9.1.4). Pure JVM, unit tested.
 */
object SafeExtractor {

    data class ExtractResult(
        val ok: Boolean,
        val error: ValidationError? = null,
        val filesExtracted: Int = 0,
        val bytesWritten: Long = 0
    )

    /** Extracts all (or only the given) file entries of the archive into destDir. */
    fun extractAll(archive: File, destDir: File, only: Set<String>? = null): ExtractResult {
        return try {
            destDir.mkdirs()
            val canonicalDest = destDir.canonicalFile
            var files = 0
            var bytes = 0L

            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val norm = PathSanitizer.sanitizeRelativePath(e.name)
                        ?: return ExtractResult(false, ValidationError(
                            ErrorCodes.ARCHIVE_PATH_TRAVERSAL, "entry", "unsafe entry '${e.name}'"))
                    if (only != null && norm !in only) continue
                    if (files >= SizeLimits.MAX_ENTRIES) {
                        return ExtractResult(false, ValidationError(
                            ErrorCodes.ARCHIVE_TOO_MANY_ENTRIES, "entries", "> ${SizeLimits.MAX_ENTRIES}"))
                    }

                    val segments = norm.split('/')
                    var dir = canonicalDest
                    for (i in 0 until segments.size - 1) dir = File(dir, segments[i])
                    val canonicalDir = dir.canonicalFile
                    if (canonicalDir != canonicalDest &&
                        !canonicalDir.path.startsWith(canonicalDest.path + File.separator)
                    ) {
                        return ExtractResult(false, ValidationError(
                            ErrorCodes.ARCHIVE_PATH_TRAVERSAL, "entry", "path escape via '${e.name}'"))
                    }
                    canonicalDir.mkdirs()

                    val outFile = File(canonicalDir, segments.last())
                    val canonicalOut = outFile.canonicalFile
                    if (canonicalOut != canonicalDest &&
                        !canonicalOut.path.startsWith(canonicalDest.path + File.separator)
                    ) {
                        return ExtractResult(false, ValidationError(
                            ErrorCodes.ARCHIVE_PATH_TRAVERSAL, "entry", "path escape via '${e.name}'"))
                    }

                    var written = 0L
                    zip.getInputStream(e).use { ins ->
                        FileOutputStream(canonicalOut).use { out ->
                            val buf = ByteArray(65536)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                written += n
                                if (written > SizeLimits.MAX_ENTRY_BYTES) {
                                    return ExtractResult(false, ValidationError(
                                        ErrorCodes.ARCHIVE_TOO_LARGE, "entry", "'${e.name}' too large"))
                                }
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    bytes += written
                    files++
                }
            }
            ExtractResult(true, filesExtracted = files, bytesWritten = bytes)
        } catch (e: java.util.zip.ZipException) {
            ExtractResult(false, ValidationError(ErrorCodes.ARCHIVE_UNSUPPORTED, "archive", e.message ?: "zip error"))
        } catch (e: java.io.IOException) {
            ExtractResult(false, ValidationError(ErrorCodes.ARCHIVE_UNSUPPORTED, "archive", e.message ?: "io error"))
        }
    }

    /** Streams one entry to an output stream with a byte cap. */
    fun streamEntry(archive: File, entryName: String, maxBytes: Int, out: OutputStream): Boolean {
        return try {
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry(entryName) ?: return false
                if (entry.isDirectory) return false
                var total = 0L
                zip.getInputStream(entry).use { ins ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return false
                        out.write(buf, 0, n)
                    }
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun readEntry(archive: File, entryName: String, maxBytes: Int): ByteArray? {
        return try {
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry(entryName) ?: return null
                if (entry.isDirectory) return null
                var total = 0
                val buf = ByteArray(maxBytes + 1)
                zip.getInputStream(entry).use { ins ->
                    while (true) {
                        if (total > maxBytes) return null
                        val n = ins.read(buf, total, buf.size - total)
                        if (n < 0) break
                        total += n
                    }
                }
                if (total > maxBytes) null else buf.copyOf(total)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun entryExists(archive: File, entryName: String): Boolean = try {
        ZipFile(archive).use { zip -> zip.getEntry(entryName) != null }
    } catch (e: Exception) {
        false
    }
}
