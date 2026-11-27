package com.gokberkeren.introatla

import android.content.Context
import android.content.SharedPreferences

class IntroPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var fallbackSeconds: Int
        get() = prefs.getInt(KEY_FALLBACK_SEC, DEFAULT_FALLBACK_SEC).coerceIn(15, 180)
        set(value) = prefs.edit().putInt(KEY_FALLBACK_SEC, value.coerceIn(15, 180)).apply()

    var alpha: Float
        get() = prefs.getFloat(KEY_ALPHA, DEFAULT_ALPHA).coerceIn(0.4f, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ALPHA, value.coerceIn(0.4f, 1.0f)).apply()

    companion object {
        private const val PREFS_NAME = "intro_atla_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FALLBACK_SEC = "fallback_sec"
        private const val KEY_ALPHA = "alpha"

        const val DEFAULT_FALLBACK_SEC = 85
        const val DEFAULT_ALPHA = 0.92f

        val FALLBACK_OPTIONS = listOf(60, 75, 85, 90, 105)
        val ALPHA_OPTIONS = listOf(
            "%70" to 0.70f,
            "%85" to 0.85f,
            "%100" to 1.00f
        )
    }
}
