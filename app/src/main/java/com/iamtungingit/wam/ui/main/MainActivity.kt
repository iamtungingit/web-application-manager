package com.iamtungingit.wam.ui.main

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iamtungingit.wam.R
import com.iamtungingit.wam.app.WamApp
import com.iamtungingit.wam.BuildConfig
import com.iamtungingit.wam.databinding.ActivityMainBinding
import com.iamtungingit.wam.storage.SafIo
import com.iamtungingit.wam.ui.common.Ui
import com.iamtungingit.wam.ui.settings.SettingsActivity
import com.iamtungingit.wam.wap.install.InstallManager
import java.io.File

/**
 * Hosts the Apps and Projects destinations, WAP import (SAF + VIEW/SEND Intents),
 * the install confirmation dialog and the widget manager (spec §6.1).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = WamApp.instance
    private var currentTab = 0
    private var importStaged: InstallManager.StagedInstall? = null
    private lateinit var uiPrefs: SharedPreferences

    /** Problem 2 (user): selecting a project, going to Apps and coming back to
     *  Projects must reopen the same project — the open editor survives tab
     *  switches (and even process death, the pointer is persisted). */
    private val editorBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closeProjectEditor()
        }
    }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFromUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ui.applyInsets(binding.mainRoot)
        setSupportActionBar(binding.toolbar)
        uiPrefs = getSharedPreferences("wam_ui", MODE_PRIVATE)

        onBackPressedDispatcher.addCallback(this, editorBackCallback)

        binding.bottomNav.setOnItemSelectedListener { item ->
            val tab = if (item.itemId == R.id.nav_projects) 1 else 0
            // Guard: programmatic selection (switchTab) must not re-trigger a swap.
            if (tab != currentTab) switchTab(tab)
            true
        }

        // Restore the tab across activity recreation; the open project itself is
        // restored by switchTab from the persisted uiPrefs pointer.
        switchTab(savedInstanceState?.getInt(KEY_TAB, 0) ?: 0)
        handleViewOrSendIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewOrSendIntent(intent)
    }

    private fun currentFragment(): Fragment? =
        supportFragmentManager.findFragmentById(R.id.fragment_container)

    private fun switchTab(tab: Int) {
        currentTab = tab
        val target = if (tab == 0) R.id.nav_apps else R.id.nav_projects
        if (binding.bottomNav.selectedItemId != target) {
            // Fires the selection listener; currentTab is already set, so it no-ops.
            binding.bottomNav.selectedItemId = target
        }
        if (tab == 0) {
            showFragment(AppsFragment())
            editorBackCallback.isEnabled = false
            return
        }
        // Tab 1 (Projects): reopen the last open project if there is one.
        val last = uiPrefs.getString(KEY_LAST_PROJECT, null)
        val project = last?.takeIf { app.projects.projectDir(it) != null }
        if (project != null) {
            showEditor(project)
        } else {
            if (last != null) uiPrefs.edit().remove(KEY_LAST_PROJECT).apply()
            showFragment(ProjectsFragment())
            editorBackCallback.isEnabled = false
        }
    }

    private fun showFragment(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, f)
            .commit()
    }

    /** Opens (or reopens) the editor for a project and remembers it as "the open one". */
    fun openProjectEditor(name: String) {
        uiPrefs.edit().putString(KEY_LAST_PROJECT, name).apply()
        showEditor(name)
    }

    private fun showEditor(name: String) {
        currentTab = 1
        showFragment(ProjectEditorFragment.newInstance(name))
        editorBackCallback.isEnabled = true
    }

    /** System back from the editor → the project list (and forget the pointer). */
    private fun closeProjectEditor() {
        uiPrefs.edit().remove(KEY_LAST_PROJECT).apply()
        currentTab = 1
        showFragment(ProjectsFragment())
        editorBackCallback.isEnabled = false
    }

    fun refreshLists() {
        (currentFragment() as? AppsFragment)?.reload()
        (currentFragment() as? ProjectsFragment)?.reload()
    }

    /** Called from the Apps tab's always-visible "Import WAP" action button. */
    fun startWapImport() {
        importLauncher.launch(arrayOf("*/*"))
    }

    // ------------------------------------------------------------------ import

    fun importFromUri(uri: Uri) {
        val name = SafIo.queryDisplayName(this, uri)
        Ui.runIO {
            when (val staged = app.installManager.stage(sourceUri = uri, sourceFile = null, sourceName = name)) {
                is InstallManager.StageResult.Failed -> Ui.onMain {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.install_failed)
                        .setMessage(getString(R.string.install_failed, staged.error.toString()))
                        .setPositiveButton(R.string.action_ok, null)
                        .show()
                }
                is InstallManager.StageResult.Staged -> Ui.onMain { showInstallDialog(staged.staged) }
            }
        }
    }

    fun importFromFile(file: File) {
        Ui.runIO {
            when (val staged = app.installManager.stage(sourceUri = null, sourceFile = file, sourceName = file.name)) {
                is InstallManager.StageResult.Failed -> Ui.onMain {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.install_failed)
                        .setMessage(getString(R.string.install_failed, staged.error.toString()))
                        .setPositiveButton(R.string.action_ok, null)
                        .show()
                }
                is InstallManager.StageResult.Staged -> Ui.onMain { showInstallDialog(staged.staged) }
            }
        }
    }

    fun showInstallDialog(staged: InstallManager.StagedInstall) {
        importStaged = staged
        val view = layoutInflater.inflate(R.layout.dialog_install_confirm, null)
        val m = staged.manifest
        view.findViewById<android.widget.TextView>(R.id.iv_source).apply {
            visibility = View.VISIBLE
            text = getString(R.string.import_from, staged.sourceName)
        }
        view.findViewById<android.widget.TextView>(R.id.iv_name).text = m.name
        view.findViewById<android.widget.TextView>(R.id.iv_id).text = m.id
        view.findViewById<android.widget.TextView>(R.id.iv_version).text = m.version
        view.findViewById<android.widget.TextView>(R.id.iv_type).text =
            getString(if (m.isOffline) R.string.type_offline else R.string.type_online)
        view.findViewById<android.widget.TextView>(R.id.iv_entry).text = m.entry
        view.findViewById<android.widget.TextView>(R.id.iv_network).text =
            getString(if (m.effectiveNetworkRequired) R.string.install_yes else R.string.install_no)
        view.findViewById<android.widget.TextView>(R.id.iv_size).text =
            Ui.fmtBytes(this, staged.sizeBytes)
        view.findViewById<android.widget.TextView>(R.id.iv_state).text = getString(
            when (staged.state) {
                InstallManager.State.NEW -> R.string.install_state_new
                InstallManager.State.UPDATE -> R.string.install_state_update
                InstallManager.State.REINSTALL -> R.string.install_state_reinstall
                InstallManager.State.DOWNGRADE -> R.string.install_state_downgrade
            }
        )
        if (staged.state == InstallManager.State.UPDATE && staged.existing != null) {
            view.findViewById<android.widget.TextView>(R.id.iv_version).text =
                "${staged.existing.version} → ${m.version}"
        }
        val warnings = view.findViewById<android.widget.TextView>(R.id.iv_warnings)
        val lines = staged.warnings.toMutableList()
        if (staged.identityChanged) lines.add(getString(R.string.identity_change_warning))
        if (lines.isNotEmpty()) {
            warnings.visibility = View.VISIBLE
            warnings.text = lines.joinToString("\n") { "• $it" }
        }

        val positiveLabel = when (staged.state) {
            InstallManager.State.NEW -> R.string.install_btn
            InstallManager.State.UPDATE -> R.string.install_btn
            InstallManager.State.REINSTALL -> R.string.install_state_reinstall
            InstallManager.State.DOWNGRADE -> R.string.install_state_downgrade
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.install_title)
            .setView(view)
            .setPositiveButton(positiveLabel) { _, _ -> commitInstall(staged) }
            .setNegativeButton(R.string.action_cancel) { _, _ -> discardInstall(staged) }
            .setOnCancelListener { discardInstall(staged) }
            .show()
    }

    private fun commitInstall(staged: InstallManager.StagedInstall) {
        Ui.runIO {
            when (val r = app.installManager.commit(staged)) {
                is InstallManager.CommitResult.Ok -> Ui.onMain {
                    android.widget.Toast.makeText(
                        this, getString(R.string.install_success, r.entry.name),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    refreshLists()
                    offerWidgetCreation(r.entry.wapId)
                }
                is InstallManager.CommitResult.Failed -> Ui.onMain {
                    android.widget.Toast.makeText(
                        this, getString(R.string.install_failed, r.error.toString()),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun discardInstall(staged: InstallManager.StagedInstall) {
        Ui.runIO { app.installManager.cancel(staged) }
    }

    private fun offerWidgetCreation(wapId: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_create_widget)
            .setMessage(R.string.widget_pinned_hint)
            .setPositiveButton(R.string.action_yes) { _, _ ->
                if (!com.iamtungingit.wam.widget.WidgetRenderer.requestPin(this)) {
                    android.widget.Toast.makeText(this, R.string.widget_pin_unsupported,
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.action_no, null)
            .show()
    }

    private fun handleViewOrSendIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) {
            // Content from other apps is untrusted (spec §3.11); validate structurally.
            importFromUri(uri)
        }
    }

    // ------------------------------------------------------------------ menu


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_about -> {
            showAbout()
            true
        }
        R.id.action_guide -> {
            startActivity(Intent(this, com.iamtungingit.wam.ui.help.HelpActivity::class.java))
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        R.id.action_widget_manager -> {
            showWidgetManager()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** About dialog (v1.5.0 user request #6) — mirrors the Settings → About card. */
    private fun showAbout() {
        val v = layoutInflater.inflate(R.layout.dialog_about, null)
        v.findViewById<TextView>(R.id.about_version).text =
            getString(R.string.set_about_version, BuildConfig.VERSION_NAME)
        v.findViewById<TextView>(R.id.about_author).text = getString(R.string.set_about_author)
        val gh = v.findViewById<TextView>(R.id.about_github)
        gh.text = getString(R.string.set_about_github, com.iamtungingit.wam.ui.settings.UpdateChecker.REPO_URL)
        gh.setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(com.iamtungingit.wam.ui.settings.UpdateChecker.REPO_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Toast.makeText(this, R.string.update_no_browser, Toast.LENGTH_SHORT).show()
            }
        }
        val checkBtn = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.about_check)
        checkBtn.setOnClickListener { _ -> checkUpdateFromDialog(checkBtn) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_about)
            .setView(v)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun checkUpdateFromDialog(button: com.google.android.material.button.MaterialButton) {
        button.isEnabled = false
        button.text = getString(R.string.update_checking)
        Ui.runIO {
            val result = com.iamtungingit.wam.ui.settings.UpdateChecker.check(BuildConfig.VERSION_NAME)
            Ui.onMain {
                button.isEnabled = true
                button.text = getString(R.string.set_check_update)
                when {
                    !result.ok -> Toast.makeText(
                        this, getString(R.string.update_check_failed, result.error ?: ""),
                        Toast.LENGTH_LONG).show()
                    result.updateAvailable -> MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.update_available_title)
                        .setMessage(getString(R.string.update_available_msg,
                            BuildConfig.VERSION_NAME, result.latestVersion ?: "?"))
                        .setPositiveButton(R.string.update_open_page) { _, _ ->
                            try {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(
                                        com.iamtungingit.wam.ui.settings.UpdateChecker.REPO_URL + "/releases/latest"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (e: Exception) {
                                Toast.makeText(this, R.string.update_no_browser, Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                    else -> Toast.makeText(this, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun showWidgetManager() {
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.widget_manager_title)
            .setPositiveButton(R.string.action_close, null)
            .create()
        val view = layoutInflater.inflate(R.layout.dialog_widget_manager, null)
        dialog.setView(view)
        dialog.show()
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.wm_recycler)
        val empty = view.findViewById<android.widget.TextView>(R.id.wm_empty)
        val records = app.widgetStore.all()
        val registry = app.registry
        val boundIds = HashSet<Int>(records.map { it.widgetId })
        // Orphaned widgets: added to home but without a record.
        val manager = android.appwidget.AppWidgetManager.getInstance(this)
        val allIds = manager?.getAppWidgetIds(
            android.content.ComponentName(this, com.iamtungingit.wam.widget.WapWidgetProvider::class.java)
        ) ?: IntArray(0)
        val orphans = allIds.filter { it !in boundIds }

        if (records.isEmpty() && orphans.isEmpty()) {
            empty.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            empty.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
            recycler.adapter = WidgetManagerAdapter(records, orphans) { reloadWidgets ->
                if (reloadWidgets) {
                    com.iamtungingit.wam.widget.WidgetRenderer.updateAll(this)
                }
            }
        }
    }

    companion object {
        private const val KEY_TAB = "main_tab"
        private const val KEY_LAST_PROJECT = "last_project"
    }
}
