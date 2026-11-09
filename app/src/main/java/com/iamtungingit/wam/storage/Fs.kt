package com.iamtungingit.wam.storage

import java.io.File

/**
 * Recursive file helpers that never follow symlinks (spec §10.4).
 */
object Fs {

    fun isSymlink(f: File): Boolean = try {
        f.canonicalFile.path != f.absoluteFile.path
    } catch (e: Exception) {
        true
    }

    /** Deletes recursively; returns approximate freed bytes. */
    fun deleteRec(root: File): Long {
        var freed = 0L
        fun rec(f: File) {
            if (f.isDirectory && !isSymlink(f)) {
                f.listFiles()?.forEach { rec(it) }
            }
            if (f.isFile) freed += f.length()
            f.delete()
        }
        if (root.exists()) rec(root)
        return freed
    }

    fun copyRec(src: File, dst: File, exclude: (String) -> Boolean = { false }): Boolean {
        if (exclude(src.name)) return true
        return try {
            if (src.isDirectory && !isSymlink(src)) {
                dst.mkdirs()
                src.listFiles()?.forEach { child ->
                    copyRec(child, File(dst, child.name), exclude)
                }
                true
            } else if (src.isFile) {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                true
            } else true
        } catch (e: Exception) {
            false
        }
    }

    fun sizeRec(root: File): Long {
        var total = 0L
        fun rec(f: File) {
            if (f.isDirectory && !isSymlink(f)) {
                f.listFiles()?.forEach { rec(it) }
            } else if (f.isFile) {
                total += f.length()
            }
        }
        if (root.exists()) rec(root)
        return total
    }
}
