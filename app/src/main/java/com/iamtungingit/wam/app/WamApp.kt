package com.iamtungingit.wam.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.iamtungingit.wam.settings.AppPrefsRepository
import com.iamtungingit.wam.settings.SettingsRepository
import com.iamtungingit.wam.storage.Paths
import com.iamtungingit.wam.storage.ProjectRepository
import com.iamtungingit.wam.wap.install.InstallManager
import com.iamtungingit.wam.wap.install.InstallRecovery
import com.iamtungingit.wam.wap.install.Registry
import com.iamtungingit.wam.wap.runtime.SessionManager
import com.iamtungingit.wam.wap.runtime.SessionSweeper
import com.iamtungingit.wam.widget.WidgetStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Application class + DI-free service locator (spec §4).
 * Startup sweep runs on a background thread, main process only (spec §10.4).
 */
class WamApp : Application() {

    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val registry: Registry by lazy { Registry(this) }
    val installManager: InstallManager by lazy { InstallManager(this, registry, settings) }
    val projects: ProjectRepository by lazy { ProjectRepository(this) }
    val sessionManager: SessionManager by lazy { SessionManager.get(this) }
    val widgetStore: WidgetStore by lazy { WidgetStore(this) }
    val appPrefs: AppPrefsRepository by lazy { AppPrefsRepository(this) }
    val mountStore: com.iamtungingit.wam.storage.MountStore by lazy { com.iamtungingit.wam.storage.MountStore(this) }

    val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    val mainHandler: Handler = Handler(Looper.getMainLooper())

    companion object {
        lateinit var instance: WamApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Honor the user's saved light/dark choice from the very first frame
        // (Material 3 Night polish; previously only applied when opening Settings).
        try {
            when (SettingsRepository(this).current.themeMode) {
                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        } catch (e: Exception) {
            // Follow system on failure.
        }

        // Material You dynamic color where available (feels native on HyperOS/Android 14+).
        try {
            DynamicColors.applyToActivitiesIfAvailable(this)
        } catch (e: Exception) {
            // Static palette remains.
        }

        if (isMainProcess()) {
            ioExecutor.execute {
                try {
                    InstallRecovery.run(this, registry)
                    SessionSweeper.sweepAll(this)
                    refreshWidgetIconCache()
                } catch (e: Exception) {
                    // Recovery must never crash startup; sweep retries on next start.
                }
            }
        }
    }

    /**
     * Re-caches widget icons that were stored below the current adaptive-icon quality
     * (older installs had ≤128px caches); keeps icons crisp on high-density widgets.
     */
    private fun refreshWidgetIconCache() {
        val entries = registry.all()
        for (e in entries) {
            try {
                val cached = Paths.iconFile(this, e.wapId)
                if (cached.isFile) {
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeFile(cached.absolutePath, opts)
                    if (opts.outWidth <= 0 || opts.outWidth >= com.iamtungingit.wam.wap.archive.SizeLimits.MAX_WIDGET_ICON_PX) {
                        continue // up-to-date or broken-but-replaceable below
                    }
                }
                val archive = Paths.archiveFile(this, e.wapId)
                if (!archive.isFile) continue
                val mf = com.iamtungingit.wam.wap.manifest.ManifestParser.fromArchive(archive, Long.MAX_VALUE / 4)
                val iconPath = (mf as? com.iamtungingit.wam.wap.manifest.ManifestParser.Result.Success)
                    ?.manifest?.icon ?: continue
                com.iamtungingit.wam.widget.IconCache.cacheFromArchive(this, e.wapId, archive, iconPath)
            } catch (e: Exception) {
                // Icon refresh is best-effort.
            }
        }
    }

    private fun isMainProcess(): Boolean {
        val procName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName() ?: packageName
        } else {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val pid = android.os.Process.myPid()
            am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: packageName
        }
        return procName == packageName
    }
}
