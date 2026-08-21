package com.safeview.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Central settings store. Values are injected into the WebView content script
 * and used by the optional TFLite classifier.
 */
class SettingsPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) = prefs.edit { putBoolean(KEY_ENABLED, v) }

    var strict: Boolean
        get() = prefs.getBoolean(KEY_STRICT, true)
        set(v) = prefs.edit { putBoolean(KEY_STRICT, v) }

    var reveal: Boolean
        get() = prefs.getBoolean(KEY_REVEAL, false)
        set(v) = prefs.edit { putBoolean(KEY_REVEAL, v) }

    var aiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI, true)
        set(v) = prefs.edit { putBoolean(KEY_AI, v) }

    var displayMode: String
        get() = prefs.getString(KEY_DISPLAY, "blur")
            ?.takeIf { it == "blur" || it == "placeholder" } ?: "blur"
        set(v) = prefs.edit { putString(KEY_DISPLAY, if (v == "placeholder") "placeholder" else "blur") }

    var explicitThreshold: Float
        get() = prefs.getFloat(KEY_EXPLICIT, 0.40f).coerceIn(0.10f, 0.90f)
        set(v) = prefs.edit { putFloat(KEY_EXPLICIT, v.coerceIn(0.10f, 0.90f)) }

    var revealingThreshold: Float
        get() = prefs.getFloat(KEY_REVEALING, 0.12f).coerceIn(0.05f, 0.50f)
        set(v) = prefs.edit { putFloat(KEY_REVEALING, v.coerceIn(0.05f, 0.50f)) }

    /** Valid JSON injected into the page as window.SafeViewNativeSettings. */
    fun toJsObject(): String = JSONObject()
        .put("enabled", enabled)
        .put("strict", strict)
        .put("reveal", reveal)
        .put("aiEnabled", aiEnabled)
        .put("displayMode", displayMode)
        .put("aiThreshold", explicitThreshold.toDouble())
        .put("revealingThreshold", revealingThreshold.toDouble())
        .toString()

    companion object {
        private const val PREFS_NAME = "safeview_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_STRICT = "strict"
        private const val KEY_REVEAL = "reveal"
        private const val KEY_AI = "ai_enabled"
        private const val KEY_DISPLAY = "display_mode"
        private const val KEY_EXPLICIT = "explicit_threshold"
        private const val KEY_REVEALING = "revealing_threshold"
    }
}
