package com.gokberkeren.superentema

import android.content.Context

data class AccentPreset(val key: String, val label: String, val color: Int, val glowColor: Int)
data class ScalePreset(val key: String, val label: String, val factor: Float)
data class OpacityPreset(val key: String, val label: String, val bgColor: Int)

class TemaPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var accentKey: String
        get() = prefs.getString(KEY_ACCENT, PRESETS[0].key) ?: PRESETS[0].key
        set(value) = prefs.edit().putString(KEY_ACCENT, value).apply()

    var scaleKey: String
        get() = prefs.getString(KEY_SCALE, SCALES[1].key) ?: SCALES[1].key // Default: Büyük
        set(value) = prefs.edit().putString(KEY_SCALE, value).apply()

    var opacityKey: String
        get() = prefs.getString(KEY_OPACITY, OPACITIES[1].key) ?: OPACITIES[1].key // Default: Tok Koyu (%96)
        set(value) = prefs.edit().putString(KEY_OPACITY, value).apply()

    fun currentAccent(): AccentPreset = PRESETS.firstOrNull { it.key == accentKey } ?: PRESETS[0]
    fun currentScale(): ScalePreset = SCALES.firstOrNull { it.key == scaleKey } ?: SCALES[1]
    fun currentOpacity(): OpacityPreset = OPACITIES.firstOrNull { it.key == opacityKey } ?: OPACITIES[1]

    companion object {
        private const val PREFS_NAME = "superen_tema_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ACCENT = "accent_key"
        private const val KEY_SCALE = "scale_key"
        private const val KEY_OPACITY = "opacity_key"

        val PRESETS = listOf(
            AccentPreset("indigo", "İndigo", 0xFF6366F1.toInt(), 0xFF818CF8.toInt()),
            AccentPreset("blue", "Mavi", 0xFF0EA5E9.toInt(), 0xFF38BDF8.toInt()),
            AccentPreset("cyan", "Turkuaz", 0xFF06B6D4.toInt(), 0xFF22D3EE.toInt()),
            AccentPreset("emerald", "Zümrüt", 0xFF10B981.toInt(), 0xFF34D399.toInt()),
            AccentPreset("lime", "Fıstık", 0xFF65A30D.toInt(), 0xFF84CC16.toInt()),
            AccentPreset("gold", "Altın", 0xFFF59E0B.toInt(), 0xFFFBBF24.toInt()),
            AccentPreset("orange", "Turuncu", 0xFFF97316.toInt(), 0xFFFB923C.toInt()),
            AccentPreset("red", "Kırmızı", 0xFFEF4444.toInt(), 0xFFF87171.toInt()),
            AccentPreset("crimson", "Bordo", 0xFFBE123C.toInt(), 0xFFE11D48.toInt()),
            AccentPreset("pink", "Pembe", 0xFFEC4899.toInt(), 0xFFF472B6.toInt()),
            AccentPreset("purple", "Mor", 0xFFA855F7.toInt(), 0xFFC084FC.toInt()),
            AccentPreset("slate", "Grafit", 0xFF475569.toInt(), 0xFF64748B.toInt())
        )

        val SCALES = listOf(
            ScalePreset("normal", "Normal", 1.15f),
            ScalePreset("large", "Büyük", 1.32f),
            ScalePreset("xlarge", "Çok Büyük", 1.50f)
        )

        val OPACITIES = listOf(
            OpacityPreset("glass", "Yarı Saydam", 0xDB101420.toInt()), // ~86%
            OpacityPreset("rich", "Tok Koyu", 0xF5101420.toInt()),     // ~96% (default)
            OpacityPreset("solid", "Tam Mat", 0xFF0D111B.toInt())      // 100%
        )
    }
}
