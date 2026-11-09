package com.iamtungingit.wam.wap.install

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.iamtungingit.wam.settings.SettingsRepository
import com.iamtungingit.wam.storage.Fs
import com.iamtungingit.wam.storage.Paths
import com.iamtungingit.wam.storage.SafIo
import com.iamtungingit.wam.util.Hashing
import com.iamtungingit.wam.wap.archive.SafeExtractor
import com.iamtungingit.wam.wap.archive.SizeLimits
import com.iamtungingit.wam.wap.archive.ZipValidator
import com.iamtungingit.wam.wap.manifest.ManifestParser
import com.iamtungingit.wam.wap.manifest.WapManifest
import com.iamtungingit.wam.wap.manifest.ValidationError
import com.iamtungingit.wam.wap.runtime.NetworkPolicy
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Transactional installation (spec §7.2): stage → user confirms → commit.
 * No extracted application files are kept at install time.
 */
class InstallManager(
    private val context: Context,
    private val registry: Registry,
    private val settings: SettingsRepository
) {

    enum class State { NEW, UPDATE, REINSTALL, DOWNGRADE }

    data class StagedInstall(
        val partFile: File,
        val manifest: WapManifest,
        val sha256: String,
        val sizeBytes: Long,
        val sourceName: String,
        val existing: RegistryEntry?,
        val state: State,
        val identityChanged: Boolean,
        val warnings: List<String>
    )

    sealed class StageResult {
        data class Staged(val staged: StagedInstall) : StageResult()
        data class Failed(val error: ValidationError) : StageResult()
    }

    sealed class CommitResult {
        data class Ok(val entry: RegistryEntry) : CommitResult()
        data class Failed(val error: ValidationError) : CommitResult()
    }

    /** Step 1-5 of spec §7.2: copy to .incoming, validate, hash, compare with registry. */
    fun stage(sourceUri: Uri?, sourceFile: File?, sourceName: String?): StageResult {
        val incoming = Paths.incomingDir(context)
        val part = File(incoming, UUID.randomUUID().toString() + ".part")
        try {
            val copy = when {
                sourceUri != null -> SafIo.copyTo(context, sourceUri, part, SizeLimits.MAX_ARCHIVE_BYTES)
                sourceFile != null -> {
                    part.parentFile?.mkdirs()
                    if (sourceFile.length() > SizeLimits.MAX_ARCHIVE_BYTES) {
                        return StageResult.Failed(ValidationError(
                            com.iamtungingit.wam.wap.manifest.ErrorCodes.ARCHIVE_TOO_LARGE, "archive",
                            "compressed size > ${SizeLimits.MAX_ARCHIVE_BYTES}"))
                    }
                    sourceFile.copyTo(part, overwrite = true)
                    SafIo.CopyResult(true, part.length())
                }
                else -> SafIo.CopyResult(false, 0, "no source")
            }
            if (!copy.ok) {
                return StageResult.Failed(ValidationError(
                    com.iamtungingit.wam.wap.manifest.ErrorCodes.ARCHIVE_UNSUPPORTED, "source", copy.reason ?: "copy failed"))
            }

            // Structural validation on the local copy (never stream the SAF URI twice).
            val report = ZipValidator.validate(part)
            if (!report.ok) {
                return StageResult.Failed(report.error ?: ValidationError(
                    com.iamtungingit.wam.wap.manifest.ErrorCodes.ARCHIVE_UNSUPPORTED, "archive", "invalid"))
            }

            val manifest = when (val mf = ManifestParser.fromArchive(part, settings.current.maxQuotaBytes)) {
                is ManifestParser.Result.Failure -> return StageResult.Failed(mf.error)
                is ManifestParser.Result.Success -> mf.manifest
            }

            val sha256 = Hashing.sha256File(part) ?: ""
            val existing = registry.get(manifest.id)
            val state: State
            var identityChanged = false
            val warnings = manifest.warnings.toMutableList()
            if (existing == null) {
                state = State.NEW
            } else {
                val cmp = VersionComparer.compare(manifest.version, manifest.versionCode, existing.version, existing.versionCode)
                state = when {
                    cmp > 0 -> State.UPDATE
                    cmp == 0 -> State.REINSTALL
                    else -> State.DOWNGRADE
                }
                if (existing.sha256 != sha256 &&
                    (existing.name != manifest.name || existing.author != manifest.author)) {
                    identityChanged = true
                }
            }
            return StageResult.Staged(
                StagedInstall(part, manifest, sha256, part.length(),
                    sourceName ?: "unknown", existing, state, identityChanged, warnings.toList())
            )
        } catch (e: Exception) {
            part.delete()
            return StageResult.Failed(ValidationError(
                com.iamtungingit.wam.wap.manifest.ErrorCodes.ARCHIVE_UNSUPPORTED, "install",
                "${e.javaClass.simpleName}: ${e.message ?: "unexpected error"}"))
        }
    }

    fun cancel(staged: StagedInstall) {
        staged.partFile.delete()
    }

    /** Steps 5-8: atomic move, registry commit, icon cache, app data dir. */
    fun commit(staged: StagedInstall): CommitResult {
        val id = staged.manifest.id
        val target = Paths.archiveFile(context, id)
        val bak = Paths.archiveBakFile(context, id)
        return try {
            target.parentFile?.mkdirs()
            if (target.exists()) target.renameTo(bak)
            if (!staged.partFile.renameTo(target)) {
                // cross-device fallback
                staged.partFile.copyTo(target, overwrite = true)
                staged.partFile.delete()
            }
            val entry = RegistryEntry(
                wapId = id,
                name = staged.manifest.name,
                version = staged.manifest.version,
                versionCode = staged.manifest.versionCode ?: 0L,
                type = staged.manifest.type,
                sha256 = staged.sha256,
                sizeBytes = staged.sizeBytes,
                installedAt = System.currentTimeMillis(),
                author = staged.manifest.author,
                sourceName = staged.sourceName,
                networkPref = staged.existing?.networkPref ?: "inherit",
                broken = false
            )
            if (!registry.put(entry)) {
                if (bak.exists()) bak.renameTo(target)
                return CommitResult.Failed(ValidationError(
                    com.iamtungingit.wam.wap.manifest.ErrorCodes.ARCHIVE_UNSUPPORTED, "registry", "registry write failed"))
            }
            bak.delete()
            Paths.appDataDir(context, id).mkdirs()
            cacheIcon(staged.manifest, target)
            refreshWidgetsFor(id)
            CommitResult.Ok(entry)
        } catch (e: Exception) {
            bak.delete()
            CommitResult.Failed(ValidationError(
                com.iamtungingit.wam.wap.manifest.ErrorCodes.ARCHIVE_UNSUPPORTED, "install", e.message ?: "commit failed"))
        }
    }

    /** Caches a ≤128px PNG icon without extracting the whole archive (spec §7.2.7). */
    private fun cacheIcon(manifest: WapManifest, archive: File) {
        val iconPath = manifest.icon ?: return
        val dest = Paths.iconFileChecked(context, manifest.id) ?: return
        try {
            val bytes = SafeExtractor.readEntry(archive, iconPath, SizeLimits.MAX_ICON_BYTES) ?: return
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            if (bounds.outWidth > SizeLimits.MAX_ICON_DIMENSION || bounds.outHeight > SizeLimits.MAX_ICON_DIMENSION) return
            var sample = 1
            var w = bounds.outWidth
            while (w / 2 >= SizeLimits.MAX_WIDGET_ICON_PX) {
                sample *= 2
                w /= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return
            val scaled = if (bmp.width > SizeLimits.MAX_WIDGET_ICON_PX || bmp.height > SizeLimits.MAX_WIDGET_ICON_PX) {
                val scale = SizeLimits.MAX_WIDGET_ICON_PX.toFloat() / maxOf(bmp.width, bmp.height)
                Bitmap.createScaledBitmap(bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1), true)
            } else bmp
            FileOutputStream(dest).use { scaled.compress(Bitmap.CompressFormat.PNG, 90, it) }
            if (scaled !== bmp) bmp.recycle()
            bmp.recycle()
        } catch (e: Exception) {
            dest.delete()
        }
    }

    fun uninstall(wapId: String, deleteData: Boolean): UninstallResult {
        if (com.iamtungingit.wam.wap.runtime.SessionManager.isRunningStatic(wapId)) {
            return UninstallResult.BUSY
        }
        Fs.deleteRec(Paths.archiveFile(context, wapId))
        Fs.deleteRec(Paths.archiveBakFile(context, wapId))
        Fs.deleteRec(Paths.iconFile(context, wapId))
        registry.remove(wapId)
        Paths.iconFile(context, wapId).delete()
        if (deleteData) {
            Paths.canonicalChild(Paths.appDataRoot(context), wapId)?.let { Fs.deleteRec(it) }
        }
        // WebView origin storage for the synthetic origin (cookies: global clear only, see LIMITATIONS.md)
        try {
            android.webkit.WebStorage.getInstance().deleteOrigin(Hashing.originFor(wapId))
        } catch (e: Exception) { /* best effort */ }
        // Widgets: keep binding records so bound widgets show "App removed" (spec §3.9).
        com.iamtungingit.wam.widget.WidgetRenderer.updateAllForWap(context, wapId)
        return UninstallResult.OK
    }

    enum class UninstallResult { OK, BUSY }

    /** Removes per-WAP data dir + WebView origin storage (spec §15). */
    fun clearData(wapId: String): Boolean {
        Paths.canonicalChild(Paths.appDataRoot(context), wapId)?.let { Fs.deleteRec(it) }
        return try {
            android.webkit.WebStorage.getInstance().deleteOrigin(Hashing.originFor(wapId))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun setNetworkPref(wapId: String, pref: String): Boolean {
        val e = registry.get(wapId) ?: return false
        e.networkPref = NetworkPolicy.normalize(pref) ?: "inherit"
        return registry.put(e)
    }

    fun resetNetworkPref(wapId: String): Boolean {
        val e = registry.get(wapId) ?: return false
        e.networkPref = "inherit"
        return registry.put(e)
    }

    private fun refreshWidgetsFor(wapId: String) {
        com.iamtungingit.wam.widget.WidgetRenderer.updateAllForWap(context, wapId)
    }
}
