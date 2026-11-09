package com.iamtungingit.wam.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iamtungingit.wam.R
import com.iamtungingit.wam.app.WamApp
import com.iamtungingit.wam.databinding.ActivityAppSettingsBinding
import com.iamtungingit.wam.ui.common.Ui
import com.iamtungingit.wam.wap.runtime.NetworkPolicy

/**
 * Per-app permission settings (v1.5.0 user request #7): camera, voice recorder,
 * text-to-speech, "requests to send data" (dangerous, red asterisk) and the
 * internet policy — enforced by RuntimeActivity/BridgeHost at run time.
 */
class AppSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSettingsBinding
    private val app get() = WamApp.instance
    private var wapId: String? = null
    private var entry: com.iamtungingit.wam.wap.install.RegistryEntry? = null
    private var suppressSave = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ui.applyInsets(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        wapId = intent.getStringExtra(EXTRA_WAP_ID)
        entry = wapId?.let { app.registry.get(it) }
        val e = entry
        if (wapId == null || e == null) {
            Toast.makeText(this, R.string.runtime_err_app_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.txtAppSubtitle.text = getString(R.string.app_settings_subtitle, e.name, wapId ?: "")

        val prefs = app.appPrefs.get(wapId!!)
        binding.swCamera.isChecked = prefs.camera
        binding.swMic.isChecked = prefs.mic
        binding.swTts.isChecked = prefs.tts
        binding.swSendData.isChecked = prefs.sendData

        // Dangerous default (user request #7): flipping "send data" ON demands a
        // confirmation so nobody enables it accidentally.
        binding.swSendData.setOnCheckedChangeListener { _, checked ->
            if (suppressSave) return@setOnCheckedChangeListener
            if (checked) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.app_perm_send_data_confirm_title)
                    .setMessage(R.string.app_perm_send_data_confirm_msg)
                    .setPositiveButton(R.string.action_yes) { _, _ -> persist() }
                    .setNegativeButton(R.string.action_no) { _, _ ->
                        suppressSave = true
                        binding.swSendData.isChecked = false
                        suppressSave = false
                    }
                    .setOnCancelListener {
                        suppressSave = true
                        binding.swSendData.isChecked = false
                        suppressSave = false
                    }
                    .show()
            } else {
                persist()
            }
        }

        binding.swCamera.setOnCheckedChangeListener { _, _ -> persist() }
        binding.swMic.setOnCheckedChangeListener { _, _ -> persist() }
        binding.swTts.setOnCheckedChangeListener { _, _ -> persist() }

        // ---- Permission examples (v1.6.0 user request #4): one tap applies a
        // realistic preset instead of guessing at individual toggles.
        binding.btnPresetCamera.setOnClickListener { applyPreset(camera = true) }
        binding.btnPresetMic.setOnClickListener { applyPreset(mic = true) }
        binding.btnPresetTts.setOnClickListener { applyPreset(tts = true) }
        binding.btnPresetOffline.setOnClickListener { applyPreset() }
        binding.btnPresetFull.setOnClickListener {
            // Full access includes the DANGEROUS "send data" — confirm it explicitly.
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_perm_send_data_confirm_title)
                .setMessage(R.string.preset_full_confirm_msg)
                .setPositiveButton(R.string.action_yes) { _, _ ->
                    applyPreset(camera = true, mic = true, tts = true, sendData = true)
                }
                .setNegativeButton(R.string.action_no, null)
                .show()
        }

        val options = listOf(
            getString(R.string.net_inherit),
            getString(R.string.net_allow),
            getString(R.string.net_ask),
            getString(R.string.net_deny)
        )
        binding.spinNetwork.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        binding.spinNetwork.setSelection(
            when (e.networkPref) {
                NetworkPolicy.ALLOW -> 1
                NetworkPolicy.ASK -> 2
                NetworkPolicy.DENY -> 3
                else -> 0
            }
        )
        binding.spinNetwork.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val pref = when (position) {
                    1 -> NetworkPolicy.ALLOW
                    2 -> NetworkPolicy.ASK
                    3 -> NetworkPolicy.DENY
                    else -> NetworkPolicy.INHERIT
                }
                if (pref != entry?.networkPref) {
                    Ui.runIO { app.installManager.setNetworkPref(wapId ?: return@runIO, pref) }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun persist() {
        val id = wapId ?: return
        val prefs = com.iamtungingit.wam.settings.AppPrefsRepository.AppPrefs(
            camera = binding.swCamera.isChecked,
            mic = binding.swMic.isChecked,
            tts = binding.swTts.isChecked,
            sendData = binding.swSendData.isChecked
        )
        Ui.runIO { app.appPrefs.set(id, prefs) }
    }

    /** Applies an example permission set (v1.6.0 request #4). */
    private fun applyPreset(
        camera: Boolean = false,
        mic: Boolean = false,
        tts: Boolean = false,
        sendData: Boolean = false
    ) {
        suppressSave = true
        binding.swCamera.isChecked = camera
        binding.swMic.isChecked = mic
        binding.swTts.isChecked = tts
        binding.swSendData.isChecked = sendData
        suppressSave = false
        persist()
        Toast.makeText(this, R.string.app_preset_applied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_WAP_ID = "wap_id"
    }
}
