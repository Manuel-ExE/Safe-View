package com.safeview.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Central settings store. Values are injected into the WebView content script
 * and used by the optional TFLite classifier and background DNS filter.
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

    var screenAiEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_AI, false)
        set(v) = prefs.edit { putBoolean(KEY_SCREEN_AI, v) }

    var screenAiWarningMode: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_AI_WARNING, false)
        set(v) = prefs.edit { putBoolean(KEY_SCREEN_AI_WARNING, v) }

    var protectedApps: Set<String>
        get() = prefs.getStringSet(KEY_PROTECTED_APPS, emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit { putStringSet(KEY_PROTECTED_APPS, v.toSet()) }

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

    /**
     * Domains blocked by the background DNS VPN.
     * Defaults to a built-in adult-site list when the preference has never been set.
     */
    var blockedDomains: Set<String>
        get() {
            val stored = prefs.getStringSet(KEY_BLOCKED_DOMAINS, null)
            return if (stored == null) DEFAULT_BLOCKED_DOMAINS else stored.toSet()
        }
        set(v) = prefs.edit { putStringSet(KEY_BLOCKED_DOMAINS, v.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.toSet()) }

    /** Valid JSON injected into the page as window.SafeViewNativeSettings. */
    fun toJsObject(): String = JSONObject()
        .put("enabled", enabled)
        .put("strict", strict)
        .put("reveal", reveal)
        .put("aiEnabled", aiEnabled)
        .put("screenAiEnabled", screenAiEnabled)
        .put("screenAiWarningMode", screenAiWarningMode)
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
        private const val KEY_SCREEN_AI = "screen_ai_enabled"
        private const val KEY_SCREEN_AI_WARNING = "screen_ai_warning_mode"
        private const val KEY_PROTECTED_APPS = "protected_apps"
        private const val KEY_DISPLAY = "display_mode"
        private const val KEY_EXPLICIT = "explicit_threshold"
        private const val KEY_REVEALING = "revealing_threshold"
        private const val KEY_BLOCKED_DOMAINS = "blocked_domains"

        val DEFAULT_BLOCKED_DOMAINS: Set<String> = setOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "redtube.com", "youporn.com",
            "xhamster.com", "spankbang.com", "onlyfans.com", "brazzers.com", "chaturbate.com",
            "pornhd.com", "tube8.com", "beeg.com", "porn.com", "xvideos.es"
        )
    }
}
