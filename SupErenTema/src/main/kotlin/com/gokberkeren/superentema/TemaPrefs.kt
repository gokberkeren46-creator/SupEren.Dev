package com.gokberkeren.superentema

import android.content.Context

data class AccentPreset(val key: String, val label: String, val color: Int, val glowColor: Int)

class TemaPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var accentKey: String
        get() = prefs.getString(KEY_ACCENT, PRESETS[0].key) ?: PRESETS[0].key
        set(value) = prefs.edit().putString(KEY_ACCENT, value).apply()

    fun currentAccent(): AccentPreset = PRESETS.firstOrNull { it.key == accentKey } ?: PRESETS[0]

    companion object {
        private const val PREFS_NAME = "superen_tema_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ACCENT = "accent_key"

        val PRESETS = listOf(
            AccentPreset("indigo", "İndigo", 0xFF6366F1.toInt(), 0xFF818CF8.toInt()),
            AccentPreset("blue", "Mavi", 0xFF0EA5E9.toInt(), 0xFF38BDF8.toInt()),
            AccentPreset("emerald", "Zümrüt", 0xFF10B981.toInt(), 0xFF34D399.toInt()),
            AccentPreset("red", "Kırmızı", 0xFFEF4444.toInt(), 0xFFF87171.toInt()),
            AccentPreset("purple", "Mor", 0xFFA855F7.toInt(), 0xFFC084FC.toInt()),
            AccentPreset("gold", "Altın", 0xFFF59E0B.toInt(), 0xFFFBBF24.toInt())
        )
    }
}
