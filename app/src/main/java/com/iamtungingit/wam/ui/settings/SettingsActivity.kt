package com.iamtungingit.wam.ui.settings

import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iamtungingit.wam.BuildConfig
import com.iamtungingit.wam.R
import com.iamtungingit.wam.app.WamApp
import com.iamtungingit.wam.databinding.ActivitySettingsBinding
import com.iamtungingit.wam.storage.Fs
import com.iamtungingit.wam.storage.Paths
import com.iamtungingit.wam.ui.common.Ui
import com.iamtungingit.wam.wap.archive.SizeLimits
import com.iamtungingit.wam.wap.runtime.SessionSweeper

/**
 * Complete settings surface (spec §15). Plain views; no extra preference dependency.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val app get() = WamApp.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ui.applyInsets(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val s = app.settings.current

        // ---- defaults
        binding.swDefaultFullscreen.isChecked = s.defaultFullscreen
        binding.swKeepScreenOn.isChecked = s.keepScreenOn
        binding.swConfirmExit.isChecked = s.confirmExit
        bindSpinner(binding.spinOrientation, R.array.array_orientations,
            when (s.defaultOrientation) {
                "portrait" -> 1; "landscape" -> 2; "sensor" -> 3; else -> 0
            }) { idx ->
            s.defaultOrientation = when (idx) { 1 -> "portrait"; 2 -> "landscape"; 3 -> "sensor"; else -> "unspecified" }
            save()
        }

        // ---- network
        bindSpinner(binding.spinNetwork, R.array.array_network,
            when (s.networkDefault) { "allow" -> 1; "deny" -> 2; else -> 0 }) { idx ->
            s.networkDefault = when (idx) { 1 -> "allow"; 2 -> "deny"; else -> "ask" }
            save()
        }
        binding.btnPerAppNetwork.setOnClickListener { perAppNetworkDialog() }
        binding.swCleartext.isChecked = s.cleartextLocalhost

        // ---- runtime & preview
        // Preview engine (v1.5.0: "Built-in browser as default").
        // 0 = Built-in browser (in-app, default) · 1 = external device browser.
        bindSpinner(binding.spinPreviewEngine, R.array.array_preview_engine,
            if (s.previewEngine == "browser") 1 else 0) { idx ->
            s.previewEngine = if (idx == 1) "browser" else "webview"
            save()
        }
        binding.lblTextZoom.text = getString(R.string.set_text_zoom, s.textZoom)
        binding.sliderTextZoom.value = s.textZoom.toFloat()
        binding.sliderTextZoom.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                s.textZoom = value.toInt()
                binding.lblTextZoom.text = getString(R.string.set_text_zoom, s.textZoom)
                save()
            }
        }
        binding.swForceDark.isChecked = s.forceDark
        binding.swSafeBrowsing.isChecked = s.safeBrowsing
        binding.swWebviewDebug.isChecked = s.webViewDebug
        binding.swDirectZip.isChecked = s.directZip

        // ---- appearance
        bindSpinner(binding.spinTheme, R.array.array_themes,
            when (s.themeMode) { "light" -> 1; "dark" -> 2; else -> 0 }) { idx ->
            s.themeMode = when (idx) { 1 -> "light"; 2 -> "dark"; else -> "system" }
            applyAppTheme()
            save()
        }
        bindSpinner(binding.spinLayout, R.array.array_app_layout,
            if (s.appLayout == "list") 1 else 0) { idx ->
            s.appLayout = if (idx == 1) "list" else "grid"
            save()
        }

        // ---- storage
        binding.btnCleanTemp.setOnClickListener { cleanTemp() }
        binding.btnClearCache.setOnClickListener { clearCache() }
        binding.btnPerAppData.setOnClickListener { perAppDataDialog() }

        // ---- developer
        binding.swDevConsole.isChecked = s.devConsole
        binding.swDevWarnings.isChecked = s.devWarnings
        bindSpinner(binding.spinLogLevel, R.array.array_log_levels,
            when (s.logLevel) { "ERROR" -> 0; "INFO" -> 2; "DEBUG" -> 3; else -> 1 }) { idx ->
            s.logLevel = when (idx) { 0 -> "ERROR"; 2 -> "INFO"; 3 -> "DEBUG"; else -> "WARN" }
            save()
        }
        binding.btnShowSessions.setOnClickListener { sessionsDialog() }

        // Official JS API (v1.6.0): open the in-app reference (Guide = JS API docs).
        binding.btnJsApi.setOnClickListener {
            startActivity(android.content.Intent(this, com.iamtungingit.wam.ui.help.HelpActivity::class.java))
        }

        // ---- compatibility
        binding.txtRuntimeVersion.text = getString(R.string.set_runtime_version, 1)
        binding.txtManifestVersion.text = getString(R.string.set_manifest_version)
        val wi = WebView.getCurrentWebViewPackage()
        binding.txtWebviewPkg.text = getString(
            R.string.set_webview_pkg,
            if (wi != null) "${wi.packageName} ${wi.versionName}" else "unknown"
        )
        binding.txtFeatureBridge.text = getString(
            R.string.set_feature_bridge,
            getString(
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
                    R.string.feature_supported else R.string.feature_unsupported
            )
        )
        binding.txtFeatureDocstart.text = getString(
            R.string.set_feature_docstart,
            getString(
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                    R.string.feature_supported else R.string.feature_unsupported
            )
        )
        binding.txtLimits.text = getString(
            R.string.set_limits,
            SizeLimits.MAX_ARCHIVE_BYTES / (1024 * 1024),
            SizeLimits.MAX_ENTRIES,
            s.maxQuotaBytes / (1024 * 1024)
        )

        // ---- about (v1.5.0 user request #6)
        binding.txtAboutVersion.text = getString(R.string.set_about_version, BuildConfig.VERSION_NAME)
        binding.txtAboutAuthor.text = getString(R.string.set_about_author)
        binding.txtAboutGithub.text = getString(R.string.set_about_github, UpdateChecker.REPO_URL)
        binding.txtAboutGithub.setOnClickListener { openUrl(UpdateChecker.REPO_URL) }
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }
        binding.txtUpdateStatus.text = getString(R.string.set_update_status_current, BuildConfig.VERSION_NAME)

        computeSizes()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.update_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkForUpdate() {
        binding.btnCheckUpdate.isEnabled = false
        binding.txtUpdateStatus.text = getString(R.string.update_checking)
        Ui.runIO {
            val result = UpdateChecker.check(BuildConfig.VERSION_NAME)
            Ui.onMain {
                binding.btnCheckUpdate.isEnabled = true
                when {
                    !result.ok -> {
                        binding.txtUpdateStatus.text = getString(
                            R.string.update_check_failed, result.error ?: "unknown")
                        Toast.makeText(this, R.string.update_check_failed_short, Toast.LENGTH_SHORT).show()
                    }
                    result.updateAvailable -> {
                        binding.txtUpdateStatus.text = getString(
                            R.string.update_available_status, result.latestVersion ?: "?")
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.update_available_title)
                            .setMessage(getString(R.string.update_available_msg,
                                BuildConfig.VERSION_NAME, result.latestVersion ?: "?"))
                            .setPositiveButton(R.string.update_open_page) { _, _ ->
                                openUrl(UpdateChecker.REPO_URL + "/releases/latest")
                            }
                            .setNegativeButton(R.string.action_cancel, null)
                            .show()
                    }
                    else -> {
                        binding.txtUpdateStatus.text = getString(
                            R.string.set_update_status_current, BuildConfig.VERSION_NAME)
                        Toast.makeText(this, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun bindSpinner(
        spinner: Spinner,
        arrayRes: Int,
        selection: Int,
        onSelected: (Int) -> Unit
    ) {
        val items = resources.getStringArray(arrayRes)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        spinner.setSelection(selection.coerceIn(0, items.size - 1))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onSelected(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun save() {
        app.settings.save(app.settings.current)
    }

    private fun applyAppTheme() {
        when (app.settings.current.themeMode) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    // ---------------------------------------------------------------- storage

    private fun computeSizes() {
        Ui.runIO {
            val installed = Fs.sizeRec(Paths.installedDir(this))
            val appData = Fs.sizeRec(Paths.appDataRoot(this))
            val temp = Fs.sizeRec(Paths.temporaryDir(this))
            val projects = Fs.sizeRec(app.projects.projectsRoot())
            val cache = Fs.sizeRec(cacheDir)
            Ui.onMain {
                binding.txtSizeInstalled.text = getString(R.string.set_storage_installed, Ui.fmtBytes(this, installed))
                binding.txtSizeAppdata.text = getString(R.string.set_storage_appdata, Ui.fmtBytes(this, appData))
                binding.txtSizeTemp.text = getString(R.string.set_storage_temp, Ui.fmtBytes(this, temp))
                binding.txtSizeProjects.text = getString(R.string.set_storage_projects, Ui.fmtBytes(this, projects))
                binding.txtSizeCache.text = getString(R.string.set_storage_cache, Ui.fmtBytes(this, cache))
            }
        }
    }

    private fun cleanTemp() {
        Ui.runIO {
            val freed = SessionSweeper.sweepInactive(this, app.sessionManager.activeIds())
            Ui.onMain {
                Toast.makeText(this, getString(R.string.toast_temp_cleaned, Ui.fmtBytes(this, freed)), Toast.LENGTH_SHORT).show()
                computeSizes()
            }
        }
    }

    private fun clearCache() {
        Ui.runIO {
            val freed = Fs.deleteRec(cacheDir)
            cacheDir.mkdirs()
            Ui.onMain {
                Toast.makeText(this, getString(R.string.toast_cache_cleared), Toast.LENGTH_SHORT).show()
                computeSizes()
            }
        }
    }

    private fun perAppNetworkDialog() {
        Ui.runIO {
            val entries = app.registry.all()
            Ui.onMain {
                if (entries.isEmpty()) {
                    Toast.makeText(this, R.string.per_app_empty, Toast.LENGTH_SHORT).show()
                    return@onMain
                }
                val labels = entries.map { e ->
                    "${e.name} · " + when (e.networkPref) {
                        "allow" -> getString(R.string.net_allow)
                        "deny" -> getString(R.string.net_deny)
                        "ask" -> getString(R.string.net_ask)
                        else -> getString(R.string.net_inherit)
                    }
                }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.set_per_app_network)
                    .setItems(labels) { _, which ->
                        resetPrefDialog(entries[which])
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        }
    }

    private fun resetPrefDialog(entry: com.iamtungingit.wam.wap.install.RegistryEntry) {
        val options = arrayOf(
            getString(R.string.net_inherit),
            getString(R.string.net_allow),
            getString(R.string.net_ask),
            getString(R.string.net_deny)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setItems(options) { _, which ->
                val pref = when (which) {
                    1 -> "allow"; 2 -> "ask"; 3 -> "deny"; else -> "inherit"
                }
                Ui.runIO { app.installManager.setNetworkPref(entry.wapId, pref) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun perAppDataDialog() {
        Ui.runIO {
            val entries = app.registry.all()
            val sizes = entries.map { e ->
                Pair(e, Fs.sizeRec(Paths.appDataDir(this, e.wapId)))
            }
            Ui.onMain {
                if (sizes.isEmpty()) {
                    Toast.makeText(this, R.string.per_app_empty, Toast.LENGTH_SHORT).show()
                    return@onMain
                }
                val labels = sizes.map {
                    "${it.first.name} · ${Ui.fmtBytes(this, it.second)}"
                }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.per_app_data_title)
                    .setItems(labels) { _, which ->
                        val e = sizes[which].first
                        MaterialAlertDialogBuilder(this)
                            .setTitle(getString(R.string.menu_clear_data))
                            .setMessage(getString(R.string.clear_data_message, e.name))
                            .setPositiveButton(R.string.action_clear) { _, _ ->
                                Ui.runIO {
                                    app.installManager.clearData(e.wapId)
                                    Ui.onMain { computeSizes() }
                                }
                            }
                            .setNegativeButton(R.string.action_cancel, null)
                            .show()
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        }
    }

    private fun sessionsDialog() {
        val sessions = SessionSweeper.listSessions(this)
        val message = if (sessions.isEmpty()) getString(R.string.sessions_empty)
        else sessions.joinToString("\n") { "• $it" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.set_show_sessions)
            .setMessage(message)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }
}
