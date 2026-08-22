package com.safeview.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Parent-controlled package selection for Screen AI.
 * Empty set = protect all apps (safer default).
 * No app content, messages, or URLs are ever read.
 */
class AppRulesActivity : AppCompatActivity() {
    private lateinit var prefs: SettingsPrefs
    private val switches = linkedMapOf<String, MaterialSwitch>()
    private lateinit var protectAllSwitch: MaterialSwitch
    private lateinit var usageStatus: TextView
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SettingsPrefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_rules_title)
            textSize = 24f
            setTextColor(getColor(R.color.sv_text))
        }
        root.addView(title, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val summary = TextView(this).apply {
            text = getString(R.string.app_rules_summary)
            textSize = 14f
            setTextColor(getColor(R.color.sv_muted))
            setPadding(0, 12, 0, 16)
        }
        root.addView(summary, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        // Usage Access status + button
        usageStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        root.addView(usageStatus, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val usageButton = Button(this).apply {
            text = getString(R.string.app_rules_usage_access_btn)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        root.addView(usageButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        // Master "Protect all apps" toggle
        val selected = prefs.protectedApps
        protectAllSwitch = MaterialSwitch(this).apply {
            text = getString(R.string.app_rules_protect_all)
            isChecked = selected.isEmpty()
            setOnCheckedChangeListener { _, checked ->
                listContainer.visibility = if (checked) android.view.View.GONE else android.view.View.VISIBLE
                if (checked) {
                    // Turning on "protect all" visually selects everything but we store empty set
                    switches.values.forEach { it.isChecked = true }
                }
            }
        }
        root.addView(protectAllSwitch, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val hint = TextView(this).apply {
            text = getString(R.string.app_rules_protect_all_hint)
            textSize = 12f
            setTextColor(getColor(R.color.sv_muted))
            setPadding(0, 4, 0, 16)
        }
        root.addView(hint, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (selected.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }

        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app ->
                (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                    packageManager.getLaunchIntentForPackage(app.packageName) != null &&
                    app.packageName != packageName // never list ourselves
            }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }

        if (apps.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = getString(R.string.app_rules_no_apps)
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 24)
            })
        } else {
            apps.forEach { app ->
                val pkg = app.packageName
                val item = MaterialSwitch(this).apply {
                    text = packageManager.getApplicationLabel(app).toString()
                    contentDescription = pkg
                    isChecked = selected.isEmpty() || pkg in selected
                }
                switches[pkg] = item
                listContainer.addView(item, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        }
        root.addView(listContainer, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val save = Button(this).apply {
            text = getString(R.string.app_rules_save)
            setOnClickListener { saveAndFinish() }
        }
        root.addView(save, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        setContentView(ScrollView(this).apply { addView(root) })
        updateUsageStatus()
    }

    override fun onResume() {
        super.onResume()
        updateUsageStatus()
    }

    private fun updateUsageStatus() {
        val granted = hasUsageAccess()
        usageStatus.text = if (granted) {
            getString(R.string.app_rules_usage_granted)
        } else {
            getString(R.string.app_rules_usage_needed)
        }
        usageStatus.setTextColor(
            getColor(if (granted) R.color.sv_ok else R.color.sv_muted)
        )
    }

    private fun saveAndFinish() {
        if (!protectAllSwitch.isChecked && !hasUsageAccess()) {
            AlertDialog.Builder(this)
                .setTitle("Usage Access required")
                .setMessage("To protect only selected apps, SafeView must know which app is currently in the foreground. Android grants this separately from normal app permissions. Grant Usage Access, then return here and save again.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .show()
            return
        }
        if (protectAllSwitch.isChecked) {
            // Empty set = protect all (documented safer default)
            prefs.protectedApps = emptySet()
        } else {
            val protected = switches.filterValues { it.isChecked }.keys
            // If parent somehow unchecks everything, still fall back to protect-all
            prefs.protectedApps = if (protected.isEmpty()) emptySet() else protected
        }
        finish()
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }
}
