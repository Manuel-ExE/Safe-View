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
 * Parent-editable list of Strict-mode heuristic terms checked against page
 * URL path/query/fragment text. One term per line. Stored in SettingsPrefs.
 *
 * The built-in default list is English-only; this screen exists so the
 * heuristic layer can be extended to other languages a family uses, since
 * neither the domain blocklist nor (by default) the AI classifier cover
 * arbitrary non-English search terms.
 */
class BlockedTermsActivity : AppCompatActivity() {
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
            text = getString(R.string.blocked_terms_title)
            textSize = 24f
            setTextColor(getColor(R.color.sv_text))
        }
        root.addView(title, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val summary = TextView(this).apply {
            text = getString(R.string.blocked_terms_summary)
            textSize = 14f
            setTextColor(getColor(R.color.sv_muted))
            setPadding(0, 12, 0, 16)
        }
        root.addView(summary, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        editor = EditText(this).apply {
            setText(prefs.blockedSearchTerms.sorted().joinToString("\n"))
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
            text = getString(R.string.blocked_terms_save)
            setOnClickListener {
                val terms = editor.text.toString()
                    .lines()
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                if (terms.isEmpty()) {
                    AlertDialog.Builder(this@BlockedTermsActivity)
                        .setTitle(R.string.blocked_terms_empty_title)
                        .setMessage(R.string.blocked_terms_empty_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.blocked_terms_save_empty) { _, _ ->
                            prefs.blockedSearchTerms = emptySet()
                            finish()
                        }
                        .show()
                    return@setOnClickListener
                }
                prefs.blockedSearchTerms = terms
                finish()
            }
        }
        root.addView(save, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val reset = Button(this).apply {
            text = getString(R.string.blocked_terms_reset)
            setOnClickListener {
                prefs.blockedSearchTerms = SettingsPrefs.DEFAULT_STRICT_TERMS
                editor.setText(SettingsPrefs.DEFAULT_STRICT_TERMS.sorted().joinToString("\n"))
            }
        }
        root.addView(reset, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }
}
