package com.iamtungingit.wam.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/** Storage Access Framework I/O helpers (spec §13). */
object SafIo {

    data class CopyResult(val ok: Boolean, val bytes: Long, val reason: String? = null)

    fun queryDisplayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /** Copies a content Uri into a local file enforcing a hard byte cap while streaming. */
    fun copyTo(ctx: Context, uri: Uri, dest: File, maxBytes: Long): CopyResult {
        return try {
            dest.parentFile?.mkdirs()
            var total = 0L
            val input = ctx.contentResolver.openInputStream(uri)
                ?: return CopyResult(false, 0, "cannot open input stream")
            input.use { ins ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return CopyResult(false, total, "too_large")
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
            }
            CopyResult(true, total)
        } catch (e: SecurityException) {
            CopyResult(false, 0, e.message ?: "security")
        } catch (e: Exception) {
            CopyResult(false, 0, e.message ?: "io error")
        }
    }

    /** Writes a local file to a SAF document uri ("wt" truncates). */
    fun writeToFile(ctx: Context, uri: Uri, src: File): Boolean {
        return try {
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                src.inputStream().use { ins -> ins.copyTo(out) }
                out.flush()
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun deleteDocument(ctx: Context, uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(ctx.contentResolver, uri)
    } catch (e: Exception) {
        false
    }

    private val TREE_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )

    private data class DocChild(val docId: String, val name: String, val isDir: Boolean, val uri: Uri)

    private fun treeChildren(ctx: Context, parentDoc: Uri): List<DocChild> {
        val out = ArrayList<DocChild>()
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(
            parentDoc, DocumentsContract.getDocumentId(parentDoc))
        try {
            ctx.contentResolver.query(kids, TREE_PROJECTION, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    out.add(DocChild(
                        id, name, mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        DocumentsContract.buildDocumentUriUsingTree(kids, id)
                    ))
                }
            }
        } catch (e: Exception) {
            // Caller decides how to surface unreadable trees.
        }
        return out
    }

    private fun treeRoot(treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    /**
     * Plain recursive copy of a SAF tree into [destDir] (add + overwrite, never
     * deletes). Used by "Import project folder" / editor "Import → folder".
     * Returns the number of files copied.
     */
    fun importTree(ctx: Context, treeUri: Uri, destDir: File, maxEntryBytes: Long): Int {
        destDir.mkdirs()
        return importChildren(ctx, treeRoot(treeUri), destDir, maxEntryBytes)
    }

    private fun importChildren(ctx: Context, parentDoc: Uri, destDir: File, maxEntry: Long): Int {
        var count = 0
        for (child in treeChildren(ctx, parentDoc)) {
            if (child.isDir) {
                val sub = File(destDir, child.name)
                sub.mkdirs()
                count += importChildren(ctx, child.uri, sub, maxEntry)
            } else {
                val dest = File(destDir, child.name)
                if (copyTo(ctx, child.uri, dest, maxEntry).ok) count++
            }
        }
        return count
    }

    /**
     * Plain recursive write of [srcDir] into a SAF tree (create + overwrite, never
     * deletes). Used by "Export folder". Returns the number of files written.
     */
    fun exportTree(ctx: Context, srcDir: File, treeUri: Uri): Int =
        exportChildren(ctx, srcDir, treeRoot(treeUri))

    private fun exportChildren(ctx: Context, srcDir: File, parentDoc: Uri): Int {
        var count = 0
        val existing = treeChildren(ctx, parentDoc).associateBy { it.name }
        for (child in srcDir.listFiles() ?: emptyArray()) {
            if (child.name.startsWith(".") || child.name == "Thumbs.db") continue
            val hit = existing[child.name]
            if (child.isDirectory) {
                val doc = hit?.takeIf { it.isDir }?.uri ?: try {
                    DocumentsContract.createDocument(
                        ctx.contentResolver, parentDoc,
                        DocumentsContract.Document.MIME_TYPE_DIR, child.name)
                } catch (e: Exception) { null } ?: continue
                count += exportChildren(ctx, child, doc)
            } else {
                val doc = hit?.takeIf { !it.isDir }?.uri ?: try {
                    DocumentsContract.createDocument(
                        ctx.contentResolver, parentDoc, "application/octet-stream", child.name)
                } catch (e: Exception) { null } ?: continue
                if (writeToFile(ctx, doc, child)) count++
            }
        }
        return count
    }
}
