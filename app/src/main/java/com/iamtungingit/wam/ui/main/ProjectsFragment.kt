package com.iamtungingit.wam.ui.main

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iamtungingit.wam.R
import com.iamtungingit.wam.app.WamApp
import com.iamtungingit.wam.databinding.FragmentProjectsBinding
import com.iamtungingit.wam.storage.ProjectRepository
import com.iamtungingit.wam.storage.SafIo
import com.iamtungingit.wam.ui.common.Ui
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File

/**
 * Developer workspace: projects list + create/rename/duplicate/delete/import/export (spec §14).
 */
class ProjectsFragment : Fragment() {

    private var binding: FragmentProjectsBinding? = null
    private val app get() = WamApp.instance
    private val projects = mutableListOf<File>()
    private var adapter: ProjectsAdapter? = null

    private val importTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) importProjectFolder(uri)
        }

    private val exportTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val name = pendingExportProject
            pendingExportProject = null
            if (uri != null && name != null) exportProjectFolder(uri, name)
        }
    private var pendingExportProject: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentProjectsBinding.inflate(inflater, container, false)
        binding = b
        adapter = ProjectsAdapter()
        b.projectsRecycler.layoutManager = LinearLayoutManager(requireContext())
        b.projectsRecycler.adapter = adapter
        // Persistent top actions (user issue: buttons kept disappearing / sat too low).
        b.btnNewProject.setOnClickListener { showNewProjectDialog() }
        b.btnImportProject.setOnClickListener { importTreeLauncher.launch(null) }
        return b.root
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
            val list = app.projects.list()
            val fallback = app.projects.usingFallback
            Ui.onMain {
                projects.clear()
                projects.addAll(list)
                adapter?.notifyDataSetChanged()
                b.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                b.storageWarning.visibility = if (fallback) View.VISIBLE else View.GONE
            }
        }
    }

    fun showNewProjectDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_new_project, null)
        val nameLayout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.np_name_layout)
        val nameInput = view.findViewById<TextInputEditText>(R.id.np_name)
        val idPreview = view.findViewById<TextView>(R.id.np_id_preview)
        val templateSpinner = view.findViewById<android.widget.Spinner>(R.id.np_template)
        val templates = resources.getStringArray(R.array.array_templates)
        templateSpinner.adapter = android.widget.ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, templates)

        // LIVE name validation (user request): the warning shows WHILE typing, not after
        // pressing Continue. Typing "my pro" immediately shows the dash/underscore hint.
        fun validate(): Boolean {
            val raw = nameInput.text?.toString().orEmpty()
            val err = com.iamtungingit.wam.util.PathSanitizer.projectNameLiveError(raw)
            if (err == null) {
                nameLayout.error = null
                idPreview.text = getString(R.string.package_id_preview,
                    com.iamtungingit.wam.util.PathSanitizer.suggestId(raw))
                return true
            }
            nameLayout.error = err.ifEmpty { null }
            idPreview.text = if (err.isEmpty()) "" else ""
            return false
        }
        nameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { validate() }
        })

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_new_project_title)
            .setView(view)
            .setPositiveButton(R.string.action_create, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.show()
        // Create stays inert until the name is valid — no more warning-after-the-fact.
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!validate()) return@setOnClickListener
            val name = nameInput.text?.toString()?.trim().orEmpty()
            dialog.dismiss()
            createProject(name, templateSpinner.selectedItemPosition)
        }
    }

    private fun createProject(name: String, templateIndex: Int) {
        val safe = com.iamtungingit.wam.util.PathSanitizer.sanitizeProjectName(name)
        if (safe == null) {
            toast(R.string.name_invalid)
            return
        }
        Ui.runIO {
            val dir = app.projects.create(safe)
            Ui.onMain {
                if (dir == null) {
                    toast(R.string.name_taken)
                } else {
                    com.iamtungingit.wam.util.Templates.writeTemplate(dir, templateIndex)
                    toastDone(getString(R.string.project_created, safe))
                    openEditor(safe)
                }
            }
        }
    }

    private fun importProjectFolder(uri: Uri) {
        Ui.runIO {
            try {
                // Derive a project name from the picked folder's display name.
                var base = SafIo.queryDisplayName(requireContext(), uri) ?: "Imported"
                base = base.substringAfterLast('/')
                var name = PathSan.sanitizeProjectName(base)
                    ?: PathSan.sanitizeProjectName(base.replace(' ', '_'))
                    ?: "Imported_project"
                var n = 1
                while (app.projects.exists(name)) {
                    n++
                    name = PathSan.sanitizeProjectName("${base.replace(' ', '_')}_$n") ?: "Imported_$n"
                }
                val dir = app.projects.create(name) ?: return@runIO
                // Shared SAF helper (add + overwrite copy, v1.5.0 refactor).
                val count = SafIo.importTree(
                    requireContext(), uri, dir, com.iamtungingit.wam.wap.archive.SizeLimits.MAX_ENTRY_BYTES)
                // Auto-generate manifest.json when missing (spec §13).
                var generated = false
                if (!File(dir, "manifest.json").isFile) {
                    com.iamtungingit.wam.util.Templates.generateManifestFor(dir, name)
                    generated = true
                }
                Ui.onMain {
                    if (generated) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.import_wap)
                            .setMessage(R.string.manifest_missing_generated)
                            .setPositiveButton(R.string.action_ok, null)
                            .show()
                    }
                    toastDone("$name ($count files)")
                    reload()
                }
            } catch (e: Exception) {
                Ui.onMain { toast(R.string.import_invalid_archive) }
            }
        }
    }

    private fun exportProjectFolder(treeUri: Uri, projectName: String) {
        Ui.runIO {
            try {
                val src = app.projects.projectDir(projectName) ?: return@runIO
                // Shared SAF helper (create + overwrite write, v1.5.0 refactor).
                val count = SafIo.exportTree(requireContext(), src, treeUri)
                Ui.onMain { toastDone("$projectName ($count files)") }
            } catch (e: Exception) {
                Ui.onMain { toast(R.string.import_invalid_archive) }
            }
        }
    }

    private fun openEditor(name: String) {
        // Problem 2 (user): opening a project registers it with MainActivity so the
        // SAME project reopens when the user hops to the Apps tab and back.
        (activity as? MainActivity)?.openProjectEditor(name) ?: run {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, ProjectEditorFragment.newInstance(name))
                .addToBackStack("editor")
                .commit()
        }
    }

    private fun toast(resId: Int) {
        context?.let { Toast.makeText(it, resId, Toast.LENGTH_SHORT).show() }
    }

    private fun toastDone(text: String) {
        context?.let { Toast.makeText(it, text, Toast.LENGTH_SHORT).show() }
    }

    private object PathSan {
        fun sanitizeProjectName(n: String): String? = com.iamtungingit.wam.util.PathSanitizer.sanitizeProjectName(n)
    }

    // ------------------------------------------------------------- adapter

    private inner class ProjectsAdapter : RecyclerView.Adapter<ProjectHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_project, parent, false)
            return ProjectHolder(v)
        }

        override fun getItemCount(): Int = projects.size

        override fun onBindViewHolder(holder: ProjectHolder, position: Int) {
            val dir = projects[position]
            holder.name.text = dir.name
            Ui.runIO {
                val mf = com.iamtungingit.wam.wap.manifest.ManifestParser.fromDirectory(dir, Long.MAX_VALUE / 4)
                Ui.onMain {
                    when (mf) {
                        is com.iamtungingit.wam.wap.manifest.ManifestParser.Result.Success ->
                            holder.status.text = "${mf.manifest.name} v${mf.manifest.version} · ${mf.manifest.type}"
                        is com.iamtungingit.wam.wap.manifest.ManifestParser.Result.Failure ->
                            holder.status.text = getString(R.string.project_manifest_invalid, mf.error.message)
                    }
                }
            }
            holder.itemView.setOnClickListener { openEditor(dir.name) }
            holder.itemView.setOnLongClickListener { anchor ->
                showMenu(anchor, dir)
                true
            }
        }
    }

    private fun showMenu(anchor: View, dir: File) {
        val menu = PopupMenu(requireContext(), anchor)
        val name = dir.name
        menu.menu.add(R.string.project_menu_open).setOnMenuItemClickListener { openEditor(name); true }
        menu.menu.add(R.string.project_menu_rename).setOnMenuItemClickListener { renameDialog(name); true }
        menu.menu.add(R.string.project_menu_duplicate).setOnMenuItemClickListener { duplicateDialog(name); true }
        menu.menu.add(R.string.project_menu_export_folder).setOnMenuItemClickListener {
            pendingExportProject = name
            exportTreeLauncher.launch(null)
            true
        }
        menu.menu.add(R.string.project_menu_import_folder).setOnMenuItemClickListener {
            importTreeLauncher.launch(null)
            true
        }
        menu.menu.add(R.string.project_menu_delete).setOnMenuItemClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.project_menu_delete)
                .setMessage(getString(R.string.dialog_delete_project_message, name))
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    Ui.runIO {
                        app.projects.delete(name)
                        Ui.onMain { reload() }
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            true
        }
        menu.show()
    }

    private fun inputDialog(titleRes: Int, initial: String, onOk: (String) -> Unit) {
        val layout = TextInputLayout(requireContext())
        val edit = TextInputEditText(requireContext())
        edit.setText(initial)
        layout.addView(edit)
        layout.setPadding(48, 24, 48, 0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setView(layout)
            .setPositiveButton(R.string.action_ok) { _, _ -> onOk(edit.text?.toString()?.trim().orEmpty()) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun renameDialog(old: String) {
        inputDialog(R.string.dialog_rename_title, old) { newName ->
            val safe = com.iamtungingit.wam.util.PathSanitizer.sanitizeProjectName(newName) ?: return@inputDialog
            Ui.runIO {
                val r = app.projects.rename(old, safe)
                Ui.onMain {
                    if (r == null) toast(R.string.name_taken) else reload()
                }
            }
        }
    }

    private fun duplicateDialog(old: String) {
        inputDialog(R.string.project_menu_duplicate, "${old}_copy") { newName ->
            val safe = com.iamtungingit.wam.util.PathSanitizer.sanitizeProjectName(newName) ?: return@inputDialog
            Ui.runIO {
                val r = app.projects.duplicate(old, safe)
                Ui.onMain {
                    if (r == null) toast(R.string.name_taken) else reload()
                }
            }
        }
    }

    private inner class ProjectHolder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.project_name)
        val status: TextView = v.findViewById(R.id.project_status)
    }
}
