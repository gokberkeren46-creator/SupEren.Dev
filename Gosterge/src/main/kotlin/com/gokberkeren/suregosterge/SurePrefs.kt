package com.gokberkeren.suregosterge

import android.content.Context

class SurePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var textSizeSp: Float
        get() = prefs.getFloat(KEY_TEXT_SIZE, SIZE_SMALL)
        set(value) = prefs.edit().putFloat(KEY_TEXT_SIZE, value.coerceIn(7.5f, 16f)).apply()

    var alpha: Float
        get() = prefs.getFloat(KEY_ALPHA, ALPHA_40)
        set(value) = prefs.edit().putFloat(KEY_ALPHA, value.coerceIn(0.10f, 0.70f)).apply()

    companion object {
        private const val PREFS_NAME = "sure_gosterge_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TEXT_SIZE = "text_size_sp"
        private const val KEY_ALPHA = "alpha"

        const val SIZE_XS = 8.5f
        const val SIZE_SMALL = 10.5f
        const val SIZE_MEDIUM = 12.5f
        const val SIZE_LARGE = 14.5f

        const val ALPHA_10 = 0.10f
        const val ALPHA_20 = 0.20f
        const val ALPHA_40 = 0.40f
        const val ALPHA_65 = 0.65f
    }
}
