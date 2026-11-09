package com.iamtungingit.wam.wap.runtime

import android.content.Context
import com.iamtungingit.wam.storage.AtomicJsonStore
import com.iamtungingit.wam.storage.Fs
import com.iamtungingit.wam.storage.Paths
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Session tracking (spec §10.1). Sessions live in temporary/sessions/<sessionId>/
 * with session.json + content/. endSession is idempotent (spec §10.2).
 */
class SessionManager private constructor(private val context: Context) {

    data class ActiveSession(
        val sessionId: String,
        val wapId: String,
        val startedAt: Long,
        val contentDir: File
    )

    private val lock = Any()
    private val active = LinkedHashMap<String, ActiveSession>()

    companion object {
        @Volatile
        private var instance: SessionManager? = null

        fun get(context: Context): SessionManager =
            instance ?: synchronized(this) {
                instance ?: SessionManager(context.applicationContext).also { instance = it }
            }

        /** Safe static check for code paths without the locator (e.g. uninstall). */
        fun isRunningStatic(wapId: String): Boolean =
            instance?.isRunning(wapId) ?: false
    }

    fun createSession(wapId: String): ActiveSession? = synchronized(lock) {
        try {
            val id = UUID.randomUUID().toString()
            val dir = Paths.sessionDir(context, id)
            File(dir, "content").mkdirs()
            val now = System.currentTimeMillis()
            AtomicJsonStore(File(dir, "session.json")).write(
                JSONObject()
                    .put("sessionId", id)
                    .put("wapId", wapId)
                    .put("startedAt", now)
                    .put("pid", android.os.Process.myPid())
            )
            val s = ActiveSession(id, wapId, now, File(dir, "content"))
            active[id] = s
            s
        } catch (e: Exception) {
            null
        }
    }

    fun get(sessionId: String): ActiveSession? = synchronized(lock) { active[sessionId] }

    fun endSession(sessionId: String) = synchronized(lock) {
        active.remove(sessionId)
        val dir = Paths.sessionDir(context, sessionId)
        if (dir.exists()) Fs.deleteRec(dir)
    }

    fun isRunning(wapId: String): Boolean = synchronized(lock) {
        active.values.any { it.wapId == wapId }
    }

    fun activeIds(): Set<String> = synchronized(lock) { active.keys.toSet() }
}
