package com.gokberkeren.introatla

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object IntroSettingsDialog {

    private const val COLOR_BG = 0xF5111624.toInt()
    private const val COLOR_CARD = 0xFF1A2236.toInt()
    private const val COLOR_PRIMARY = 0xFF6366F1.toInt()
    private const val COLOR_PRIMARY_FOCUS = 0xFF818CF8.toInt()
    private const val COLOR_MUTED = 0xFF27314A.toInt()
    private const val COLOR_MUTED_FOCUS = 0xFF394769.toInt()

    fun show(activity: Activity, prefs: IntroPrefs, onChanged: () -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        fun roundedBg(color: Int, radiusDp: Int = 10, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
            return GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radiusDp).toFloat()
                if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
            }
        }

        fun focusBtn(normal: Int, focused: Int, selected: Boolean = false): StateListDrawable {
            val nBg = roundedBg(normal, 8, if (selected) Color.WHITE else 0, if (selected) 1 else 0)
            val fBg = roundedBg(focused, 8, Color.WHITE, 2)
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), fBg)
                addState(intArrayOf(android.R.attr.state_pressed), fBg)
                addState(intArrayOf(), nBg)
            }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(COLOR_BG, 14, 0x55FFFFFF, 1)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        root.addView(TextView(activity).apply {
            text = "⏭ IntroAtla Ayarları"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })

        root.addView(TextView(activity).apply {
            text = "Sol altta Netflix tarzı İntro Atla butonu. API verisi yoksa ilk 5 dk HUD açıkken yedek atlama süresi kullanılır."
            textSize = 11.5f
            setTextColor(0xFFAAB4CF.toInt())
            setPadding(0, 0, 0, dp(10))
        })

        // Enable toggle
        val toggleBtn = Button(activity).apply {
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        fun refreshToggle() {
            val on = prefs.enabled
            toggleBtn.text = if (on) "✅ IntroAtla: AÇIK" else "⛔ IntroAtla: KAPALI"
            toggleBtn.background = focusBtn(
                if (on) 0xFF16A34A.toInt() else 0xFFDC2626.toInt(),
                if (on) 0xFF22C55E.toInt() else 0xFFEF4444.toInt()
            )
        }
        toggleBtn.setOnClickListener {
            prefs.enabled = !prefs.enabled
            refreshToggle()
            onChanged()
        }
        refreshToggle()
        root.addView(toggleBtn)

        // Fallback seconds card
        val secCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(COLOR_CARD, 10)
            setPadding(dp(10), dp(8), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        secCard.addView(TextView(activity).apply {
            text = "YEDEK ATLAMA SÜRESİ (API VERİSİ YOKSA)"
            textSize = 11f
            setTextColor(0xFF94A3B8.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        val secRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val secBtns = IntroPrefs.FALLBACK_OPTIONS.map { sec ->
            sec to Button(activity).apply {
                text = "+${sec}sn"
                isAllCaps = false
                textSize = 12f
                setTextColor(Color.WHITE)
                isFocusable = true
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(34)
                minimumHeight = dp(34)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
            }
        }
        fun refreshSec() {
            secBtns.forEach { (sec, b) ->
                val sel = prefs.fallbackSeconds == sec
                b.background = if (sel) focusBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS, true)
                else focusBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            }
        }
        secBtns.forEach { (sec, b) ->
            b.setOnClickListener {
                prefs.fallbackSeconds = sec
                refreshSec()
                onChanged()
            }
            secRow.addView(b)
        }
        refreshSec()
        secCard.addView(secRow)
        root.addView(secCard)

        // Opacity card
        val alphaCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(COLOR_CARD, 10)
            setPadding(dp(10), dp(8), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        alphaCard.addView(TextView(activity).apply {
            text = "BUTON ŞEFFAFLIĞI"
            textSize = 11f
            setTextColor(0xFF94A3B8.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        val alphaRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val alphaBtns = IntroPrefs.ALPHA_OPTIONS.map { (lbl, a) ->
            a to Button(activity).apply {
                text = lbl
                isAllCaps = false
                textSize = 12f
                setTextColor(Color.WHITE)
                isFocusable = true
                minHeight = dp(34)
                minimumHeight = dp(34)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
            }
        }
        fun refreshAlpha() {
            alphaBtns.forEach { (a, b) ->
                val sel = kotlin.math.abs(prefs.alpha - a) < 0.05f
                b.background = if (sel) focusBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS, true)
                else focusBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            }
        }
        alphaBtns.forEach { (a, b) ->
            b.setOnClickListener {
                prefs.alpha = a
                refreshAlpha()
                onChanged()
            }
            alphaRow.addView(b)
        }
        refreshAlpha()
        alphaCard.addView(alphaRow)
        root.addView(alphaCard)

        val closeBtn = Button(activity).apply {
            text = "Kapat"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = focusBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            isFocusable = true
        }
        root.addView(closeBtn)

        val scroll = ScrollView(activity).apply { addView(root) }
        val dialog = AlertDialog.Builder(activity).setView(scroll).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
