package com.gokberkeren.fragmanizle

import android.content.Context
import android.content.SharedPreferences

class FragmanPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** false = in-app popup player (default), true = external YouTube / SmartTube app */
    var openExternal: Boolean
        get() = prefs.getBoolean(KEY_OPEN_EXTERNAL, false)
        set(value) = prefs.edit().putBoolean(KEY_OPEN_EXTERNAL, value).apply()

    /** true = search Turkish trailer first then fallback to original (default), false = original trailer first */
    var turkishFirst: Boolean
        get() = prefs.getBoolean(KEY_TURKISH_FIRST, true)
        set(value) = prefs.edit().putBoolean(KEY_TURKISH_FIRST, value).apply()

    companion object {
        private const val PREFS_NAME = "fragman_izle_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_OPEN_EXTERNAL = "open_external"
        private const val KEY_TURKISH_FIRST = "turkish_first"
    }
}
