package com.iamtungingit.wam.wap.manifest

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * manifest.json parsing with size/depth/duplicate-key handling (spec §5.3).
 */
object ManifestParser {

    const val MAX_MANIFEST_BYTES = 256 * 1024
    const val MAX_DEPTH = 8
    private const val MANIFEST_ENTRY = "manifest.json"

    sealed class Result {
        data class Success(val manifest: WapManifest, val warnings: List<String>) : Result()
        data class Failure(val error: ValidationError) : Result()
    }

    /** Reads and validates manifest.json from the archive root. */
    fun fromArchive(archive: File, maxQuotaBytes: Long): Result {
        val bytes = try {
            ZipFile(archive).use { zf ->
                val entry = zf.getEntry(MANIFEST_ENTRY) ?: return missingManifest()
                zf.getInputStream(entry).use { ins ->
                    val buf = ByteArray(MAX_MANIFEST_BYTES + 1)
                    var total = 0
                    while (true) {
                        if (total > MAX_MANIFEST_BYTES) {
                            return Result.Failure(
                                ValidationError(ErrorCodes.MANIFEST_TOO_LARGE, MANIFEST_ENTRY, "> $MAX_MANIFEST_BYTES bytes")
                            )
                        }
                        val n = ins.read(buf, total, buf.size - total)
                        if (n < 0) break
                        total += n
                    }
                    buf.copyOf(total)
                }
            }
        } catch (e: Exception) {
            return Result.Failure(
                ValidationError(ErrorCodes.MANIFEST_MISSING, MANIFEST_ENTRY, "cannot read archive: ${e.message}")
            )
        }
        return fromBytes(bytes, null, maxQuotaBytes)
    }

    /** Reads and validates manifest.json from a developer project directory (spec §14). */
    fun fromDirectory(projectDir: File, maxQuotaBytes: Long): Result {
        val manifestFile = File(projectDir, MANIFEST_ENTRY)
        if (!manifestFile.isFile) return missingManifest()
        val bytes = try {
            manifestFile.readBytes()
        } catch (e: Exception) {
            return Result.Failure(
                ValidationError(ErrorCodes.MANIFEST_MISSING, MANIFEST_ENTRY, "cannot read manifest.json")
            )
        }
        if (bytes.size > MAX_MANIFEST_BYTES) {
            return Result.Failure(
                ValidationError(ErrorCodes.MANIFEST_TOO_LARGE, MANIFEST_ENTRY, "> $MAX_MANIFEST_BYTES bytes")
            )
        }
        val names = HashSet<String>()
        fun collect(dir: File, prefix: String) {
            for (c in dir.listFiles() ?: emptyArray()) {
                if (c.isDirectory) collect(c, "$prefix${c.name}/") else names.add("$prefix${c.name}")
            }
        }
        collect(projectDir, "")
        return fromBytes(bytes, names, maxQuotaBytes)
    }

    fun fromBytes(bytes: ByteArray, entryNames: Set<String>?, maxQuotaBytes: Long): Result {
        if (bytes.size > MAX_MANIFEST_BYTES) {
            return Result.Failure(
                ValidationError(ErrorCodes.MANIFEST_TOO_LARGE, MANIFEST_ENTRY, "> $MAX_MANIFEST_BYTES bytes")
            )
        }
        val text = String(bytes, Charsets.UTF_8)
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return Result.Failure(
                ValidationError(ErrorCodes.MANIFEST_MALFORMED, MANIFEST_ENTRY, "invalid JSON: ${e.message}")
            )
        }
        if (maxDepth(text) > MAX_DEPTH) {
            return Result.Failure(
                ValidationError(ErrorCodes.MANIFEST_MALFORMED, MANIFEST_ENTRY, "nesting deeper than $MAX_DEPTH levels")
            )
        }
        val dupWarnings = topLevelDuplicateKeys(text).map { "Duplicate key '$it' ignored (last value wins)" }
        return ManifestValidator.validate(json, entryNames, maxQuotaBytes, dupWarnings)
    }

    private fun missingManifest(): Result = Result.Failure(
        ValidationError(
            ErrorCodes.MANIFEST_MISSING, MANIFEST_ENTRY,
            "manifest.json must be at the archive root (a wrapping folder is not accepted)"
        )
    )

    private fun maxDepth(text: String): Int {
        var depth = 0
        var max = 0
        var inStr = false
        var esc = false
        for (c in text) {
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{', '[' -> { depth++; if (depth > max) max = depth }
                    '}', ']' -> depth--
                }
            }
        }
        return max
    }

    private fun topLevelDuplicateKeys(text: String): List<String> {
        val seen = LinkedHashMap<String, Int>()
        var depth = 0
        var inStr = false
        var esc = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                    else -> if (depth == 0 && c == '"') {
                        val end = text.indexOf('"', i + 1)
                        if (end == -1) break
                        var j = end + 1
                        while (j < text.length && text[j].isWhitespace()) j++
                        if (j < text.length && text[j] == ':') {
                            val key = text.substring(i + 1, end)
                            seen[key] = (seen[key] ?: 0) + 1
                        }
                        i = end
                    }
                }
            }
            i++
        }
        return seen.filterValues { it > 1 }.keys.toList()
    }
}
