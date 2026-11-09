package com.iamtungingit.wam.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.iamtungingit.wam.R
import com.iamtungingit.wam.app.WamApp
import com.iamtungingit.wam.databinding.ActivityWidgetConfigureBinding
import com.iamtungingit.wam.ui.common.Ui
import com.iamtungingit.wam.util.JsonUtil
import com.iamtungingit.wam.wap.manifest.ManifestParser
import com.iamtungingit.wam.widget.WidgetRecord
import com.iamtungingit.wam.widget.WidgetRenderer
import com.iamtungingit.wam.widget.WidgetStore

/**
 * Technical exception D4: configuration Activity required by android:configure.
 * Handles both widget-picker creation and "Tap to set up" from a bound widget.
 */
class WidgetConfigureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWidgetConfigureBinding
    private val app get() = WamApp.instance
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private val entries = mutableListOf<com.iamtungingit.wam.wap.install.RegistryEntry>()
    private var manifestIcons = mutableMapOf<String, com.iamtungingit.wam.wap.manifest.WapManifest?>()
    private var bgColor: Int? = null
    private var textColor: Int? = null

    private val presetColors = intArrayOf(
        0xFF1B5E20.toInt(), 0xFF0D47A1.toInt(), 0xFFBF360C.toInt(), 0xFF4A148C.toInt(),
        0xFF263238.toInt(), 0xFFF57F17.toInt(), 0xFFAD1457.toInt(), 0xFF00695C.toInt()
    )
    private val textPresetColors = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF1B1B1B.toInt(), 0xFFFFD54F.toInt(), 0xFF90CAF9.toInt()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        binding = ActivityWidgetConfigureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ui.applyInsets(binding.root)

        val extras = intent.extras
        appWidgetId = extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        Ui.runIO {
            val list = app.registry.all().filter { !it.broken }
            val manifests = list.associate { e ->
                Pair(e.wapId, (ManifestParser.fromArchive(
                    com.iamtungingit.wam.storage.Paths.archiveFile(applicationContext, e.wapId),
                    Long.MAX_VALUE / 4
                ) as? ManifestParser.Result.Success)?.manifest)
            }
            Ui.onMain {
                entries.clear()
                entries.addAll(list)
                manifestIcons = manifests.toMutableMap()
                if (entries.isEmpty()) {
                    Toast.makeText(this, R.string.wc_no_apps, Toast.LENGTH_SHORT).show()
                    finish()
                    return@onMain
                }
                binding.wcApp.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_dropdown_item,
                    entries.map { it.name }
                )
                binding.wcApp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        fillEntries(pos)
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
                fillEntries(0)
            }
        }

        bindSpinner(binding.wcShape, R.array.array_shapes, 0)
        bindSpinner(binding.wcTheme, R.array.array_themes, 0)
        bindSpinner(binding.wcNetwork, listOf(
            getString(R.string.net_inherit), getString(R.string.net_deny), getString(R.string.net_ask)
        ), 0)

        binding.wcBgColor.setOnClickListener { pickColor(true) }
        binding.wcTextColor.setOnClickListener { pickColor(false) }
        updatePreview()

        // Make the DPI-based sizing visible/verifiable (user asked to size widgets by
        // detecting the phone's density bucket — mdpi … xxxhdpi — at runtime).
        val dm = resources.displayMetrics
        val bucket = when {
            dm.densityDpi <= 120 -> "ldpi"
            dm.densityDpi <= 160 -> "mdpi"
            dm.densityDpi <= 213 -> "tvdpi"
            dm.densityDpi <= 240 -> "hdpi"
            dm.densityDpi <= 280 -> "hdpi(280)"
            dm.densityDpi <= 320 -> "xhdpi"
            dm.densityDpi <= 420 -> "xhdpi(400)"
            dm.densityDpi <= 480 -> "xxhdpi"
            else -> "xxxhdpi"
        }
        binding.wcDensityInfo.text = getString(
            R.string.wc_density_info,
            bucket, dm.densityDpi, String.format(java.util.Locale.US, "%.2f×", dm.density)
        )

        binding.wcSave.setOnClickListener { saveAndFinish() }
    }

    private fun fillEntries(position: Int) {
        val entry = entries.getOrNull(position) ?: return
        val manifest = manifestIcons[entry.wapId]
        val options = (manifest?.effectiveAllowedEntries ?: listOf(entry.wapId))
            .map { if (manifest?.isOffline == true) it else it }
        binding.wcEntry.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, options
        )
        // Problem 1 (user): widgets hold ONE app space — the label/subtitle fields
        // and the "Show label" toggle are gone; the widget is just the app icon.
        binding.wcFullscreen.isChecked = manifest?.fullscreen ?: false
        bgColor = manifest?.widgetBackgroundColor?.let { JsonUtil.parseColorHex(it) } ?: 0xFF1B5E20.toInt()
        textColor = manifest?.widgetTextColor?.let { JsonUtil.parseColorHex(it) } ?: 0xFFFFFFFF.toInt()
        updatePreview()
    }

    private fun pickColor(isBackground: Boolean) {
        val presets = if (isBackground) presetColors else textPresetColors
        val names = presets.map { "#%08X".format(it) }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (isBackground) R.string.wc_bg else R.string.wc_text)
            .setItems(names) { _, which ->
                if (isBackground) bgColor = presets[which] else textColor = presets[which]
                updatePreview()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun updatePreview() {
        binding.wcColorPreview.setBackgroundColor(bgColor ?: 0xFF1B5E20.toInt())
    }

    private fun bindSpinner(spinner: Spinner, arrayRes: Int, selection: Int) {
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            resources.getStringArray(arrayRes)
        )
        spinner.setSelection(selection)
    }

    private fun bindSpinner(spinner: Spinner, items: List<String>, selection: Int) {
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, items
        )
        spinner.setSelection(selection)
    }

    private fun saveAndFinish() {
        val position = binding.wcApp.selectedItemPosition
        val entry = entries.getOrNull(position) ?: return
        val manifest = manifestIcons[entry.wapId]
        val entryChoice = binding.wcEntry.selectedItem?.toString()
        val defaultEntry = manifest?.entry ?: "index.html"

        val record = WidgetRecord(
            widgetId = appWidgetId,
            wapId = entry.wapId,
            // Label kept only as an identifier for the widget-manager list.
            label = entry.name,
            subtitle = "",
            entry = if (entryChoice != null && entryChoice != defaultEntry) entryChoice else null,
            fullscreen = binding.wcFullscreen.isChecked,
            theme = when (binding.wcTheme.selectedItemPosition) {
                1 -> "light"; 2 -> "dark"; else -> null
            },
            network = when (binding.wcNetwork.selectedItemPosition) {
                1 -> "deny"; 2 -> "ask"; else -> "inherit"
            },
            shape = when (binding.wcShape.selectedItemPosition) {
                1 -> "square"; 2 -> "circle"; else -> "rounded"
            },
            backgroundColor = bgColor,
            textColor = textColor,
            showLabel = false,
            createdAt = System.currentTimeMillis()
        )

        Ui.runIO {
            WidgetStore(applicationContext).put(record)
            Ui.onMain {
                WidgetRenderer.renderFor(
                    applicationContext,
                    AppWidgetManager.getInstance(applicationContext),
                    appWidgetId
                )
                val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                setResult(RESULT_OK, result)
                finish()
            }
        }
    }
}
