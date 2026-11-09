package com.iamtungingit.wam.wap.install

import android.content.Context
import com.iamtungingit.wam.storage.Fs
import com.iamtungingit.wam.storage.Paths
import com.iamtungingit.wam.wap.archive.ZipValidator
import com.iamtungingit.wam.wap.manifest.ManifestParser
import java.io.File

/**
 * Startup recovery (spec §7.2 "Recovery from interruption"): idempotent.
 */
object InstallRecovery {

    fun run(context: Context, registry: Registry, onQuarantined: (String) -> Unit = {}) {
        // 1. Delete transient .part files.
        Paths.incomingDir(context).listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }

        // 2. Delete orphan .bak files (registry decides the current archive).
        Paths.installedDir(context).listFiles { f -> f.name.endsWith(".bak") }?.forEach { it.delete() }

        // 3. Reconcile archives vs registry.
        val ids = HashSet<String>()
        Paths.installedDir(context).listFiles { f -> f.isFile && f.name.endsWith(".wap") }?.forEach { archive ->
            val id = archive.name.removeSuffix(".wap")
            val entry = registry.get(id)
            if (entry == null) {
                // Archive without registry entry: re-validate and register, or quarantine (delete).
                val report = ZipValidator.validate(archive)
                val ok = report.ok && when (val mf = ManifestParser.fromArchive(archive, Long.MAX_VALUE / 4)) {
                    is ManifestParser.Result.Success -> {
                        registry.put(RegistryEntry(
                            wapId = mf.manifest.id,
                            name = mf.manifest.name,
                            version = mf.manifest.version,
                            versionCode = mf.manifest.versionCode ?: 0L,
                            type = mf.manifest.type,
                            sha256 = com.iamtungingit.wam.util.Hashing.sha256File(archive) ?: "",
                            sizeBytes = archive.length(),
                            installedAt = System.currentTimeMillis(),
                            sourceName = "recovered"
                        ))
                        true
                    }
                    else -> false
                }
                if (!ok) {
                    Fs.deleteRec(archive)
                    onQuarantined(id)
                }
            } else {
                // Registry entry with archive: verify hash; mismatch → mark broken.
                if (!entry.broken) {
                    val actual = com.iamtungingit.wam.util.Hashing.sha256File(archive)
                    if (actual != null && actual != entry.sha256) {
                        entry.broken = true
                        registry.put(entry)
                    }
                }
            }
            ids.add(id)
        }

        // 4. Registry entries without archive → mark broken (offer removal in UI).
        for (entry in registry.all()) {
            if (!ids.contains(entry.wapId) && !entry.broken) {
                entry.broken = true
                registry.put(entry)
            }
        }
    }
}
