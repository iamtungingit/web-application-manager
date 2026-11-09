package com.iamtungingit.wam.storage

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Atomic JSON file store (spec D9): write to a temp file in the same directory,
 * fsync, then rename over the target. One mutex per file.
 */
class AtomicJsonStore(private val file: File) {

    private val lock = Any()

    fun read(): JSONObject? = synchronized(lock) { readUnlocked() }

    private fun readUnlocked(): JSONObject? = try {
        if (!file.exists()) null else JSONObject(file.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    fun write(obj: JSONObject): Boolean = synchronized(lock) { writeUnlocked(obj) }

    /** Read-modify-write under the same mutex; a missing file starts from {}. */
    fun update(transform: (JSONObject) -> JSONObject): Boolean = synchronized(lock) {
        val current = readUnlocked() ?: JSONObject()
        writeUnlocked(transform(current))
    }

    private fun writeUnlocked(obj: JSONObject): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(obj.toString(2).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (file.exists()) {
                // POSIX rename over an existing file is atomic; delete() first keeps
                // the operation valid on filesystems that refuse replace-rename.
                file.delete()
            }
            tmp.renameTo(file)
        } catch (e: IOException) {
            false
        }
    }
}
