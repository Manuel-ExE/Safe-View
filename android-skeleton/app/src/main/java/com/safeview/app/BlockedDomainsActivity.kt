package com.safeview.app

import android.os.Bundle
import android.app.AlertDialog
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Parent-editable list of domains blocked by the background DNS VPN.
 * One domain per line. Stored in SettingsPrefs.
 */
class BlockedDomainsActivity : AppCompatActivity() {
    private lateinit var prefs: SettingsPrefs
    private lateinit var editor: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SettingsPrefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = getString(R.string.blocked_domains_title)
            textSize = 24f
            setTextColor(getColor(R.color.sv_text))
        }
        root.addView(title, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val summary = TextView(this).apply {
            text = getString(R.string.blocked_domains_summary)
            textSize = 14f
            setTextColor(getColor(R.color.sv_muted))
            setPadding(0, 12, 0, 16)
        }
        root.addView(summary, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        editor = EditText(this).apply {
            setText(prefs.blockedDomains.sorted().joinToString("\n"))
            minLines = 12
            gravity = android.view.Gravity.TOP
            setTextColor(getColor(R.color.sv_text))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(editor, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val save = Button(this).apply {
            text = getString(R.string.blocked_domains_save)
            setOnClickListener {
                val domains = editor.text.toString()
                    .lines()
                    .map { it.trim().lowercase().removePrefix("www.") }
                    .filter { it.isNotEmpty() }
                    .filter(::isValidHostname)
                    .toSet()
                val invalidCount = editor.text.toString().lines().count { line ->
                    val value = line.trim().lowercase().removePrefix("www.")
                    value.isNotEmpty() && !isValidHostname(value)
                }
                if (invalidCount > 0) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.blocked_domains_invalid_title)
                        .setMessage(getString(R.string.blocked_domains_invalid_message, invalidCount))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@setOnClickListener
                }
                if (domains.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.blocked_domains_empty_title)
                        .setMessage(R.string.blocked_domains_empty_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.blocked_domains_save_empty) { _, _ ->
                            prefs.blockedDomains = emptySet()
                            finish()
                        }
                        .show()
                    return@setOnClickListener
                }
                prefs.blockedDomains = domains
                finish()
            }
        }
        root.addView(save, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val reset = Button(this).apply {
            text = getString(R.string.blocked_domains_reset)
            setOnClickListener {
                prefs.blockedDomains = SettingsPrefs.DEFAULT_BLOCKED_DOMAINS
                editor.setText(SettingsPrefs.DEFAULT_BLOCKED_DOMAINS.sorted().joinToString("\n"))
            }
        }
        root.addView(reset, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }

    private fun isValidHostname(value: String): Boolean {
        if (value.length > 253 || value.startsWith('.') || value.endsWith('.') || value.contains("..")) return false
        return value.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }
}
