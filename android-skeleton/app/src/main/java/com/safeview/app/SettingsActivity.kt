package com.safeview.app

import android.os.Bundle
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SettingsPrefs

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
        switchAi.isChecked = prefs.aiEnabled
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
        if (!modelReady) {
            // The submitted build does not bundle a model; do not present AI as active.
            prefs.aiEnabled = false
        }
        switchAi.isEnabled = modelReady
        switchAi.isChecked = modelReady && prefs.aiEnabled
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
        switchAi.setOnCheckedChangeListener { _, checked ->
            prefs.aiEnabled = checked
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
}
