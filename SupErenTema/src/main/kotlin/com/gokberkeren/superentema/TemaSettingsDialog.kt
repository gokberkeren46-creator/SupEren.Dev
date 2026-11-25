package com.gokberkeren.superentema

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/**
 * Compact dark settings dialog for SupEren v2.0 (size, opacity, accent color on both Tablet & TV).
 */
object TemaSettingsDialog {

    private const val COLOR_BG = 0xFF121622.toInt()
    private const val COLOR_CARD = 0xFF1B2234.toInt()
    private const val COLOR_STROKE = 0xFF2A344D.toInt()
    private const val COLOR_PRIMARY = 0xFF6366F1.toInt()
    private const val COLOR_PRIMARY_FOCUS = 0xFF818CF8.toInt()
    private const val COLOR_ONLINE = 0xFF22C55E.toInt()
    private const val COLOR_MUTED = 0xFF252E44.toInt()
    private const val COLOR_MUTED_FOCUS = 0xFF364261.toInt()
    private const val COLOR_TEXT_PRIMARY = 0xFFF8FAFC.toInt()
    private const val COLOR_TEXT_SECONDARY = 0xFF94A3B8.toInt()

    fun show(context: Context, prefs: TemaPrefs, isTv: Boolean, onChanged: () -> Unit) {
        val dm = context.resources.displayMetrics
        val d = dm.density
        fun dp(v: Int): Int = (v * d).toInt()

        fun cardDrawable(bg: Int = COLOR_CARD, stroke: Int = COLOR_STROKE, radiusDp: Int = 12): GradientDrawable {
            return GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(radiusDp).toFloat()
                setStroke(dp(1), stroke)
            }
        }

        fun focusableBtn(normalColor: Int, focusedColor: Int, selectedBorder: Boolean = false): StateListDrawable {
            val normal = GradientDrawable().apply {
                setColor(normalColor)
                cornerRadius = dp(10).toFloat()
                if (selectedBorder) setStroke(dp(2), Color.WHITE)
            }
            val focused = GradientDrawable().apply {
                setColor(focusedColor)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(2), Color.WHITE)
            }
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(android.R.attr.state_pressed), focused)
                addState(intArrayOf(), normal)
            }
        }

        fun chipButton(label: String): Button {
            return Button(context).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(4), dp(6), dp(4), dp(6))
                minHeight = dp(34)
                minimumHeight = dp(34)
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(COLOR_BG, COLOR_STROKE, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val title = TextView(context).apply {
            text = "SupEren v2.0"
            textSize = 17f
            setTextColor(COLOR_TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = TextView(context).apply {
            text = if (isTv) {
                "SupEren.Dev • TV Otomatik Alt Menü (◀ / ▼ / Geri) & Vurgu Rengi"
            } else {
                "SupEren.Dev • Yüzen Alt Menü & Vurgu Rengi"
            }
            textSize = 10f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(10))
        }

        val toggleBtn = Button(context).apply {
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dp(38)
            minimumHeight = dp(38)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        fun refreshToggle() {
            if (prefs.enabled) {
                toggleBtn.text = "● Tema & Alt Menü Açık"
                toggleBtn.background = focusableBtn(COLOR_ONLINE, 0xFF4ADE80.toInt())
            } else {
                toggleBtn.text = "○ Tema Kapalı (Açmak için bas)"
                toggleBtn.background = focusableBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            }
        }
        refreshToggle()
        toggleBtn.setOnClickListener {
            prefs.enabled = !prefs.enabled
            refreshToggle()
            onChanged()
        }

        // 1. Scale card (available on both Tablet & TV!)
        val scaleCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        val scaleLabel = TextView(context).apply {
            text = "MENÜ BOYUTU"
            textSize = 10f
            setTextColor(COLOR_TEXT_SECONDARY)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        val scaleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val scaleBtns = TemaPrefs.SCALES.map { it to chipButton(it.label) }
        fun refreshScales() {
            scaleBtns.forEach { (preset, btn) ->
                val sel = preset.key == prefs.scaleKey
                btn.background = if (sel) focusableBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS, selectedBorder = true)
                else focusableBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            }
        }
        scaleBtns.forEach { (preset, btn) ->
            btn.setOnClickListener {
                prefs.scaleKey = preset.key
                refreshScales()
                onChanged()
            }
            scaleRow.addView(btn)
        }
        refreshScales()
        scaleCard.addView(scaleLabel)
        scaleCard.addView(scaleRow)

        // 2. Opacity card (available on both Tablet & TV!)
        val opacityCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        val opacityLabel = TextView(context).apply {
            text = "ARKA PLAN KOYULUĞU"
            textSize = 10f
            setTextColor(COLOR_TEXT_SECONDARY)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        val opacityRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val opacityBtns = TemaPrefs.OPACITIES.map { it to chipButton(it.label) }
        fun refreshOpacities() {
            opacityBtns.forEach { (preset, btn) ->
                val sel = preset.key == prefs.opacityKey
                btn.background = if (sel) focusableBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS, selectedBorder = true)
                else focusableBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            }
        }
        opacityBtns.forEach { (preset, btn) ->
            btn.setOnClickListener {
                prefs.opacityKey = preset.key
                refreshOpacities()
                onChanged()
            }
            opacityRow.addView(btn)
        }
        refreshOpacities()
        opacityCard.addView(opacityLabel)
        opacityCard.addView(opacityRow)

        // 3. Accent color picker
        val colorCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
        val colorLabel = TextView(context).apply {
            text = "VURGU RENGİ"
            textSize = 10f
            setTextColor(COLOR_TEXT_SECONDARY)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        colorCard.addView(colorLabel)

        val colorButtons = mutableListOf<Pair<AccentPreset, Button>>()

        fun refreshColors() {
            val activeKey = prefs.accentKey
            colorButtons.forEach { (preset, btn) ->
                val isSelected = preset.key == activeKey
                btn.background = focusableBtn(preset.color, preset.glowColor, selectedBorder = isSelected)
                btn.text = if (isSelected) "✓ ${preset.label}" else preset.label
            }
        }

        TemaPrefs.PRESETS.chunked(3).forEachIndexed { rowIdx, rowPresets ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rowIdx > 0) topMargin = dp(6)
                }
            }
            rowPresets.forEach { preset ->
                val btn = Button(context).apply {
                    isAllCaps = false
                    textSize = 11.5f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    minHeight = dp(34)
                    minimumHeight = dp(34)
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(3)
                        marginEnd = dp(3)
                    }
                    setOnClickListener {
                        prefs.accentKey = preset.key
                        refreshColors()
                        onChanged()
                    }
                }
                colorButtons.add(preset to btn)
                row.addView(btn)
            }
            colorCard.addView(row)
        }
        refreshColors()

        val closeBtn = Button(context).apply {
            text = "Kapat"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = focusableBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            minHeight = dp(36)
            minimumHeight = dp(36)
            isFocusable = true
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(toggleBtn)
        root.addView(scaleCard)
        root.addView(opacityCard)
        root.addView(colorCard)
        root.addView(closeBtn)

        val scroll = ScrollView(context).apply { addView(root) }
        val dialog = AlertDialog.Builder(context).setView(scroll).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()

        val targetWidth = min(dp(335), (dm.widthPixels * 0.80f).toInt())
        dialog.window?.setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        toggleBtn.requestFocus()
    }
}
