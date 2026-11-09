package com.iamtungingit.wam.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.iamtungingit.wam.wap.archive.SizeLimits
import java.io.File

/**
 * Mirrors a developer project directory to a SAF folder (v1.5.0 "mount project to
 * a SAF folder so you can just edit your project there").
 *
 * Semantics (documented in the Guide):
 *  - [exportTo] ("push", runs automatically after every WAM-side change): the SAF
 *    folder becomes an exact mirror of the project — files are added/updated and
 *    entries deleted in WAM are removed from the folder too.
 *  - [importFrom] ("pull", the Sync button / on open): the project becomes an
 *    exact mirror of the SAF folder — edits made by other apps arrive in WAM.
 *  - Last-writer-wins per file; the automatic push after each WAM change makes the
 *    race window tiny in practice.
 */
object SafMirror {

    data class Result(val ok: Boolean, val files: Int, val error: String? = null) {
        companion object {
            fun failure(error: String) = Result(false, 0, error)
        }
    }

    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )

    private data class Child(val docId: String, val name: String, val isDir: Boolean, val uri: Uri)

    private fun children(ctx: Context, parentDoc: Uri): List<Child> {
        val out = ArrayList<Child>()
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(
            parentDoc, DocumentsContract.getDocumentId(parentDoc))
        try {
            ctx.contentResolver.query(kids, PROJECTION, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    out.add(Child(
                        id, name,
                        mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        DocumentsContract.buildDocumentUriUsingTree(kids, id)
                    ))
                }
            }
        } catch (e: Exception) {
            // Query failure → treat as unreadable; callers surface the error.
        }
        return out
    }

    private fun rootDoc(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    // ------------------------------------------------------------- push (WAM → SAF)

    fun exportTo(ctx: Context, rootDir: File, treeUri: Uri): Result {
        if (!rootDir.isDirectory) return Result.failure("project folder missing")
        return try {
            val n = exportChildren(ctx, rootDir, rootDoc(treeUri))
            Result(true, n)
        } catch (e: Exception) {
            Result.failure(e.message ?: "export failed")
        }
    }

    /** Recursively makes [dir]'s SAF mirror match the local directory (add/update/delete). */
    private fun exportChildren(ctx: Context, dir: File, parentDoc: Uri): Int {
        val locals = (dir.listFiles() ?: emptyArray())
            .filter { it.name != ".DS_Store" && it.name != "Thumbs.db" }
        val byName = locals.associateBy { it.name }
        var count = 0

        // Remove SAF entries that no longer exist locally (true mirror).
        for (child in children(ctx, parentDoc)) {
            if (byName[child.name] == null) {
                try { DocumentsContract.deleteDocument(ctx.contentResolver, child.uri) } catch (e: Exception) {}
            }
        }

        // One query per directory; entries created below are not looked up again.
        val existingByName = children(ctx, parentDoc).associateBy { it.name }

        for (f in locals) {
            val existing = existingByName[f.name]
            if (f.isDirectory) {
                val doc = existing?.takeIf { it.isDir }?.uri
                    ?: try {
                        DocumentsContract.createDocument(
                            ctx.contentResolver, parentDoc,
                            DocumentsContract.Document.MIME_TYPE_DIR, f.name)
                    } catch (e: Exception) { null } ?: continue
                count += exportChildren(ctx, f, doc)
            } else {
                val doc = existing?.takeIf { !it.isDir }?.uri
                    ?: try {
                        DocumentsContract.createDocument(
                            ctx.contentResolver, parentDoc, "application/octet-stream", f.name)
                    } catch (e: Exception) { null } ?: continue
                if (SafIo.writeToFile(ctx, doc, f)) count++
            }
        }
        return count
    }

    // ------------------------------------------------------------- pull (SAF → WAM)

    fun importFrom(ctx: Context, treeUri: Uri, rootDir: File): Result {
        if (!rootDir.isDirectory) rootDir.mkdirs()
        return try {
            val n = importChildren(ctx, rootDoc(treeUri), rootDir)
            Result(true, n)
        } catch (e: Exception) {
            Result.failure(e.message ?: "import failed")
        }
    }

    /** Recursively copies the SAF tree into [destDir], overwriting existing files. */
    private fun importChildren(ctx: Context, parentDoc: Uri, destDir: File): Int {
        var count = 0
        val safNames = HashSet<String>()
        for (child in children(ctx, parentDoc)) {
            safNames.add(child.name)
            if (child.isDir) {
                val sub = File(destDir, child.name)
                sub.mkdirs()
                count += importChildren(ctx, child.uri, sub)
            } else {
                val dest = File(destDir, child.name)
                val r = SafIo.copyTo(ctx, child.uri, dest, SizeLimits.MAX_ENTRY_BYTES)
                if (r.ok) count++
            }
        }
        // True mirror: drop local entries the user deleted in the mounted folder.
        for (f in destDir.listFiles() ?: emptyArray()) {
            if (f.name == ".DS_Store" || f.name == "Thumbs.db") continue
            if (f.name !in safNames) Fs.deleteRec(f)
        }
        return count
    }
}
