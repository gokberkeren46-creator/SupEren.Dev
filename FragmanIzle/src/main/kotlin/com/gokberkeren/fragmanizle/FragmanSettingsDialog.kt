package com.gokberkeren.fragmanizle

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

object FragmanSettingsDialog {

    private const val COLOR_BG = 0xF5111624.toInt()
    private const val COLOR_CARD = 0xFF1A2236.toInt()
    private const val COLOR_PRIMARY = 0xFF6366F1.toInt()
    private const val COLOR_PRIMARY_FOCUS = 0xFF818CF8.toInt()
    private const val COLOR_MUTED = 0xFF27314A.toInt()
    private const val COLOR_MUTED_FOCUS = 0xFF394769.toInt()

    fun show(activity: Activity, prefs: FragmanPrefs, onChanged: () -> Unit) {
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
            text = "🎬 FragmanIzle Ayarları"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })

        root.addView(TextView(activity).apply {
            text = "Film ve dizi detay sayfasında 'Oynat' butonunun yanına/altına hızlı fragman butonu ekler."
            textSize = 11.5f
            setTextColor(0xFFAAB4CF.toInt())
            setPadding(0, 0, 0, dp(10))
        })

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
            toggleBtn.text = if (on) "✅ Fragman Butonu: AÇIK" else "⛔ Fragman Butonu: KAPALI"
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

        // Open mode card
        val modeCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(COLOR_CARD, 10)
            setPadding(dp(10), dp(8), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        modeCard.addView(TextView(activity).apply {
            text = "FRAGMAN AÇILIŞ ŞEKLİ"
            textSize = 11f
            setTextColor(0xFF94A3B8.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        val inAppBtn = Button(activity).apply {
            text = "🪟 Uygulama İçi Pencere (Varsayılan)"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        val extBtn = Button(activity).apply {
            text = "📺 Doğrudan YouTube / SmartTube Uygulaması"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        fun refreshMode() {
            val ext = prefs.openExternal
            inAppBtn.background = if (!ext) focusBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS, true) else focusBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            extBtn.background = if (ext) focusBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS, true) else focusBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
        }
        inAppBtn.setOnClickListener {
            prefs.openExternal = false
            refreshMode()
        }
        extBtn.setOnClickListener {
            prefs.openExternal = true
            refreshMode()
        }
        refreshMode()
        modeCard.addView(inAppBtn)
        modeCard.addView(extBtn)
        root.addView(modeCard)

        // Language preference card
        val langCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(COLOR_CARD, 10)
            setPadding(dp(10), dp(8), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        langCard.addView(TextView(activity).apply {
            text = "FRAGMAN DİL ÖNCELİĞİ"
            textSize = 11f
            setTextColor(0xFF94A3B8.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        val trFirstBtn = Button(activity).apply {
            text = "🇹🇷 Önce Türkçe Fragman, Bulunamazsa Orijinal"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        val origFirstBtn = Button(activity).apply {
            text = "🌐 Her Zaman Orijinal Resmi Fragman"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        fun refreshLang() {
            val tr = prefs.turkishFirst
            trFirstBtn.background = if (tr) focusBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS, true) else focusBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            origFirstBtn.background = if (!tr) focusBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS, true) else focusBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
        }
        trFirstBtn.setOnClickListener {
            prefs.turkishFirst = true
            refreshLang()
        }
        origFirstBtn.setOnClickListener {
            prefs.turkishFirst = false
            refreshLang()
        }
        refreshLang()
        langCard.addView(trFirstBtn)
        langCard.addView(origFirstBtn)
        root.addView(langCard)

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
