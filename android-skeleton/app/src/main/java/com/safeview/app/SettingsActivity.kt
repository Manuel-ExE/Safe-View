package com.safeview.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import androidx.appcompat.app.AlertDialog
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SettingsPrefs
    private lateinit var backgroundSwitch: MaterialSwitch
    private lateinit var backgroundStatus: TextView
    private lateinit var screenAiSwitch: MaterialSwitch
    private lateinit var screenAiStatus: TextView
    private lateinit var setupStatus: TextView
    private lateinit var dashboardStats: TextView
    private var waitingForOverlayPermission = false

    private val backgroundPrefs by lazy {
        getSharedPreferences("safeview_background", MODE_PRIVATE)
    }

    private val screenAiStatePrefs by lazy {
        getSharedPreferences("safeview_screen_ai", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = SettingsPrefs(this)

        findViewById<ImageButton>(R.id.btnBackSettings).setOnClickListener {
            finish()
        }

        val switchEnabled = findViewById<MaterialSwitch>(R.id.switchEnabled)
        val switchStrict = findViewById<MaterialSwitch>(R.id.switchStrict)
        val switchReveal = findViewById<MaterialSwitch>(R.id.switchReveal)
        val switchAi = findViewById<MaterialSwitch>(R.id.switchAi)
        val switchScreenAiWarning = findViewById<MaterialSwitch>(R.id.switchScreenAiWarning)
        backgroundSwitch = findViewById(R.id.switchBackground)
        backgroundStatus = findViewById(R.id.backgroundStatus)
        screenAiSwitch = findViewById(R.id.switchScreenAi)
        screenAiStatus = findViewById(R.id.screenAiStatus)
        setupStatus = findViewById(R.id.setupStatus)
        dashboardStats = findViewById(R.id.dashboardStats)
        setupStatus.setOnClickListener { runNextSetupStep() }
        val appRulesButton = findViewById<TextView>(R.id.appRulesButton)
        val appRulesSummary = findViewById<TextView>(R.id.appRulesSummary)
        val sliderExplicit = findViewById<Slider>(R.id.sliderExplicit)
        val sliderRevealing = findViewById<Slider>(R.id.sliderRevealing)
        val radioDisplay = findViewById<RadioGroup>(R.id.radioDisplay)
        val radioBlur = findViewById<RadioButton>(R.id.radioBlur)
        val radioPlaceholder = findViewById<RadioButton>(R.id.radioPlaceholder)
        val aiStatus = findViewById<TextView>(R.id.aiStatus)

        // Load current values
        switchEnabled.isChecked = prefs.enabled
        switchStrict.isChecked = prefs.strict
        switchReveal.isChecked = prefs.reveal
        backgroundSwitch.isChecked = backgroundPrefs.getBoolean("enabled", false)
        updateBackgroundStatus()
        screenAiSwitch.isChecked = false
        updateScreenAiStatus(false)
        switchAi.isChecked = prefs.aiEnabled
        switchScreenAiWarning.isChecked = prefs.screenAiWarningMode
        sliderExplicit.value = prefs.explicitThreshold
        sliderRevealing.value = prefs.revealingThreshold
        if (prefs.displayMode == "placeholder") {
            radioPlaceholder.isChecked = true
        } else {
            radioBlur.isChecked = true
        }

        // AI status from MainActivity static / application state
        val modelReady = (application as? SafeViewApp)?.let {
            it.aiPipelineAvailable && it.classifier.isReady
        } == true
        screenAiSwitch.isEnabled = modelReady
        switchScreenAiWarning.isEnabled = modelReady
        val captureState = screenAiStatePrefs.getString(
            SafeViewScreenAiService.CAPTURE_STATE_KEY,
            SafeViewScreenAiService.STATE_STOPPED
        ) ?: SafeViewScreenAiService.STATE_STOPPED
        screenAiSwitch.isChecked = modelReady && prefs.screenAiEnabled &&
            (captureState == SafeViewScreenAiService.STATE_ACTIVE ||
                captureState == SafeViewScreenAiService.STATE_STARTING)
        updateScreenAiStatus(modelReady && prefs.screenAiEnabled, captureState)
        updateAppRulesSummary(appRulesSummary)
        updateSetupStatus(modelReady)
        if (!modelReady) {
            // The submitted build does not bundle a model; do not present AI as active.
            prefs.aiEnabled = false
        }
        switchAi.isEnabled = modelReady
        switchAi.isChecked = modelReady && prefs.aiEnabled
        switchScreenAiWarning.isChecked = modelReady && prefs.screenAiWarningMode
        aiStatus.text = if (modelReady) {
            getString(R.string.ai_status_ready)
        } else {
            getString(R.string.ai_status_missing)
        }
        aiStatus.setTextColor(
            getColor(if (modelReady) R.color.sv_ok else R.color.sv_muted)
        )

        // Listeners
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.enabled = checked
        }
        switchStrict.setOnCheckedChangeListener { _, checked ->
            prefs.strict = checked
        }
        switchReveal.setOnCheckedChangeListener { _, checked ->
            prefs.reveal = checked
        }
        backgroundSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) requestBackgroundProtection()
            else {
                backgroundPrefs.edit().putBoolean("enabled", false).apply()
                stopService(Intent(this, SafeViewVpnService::class.java))
                updateBackgroundStatus()
            }
        }
        screenAiSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) requestScreenAi() else stopScreenAi()
        }
        appRulesButton.setOnClickListener {
            startActivity(Intent(this, AppRulesActivity::class.java))
        }
        // Tap the background status text to edit the blocked-domain list
        backgroundStatus.setOnClickListener {
            startActivity(Intent(this, BlockedDomainsActivity::class.java))
        }
        switchAi.setOnCheckedChangeListener { _, checked ->
            prefs.aiEnabled = checked
        }
        switchScreenAiWarning.setOnCheckedChangeListener { _, checked ->
            prefs.screenAiWarningMode = checked
        }
        sliderExplicit.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.explicitThreshold = value
        }
        sliderRevealing.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.revealingThreshold = value
        }
        radioDisplay.setOnCheckedChangeListener { _, checkedId ->
            prefs.displayMode = if (checkedId == R.id.radioPlaceholder) "placeholder" else "blur"
        }
    }

    private fun requestBackgroundProtection() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, VPN_PERMISSION_REQUEST)
        } else {
            startBackgroundProtection()
        }
    }

    private fun startBackgroundProtection() {
        backgroundPrefs.edit().putBoolean("enabled", true).apply()
        ContextCompat.startForegroundService(
            this,
            Intent(this, SafeViewVpnService::class.java)
        )
        updateBackgroundStatus()
    }

    private fun requestScreenAi() {
        val modelReady = (application as? SafeViewApp)?.classifier?.isReady == true
        if (!modelReady) {
            screenAiSwitch.isChecked = false
            updateScreenAiStatus(false)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.screen_ai_consent_title)
            .setMessage(R.string.screen_ai_consent_message)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                screenAiSwitch.isChecked = false
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (!Settings.canDrawOverlays(this)) {
                    waitingForOverlayPermission = true
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    screenAiStatus.text = getString(R.string.screen_ai_status_permission)
                } else {
                    requestScreenCapture()
                }
            }
            .setOnCancelListener { screenAiSwitch.isChecked = false }
            .show()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_AI_PERMISSION_REQUEST)
    }

    private fun startScreenAi(resultCode: Int, data: Intent) {
        prefs.screenAiEnabled = true
        ContextCompat.startForegroundService(
            this,
            Intent(this, SafeViewScreenAiService::class.java)
                .putExtra(SafeViewScreenAiService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(SafeViewScreenAiService.EXTRA_RESULT_DATA, data)
        )
        screenAiSwitch.isChecked = true
        updateScreenAiStatus(true, SafeViewScreenAiService.STATE_STARTING)
    }

    private fun stopScreenAi() {
        prefs.screenAiEnabled = false
        stopService(Intent(this, SafeViewScreenAiService::class.java).setAction(SafeViewScreenAiService.ACTION_STOP))
        updateScreenAiStatus(false, SafeViewScreenAiService.STATE_STOPPED)
    }

    private fun updateScreenAiStatus(active: Boolean, state: String = SafeViewScreenAiService.STATE_STOPPED) {
        if (!::screenAiStatus.isInitialized) return
        screenAiStatus.text = when {
            active && state == SafeViewScreenAiService.STATE_PAUSED -> getString(R.string.screen_ai_status_paused)
            active && state == SafeViewScreenAiService.STATE_STARTING -> getString(R.string.screen_ai_status_starting)
            active && state == SafeViewScreenAiService.STATE_ACTIVE -> getString(R.string.screen_ai_status_on)
            else -> getString(R.string.screen_ai_status_off)
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false
            if (Settings.canDrawOverlays(this)) requestScreenCapture()
            else {
                screenAiSwitch.isChecked = false
                updateScreenAiStatus(false, SafeViewScreenAiService.STATE_STOPPED)
            }
        }
        val modelReady = (application as? SafeViewApp)?.let {
            it.aiPipelineAvailable && it.classifier.isReady
        } == true
        val state = screenAiStatePrefs.getString(
            SafeViewScreenAiService.CAPTURE_STATE_KEY,
            SafeViewScreenAiService.STATE_STOPPED
        ) ?: SafeViewScreenAiService.STATE_STOPPED
        val enabled = modelReady && prefs.screenAiEnabled
        screenAiSwitch.isChecked = enabled && (state == SafeViewScreenAiService.STATE_ACTIVE || state == SafeViewScreenAiService.STATE_STARTING)
        updateScreenAiStatus(enabled, state)
        findViewById<TextView?>(R.id.appRulesSummary)?.let { updateAppRulesSummary(it) }
        updateSetupStatus(modelReady)
    }

    private fun updateAppRulesSummary(view: TextView) {
        val count = prefs.protectedApps.size
        view.text = if (count == 0) {
            getString(R.string.app_rules_none)
        } else {
            getString(R.string.app_rules_selected, count)
        }
    }

    private fun runNextSetupStep() {
        val vpnActive = getSharedPreferences("safeview_vpn_status", MODE_PRIVATE)
            .getBoolean(SafeViewVpnService.KEY_ACTIVE, false)
        if (VpnService.prepare(this) != null || !vpnActive) {
            requestBackgroundProtection()
            return
        }
        val modelReady = (application as? SafeViewApp)?.let {
            it.aiPipelineAvailable && it.classifier.isReady
        } == true
        if (modelReady && !Settings.canDrawOverlays(this)) {
            requestScreenAi()
            return
        }
        if (modelReady && !prefs.screenAiEnabled) {
            requestScreenAi()
            return
        }
        startActivity(Intent(this, AppRulesActivity::class.java))
    }

    private fun updateSetupStatus(modelReady: Boolean) {
        if (!::setupStatus.isInitialized) return
        val vpnConsentReady = VpnService.prepare(this) == null
        val vpnActive = getSharedPreferences("safeview_vpn_status", MODE_PRIVATE)
            .getBoolean(SafeViewVpnService.KEY_ACTIVE, false)
        val vpnReady = vpnConsentReady && vpnActive
        val overlayReady = Settings.canDrawOverlays(this)
        val captureState = screenAiStatePrefs.getString(
            SafeViewScreenAiService.CAPTURE_STATE_KEY,
            SafeViewScreenAiService.STATE_STOPPED
        ) ?: SafeViewScreenAiService.STATE_STOPPED
        val captureReady = captureState == SafeViewScreenAiService.STATE_ACTIVE
        val appSelection = if (prefs.protectedApps.isEmpty()) "all apps" else "${prefs.protectedApps.size} selected apps"
        setupStatus.text = "Strict mode: ${if (prefs.strict) "ON" else "OFF"}\n" +
            "VPN filter: ${if (vpnReady) "active" else if (vpnConsentReady) "not running" else "permission required"}\n" +
            "AI model: ${if (modelReady) "ready" else "missing"}\n" +
            "Screen capture: ${if (captureReady) "running" else "not running"}\n" +
            "Overlay access: ${if (overlayReady) "granted" else "required"}\n" +
            "Protected apps: $appSelection"
        setupStatus.setTextColor(getColor(if (vpnReady && modelReady && overlayReady) R.color.sv_ok else R.color.sv_muted))
        val counts = SafeViewMediaDatabase(this).counts()
        dashboardStats.text = "${counts["media"] ?: 0} media scanned · ${counts["events"] ?: 0} events · ${counts["blocked"] ?: 0} blocked"
    }

    private fun updateBackgroundStatus() {
        if (!::backgroundStatus.isInitialized) return
        backgroundStatus.text = if (backgroundPrefs.getBoolean("enabled", false)) {
            getString(R.string.background_status_on)
        } else {
            getString(R.string.background_status_off)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST) {
            if (resultCode == Activity.RESULT_OK) {
                startBackgroundProtection()
            } else {
                backgroundPrefs.edit().putBoolean("enabled", false).apply()
                backgroundSwitch.isChecked = false
                backgroundStatus.text = getString(R.string.background_status_permission)
            }
            return
        }
        if (requestCode == SCREEN_AI_PERMISSION_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startScreenAi(resultCode, data)
            } else {
                screenAiSwitch.isChecked = false
                prefs.screenAiEnabled = false
                updateScreenAiStatus(false, SafeViewScreenAiService.STATE_STOPPED)
            }
        }
    }

    companion object {
        private const val VPN_PERMISSION_REQUEST = 7102
        private const val SCREEN_AI_PERMISSION_REQUEST = 7103
    }
}
