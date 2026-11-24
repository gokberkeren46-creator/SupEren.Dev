package com.gokberkeren.suregosterge

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
import kotlin.math.abs
import kotlin.math.min

/**
 * Compact dark-themed settings dialog for Gösterge (zero XML, TV D-Pad friendly).
 */
object SureSettingsDialog {

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

    fun show(context: Context, prefs: SurePrefs, onChanged: () -> Unit) {
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

        fun focusableBtn(normalColor: Int, focusedColor: Int): StateListDrawable {
            val normal = GradientDrawable().apply {
                setColor(normalColor)
                cornerRadius = dp(10).toFloat()
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
            text = "Gösterge"
            textSize = 17f
            setTextColor(COLOR_TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = TextView(context).apply {
            text = "SupEren.Dev • İlk 1 dk + Her 10 dk'da 30 sn"
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
                bottomMargin = dp(10)
            }
        }

        fun refreshToggle() {
            if (prefs.enabled) {
                toggleBtn.text = "● Gösterge Açık (Kapatmak için bas)"
                toggleBtn.background = focusableBtn(COLOR_ONLINE, 0xFF4ADE80.toInt())
            } else {
                toggleBtn.text = "○ Gösterge Kapalı (Açmak için bas)"
                toggleBtn.background = focusableBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            }
        }
        refreshToggle()
        toggleBtn.setOnClickListener {
            prefs.enabled = !prefs.enabled
            refreshToggle()
            onChanged()
        }

        // Size section (4 options including Çok Küçük for TV)
        val sizeCard = LinearLayout(context).apply {
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
        val sizeLabel = TextView(context).apply {
            text = "YAZI BOYUTU"
            textSize = 10f
            setTextColor(COLOR_TEXT_SECONDARY)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        val sizeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val sizeOptions = listOf(
            "Çok Küçük" to SurePrefs.SIZE_XS,
            "Küçük" to SurePrefs.SIZE_SMALL,
            "Orta" to SurePrefs.SIZE_MEDIUM,
            "Büyük" to SurePrefs.SIZE_LARGE
        )
        val sizeBtns = sizeOptions.map { (lbl, _) -> chipButton(lbl) }
        fun refreshSizes() {
            sizeBtns.forEachIndexed { idx, btn ->
                val selected = abs(prefs.textSizeSp - sizeOptions[idx].second) < 0.6f
                btn.background = if (selected) focusableBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS)
                else focusableBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            }
        }
        sizeBtns.forEachIndexed { idx, btn ->
            btn.setOnClickListener {
                prefs.textSizeSp = sizeOptions[idx].second
                refreshSizes()
                onChanged()
            }
            sizeRow.addView(btn)
        }
        refreshSizes()
        sizeCard.addView(sizeLabel)
        sizeCard.addView(sizeRow)

        // Alpha section (%10, %20, %40, %65)
        val alphaCard = LinearLayout(context).apply {
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
        val alphaLabel = TextView(context).apply {
            text = "SAYDAMLIK"
            textSize = 10f
            setTextColor(COLOR_TEXT_SECONDARY)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        val alphaRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val alphaOptions = listOf(
            "%10" to SurePrefs.ALPHA_10,
            "%20" to SurePrefs.ALPHA_20,
            "%40" to SurePrefs.ALPHA_40,
            "%65" to SurePrefs.ALPHA_65
        )
        val alphaBtns = alphaOptions.map { (lbl, _) -> chipButton(lbl) }
        fun refreshAlphas() {
            alphaBtns.forEachIndexed { idx, btn ->
                val selected = abs(prefs.alpha - alphaOptions[idx].second) < 0.05f
                btn.background = if (selected) focusableBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS)
                else focusableBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            }
        }
        alphaBtns.forEachIndexed { idx, btn ->
            btn.setOnClickListener {
                prefs.alpha = alphaOptions[idx].second
                refreshAlphas()
                onChanged()
            }
            alphaRow.addView(btn)
        }
        refreshAlphas()
        alphaCard.addView(alphaLabel)
        alphaCard.addView(alphaRow)

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
        root.addView(sizeCard)
        root.addView(alphaCard)
        root.addView(closeBtn)

        val scroll = ScrollView(context).apply { addView(root) }
        val dialog = AlertDialog.Builder(context).setView(scroll).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()

        val targetWidth = min(dp(330), (dm.widthPixels * 0.80f).toInt())
        dialog.window?.setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT)
    }
}
