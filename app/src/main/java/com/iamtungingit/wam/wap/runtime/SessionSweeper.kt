package com.iamtungingit.wam.wap.runtime

import android.content.Context
import com.iamtungingit.wam.storage.Fs
import com.iamtungingit.wam.storage.Paths
import org.json.JSONObject
import java.io.File

/**
 * Mandatory startup + on-demand sweeping of stale temporary files (spec §10.4).
 * WAM is single-process, so every session directory present at cold start is stale.
 */
object SessionSweeper {

    /** Cold start: delete all session directories; returns freed bytes. */
    fun sweepAll(context: Context): Long {
        val root = Paths.sessionsRoot(context)
        var freed = Fs.deleteRec(root)
        root.mkdirs()
        freed += sweepSharedTransient(context)
        return freed
    }

    /** Safe sweep: only delete directories NOT belonging to active sessions. */
    fun sweepInactive(context: Context, activeIds: Set<String>): Long {
        var freed = 0L
        Paths.sessionsRoot(context).listFiles()?.forEach { dir ->
            if (dir.name !in activeIds) freed += Fs.deleteRec(dir)
        }
        freed += sweepSharedTransient(context)
        return freed
    }

    private fun sweepSharedTransient(context: Context): Long {
        var freed = 0L
        // transient share copies
        freed += Fs.deleteRec(Paths.shareDir(context))
        // stale .part files
        Paths.incomingDir(context).listFiles { f -> f.name.endsWith(".part") }?.forEach {
            freed += it.length()
            it.delete()
        }
        // stale atomic-store tmp files
        Paths.configDir(context).listFiles { f -> f.name.endsWith(".tmp") }?.forEach {
            freed += it.length()
            it.delete()
        }
        return freed
    }

    /** Lists current session directory names (Settings → "Show session directories"). */
    fun listSessions(context: Context): List<String> =
        Paths.sessionsRoot(context).listFiles()?.map { it.name } ?: emptyList()

    fun sessionInfo(context: Context, sessionId: String): JSONObject? =
        com.iamtungingit.wam.storage.AtomicJsonStore(File(Paths.sessionDir(context, sessionId), "session.json")).read()
}
