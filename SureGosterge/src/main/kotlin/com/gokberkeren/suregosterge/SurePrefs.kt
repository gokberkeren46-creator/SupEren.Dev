package com.gokberkeren.suregosterge

import android.content.Context

class SurePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var textSizeSp: Float
        get() = prefs.getFloat(KEY_TEXT_SIZE, SIZE_MEDIUM)
        set(value) = prefs.edit().putFloat(KEY_TEXT_SIZE, value.coerceIn(10f, 18f)).apply()

    var alpha: Float
        get() = prefs.getFloat(KEY_ALPHA, ALPHA_MEDIUM)
        set(value) = prefs.edit().putFloat(KEY_ALPHA, value.coerceIn(0.25f, 1.0f)).apply()

    companion object {
        private const val PREFS_NAME = "sure_gosterge_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TEXT_SIZE = "text_size_sp"
        private const val KEY_ALPHA = "alpha"

        const val SIZE_SMALL = 11f
        const val SIZE_MEDIUM = 13f
        const val SIZE_LARGE = 15f

        const val ALPHA_LOW = 0.40f
        const val ALPHA_MEDIUM = 0.68f
        const val ALPHA_HIGH = 0.92f
    }
}
