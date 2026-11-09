package com.iamtungingit.wam.storage

import android.content.Context
import com.iamtungingit.wam.util.PathSanitizer
import java.io.File
import java.io.IOException

/** Every storage path WAM uses is built here (spec §8). */
object Paths {

    // ---- Internal private storage (installed archives, data, config) ----

    fun installedDir(ctx: Context): File = File(ctx.filesDir, "installed").apply { mkdirs() }
    fun incomingDir(ctx: Context): File = File(installedDir(ctx), ".incoming").apply { mkdirs() }
    fun archiveFile(ctx: Context, wapId: String): File = File(installedDir(ctx), "$wapId.wap")
    fun archiveBakFile(ctx: Context, wapId: String): File = File(installedDir(ctx), "$wapId.wap.bak")

    fun appDataRoot(ctx: Context): File = File(ctx.filesDir, "app_data").apply { mkdirs() }
    fun appDataDir(ctx: Context, wapId: String): File = File(appDataRoot(ctx), wapId)

    fun configDir(ctx: Context): File = File(ctx.filesDir, "config").apply { mkdirs() }
    fun iconsDir(ctx: Context): File = File(configDir(ctx), "icons").apply { mkdirs() }
    fun iconFile(ctx: Context, wapId: String): File = File(iconsDir(ctx), "$wapId.png")
    fun registryFile(ctx: Context): File = File(configDir(ctx), "registry.json")
    fun widgetsFile(ctx: Context): File = File(configDir(ctx), "widgets.json")
    fun settingsFile(ctx: Context): File = File(configDir(ctx), "settings.json")
    fun appPrefsFile(ctx: Context): File = File(configDir(ctx), "app_prefs.json")
    fun mountsFile(ctx: Context): File = File(configDir(ctx), "mounts.json")

    fun temporaryDir(ctx: Context): File = File(ctx.filesDir, "temporary").apply { mkdirs() }
    fun sessionsRoot(ctx: Context): File = File(temporaryDir(ctx), "sessions").apply { mkdirs() }
    fun sessionDir(ctx: Context, sessionId: String): File = File(sessionsRoot(ctx), sessionId)

    fun shareDir(ctx: Context): File = File(ctx.cacheDir, "share").apply { mkdirs() }

    // ---- Validated id-derived directories (spec D10 + §7.1.7) ----

    /** Canonical-path confirmation before any id-derived directory is used. */
    fun canonicalChild(parent: File, name: String): File? = try {
        val canonicalParent = parent.canonicalFile
        val child = File(canonicalParent, name)
        val canonicalChild = child.canonicalFile
        if (canonicalChild.path.startsWith(canonicalParent.path + File.separator) ||
            canonicalChild.path == canonicalParent.path
        ) {
            child
        } else {
            null
        }
    } catch (e: IOException) {
        null
    }

    /** Returns the per-WAP data directory only after id validation + canonical check. */
    fun appDataDirChecked(ctx: Context, wapId: String): File? {
        if (!PathSanitizer.isValidId(wapId) && !wapId.startsWith("__preview__")) return null
        return canonicalChild(appDataRoot(ctx), wapId)
    }

    fun iconFileChecked(ctx: Context, wapId: String): File? {
        if (!PathSanitizer.isValidId(wapId)) return null
        return canonicalChild(iconsDir(ctx), "$wapId.png")
    }
}
