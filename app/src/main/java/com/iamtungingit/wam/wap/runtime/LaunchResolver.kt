package com.iamtungingit.wam.wap.runtime

import android.content.Context
import com.iamtungingit.wam.settings.SettingsRepository
import com.iamtungingit.wam.storage.Paths
import com.iamtungingit.wam.util.PathSanitizer
import com.iamtungingit.wam.util.Hashing
import com.iamtungingit.wam.wap.archive.ZipValidator
import com.iamtungingit.wam.wap.install.Registry
import com.iamtungingit.wam.wap.install.RegistryEntry
import com.iamtungingit.wam.wap.manifest.ManifestParser
import com.iamtungingit.wam.wap.manifest.WapManifest
import java.io.File

/**
 * Launch pipeline (spec §9.1): produces an immutable LaunchPlan or a typed failure.
 * Archive integrity (SHA-256) is verified against the registry before launch.
 */
object LaunchResolver {

    data class LaunchPlan(
        val wapId: String,
        val dataId: String,
        val archive: File,
        val manifest: WapManifest,
        val entry: String,
        val entryQueryFragment: String,
        val isPreview: Boolean,
        val previewProject: String?,
        val fullscreen: Boolean,
        val theme: String,
        val orientation: String,
        val keepScreenOn: Boolean,
        val networkPolicy: String,
        val widgetId: Int?,
        val domain: String
    )

    sealed class Result {
        data class Ok(val plan: LaunchPlan) : Result()
        data class Fail(val message: String) : Result()
    }

    fun resolve(
        context: Context,
        registry: Registry,
        settings: SettingsRepository,
        overrides: IntentOverrideResolver.Resolved,
        previewPath: String?,
        previewProject: String?,
        widgetNetwork: String?
    ): Result {
        // ---------- Preview launch (developer workspace, spec §14) ----------
        if (previewPath != null) {
            val archive = File(previewPath)
            if (!archive.isFile) return Result.Fail("preview package missing")
            val report = ZipValidator.validate(archive)
            if (!report.ok) return Result.Fail(report.error?.toString() ?: "invalid preview package")
            val manifest = when (val mf = ManifestParser.fromArchive(archive, settings.current.maxQuotaBytes)) {
                is ManifestParser.Result.Failure -> return Result.Fail(mf.error.toString())
                is ManifestParser.Result.Success -> mf.manifest
            }
            // Online projects can be previewed too: their data stays isolated under
            // __preview__<project> and the network policy resolves like any online WAP
            // (global default → ASK prompt) instead of being force-denied.
            val project = previewProject ?: "project"
            val dataId = PathSanitizer.previewDataId(project)
            val previewPolicy = if (manifest.type == "offline") NetworkPolicy.DENY
            else NetworkPolicy.resolveForWap(settings.current.networkDefault, null)
            val entryPath = (overrides.entry ?: manifest.entry)
            val plan = LaunchPlan(
                wapId = manifest.id,
                dataId = dataId,
                archive = archive,
                manifest = manifest,
                entry = entryPath.substringBefore('?').substringBefore('#'),
                entryQueryFragment = entryPath.substring(entryPath.substringBefore('?').substringBefore('#').length),
                isPreview = true,
                previewProject = project,
                fullscreen = overrides.fullscreen ?: manifest.fullscreen ?: settings.current.defaultFullscreen,
                theme = overrides.theme ?: manifest.theme ?: "system",
                orientation = manifest.orientation ?: settings.current.defaultOrientation,
                keepScreenOn = manifest.keepScreenOn ?: settings.current.keepScreenOn,
                networkPolicy = previewPolicy,
                widgetId = null,
                domain = Hashing.originDomainFor(dataId)
            )
            return Result.Ok(plan)
        }

        // ---------- Normal launch ----------
        val id = overrides.id
        if (id == null || !overrides.idValid) return Result.Fail("invalid package id")
        val entry = registry.get(id) ?: return Result.Fail("app not found: $id")
        if (entry.broken) return Result.Fail("app package is broken — reinstall it")

        val archive = Paths.archiveFile(context, id)
        if (!archive.isFile) return Result.Fail("app archive missing: $id")

        // Re-validate the manifest at launch (archive could have changed since install).
        val report = ZipValidator.validate(archive)
        if (!report.ok) return Result.Fail(report.error?.toString() ?: "archive invalid")
        val manifest = when (val mf = ManifestParser.fromArchive(archive, settings.current.maxQuotaBytes)) {
            is ManifestParser.Result.Failure -> return Result.Fail(mf.error.toString())
            is ManifestParser.Result.Success -> mf.manifest
        }
        if (manifest.id != id) return Result.Fail("manifest id does not match installed id")
        if (manifest.minRuntime > WapManifest.RUNTIME_VERSION) {
            return Result.Fail("app needs runtime ${manifest.minRuntime} > ${WapManifest.RUNTIME_VERSION}")
        }

        // Effective entry: override (already allow-list checked) or manifest entry.
        val entryPath = overrides.entry ?: manifest.entry
        val barePath = entryPath.substringBefore('?').substringBefore('#')
        val qf = entryPath.substring(barePath.length)

        // Network policy: global default ← per-WAP user pref ← widget/intent override.
        val userBase = NetworkPolicy.resolveForWap(settings.current.networkDefault, entry.networkPref)
        var policy = userBase
        if (overrides.widgetUsable && widgetNetwork != null && widgetNetwork != NetworkPolicy.INHERIT) {
            policy = NetworkPolicy.strictest(policy, widgetNetwork)
        }
        policy = NetworkPolicy.applyOverride(overrides.networkRequest, policy)

        return Result.Ok(
            LaunchPlan(
                wapId = id,
                dataId = id,
                archive = archive,
                manifest = manifest,
                entry = barePath,
                entryQueryFragment = qf,
                isPreview = false,
                previewProject = null,
                fullscreen = overrides.fullscreen ?: manifest.fullscreen ?: settings.current.defaultFullscreen,
                theme = overrides.theme ?: manifest.theme ?: "system",
                orientation = manifest.orientation ?: settings.current.defaultOrientation,
                keepScreenOn = manifest.keepScreenOn ?: settings.current.keepScreenOn,
                networkPolicy = policy,
                widgetId = if (overrides.widgetUsable) overrides.widgetId else null,
                domain = Hashing.originDomainFor(id)
            )
        )
    }

    /** Convenience for UI: quick existence check. */
    fun findEntry(context: Context, wapId: String): RegistryEntry? =
        if (PathSanitizer.isValidId(wapId)) Paths.archiveFile(context, wapId).takeIf { it.isFile }?.let { com.iamtungingit.wam.wap.install.Registry(context).get(wapId) } else null
}
