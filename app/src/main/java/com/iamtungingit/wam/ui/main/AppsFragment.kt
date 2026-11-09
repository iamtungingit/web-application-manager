package com.iamtungingit.wam.ui.main

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.iamtungingit.wam.R
import com.iamtungingit.wam.app.WamApp
import com.iamtungingit.wam.databinding.FragmentAppsBinding
import com.iamtungingit.wam.storage.Fs
import com.iamtungingit.wam.storage.Paths
import com.iamtungingit.wam.storage.SafIo
import com.iamtungingit.wam.ui.common.Ui
import com.iamtungingit.wam.ui.runtime.RuntimeActivity
import com.iamtungingit.wam.wap.archive.SizeLimits
import com.iamtungingit.wam.wap.install.InstallManager
import com.iamtungingit.wam.wap.runtime.IntentOverrideResolver
import com.iamtungingit.wam.widget.IconCache
import com.iamtungingit.wam.widget.WidgetRenderer

/**
 * Installed apps grid/list (spec §6.1 "Apps").
 */
class AppsFragment : Fragment() {

    private var binding: FragmentAppsBinding? = null
    private val app get() = WamApp.instance
    private val entries = mutableListOf<com.iamtungingit.wam.wap.install.RegistryEntry>()
    private var adapter: AppsAdapter? = null
    private var pendingExportId: String? = null
    private var pendingDownloadStream: Pair<Uri, String>? = null

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val id = pendingExportId
            pendingExportId = null
            if (uri != null && id != null) {
                val src = Paths.archiveFile(requireContext(), id)
                Ui.runIO {
                    val ok = SafIo.writeToFile(requireContext(), uri, src)
                    Ui.onMain {
                        if (!ok) {
                            Toast.makeText(requireContext(), R.string.install_failed, Toast.LENGTH_SHORT).show()
                        } else {
                            checkExtensionChanged(uri)
                        }
                    }
                }
            }
        }

    private fun checkExtensionChanged(uri: Uri) {
        val name = SafIo.queryDisplayName(requireContext(), uri)
        if (name != null && !name.endsWith(".wap", ignoreCase = true)) {
            Ui.onMain {
                Toast.makeText(requireContext(),
                    getString(R.string.extension_changed_warning, name), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentAppsBinding.inflate(inflater, container, false)
        binding = b
        adapter = AppsAdapter()
        b.appsRecycler.adapter = adapter
        applyLayout(b)
        // Persistent top action (user issue: the import button kept disappearing /
        // sat too low next to the bottom tabs).
        b.btnImportApp.setOnClickListener { (activity as? MainActivity)?.startWapImport() }
        return b.root
    }

    private fun applyLayout(b: FragmentAppsBinding) {
        val grid = app.settings.current.appLayout != "list"
        b.appsRecycler.layoutManager =
            if (grid) GridLayoutManager(requireContext(), 2) else LinearLayoutManager(requireContext())
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    fun reload() {
        val b = binding ?: return
        Ui.runIO {
            val list = app.registry.all()
            Ui.onMain {
                entries.clear()
                entries.addAll(list)
                adapter?.notifyDataSetChanged()
                b.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                applyLayout(b)
            }
        }
    }

    private fun openApp(wapId: String) {
        startActivity(
            Intent(requireContext(), RuntimeActivity::class.java)
                .putExtra(IntentOverrideResolver.EXTRA_ID, wapId)
        )
    }

    private fun export(entry: com.iamtungingit.wam.wap.install.RegistryEntry) {
        pendingExportId = entry.wapId
        exportLauncher.launch("${entry.wapId}-${entry.version}.wap")
    }

    private fun share(entry: com.iamtungingit.wam.wap.install.RegistryEntry) {
        val ctx = requireContext()
        Ui.runIO {
            try {
                val shareDir = Paths.shareDir(ctx)
                val copy = java.io.File(shareDir, "${entry.wapId}-${entry.version}.wap")
                Paths.archiveFile(ctx, entry.wapId).copyTo(copy, overwrite = true)
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", copy)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Ui.onMain {
                    startActivity(Intent.createChooser(send, getString(R.string.action_share)))
                }
            } catch (e: Exception) {
                Ui.onMain {
                    Toast.makeText(ctx, R.string.import_invalid_archive, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showInfo(entry: com.iamtungingit.wam.wap.install.RegistryEntry) {
        val view = layoutInflater.inflate(R.layout.dialog_app_info, null)
        view.findViewById<TextView>(R.id.ai_id).text = entry.wapId
        view.findViewById<TextView>(R.id.ai_version).text = entry.version
        view.findViewById<TextView>(R.id.ai_type).text =
            getString(if (entry.type == "offline") R.string.type_offline else R.string.type_online)
        view.findViewById<TextView>(R.id.ai_size).text = Ui.fmtBytes(requireContext(), entry.sizeBytes)
        Ui.runIO {
            val archive = Paths.archiveFile(requireContext(), entry.wapId)
            val mf = com.iamtungingit.wam.wap.manifest.ManifestParser.fromArchive(archive, Long.MAX_VALUE / 4)
            Ui.onMain {
                (view.findViewById<TextView>(R.id.ai_entry)).text =
                    (mf as? com.iamtungingit.wam.wap.manifest.ManifestParser.Result.Success)?.manifest?.entry ?: entry.wapId
            }
        }
        val spinner = view.findViewById<Spinner>(R.id.ai_net_spinner)
        val options = listOf(
            getString(R.string.net_inherit),
            getString(R.string.net_allow),
            getString(R.string.net_ask),
            getString(R.string.net_deny)
        )
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, options)
        val current = when (entry.networkPref) {
            "allow" -> 1; "ask" -> 2; "deny" -> 3; else -> 0
        }
        spinner.setSelection(current)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(entry.name)
            .setView(view)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val pref = when (spinner.selectedItemPosition) {
                    1 -> "allow"; 2 -> "ask"; 3 -> "deny"; else -> "inherit"
                }
                Ui.runIO { app.installManager.setNetworkPref(entry.wapId, pref) }
            }
            .setNeutralButton(R.string.action_reset) { _, _ ->
                Ui.runIO { app.installManager.resetNetworkPref(entry.wapId) }
            }
            .show()
    }

    private fun clearData(entry: com.iamtungingit.wam.wap.install.RegistryEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_data_title)
            .setMessage(getString(R.string.clear_data_message, entry.name))
            .setPositiveButton(R.string.action_clear) { _, _ ->
                Ui.runIO {
                    app.installManager.clearData(entry.wapId)
                    Ui.onMain {
                        Toast.makeText(requireContext(), R.string.clear_data_done, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun uninstall(entry: com.iamtungingit.wam.wap.install.RegistryEntry) {
        val switch = MaterialSwitch(requireContext()).apply {
            text = getString(R.string.uninstall_delete_data)
            setPadding(48, 24, 48, 0)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.uninstall_title)
            .setMessage(getString(R.string.uninstall_message, entry.name))
            .setView(switch)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                Ui.runIO {
                    when (app.installManager.uninstall(entry.wapId, switch.isChecked)) {
                        InstallManager.UninstallResult.OK -> Ui.onMain {
                            Toast.makeText(requireContext(),
                                getString(R.string.uninstall_success, entry.name), Toast.LENGTH_SHORT).show()
                            reload()
                        }
                        InstallManager.UninstallResult.BUSY -> Ui.onMain {
                            Toast.makeText(requireContext(), R.string.loading_please_wait, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun createWidget(entry: com.iamtungingit.wam.wap.install.RegistryEntry) {
        if (!WidgetRenderer.requestPin(requireContext())) {
            Toast.makeText(requireContext(), R.string.widget_pin_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------- adapter

    private inner class AppsAdapter : RecyclerView.Adapter<AppHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return AppHolder(v)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: AppHolder, position: Int) {
            val entry = entries[position]
            holder.name.text = entry.name
            holder.version.text = if (entry.broken) {
                getString(R.string.widget_status_removed)
            } else {
                "v${entry.version}"
            }
            holder.badge.text = getString(
                if (entry.type == "offline") R.string.type_offline else R.string.type_online
            )
            holder.badge.setBackgroundResource(
                if (entry.type == "offline") R.drawable.bg_badge_offline else R.drawable.bg_badge_online
            )
            holder.badge.setTextColor(
                requireContext().getColor(
                    if (entry.type == "offline") R.color.badge_offline_text else R.color.badge_online_text
                )
            )
            val icon = IconCache.loadBitmap(requireContext(), entry.wapId)
            if (icon != null) {
                holder.icon.setImageBitmap(icon)
            } else {
                holder.icon.setImageResource(R.drawable.ic_globe)
            }
            holder.itemView.setOnClickListener { openApp(entry.wapId) }
            holder.itemView.setOnLongClickListener { anchor ->
                showMenu(anchor, entry)
                true
            }
        }
    }

    private fun showMenu(anchor: View, entry: com.iamtungingit.wam.wap.install.RegistryEntry) {
        val menu = PopupMenu(requireContext(), anchor)
        menu.menu.add(R.string.action_open).setOnMenuItemClickListener { openApp(entry.wapId); true }
        menu.menu.add(R.string.app_settings_title).setOnMenuItemClickListener {
            startActivity(
                Intent(requireContext(), com.iamtungingit.wam.ui.settings.AppSettingsActivity::class.java)
                    .putExtra(com.iamtungingit.wam.ui.settings.AppSettingsActivity.EXTRA_WAP_ID, entry.wapId)
            )
            true
        }
        menu.menu.add(R.string.menu_create_widget).setOnMenuItemClickListener { createWidget(entry); true }
        menu.menu.add(R.string.menu_export_wap).setOnMenuItemClickListener { export(entry); true }
        menu.menu.add(R.string.action_share).setOnMenuItemClickListener { share(entry); true }
        menu.menu.add(R.string.menu_info).setOnMenuItemClickListener { showInfo(entry); true }
        menu.menu.add(R.string.menu_clear_data).setOnMenuItemClickListener { clearData(entry); true }
        menu.menu.add(R.string.menu_uninstall).setOnMenuItemClickListener { uninstall(entry); true }
        menu.show()
    }

    private inner class AppHolder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.app_icon)
        val name: TextView = v.findViewById(R.id.app_name)
        val version: TextView = v.findViewById(R.id.app_version)
        val badge: TextView = v.findViewById(R.id.app_type_badge)
    }
}
