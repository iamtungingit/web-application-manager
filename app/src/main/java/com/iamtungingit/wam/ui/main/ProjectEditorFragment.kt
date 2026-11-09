package com.iamtungingit.wam.ui.main

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnNextLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.iamtungingit.wam.R
import com.iamtungingit.wam.app.WamApp
import com.iamtungingit.wam.databinding.FragmentEditorBinding
import com.iamtungingit.wam.storage.Fs
import com.iamtungingit.wam.storage.SafIo
import com.iamtungingit.wam.storage.SafMirror
import com.iamtungingit.wam.ui.common.Ui
import com.iamtungingit.wam.util.MimeTypes
import com.iamtungingit.wam.util.PathSanitizer
import com.iamtungingit.wam.util.CodeTokenizer
import com.iamtungingit.wam.util.Templates
import com.iamtungingit.wam.wap.archive.SizeLimits
import com.iamtungingit.wam.wap.archive.WapPackager
import com.iamtungingit.wam.wap.manifest.ErrorCodes
import com.iamtungingit.wam.wap.manifest.ManifestParser
import com.iamtungingit.wam.wap.manifest.ManifestValidator
import com.iamtungingit.wam.wap.runtime.IntentOverrideResolver
import org.json.JSONObject
import java.io.File

/**
 * Developer workspace: file browser + text editor + manifest editor + packaging (spec §14).
 *
 * v1.5.0 file-manager rework (user request #8/#9):
 *  - ONE "Create" button (file name + type selector html/css/js/folder/other with
 *    extension input; html/css/js get starter templates) + "Import" right next to
 *    it (files or folders from SAF).
 *  - "Mount" button BELOW the file list: mirrors the project to a device folder
 *    (SAF) so it can be edited with other apps; every WAM change is pushed back
 *    automatically, the Sync button pulls external edits in.
 *  - Up-navigation, search filter, duplicate, per-type icons.
 */
class ProjectEditorFragment : Fragment() {

    companion object {
        fun newInstance(projectName: String) = ProjectEditorFragment().apply {
            arguments = Bundle().apply { putString(ARG_PROJECT, projectName) }
        }
        private const val ARG_PROJECT = "project"
    }

    private var binding: FragmentEditorBinding? = null
    private val app get() = WamApp.instance
    private val projectName get() = requireArguments().getString(ARG_PROJECT) ?: "project"
    private var rootDir: File? = null
    private var cwd: File? = null
    private val files = mutableListOf<File>()
    private var filesAdapter: FilesAdapter? = null
    private var searchQuery: String = ""
    private var searchVisible: Boolean = false
    private var pullInFlight: Boolean = false
    // Sort control (v1.6.0 "more controls"): 0 name A→Z · 1 name Z→A · 2 largest first.
    private var sortMode: Int = 0

    // Export a single file to device storage (v1.6.0 file-manager extra).
    private var pendingExportFile: File? = null
    private val exportFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val src = pendingExportFile
            pendingExportFile = null
            if (uri != null && src != null) {
                Ui.runIO {
                    val ok = SafIo.writeToFile(requireContext(), uri, src)
                    Ui.onMain {
                        if (ok) toast(getString(R.string.export_file_done, src.name))
                        else toast(R.string.pkg_failed_title)
                    }
                }
            }
        }

    // editor state
    private var openFile: File? = null
    private var dirty = false
    private class UndoEntry(val text: String, val cursor: Int)
    private val undoStack = ArrayDeque<UndoEntry>()
    private val redoStack = ArrayDeque<UndoEntry>()
    private var lastSnapshot = 0L
    private var beforeText = ""
    private var lastGutterLines = -1
    private var openFileToken = 0
    private var suppress = false
    private val handler = Handler(Looper.getMainLooper())
    private var packagedFile: File? = null

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val src = packagedFile
            packagedFile = null
            if (uri != null && src != null) {
                Ui.runIO {
                    val ok = SafIo.writeToFile(requireContext(), uri, src)
                    Ui.onMain {
                        if (!ok) toast(R.string.pkg_failed, "cannot write to selected location")
                    }
                }
            }
        }

    // ---- Import (v1.5.0 #8.2): single/multiple files AND folders from SAF ----
    private val importFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importUris(uris)
        }

    private val importFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) importFolder(uri)
        }

    // ---- Mount (v1.5.0 #5/#8.3): mirror the project into a SAF folder ----
    private val mountLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) mountTo(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentEditorBinding.inflate(inflater, container, false)
        binding = b
        rootDir = app.projects.projectDir(projectName)
        cwd = rootDir

        filesAdapter = FilesAdapter()
        b.filesRecycler.layoutManager = LinearLayoutManager(requireContext())
        b.filesRecycler.adapter = filesAdapter

        b.editorModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) when (checkedId) {
                R.id.btn_mode_files -> switchMode(Mode.FILES)
                R.id.btn_mode_code -> switchMode(Mode.CODE)
                R.id.btn_mode_manifest -> switchMode(Mode.MANIFEST)
            }
        }

        b.btnCreate.setOnClickListener { createEntryDialog() }
        b.btnImportFiles.setOnClickListener { showImportDialog() }

        // Up-navigation (v1.5.0): the file list previously had no way back to the root.
        b.btnUp.setOnClickListener { goUp() }

        // Sort (v1.6.0): cycles name A→Z → name Z→A → largest first.
        b.btnSort.setOnClickListener {
            sortMode = (sortMode + 1) % 3
            toast(
                when (sortMode) {
                    1 -> R.string.sort_name_desc
                    2 -> R.string.sort_size_desc
                    else -> R.string.sort_name_asc
                }
            )
            refreshFiles()
        }

        // Search filter (v1.5.0 "more controls for the file manager").
        b.btnSearchFiles.setOnClickListener {
            searchVisible = !searchVisible
            b.filesSearchLayout.visibility = if (searchVisible) View.VISIBLE else View.GONE
            if (!searchVisible) {
                b.filesSearch.setText("")
                searchQuery = ""
                refreshFiles()
            } else {
                b.filesSearch.requestFocus()
            }
        }
        b.filesSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                refreshFiles()
            }
        })

        // Mount bar (below the file manager, v1.5.0 #8).
        b.btnMount.setOnClickListener { mountLauncher.launch(null) }
        b.btnSyncMount.setOnClickListener { syncMountedFolder() }
        b.btnUnmount.setOnClickListener { confirmUnmount() }

        b.editorText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (suppress) return
                val now = System.currentTimeMillis()
                if (now - lastSnapshot > 500) {
                    if (undoStack.size >= 60) undoStack.removeFirst()
                    undoStack.addLast(UndoEntry(beforeText, binding?.editorText?.selectionStart ?: 0))
                    redoStack.clear()
                    lastSnapshot = now
                }
                beforeText = s?.toString() ?: ""
                dirty = true
                updateFileHeader()
                updateLineCol()
                scheduleHighlight()
                scheduleAutosave()
            }
        })

        b.editorText.doOnNextLayout { updateGutter() }
        // Long lines scroll sideways instead of wrapping — with inputType textMultiLine
        // this must be set from code (android:horizontallyScrolling is not exposed in
        // XML for EditText). Keeps every code line on one visual row, so the line-number
        // gutter can never misalign on wrapped lines.
        b.editorText.setHorizontallyScrolling(true)
        b.editorText.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            b.editorGutter.scrollY = scrollY
        }
        b.btnCloseFile.setOnClickListener {
            if (dirty) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.editor_unsaved_title)
                    .setMessage(R.string.editor_unsaved_message)
                    .setPositiveButton(R.string.editor_discard) { _, _ -> closeEditor() }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            } else {
                closeEditor()
            }
        }

        b.btnUndo.setOnClickListener { undo() }
        b.btnRedo.setOnClickListener { redo() }
        b.btnFind.setOnClickListener { findDialog() }
        // User request: the Save button must drop you back on the file manager.
        b.btnSave.setOnClickListener { saveOpenFile(returnToFiles = true) }

        b.btnManifestForm.setOnClickListener { manifestFormDialog() }
        b.btnManifestRaw.setOnClickListener { openFile(File(rootDir, "manifest.json")) }
        b.btnPackage.setOnClickListener { doPackage(install = false, preview = false) }
        b.btnPackageInstall.setOnClickListener { doPackage(install = true, preview = false) }
        b.btnPreview.setOnClickListener { doPackage(install = false, preview = true) }
        b.btnExportWap.setOnClickListener {
            doPackage(install = false, preview = false, thenExport = true)
        }

        switchMode(Mode.FILES)
        updateMountBar()
        return b.root
    }

    override fun onResume() {
        super.onResume()
        // External edits first (project mounted → folder wins on open), then list.
        if (isMounted() && openFile == null) pullMountedFolder(onDone = null)
        else refreshFiles()
        updateManifestStatus()
        updateMountBar()
    }

    override fun onPause() {
        super.onPause()
        if (dirty) saveOpenFile()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private enum class Mode { FILES, CODE, MANIFEST }

    private fun switchMode(mode: Mode) {
        val b = binding ?: return
        b.panelFiles.visibility = if (mode == Mode.FILES) View.VISIBLE else View.GONE
        b.panelCode.visibility = if (mode == Mode.CODE) View.VISIBLE else View.GONE
        b.panelManifest.visibility = if (mode == Mode.MANIFEST) View.VISIBLE else View.GONE
        // Empty-state guard: never leave a dead black editor on screen (user-reported
        // "bug in the code editor") — Code mode without an open file explains itself.
        val empty = mode == Mode.CODE && openFile == null
        b.editorEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
        b.editorCodeArea.visibility = if (empty) View.GONE else View.VISIBLE
        if (mode == Mode.FILES) {
            refreshFiles()
        }
        if (mode == Mode.MANIFEST) updateManifestStatus()
    }

    // ------------------------------------------------------------------ files

    private fun refreshFiles() {
        val dir = cwd ?: return
        val b = binding ?: return
        Ui.runIO {
            val all = (dir.listFiles() ?: emptyArray())
                .sortedWith(
                    when (sortMode) {
                        1 -> compareByDescending<File> { it.isDirectory }.thenByDescending { it.name.lowercase() }
                        2 -> compareByDescending<File> { it.isDirectory }
                            .thenByDescending { if (it.isFile) it.length() else Long.MAX_VALUE }
                            .thenBy { it.name.lowercase() }
                        else -> compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
                    }
                )
                .toList()
            val filtered = if (searchQuery.isEmpty()) all else all.filter {
                it.name.contains(searchQuery, ignoreCase = true)
            }
            Ui.onMain {
                files.clear()
                files.addAll(filtered)
                filesAdapter?.notifyDataSetChanged()
                val rel = try {
                    rootDir?.let { r -> dir.relativeTo(r).path } ?: dir.path
                } catch (e: Exception) { dir.path }
                b.editorPath.text = "$projectName/${if (rel == ".") "" else rel}"
                b.btnUp.isEnabled = rootDir != null && dir != rootDir
            }
        }
    }

    private fun goUp() {
        val root = rootDir ?: return
        val dir = cwd ?: root
        if (dir == root) return
        val parent = dir.parentFile
        cwd = if (parent != null && parent.path.startsWith(root.path)) parent else root
        refreshFiles()
    }

    // ------------------------------------------------------------------ create

    /**
     * Live validation for the Create dialog name field: returns a reason while the
     * user types (empty string = neutral, null = valid). Mirrors the rules of
     * PathSanitizer.sanitizeName so the dialog and the writer never disagree.
     */
    private fun createNameLiveError(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        if (t.length > 255) return getString(R.string.create_err_too_long)
        if (t == "." || t == "..") return getString(R.string.create_err_reserved)
        val bad = t.firstOrNull { it.code < 0x20 || it.code == 0x7f || "\\/:*?\"<>|".contains(it) }
        if (bad != null) return getString(R.string.create_err_bad_char, bad.toString())
        return null
    }

    private fun createExtLiveError(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val normalized = t.removePrefix(".")
        if (normalized.isEmpty()) return getString(R.string.create_err_ext_empty)
        if (normalized.length > 64) return getString(R.string.create_err_too_long)
        val bad = normalized.firstOrNull { it.code < 0x20 || it.code == 0x7f || "\\/:*?\"<>|".contains(it) }
        if (bad != null) return getString(R.string.create_err_bad_char, bad.toString())
        return null
    }

    /** Single Create dialog (v1.5.0 #8.1) with name + type + conditional extension. */
    private fun createEntryDialog() {
        if (cwd == null) return
        val view = layoutInflater.inflate(R.layout.dialog_create_entry, null)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.ce_name_layout)
        val nameInput = view.findViewById<TextInputEditText>(R.id.ce_name)
        val extLayout = view.findViewById<TextInputLayout>(R.id.ce_ext_layout)
        val extInput = view.findViewById<TextInputEditText>(R.id.ce_ext)
        val typeSpinner = view.findViewById<Spinner>(R.id.ce_type)
        typeSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item,
            resources.getStringArray(R.array.array_create_types))

        fun validate(): Boolean {
            val nameErr = createNameLiveError(nameInput.text?.toString().orEmpty())
            nameLayout.error = when {
                nameErr == null -> null
                nameErr.isEmpty() -> null
                else -> nameErr
            }
            val extErr = if (typeSpinner.selectedItemPosition == Templates.CREATE_TYPE_OTHER) {
                createExtLiveError(extInput.text?.toString().orEmpty())
            } else null
            extLayout.error = if (extErr.isNullOrEmpty()) null else extErr
            return nameErr == null && (extErr.isNullOrEmpty())
        }
        nameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { validate() }
        })
        extInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { validate() }
        })
        typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                extLayout.visibility =
                    if (pos == Templates.CREATE_TYPE_OTHER) View.VISIBLE else View.GONE
                validate()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.action_create, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!validate()) return@setOnClickListener
            val rawName = nameInput.text?.toString()?.trim().orEmpty()
            val type = typeSpinner.selectedItemPosition
            val finalName = buildFinalName(rawName, type, extInput.text?.toString()?.trim().orEmpty())
            val safe = PathSanitizer.sanitizeName(finalName)
            if (safe == null) {
                nameLayout.error = getString(R.string.invalid_file_name)
                return@setOnClickListener
            }
            val target = File(cwd, safe)
            if (target.exists()) {
                toast(R.string.file_exists)
                return@setOnClickListener
            }
            dialog.dismiss()
            Ui.runIO {
                val ok = if (type == Templates.CREATE_TYPE_FOLDER) {
                    target.mkdirs()
                } else {
                    Templates.writeStarter(target, type)
                    true
                }
                Ui.onMain {
                    if (!ok) {
                        toast(R.string.invalid_file_name)
                    } else {
                        toast(getString(R.string.create_done, safe))
                        mirrorIfMounted()
                        refreshFiles()
                    }
                }
            }
        }
    }

    /** name + type → final file name (html/css/js auto-append the extension, other uses the ext field). */
    private fun buildFinalName(name: String, type: Int, ext: String): String {
        if (type == Templates.CREATE_TYPE_FOLDER) return name
        if (type == Templates.CREATE_TYPE_OTHER) {
            if (ext.isEmpty()) return name
            return if (ext.startsWith(".")) "$name$ext" else "$name.$ext"
        }
        val wanted = when (type) {
            Templates.CREATE_TYPE_HTML -> "html"
            Templates.CREATE_TYPE_CSS -> "css"
            else -> "js"
        }
        return if (name.substringAfterLast('.', "").lowercase() == wanted) name else "$name.$wanted"
    }

    // ------------------------------------------------------------------ import

    /** Import chooser (v1.5.0 #8.2): files or a folder from the SAF picker. */
    private fun showImportDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.editor_import)
            .setItems(arrayOf(
                getString(R.string.import_pick_files),
                getString(R.string.import_pick_folder)
            )) { _, which ->
                when (which) {
                    0 -> importFilesLauncher.launch(arrayOf("*/*"))
                    1 -> importFolderLauncher.launch(null)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun importUris(uris: List<Uri>) {
        val dir = cwd ?: return
        Ui.runIO {
            var count = 0
            for (uri in uris) {
                var base = SafIo.queryDisplayName(requireContext(), uri) ?: continue
                base = base.substringAfterLast('/')
                val safe = PathSanitizer.sanitizeName(base) ?: continue
                val dest = File(dir, uniqueName(dir, safe))
                if (SafIo.copyTo(requireContext(), uri, dest, SizeLimits.MAX_ENTRY_BYTES).ok) count++
            }
            Ui.onMain {
                if (count > 0) {
                    toast(getString(R.string.import_files_done, count))
                    mirrorIfMounted()
                }
                refreshFiles()
            }
        }
    }

    private fun importFolder(uri: Uri) {
        val dir = cwd ?: return
        Ui.runIO {
            try {
                var base = SafMirrorDisplayName(uri)
                base = base.substringAfterLast('/').ifEmpty { "folder" }
                val safe = PathSanitizer.sanitizeName(base.replace(' ', '_')) ?: "folder"
                val sub = File(dir, uniqueName(dir, safe))
                sub.mkdirs()
                val count = SafIo.importTree(
                    requireContext(), uri, sub, SizeLimits.MAX_ENTRY_BYTES)
                Ui.onMain {
                    toast(getString(R.string.import_files_done, count))
                    mirrorIfMounted()
                    refreshFiles()
                }
            } catch (e: Exception) {
                Ui.onMain { toast(R.string.import_invalid_archive) }
            }
        }
    }

    /** Display name of a picked tree's root document (folder name). */
    private fun SafMirrorDisplayName(uri: Uri): String =
        com.iamtungingit.wam.storage.MountStore.displayName(requireContext(), uri)

    private fun uniqueName(dir: File, base: String): String {
        if (!File(dir, base).exists()) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        var n = 1
        while (File(dir, "$stem-$n$ext").exists()) n++
        return "$stem-$n$ext"
    }

    // ------------------------------------------------------------------ mount

    private fun isMounted(): Boolean = app.mountStore.get(projectName) != null

    private fun updateMountBar() {
        val b = binding ?: return
        val rec = app.mountStore.get(projectName)
        if (rec == null) {
            b.btnMount.visibility = View.VISIBLE
            b.mountStatus.visibility = View.GONE
            b.btnSyncMount.visibility = View.GONE
            b.btnUnmount.visibility = View.GONE
        } else {
            b.btnMount.visibility = View.GONE
            b.mountStatus.visibility = View.VISIBLE
            b.mountStatus.text = getString(R.string.editor_mounted_to, rec.display)
            b.btnSyncMount.visibility = View.VISIBLE
            b.btnUnmount.visibility = View.VISIBLE
        }
    }

    private fun mountTo(treeUri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Some providers hand out read-only trees; mirroring still works as far
            // as it can and failures surface in the sync toast.
        }
        val display = com.iamtungingit.wam.storage.MountStore.displayName(requireContext(), treeUri)
        app.mountStore.set(projectName, treeUri, display)
        updateMountBar()
        toast(getString(R.string.editor_mount_done, display))
        val root = rootDir ?: return
        Ui.runIO {
            val r = SafMirror.exportTo(requireContext(), root, treeUri)
            Ui.onMain {
                if (!r.ok) toast(getString(R.string.editor_mount_sync_failed, r.error ?: ""))
                refreshFiles()
            }
        }
    }

    /** Pull external edits into WAM (folder → project). */
    private fun pullMountedFolder(onDone: (() -> Unit)?) {
        val rec = app.mountStore.get(projectName) ?: return
        val root = rootDir ?: return
        if (pullInFlight) return
        pullInFlight = true
        Ui.runIO {
            val r = try {
                SafMirror.importFrom(requireContext(), Uri.parse(rec.treeUri), root)
            } catch (e: Exception) {
                SafMirror.Result(false, 0, e.message ?: "pull failed")
            }
            pullInFlight = false
            Ui.onMain {
                if (!r.ok && isAdded) {
                    toast(getString(R.string.editor_mount_sync_failed, r.error ?: ""))
                }
                refreshFiles()
                onDone?.invoke()
            }
        }
    }

    /** Sync button: pull (folder → project) then push (project → folder). */
    private fun syncMountedFolder() {
        val rec = app.mountStore.get(projectName) ?: return
        val root = rootDir ?: return
        val uri = Uri.parse(rec.treeUri)
        toast(getString(R.string.editor_mount_syncing))
        Ui.runIO {
            val pull = try {
                SafMirror.importFrom(requireContext(), uri, root)
            } catch (e: Exception) {
                SafMirror.Result(false, 0, e.message ?: "pull failed")
            }
            val push = if (pull.ok) try {
                SafMirror.exportTo(requireContext(), root, uri)
            } catch (e: Exception) {
                SafMirror.Result(false, 0, e.message ?: "push failed")
            } else pull
            Ui.onMain {
                if (!push.ok) {
                    toast(getString(R.string.editor_mount_sync_failed, push.error ?: ""))
                } else {
                    toast(getString(R.string.editor_mount_sync_done, pull.files + push.files))
                }
                refreshFiles()
            }
        }
    }

    private fun confirmUnmount() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.editor_mount_unmount)
            .setMessage(R.string.editor_mount_unmount_msg)
            .setPositiveButton(R.string.editor_mount_unmount) { _, _ ->
                app.mountStore.remove(projectName)
                updateMountBar()
                toast(R.string.editor_mount_unmounted)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Write-through: after every WAM-side change (create / rename / delete / save /
     * import) the mounted folder is re-mirrored, so "edit your project there" works
     * from both directions with WAM changes landing instantly.
     */
    private fun mirrorIfMounted() {
        val rec = app.mountStore.get(projectName) ?: return
        val root = rootDir ?: return
        val treeUri = Uri.parse(rec.treeUri)
        Ui.runIO {
            try {
                SafMirror.exportTo(requireContext(), root, treeUri)
            } catch (e: Exception) {
                // Push failures are non-fatal; the Sync button retries on demand.
            }
        }
    }

    // ------------------------------------------------------------------ rename/delete/duplicate

    private fun renameEntry(f: File) {
        inputDialog(R.string.action_rename, f.name) { rawName ->
            val name = PathSanitizer.sanitizeName(rawName.trim())
            if (name == null) {
                toast(R.string.invalid_file_name)
                return@inputDialog
            }
            val target = File(f.parentFile, name)
            if (target.exists()) {
                toast(R.string.file_exists)
                return@inputDialog
            }
            Ui.runIO {
                val ok = f.renameTo(target)
                Ui.onMain {
                    if (!ok) toast(R.string.invalid_file_name)
                    mirrorIfMounted()
                    refreshFiles()
                }
            }
        }
    }

    private fun duplicateEntry(f: File) {
        val dir = f.parentFile ?: return
        val target = File(dir, uniqueName(dir, duplicateName(f.name)))
        Ui.runIO {
            val ok = if (f.isDirectory) {
                target.mkdirs()
                Fs.copyRec(f, target)
            } else {
                try {
                    f.copyTo(target, overwrite = false).isFile
                } catch (e: Exception) { false }
            }
            Ui.onMain {
                if (!ok) toast(getString(R.string.pkg_failed, "duplicate failed"))
                mirrorIfMounted()
                refreshFiles()
            }
        }
    }

    private fun duplicateName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)}_copy${name.substring(dot)}"
        else "${name}_copy"
    }

    private fun deleteEntry(f: File) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.delete_item_message, f.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                Ui.runIO {
                    Fs.deleteRec(f)
                    Ui.onMain {
                        if (openFile != null && (openFile == f || openFile!!.path.startsWith(f.path))) {
                            closeEditor()
                        }
                        mirrorIfMounted()
                        refreshFiles()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ editor

    private fun openFile(f: File) {
        if (f.isDirectory) {
            cwd = f
            refreshFiles()
            return
        }
        // Token guard: tapping file A then quickly file B used to let A's slower read
        // win and display A's content under B's header (an editor bug that was hard
        // to describe — exactly this stale-callback race).
        val token = ++openFileToken
        Ui.runIO {
            try {
                if (f.length() > SizeLimits.MAX_EDITABLE_FILE) {
                    Ui.onMain { toast(R.string.editor_file_too_big) }
                    return@runIO
                }
                if (!MimeTypes.isText(MimeTypes.fromPath(f.name))) {
                    Ui.onMain { toast(R.string.editor_binary) }
                    return@runIO
                }
                val text = f.readText(Charsets.UTF_8)
                Ui.onMain {
                    if (token != openFileToken) return@onMain // a newer tap already won
                    openFile = f
                    undoStack.clear()
                    redoStack.clear()
                    lastGutterLines = -1
                    suppress = true
                    binding?.editorText?.setText(text)
                    binding?.editorText?.setSelection(0)
                    suppress = false
                    beforeText = text
                    dirty = false
                    updateFileHeader()
                    updateGutter()
                    applyHighlight()
                    binding?.editorEmptyState?.visibility = View.GONE
                    binding?.editorCodeArea?.visibility = View.VISIBLE
                    binding?.editorModeGroup?.check(R.id.btn_mode_code)
                }
            } catch (e: Exception) {
                Ui.onMain { toast(R.string.editor_binary) }
            }
        }
    }

    // -------------------------------------------------- highlighting & gutter

    private val highlightRunnable = Runnable { applyHighlight() }

    private fun scheduleHighlight() {
        handler.removeCallbacks(highlightRunnable)
        handler.postDelayed(highlightRunnable, 260)
    }

    private fun applyHighlight() {
        val b = binding ?: return
        val f = openFile ?: return
        val editable = b.editorText.editableText ?: return
        if (editable.isEmpty()) return
        CodeHighlighter.highlight(b.editorText, f.name, editable)
        updateGutter()
    }

    /** Line-number gutter: rebuilt only when the line COUNT changes (typing used to
     *  rebuild it on every keystroke and lagged on low-end phones). */
    private fun updateGutter() {
        val b = binding ?: return
        val text = b.editorText.text ?: return
        val lines = text.count { it == '\n' } + 1
        if (lines == lastGutterLines) return
        lastGutterLines = lines
        if (lines > 5000) {
            b.editorGutter.text = "1\n\u2026"
            return
        }
        val sb = StringBuilder(lines * 5)
        for (i in 1..lines) sb.append(i).append('\n')
        b.editorGutter.text = sb
        b.editorGutter.scrollY = b.editorText.scrollY
    }

    private fun updateFileHeader() {
        val b = binding ?: return
        val f = openFile
        if (f == null) {
            b.editorFileHeader.visibility = View.GONE
            return
        }
        b.editorFileHeader.visibility = View.VISIBLE
        val rel = try {
            rootDir?.let { r -> f.relativeTo(r).path } ?: f.name
        } catch (e: Exception) {
            f.name
        }
        b.editorFileName.text = if (dirty) "$rel \u2022" else rel
    }

    private fun closeEditor() {
        openFile = null
        dirty = false
        lastGutterLines = -1
        suppress = true
        binding?.editorText?.setText("")
        suppress = false
        updateFileHeader()
        binding?.editorGutter?.text = "1"
        // Return to the file list so the user never stares at an empty code panel.
        if (binding?.editorModeGroup?.checkedButtonId != R.id.btn_mode_files) {
            binding?.editorModeGroup?.check(R.id.btn_mode_files)
        } else {
            switchMode(Mode.FILES)
        }
    }

    /**
     * Saves the open file. [returnToFiles] (the explicit Save button) also returns to
     * the file list — the user asked for “save button doesn't send you back to the
     * file manager” to be fixed. Background autosaves (onPause, every 2 s) never
     * switch modes — only the button does.
     */
    private fun saveOpenFile(returnToFiles: Boolean = false) {
        val f = openFile ?: return
        val text = binding?.editorText?.text?.toString() ?: return
        Ui.runIO {
            try {
                f.parentFile?.mkdirs()
                f.writeText(text, Charsets.UTF_8)
                Ui.onMain {
                    dirty = false
                    updateFileHeader()
                    applyHighlight() // big files re-highlight on save (typing stays plain)
                    if (returnToFiles && isAdded) {
                        toast(R.string.editor_saved)
                        val b = binding ?: return@onMain
                        if (b.editorModeGroup.checkedButtonId != R.id.btn_mode_files) {
                            // Checking the Files button fires the toggle listener → switchMode(FILES).
                            b.editorModeGroup.check(R.id.btn_mode_files)
                        } else {
                            switchMode(Mode.FILES)
                        }
                    }
                }
                mirrorIfMounted()
            } catch (e: Exception) {
                Ui.onMain { toast(R.string.pkg_failed, e.message ?: "write failed") }
            }
        }
    }

    private fun scheduleAutosave() {
        handler.removeCallbacks(autosaveRunnable)
        handler.postDelayed(autosaveRunnable, 2000)
    }

    private val autosaveRunnable = Runnable {
        if (dirty && openFile != null && isAdded) saveOpenFile()
    }

    private fun undo() {
        val b = binding ?: return
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(UndoEntry(b.editorText.text?.toString() ?: "", b.editorText.selectionEnd))
        suppress = true
        b.editorText.setText(prev.text)
        suppress = false
        beforeText = prev.text
        dirty = true
        updateFileHeader()
        updateGutter()
        // Cursor used to jump to the end on undo — restore where the user was.
        b.editorText.setSelection(prev.cursor.coerceIn(0, prev.text.length))
        updateLineCol()
    }

    private fun redo() {
        val b = binding ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(UndoEntry(b.editorText.text?.toString() ?: "", b.editorText.selectionEnd))
        suppress = true
        b.editorText.setText(next.text)
        suppress = false
        beforeText = next.text
        dirty = true
        updateFileHeader()
        updateGutter()
        b.editorText.setSelection(next.cursor.coerceIn(0, next.text.length))
        updateLineCol()
    }

    private fun findDialog() {
        val b = binding ?: return
        if (b.panelCode.visibility != View.VISIBLE) return
        val layout = TextInputLayout(requireContext())
        val input = TextInputEditText(requireContext())
        input.hint = getString(R.string.editor_find_hint)
        layout.addView(input)
        layout.setPadding(48, 24, 48, 0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.editor_find)
            .setView(layout)
            .setPositiveButton(R.string.editor_find_next) { _, _ ->
                val query = input.text?.toString() ?: return@setPositiveButton
                if (query.isEmpty()) return@setPositiveButton
                val text = b.editorText.text?.toString() ?: return@setPositiveButton
                val from = (b.editorText.selectionEnd).coerceIn(0, text.length)
                val idx = text.indexOf(query, from, ignoreCase = true)
                val found = if (idx >= 0) idx else text.indexOf(query, 0, ignoreCase = true)
                if (found >= 0) {
                    b.editorText.requestFocus()
                    b.editorText.setSelection(found, found + query.length)
                    updateLineCol()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun updateLineCol() {
        val b = binding ?: return
        val text = b.editorText.text?.toString() ?: ""
        val sel = b.editorText.selectionStart.coerceIn(0, text.length)
        val line = text.substring(0, sel).count { it == '\n' } + 1
        val col = sel - (text.lastIndexOf('\n', (sel - 1).coerceAtLeast(0)) + 1) + 1
        b.editorLineCol.text = "$line:$col"
    }

    // ------------------------------------------------------------------ manifest

    private fun updateManifestStatus() {
        val b = binding ?: return
        val dir = rootDir ?: return
        Ui.runIO {
            val mf = ManifestParser.fromDirectory(dir, app.settings.current.maxQuotaBytes)
            Ui.onMain {
                when (mf) {
                    is ManifestParser.Result.Success ->
                        b.manifestStatus.text = "${mf.manifest.name} v${mf.manifest.version} · ${mf.manifest.type}" +
                                if (mf.warnings.isNotEmpty()) "\n⚠ ${mf.warnings.size} warning(s)" else ""
                    is ManifestParser.Result.Failure ->
                        b.manifestStatus.text = mf.error.toString()
                }
            }
        }
    }

    private fun manifestFormDialog() {
        val dir = rootDir ?: return
        val existing = try {
            val f = File(dir, "manifest.json")
            if (f.isFile) JSONObject(f.readText()) else JSONObject()
        } catch (e: Exception) { JSONObject() }

        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        fun addText(hint: String, value: String, single: Boolean = true): TextInputEditText {
            val til = TextInputLayout(ctx)
            val et = TextInputEditText(ctx)
            et.setText(value)
            et.hint = hint
            if (single) et.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else et.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            til.addView(et)
            container.addView(til)
            return et
        }

        fun addSwitch(label: String, checked: Boolean): Switch {
            val sw = Switch(ctx)
            sw.text = label
            sw.isChecked = checked
            container.addView(sw)
            return sw
        }

        fun addSpinner(label: String, entries: List<String>, selection: Int): Spinner {
            val tv = TextView(ctx)
            tv.text = label
            container.addView(tv)
            val sp = Spinner(ctx)
            sp.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, entries)
            sp.setSelection(selection)
            container.addView(sp)
            return sp
        }

        val fName = addText(getString(R.string.mf_name), existing.optString("name"))
        val fId = addText(getString(R.string.mf_id), existing.optString("id"))
        val fVersion = addText(getString(R.string.mf_version), existing.optString("version", "1.0.0"))
        val fDesc = addText(getString(R.string.mf_description), existing.optString("description"), single = false)
        val fAuthor = addText(getString(R.string.mf_author), existing.optString("author"))
        val typeSpinner = addSpinner(
            getString(R.string.mf_type),
            listOf(getString(R.string.type_offline), getString(R.string.type_online)),
            if (existing.optString("type", "offline") == "online") 1 else 0
        )
        val fEntry = addText(getString(R.string.mf_entry), existing.optString("entry", "index.html"))
        val fIcon = addText(getString(R.string.mf_icon), existing.optString("icon", ""))
        val fWLabel = addText(getString(R.string.mf_widget_label), existing.optJSONObject("widget")?.optString("label", "") ?: "")
        val fWSub = addText(getString(R.string.mf_widget_subtitle), existing.optJSONObject("widget")?.optString("subtitle", "") ?: "")
        val orientationSpinner = addSpinner(
            getString(R.string.mf_orientation),
            resources.getStringArray(R.array.array_orientations).toList(),
            when (existing.optJSONObject("display")?.optString("orientation")) {
                "portrait" -> 1; "landscape" -> 2; "sensor" -> 3; else -> 0
            }
        )
        val themeSpinner = addSpinner(
            getString(R.string.mf_theme),
            resources.getStringArray(R.array.array_themes).toList(),
            when (existing.optJSONObject("display")?.optString("theme")) {
                "light" -> 1; "dark" -> 2; else -> 0
            }
        )
        val swFullscreen = addSwitch(getString(R.string.mf_fullscreen),
            existing.optJSONObject("display")?.optBoolean("fullscreen") ?: false)
        val swKeepOn = addSwitch(getString(R.string.set_keep_screen_on),
            existing.optJSONObject("display")?.optBoolean("keepScreenOn") ?: false)
        val swNetwork = addSwitch(getString(R.string.mf_network_required),
            existing.optJSONObject("network")?.optBoolean("required") ?: false)
        val swBridge = addSwitch(getString(R.string.mf_bridge),
            existing.optJSONObject("data")?.optBoolean("bridge") ?: false)
        val swWidget = addSwitch(getString(R.string.mf_widget_enabled),
            existing.optJSONObject("widget")?.optBoolean("enabled", true) ?: true)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.manifest_form_title)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                applyManifestForm(
                    existing, dir, fName, fId, fVersion, fDesc, fAuthor, fEntry, fIcon, fWLabel, fWSub,
                    typeSpinner, orientationSpinner, themeSpinner,
                    swFullscreen, swKeepOn, swNetwork, swBridge, swWidget
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    @Suppress("LongParameterList")
    private fun applyManifestForm(
        existing: JSONObject,
        dir: File,
        fName: TextInputEditText, fId: TextInputEditText, fVersion: TextInputEditText,
        fDesc: TextInputEditText, fAuthor: TextInputEditText, fEntry: TextInputEditText,
        fIcon: TextInputEditText, fWLabel: TextInputEditText, fWSub: TextInputEditText,
        typeSpinner: Spinner, orientationSpinner: Spinner,
        themeSpinner: Spinner,
        swFullscreen: Switch, swKeepOn: Switch, swNetwork: Switch, swBridge: Switch, swWidget: Switch
    ) {
        val o = existing
        o.put("name", fName.text?.toString()?.trim() ?: "")
        o.put("id", fId.text?.toString()?.trim() ?: "")
        o.put("version", fVersion.text?.toString()?.trim() ?: "1.0.0")
        o.put("description", fDesc.text?.toString()?.trim() ?: "")
        o.put("author", fAuthor.text?.toString()?.trim() ?: "")
        o.put("type", if (typeSpinner.selectedItemPosition == 1) "online" else "offline")
        o.put("entry", fEntry.text?.toString()?.trim() ?: "index.html")
        val iconVal = fIcon.text?.toString()?.trim().orEmpty()
        if (iconVal.isEmpty()) o.remove("icon") else o.put("icon", iconVal)

        val display = o.optJSONObject("display") ?: JSONObject()
        display.put("fullscreen", swFullscreen.isChecked)
        display.put("keepScreenOn", swKeepOn.isChecked)
        display.put("orientation", when (orientationSpinner.selectedItemPosition) {
            1 -> "portrait"; 2 -> "landscape"; 3 -> "sensor"; else -> "unspecified"
        })
        display.put("theme", when (themeSpinner.selectedItemPosition) {
            1 -> "light"; 2 -> "dark"; else -> "system"
        })
        o.put("display", display)

        val network = o.optJSONObject("network") ?: JSONObject()
        network.put("required", swNetwork.isChecked)
        o.put("network", network)

        val widget = o.optJSONObject("widget") ?: JSONObject()
        widget.put("enabled", swWidget.isChecked)
        val labelVal = fWLabel.text?.toString()?.trim().orEmpty()
        if (labelVal.isEmpty()) widget.remove("label") else widget.put("label", labelVal)
        val subVal = fWSub.text?.toString()?.trim().orEmpty()
        if (subVal.isEmpty()) widget.remove("subtitle") else widget.put("subtitle", subVal)
        o.put("widget", widget)

        val data = o.optJSONObject("data") ?: JSONObject()
        data.put("bridge", swBridge.isChecked)
        if (!data.has("quotaBytes")) data.put("quotaBytes", 5242880L)
        o.put("data", data)

        // Validate live against project entry names (spec §14).
        val names = HashSet<String>()
        fun collect(d: File, prefix: String) {
            for (c in d.listFiles() ?: emptyArray()) {
                if (c.isDirectory) collect(c, "$prefix${c.name}/") else names.add("$prefix${c.name}")
            }
        }
        collect(dir, "")
        when (val r = ManifestValidator.validate(o, names, app.settings.current.maxQuotaBytes)) {
            is ManifestParser.Result.Failure -> {
                if (r.error.code == ErrorCodes.ID_INVALID) {
                    val suggestion = PathSanitizer.suggestId(o.optString("name"))
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.mf_invalid_title)
                        .setMessage(getString(R.string.mf_id_suggestion, suggestion))
                        .setPositiveButton(R.string.action_yes) { _, _ ->
                            o.put("id", suggestion)
                            revalidateAndSave(o, dir)
                        }
                        .setNegativeButton(R.string.action_no, null)
                        .show()
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.mf_invalid_title)
                        .setMessage(getString(R.string.mf_invalid, r.error.toString()))
                        .setPositiveButton(R.string.action_ok, null)
                        .show()
                }
            }
            is ManifestParser.Result.Success -> saveManifest(o, dir)
        }
    }

    private fun revalidateAndSave(o: JSONObject, dir: File) {
        val names = HashSet<String>()
        fun collect(d: File, prefix: String) {
            for (c in d.listFiles() ?: emptyArray()) {
                if (c.isDirectory) collect(c, "$prefix${c.name}/") else names.add("$prefix${c.name}")
            }
        }
        collect(dir, "")
        when (val r = ManifestValidator.validate(o, names, app.settings.current.maxQuotaBytes)) {
            is ManifestParser.Result.Failure ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.mf_invalid_title)
                    .setMessage(getString(R.string.mf_invalid, r.error.toString()))
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
            is ManifestParser.Result.Success -> saveManifest(o, dir)
        }
    }

    private fun saveManifest(o: JSONObject, dir: File) {
        Ui.runIO {
            try {
                File(dir, "manifest.json").writeText(o.toString(2))
                Ui.onMain {
                    toast(R.string.mf_saved)
                    updateManifestStatus()
                }
                mirrorIfMounted()
            } catch (e: Exception) {
                Ui.onMain { toast(R.string.pkg_failed, e.message ?: "save failed") }
            }
        }
    }

    // ------------------------------------------------------------------ packaging

    private fun doPackage(install: Boolean, preview: Boolean, thenExport: Boolean = false) {
        val dir = rootDir ?: return
        val b = binding ?: return
        toast(R.string.packaging_running)
        Ui.runIO {
            val outDir = File(requireContext().cacheDir, "packaged")
            outDir.mkdirs()
            val outFile = File(outDir, "$projectName.wap")
            val r = WapPackager.packageProject(dir, outFile, app.settings.current.maxQuotaBytes)
            Ui.onMain {
                when {
                    !r.ok -> {
                        // Full error in a dialog — a toast would truncate the message and
                        // hide the real cause (this is exactly why the old
                        // "[ARCHIVE_UNSUPPORTED]" reports were impossible to diagnose).
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.pkg_failed_title)
                            .setMessage(r.error?.toString() ?: "unknown error")
                            .setPositiveButton(R.string.action_ok, null)
                            .show()
                    }
                    preview -> {
                        val intent = android.content.Intent(requireContext(), com.iamtungingit.wam.ui.runtime.RuntimeActivity::class.java)
                            .putExtra(IntentOverrideResolver.EXTRA_PREVIEW_PATH, r.file!!.absolutePath)
                            .putExtra("WAP_PREVIEW_PROJECT", projectName)
                        startActivity(intent)
                    }
                    install -> {
                        (activity as? MainActivity)?.importFromFile(r.file!!)
                    }
                    thenExport -> {
                        packagedFile = r.file
                        exportLauncher.launch("$projectName-${readVersion()}.wap")
                    }
                    else -> {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.pkg_ok_title)
                            .setMessage(getString(R.string.pkg_ok, r.file!!.path) +
                                    (if (r.warnings.isNotEmpty()) "\n\n" + r.warnings.joinToString("\n") { "• $it" } else ""))
                            .setPositiveButton(R.string.action_ok, null)
                            .show()
                    }
                }
            }
        }
    }

    private fun readVersion(): String {
        val dir = rootDir ?: return "1.0.0"
        return try {
            val mf = ManifestParser.fromDirectory(dir, Long.MAX_VALUE / 4)
            (mf as? ManifestParser.Result.Success)?.manifest?.version ?: "1.0.0"
        } catch (e: Exception) { "1.0.0" }
    }

    // ------------------------------------------------------------------ helpers

    private fun inputDialog(titleRes: Int, initial: String, onOk: (String) -> Unit) {
        val layout = TextInputLayout(requireContext())
        val edit = TextInputEditText(requireContext())
        edit.setText(initial)
        layout.addView(edit)
        layout.setPadding(48, 24, 48, 0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setView(layout)
            .setPositiveButton(R.string.action_ok) { _, _ -> onOk(edit.text?.toString().orEmpty()) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun toast(resId: Int, extra: String? = null) {
        context?.let {
            Toast.makeText(it, getString(resId, extra ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun toast(text: String) {
        context?.let { Toast.makeText(it, text, Toast.LENGTH_SHORT).show() }
    }

    // ------------------------------------------------------------------ adapter

    private inner class FilesAdapter : RecyclerView.Adapter<FileHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
            return FileHolder(v)
        }

        override fun getItemCount(): Int = files.size

        override fun onBindViewHolder(holder: FileHolder, position: Int) {
            val f = files[position]
            holder.name.text = f.name
            if (f.isDirectory) {
                holder.icon.setImageResource(R.drawable.ic_folder)
                holder.icon.setColorFilter(requireContext().getColor(R.color.wam_primary))
                holder.size.text = ""
            } else {
                // Per-type icons (v1.5.0 user request #2): image/video/audio/document
                // glyphs with their own tint per extension.
                val ti = FileIcons.iconFor(f.name)
                holder.icon.setImageResource(ti.drawable)
                if (ti.tint != null) {
                    holder.icon.setColorFilter(requireContext().getColor(ti.tint))
                } else {
                    holder.icon.setColorFilter(requireContext().getColor(R.color.file_txt))
                }
                holder.size.text = Ui.fmtBytes(requireContext(), f.length())
            }
            holder.itemView.setOnClickListener { openFile(f) }
            holder.itemView.setOnLongClickListener { anchor ->
                showFileMenu(anchor, f)
                true
            }
        }
    }

    private fun showFileMenu(anchor: View, f: File) {
        val menu = PopupMenu(requireContext(), anchor)
        if (!f.isDirectory && MimeTypes.isText(MimeTypes.fromPath(f.name))) {
            menu.menu.add(R.string.action_open).setOnMenuItemClickListener { openFile(f); true }
        }
        menu.menu.add(R.string.action_rename).setOnMenuItemClickListener { renameEntry(f); true }
        menu.menu.add(R.string.action_duplicate).setOnMenuItemClickListener { duplicateEntry(f); true }
        if (!f.isDirectory) {
            menu.menu.add(R.string.action_export_file).setOnMenuItemClickListener {
                pendingExportFile = f
                try {
                    exportFileLauncher.launch(f.name)
                } catch (e: Exception) {
                    pendingExportFile = null
                    toast(R.string.import_invalid_archive)
                }
                true
            }
        }
        menu.menu.add(R.string.action_delete).setOnMenuItemClickListener { deleteEntry(f); true }
        menu.show()
    }

    private inner class FileHolder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: android.widget.ImageView = v.findViewById(R.id.file_icon)
        val name: TextView = v.findViewById(R.id.file_name)
        val size: TextView = v.findViewById(R.id.file_size)
    }
}
