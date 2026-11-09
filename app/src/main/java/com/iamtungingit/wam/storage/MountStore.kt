package com.iamtungingit.wam.storage

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Persists which device folder (SAF tree) each developer project is mounted to
 * (v1.5.0 user request: "Mounting the project to a SAF folder so you can just edit
 * your project there"). Stored in config/mounts.json.
 */
class MountStore(context: Context) {

    data class MountRecord(val projectName: String, val treeUri: String, val display: String)

    private val store = AtomicJsonStore(Paths.mountsFile(context))

    private fun readMap(): MutableMap<String, JSONObject> {
        val root = store.read() ?: return LinkedHashMap()
        val mounts = root.optJSONObject("mounts") ?: return LinkedHashMap()
        val out = LinkedHashMap<String, JSONObject>()
        for (key in mounts.keys()) out[key] = mounts.optJSONObject(key) ?: JSONObject()
        return out
    }

    private fun writeMap(map: Map<String, JSONObject>): Boolean {
        val mounts = JSONObject()
        for ((k, v) in map) mounts.put(k, v)
        return store.write(JSONObject().put("mounts", mounts))
    }

    fun get(projectName: String): MountRecord? {
        val o = readMap()[projectName] ?: return null
        val uri = o.optString("treeUri")
        if (uri.isEmpty()) return null
        return MountRecord(projectName, uri, o.optString("display", uri))
    }

    fun set(projectName: String, treeUri: Uri, display: String): Boolean {
        val map = readMap()
        map[projectName] = JSONObject()
            .put("treeUri", treeUri.toString())
            .put("display", display)
        return writeMap(map)
    }

    fun remove(projectName: String): Boolean {
        val map = readMap()
        if (map.remove(projectName) == null) return true
        return writeMap(map)
    }

    /** True when the stored tree permission still resolves (user may have deleted the folder). */
    fun usable(ctx: Context, projectName: String): Boolean {
        val rec = get(projectName) ?: return false
        return try {
            val uri = Uri.parse(rec.treeUri)
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
            ctx.contentResolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        fun displayName(ctx: Context, treeUri: Uri): String = try {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            SafIo.queryDisplayName(ctx, docUri)
                ?: docId.substringAfterLast(':').ifEmpty { docId }
        } catch (e: Exception) {
            treeUri.toString()
        }
    }
}
