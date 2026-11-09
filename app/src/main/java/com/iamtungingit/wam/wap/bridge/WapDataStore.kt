package com.iamtungingit.wam.wap.bridge

import com.iamtungingit.wam.storage.Fs
import com.iamtungingit.wam.util.PathSanitizer
import com.iamtungingit.wam.wap.archive.SizeLimits
import java.io.File
import java.io.FileOutputStream

/**
 * Per-WAP persistent data store rooted at files/app_data/<id>/ (spec §9.6).
 * Flat namespace, strict names, quotas, atomic writes. No API can escape the root.
 * Pure JVM (root passed as File), unit tested.
 */
class WapDataStore(private val root: File, private val quotaBytes: Long) {

    data class WriteResult(val ok: Boolean, val error: String? = null)

    private val lock = Any()

    init {
        root.mkdirs()
    }

    private fun fileFor(name: String): File? {
        if (!NAME_REGEX.matches(name)) return null
        if (name.contains("..")) return null
        return File(root, name)
    }

    fun list(): List<String> = synchronized(lock) {
        root.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()
    }

    fun readText(name: String): String? = synchronized(lock) {
        val f = fileFor(name) ?: return null
        if (!f.exists() || f.length() > SizeLimits.BRIDGE_MAX_FILE) return null
        try {
            f.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** Binary read for captures/recordings saved by the official JS API (v1.6.0).
     *  Returns null for invalid names, missing files and oversized content. */
    fun readBinary(name: String): ByteArray? = synchronized(lock) {
        val f = fileFor(name) ?: return null
        if (!f.exists() || f.length() > SizeLimits.BRIDGE_MAX_FILE) return null
        try {
            f.readBytes()
        } catch (e: Exception) {
            null
        }
    }

    fun writeText(name: String, text: String): WriteResult = synchronized(lock) {
        val f = fileFor(name) ?: return WriteResult(false, "invalid_name")
        val data = try {
            text.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            return WriteResult(false, "encode_failed")
        }
        if (data.size > SizeLimits.BRIDGE_MAX_FILE) return WriteResult(false, "file_too_large")

        val existing = root.listFiles()?.filter { it.isFile } ?: emptyList()
        val filesWithoutTarget = existing.count { it.name != name }
        if (filesWithoutTarget + 1 > SizeLimits.BRIDGE_MAX_FILES) return WriteResult(false, "too_many_files")

        val currentUsage = existing.sumOf { it.length() }
        val replaced = if (f.exists()) f.length() else 0L
        if (currentUsage - replaced + data.size > quotaBytes) return WriteResult(false, "quota_exceeded")

        return try {
            root.mkdirs()
            val tmp = File(root, "." + name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(data)
                out.flush()
                out.fd.sync()
            }
            if (f.exists()) f.delete()
            if (tmp.renameTo(f)) WriteResult(true) else WriteResult(false, "rename_failed")
        } catch (e: Exception) {
            WriteResult(false, e.message ?: "write_failed")
        }
    }

    fun delete(name: String): Boolean = synchronized(lock) {
        val f = fileFor(name) ?: return false
        f.delete()
    }

    /**
     * Binary write (photos from WAP.camera.capture, audio from WAP.mic.record).
     * Same strict name rules, file count and quota as writeText.
     */
    fun writeBinary(name: String, data: ByteArray): WriteResult = synchronized(lock) {
        val f = fileFor(name) ?: return WriteResult(false, "invalid_name")
        if (data.size > SizeLimits.BRIDGE_MAX_FILE) return WriteResult(false, "file_too_large")

        val existing = root.listFiles()?.filter { it.isFile } ?: emptyList()
        val filesWithoutTarget = existing.count { it.name != name }
        if (filesWithoutTarget + 1 > SizeLimits.BRIDGE_MAX_FILES) return WriteResult(false, "too_many_files")

        val currentUsage = existing.sumOf { it.length() }
        val replaced = if (f.exists()) f.length() else 0L
        if (currentUsage - replaced + data.size > quotaBytes) return WriteResult(false, "quota_exceeded")

        return try {
            root.mkdirs()
            val tmp = File(root, "." + name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(data)
                out.flush()
                out.fd.sync()
            }
            if (f.exists()) f.delete()
            if (tmp.renameTo(f)) WriteResult(true) else WriteResult(false, "rename_failed")
        } catch (e: Exception) {
            WriteResult(false, e.message ?: "write_failed")
        }
    }

    /** Returns (bytes, files). */
    fun usage(): Pair<Long, Int> = synchronized(lock) {
        val files = root.listFiles()?.filter { it.isFile } ?: emptyList()
        Pair(files.sumOf { it.length() }, files.size)
    }

    fun deleteAll() = synchronized(lock) {
        Fs.deleteRec(root)
        root.mkdirs()
    }

    companion object {
        /** Flat namespace rule (spec §9.6). */
        val NAME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

        /** Builds a store for a validated id (caller must pass a validated/preview dataId). */
        fun forId(context: android.content.Context, dataId: String, quotaBytes: Long): WapDataStore? {
            val dir = com.iamtungingit.wam.storage.Paths.appDataDirChecked(context, dataId) ?: return null
            return WapDataStore(dir, quotaBytes)
        }
    }
}
