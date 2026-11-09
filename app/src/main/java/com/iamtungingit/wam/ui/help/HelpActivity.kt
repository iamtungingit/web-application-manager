package com.iamtungingit.wam.ui.help

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.iamtungingit.wam.R
import com.iamtungingit.wam.databinding.ActivityHelpBinding
import com.iamtungingit.wam.ui.common.Ui

/**
 * In-app documentation (user request: "could we add better docs"). Ten sections
 * rendered from string arrays — quick start, editing, packaging, widgets/DPI,
 * per-app network, and the troubleshooting entries for the reported issues
 * (black screen, packaging errors, widget sizing). No WebView, no network: the
 * guide must always open, even when the WebView engine is broken.
 */
class HelpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ui.applyInsets(binding.helpRoot)
        binding.helpToolbar.setTitle(R.string.guide_title)
        binding.helpToolbar.setNavigationOnClickListener { finish() }

        val titles = resources.getStringArray(R.array.guide_titles)
        val bodies = resources.getStringArray(R.array.guide_bodies)

        val container = binding.helpContainer
        val primary = ContextCompat.getColor(this, R.color.wam_primary)
        for (i in titles.indices) {
            container.addView(sectionTitle(titles[i], primary))
            container.addView(sectionBody(bodies.getOrElse(i) { "" }))
        }
    }

    private fun sectionTitle(text: String, color: Int): View =
        TextView(this).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTextColor(color)
            setPadding(0, dp(20), 0, dp(4))
        }

    private fun sectionBody(text: String): View =
        TextView(this).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, 0, 0, dp(2))
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
