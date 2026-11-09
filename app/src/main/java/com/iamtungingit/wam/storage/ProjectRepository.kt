package com.iamtungingit.wam.storage

import android.content.Context
import android.os.Environment
import com.iamtungingit.wam.util.PathSanitizer
import java.io.File

/**
 * Developer workspace in app-specific EXTERNAL storage with private fallback (spec §8 A, §3.2).
 */
class ProjectRepository(private val context: Context) {

    @Volatile
    var usingFallback: Boolean = false
        private set

    fun projectsRoot(): File {
        val ext = context.getExternalFilesDir(null)
        val state = if (ext != null) Environment.getExternalStorageState(ext) else null
        if (ext != null && Environment.MEDIA_MOUNTED == state) {
            usingFallback = false
            return File(ext, "projects").apply { mkdirs() }
        }
        usingFallback = true
        val base = File(context.filesDir, "external_fallback")
        return File(base, "projects").apply { mkdirs() }
    }

    fun list(): List<File> =
        projectsRoot().listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun projectDir(name: String): File? {
        val safe = PathSanitizer.sanitizeProjectName(name) ?: return null
        return Paths.canonicalChild(projectsRoot(), safe)
    }

    fun exists(name: String): Boolean = projectDir(name)?.exists() == true

    /** Creates a project directory; null when the name is invalid or already taken. */
    fun create(name: String): File? {
        val dir = projectDir(name) ?: return null
        if (dir.exists()) return null
        if (!dir.mkdirs()) return null
        return dir
    }

    fun rename(oldName: String, newName: String): File? {
        val old = projectDir(oldName) ?: return null
        if (!old.exists()) return null
        val target = projectDir(newName) ?: return null
        if (target.exists()) return null
        return if (old.renameTo(target)) target else null
    }

    fun duplicate(oldName: String, newName: String): File? {
        val old = projectDir(oldName) ?: return null
        if (!old.exists()) return null
        val target = projectDir(newName) ?: return null
        if (target.exists()) return null
        target.mkdirs()
        return if (Fs.copyRec(old, target)) target else null
    }

    fun delete(name: String): Boolean {
        val dir = projectDir(name) ?: return false
        if (!dir.exists()) return true
        Fs.deleteRec(dir)
        return !dir.exists()
    }
}
