package com.iamtungingit.wam.wap.archive

import com.iamtungingit.wam.util.MimeTypes
import com.iamtungingit.wam.util.PathSanitizer
import com.iamtungingit.wam.wap.manifest.ErrorCodes
import com.iamtungingit.wam.wap.manifest.ManifestParser
import com.iamtungingit.wam.wap.manifest.ValidationError
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages a developer project directory into a .wap archive (spec §14).
 *
 * Custom packaging pipeline (v2, hardened after the on-device "[ARCHIVE_UNSUPPORTED]"
 * reports):
 *  1. every failure stage returns a TYPED error — ARCHIVE_UNSUPPORTED is only used for
 *     genuinely unreadable archive data, never as a catch-all;
 *  2. the manifest is validated from the project directory BEFORE any zip is written,
 *     so the most common mistakes surface with actionable messages;
 *  3. the archive is written to a temporary ".part" file and renamed atomically, so a
 *     failed run can never leave a half-written .wap behind;
 *  4. round trip: the produced archive is re-validated with the same ZipValidator /
 *     ManifestParser used on import (spec §14 "Package").
 */
object WapPackager {

    data class PackageResult(
        val ok: Boolean,
        val file: File? = null,
        val error: ValidationError? = null,
        val warnings: List<String> = emptyList()
    )

    fun packageProject(projectDir: File, outFile: File, maxQuotaBytes: Long): PackageResult {
        if (!projectDir.isDirectory) {
            return PackageResult(false, error = ValidationError(
                ErrorCodes.PROJECT_INVALID, "project",
                "project folder does not exist: ${projectDir.path}"))
        }

        // --- Stage 1: manifest present? (typed error, nothing written yet) ---
        val manifestFile = File(projectDir, "manifest.json")
        if (!manifestFile.isFile) {
            return PackageResult(false, error = ValidationError(
                ErrorCodes.MANIFEST_MISSING, "manifest.json",
                "the project has no manifest.json — open the Manifest tab and create one"))
        }

        // --- Stage 2: collect files (deterministic order, exclusions applied) ---
        // manifest.json is written explicitly as the FIRST entry below, so it must be
        // excluded here — including it twice made ZipOutputStream throw
        // "duplicate entry: manifest.json", which the old catch-all reported as
        // [ARCHIVE_UNSUPPORTED] for every project (the on-device packaging bug).
        val files = ArrayList<File>()
        collectFiles(projectDir, files)
        files.removeAll { it.canonicalPath == manifestFile.canonicalPath }
        if (files.size + 1 > SizeLimits.MAX_ENTRIES) {
            return PackageResult(false, error = ValidationError(
                ErrorCodes.ARCHIVE_TOO_MANY_ENTRIES, "entries",
                "project has more than ${SizeLimits.MAX_ENTRIES} files"))
        }
        var total = manifestFile.length()
        for (f in files) total += f.length()
        if (total > SizeLimits.MAX_TOTAL_UNCOMPRESSED) {
            return PackageResult(false, error = ValidationError(
                ErrorCodes.ARCHIVE_TOO_LARGE, "project",
                "uncompressed size exceeds ${SizeLimits.MAX_TOTAL_UNCOMPRESSED} bytes"))
        }
        val rels = HashMap<File, String>(files.size)
        for (f in files) {
            val rel = relativePath(projectDir, f)
            if (rel == null) {
                return PackageResult(false, error = ValidationError(
                    ErrorCodes.PROJECT_INVALID, "file",
                    "cannot package '${f.name}' — unsafe or outside the project folder"))
            }
            rels[f] = rel
        }

        // --- Stage 3: validate the manifest against the real file list BEFORE zipping ---
        val preWarnings = mutableListOf<String>()
        when (val pre = ManifestParser.fromDirectory(projectDir, maxQuotaBytes)) {
            is ManifestParser.Result.Failure -> return PackageResult(false, error = pre.error)
            is ManifestParser.Result.Success -> preWarnings.addAll(pre.warnings)
        }

        // --- Stage 4: write to a .part file, then rename atomically ---
        val partFile = File(outFile.parentFile, outFile.name + ".part")
        try {
            partFile.parentFile?.mkdirs()
            partFile.delete()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(partFile))).use { zos ->
                putFile(zos, manifestFile, "manifest.json")
                files.sortedBy { rels[it] }
                    .filter { rels[it] != "manifest.json" } // never write the manifest twice
                    .forEach { putFile(zos, it, rels[it]!!) }
            }
        } catch (e: Exception) {
            partFile.delete()
            return PackageResult(false, error = ValidationError(
                ErrorCodes.PROJECT_WRITE_FAILED, "zip",
                "could not write the package (${e.javaClass.simpleName}): ${e.message ?: "unknown error"}"))
        }
        if (!partFile.renameTo(outFile)) {
            partFile.delete()
            outFile.delete()
            return PackageResult(false, error = ValidationError(
                ErrorCodes.PROJECT_WRITE_FAILED, "zip",
                "could not move the finished package to ${outFile.path}"))
        }

        // --- Stage 5: round trip — validate exactly like an import (spec §14) ---
        val report = ZipValidator.validate(outFile)
        if (!report.ok) {
            val err = report.error ?: ValidationError(
                ErrorCodes.ARCHIVE_UNSUPPORTED, "archive", "validation failed")
            outFile.delete()
            return PackageResult(false, error = err)
        }
        val mf = ManifestParser.fromBytes(
            SafeExtractor.readEntry(outFile, "manifest.json", ManifestParser.MAX_MANIFEST_BYTES)
                ?: ByteArray(0),
            report.entryNames, maxQuotaBytes
        )
        when (mf) {
            is ManifestParser.Result.Failure -> {
                outFile.delete()
                return PackageResult(false, error = mf.error)
            }
            is ManifestParser.Result.Success -> preWarnings.addAll(mf.warnings)
        }
        return PackageResult(true, outFile, warnings = preWarnings)
    }

    private fun putFile(zos: ZipOutputStream, f: File, entryName: String) {
        val entry = ZipEntry(entryName)
        entry.time = 0L // deterministic archives
        entry.method = ZipEntry.DEFLATED
        zos.putNextEntry(entry)
        f.inputStream().use { it.copyTo(zos, 65536) }
        zos.closeEntry()
    }

    private fun relativePath(root: File, f: File): String? {
        val rootPath = root.canonicalPath + File.separator
        val path = f.canonicalPath
        if (!path.startsWith(rootPath)) return null
        val rel = path.substring(rootPath.length).replace(File.separatorChar, '/')
        return if (PathSanitizer.sanitizeRelativePath(rel) == rel) rel else null
    }

    private fun collectFiles(dir: File, out: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (c in children.sortedBy { it.name }) {
            when {
                c.name.startsWith(".") || c.name == "Thumbs.db" || c.name.endsWith(".wap") -> continue
                c.isDirectory -> collectFiles(c, out)
                c.isFile -> out.add(c)
            }
        }
    }

    /** Suggested export file name for a packaged project. */
    fun suggestedName(manifest: com.iamtungingit.wam.wap.manifest.WapManifest): String =
        "${manifest.id}-${manifest.version}.wap"

    fun mimeTypeFor(): String = MimeTypes.fromPath("x.wap")
}
