package com.gokberkeren.bulutsenkron

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Compact dark-themed settings dialog for BulutSenkron (zero XML, TV D-Pad friendly).
 */
object SyncDialog {

    private const val COLOR_BG = 0xFF121622.toInt()
    private const val COLOR_CARD = 0xFF1B2234.toInt()
    private const val COLOR_INPUT = 0xFF0E121C.toInt()
    private const val COLOR_STROKE = 0xFF2A344D.toInt()
    private const val COLOR_PRIMARY = 0xFF0EA5E9.toInt()
    private const val COLOR_PRIMARY_FOCUS = 0xFF38BDF8.toInt()
    private const val COLOR_MUTED = 0xFF252E44.toInt()
    private const val COLOR_MUTED_FOCUS = 0xFF364261.toInt()
    private const val COLOR_TEXT_PRIMARY = 0xFFF8FAFC.toInt()
    private const val COLOR_TEXT_SECONDARY = 0xFF94A3B8.toInt()
    private const val COLOR_ONLINE = 0xFF22C55E.toInt()
    private const val COLOR_CONNECTING = 0xFFF59E0B.toInt()

    fun show(context: Context, prefs: SyncPrefs, engine: SyncEngine) {
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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

        fun focusableInput(): StateListDrawable {
            val normal = cardDrawable(COLOR_INPUT, COLOR_STROKE, 8)
            val focused = GradientDrawable().apply {
                setColor(COLOR_INPUT)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(2), COLOR_PRIMARY_FOCUS)
            }
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(), normal)
            }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(COLOR_BG, COLOR_STROKE, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val title = TextView(context).apply {
            text = "Senkron"
            textSize = 17f
            setTextColor(COLOR_TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = TextView(context).apply {
            text = "SupEren.Dev • Kaldığın Yer & Arama Geçmişi"
            textSize = 10f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(10))
        }

        val formCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable()
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val serverRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(4))
        }
        val serverLabel = TextView(context).apply {
            text = "SUNUCU / KISA KOD"
            textSize = 10f
            setTextColor(COLOR_TEXT_SECONDARY)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val serverBadge = TextView(context).apply {
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
        }
        serverRow.addView(serverLabel)
        serverRow.addView(serverBadge)

        val serverInput = EditText(context).apply {
            setText(prefs.serverInput)
            hint = "https://...firebasedatabase.app"
            setHintTextColor(COLOR_TEXT_SECONDARY)
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 12f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = focusableInput()
            setPadding(dp(10), dp(7), dp(10), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val pinLabel = TextView(context).apply {
            text = "6 HANELİ PROFİL KODU (TÜM CİHAZLARDA AYNI)"
            textSize = 10f
            setTextColor(COLOR_TEXT_SECONDARY)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        val pinInput = EditText(context).apply {
            setText(prefs.profilePin)
            hint = "Örn. 461646"
            setHintTextColor(COLOR_TEXT_SECONDARY)
            setTextColor(COLOR_TEXT_PRIMARY)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            gravity = Gravity.CENTER
            textSize = 17f
            letterSpacing = 0.25f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            background = focusableInput()
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }

        fun refreshBadge() {
            when {
                prefs.isUsingPrivateShortCode() -> {
                    serverBadge.text = "● Özel Sunucu"
                    serverBadge.setTextColor(COLOR_ONLINE)
                }
                prefs.isServerConfigured() -> {
                    serverBadge.text = "● Hazır"
                    serverBadge.setTextColor(COLOR_PRIMARY_FOCUS)
                }
                else -> {
                    serverBadge.text = "● Gerekli"
                    serverBadge.setTextColor(COLOR_CONNECTING)
                }
            }
        }

        serverInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                prefs.serverInput = s?.toString().orEmpty()
                refreshBadge()
            }
        })
        refreshBadge()

        formCard.addView(serverRow)
        formCard.addView(serverInput)
        formCard.addView(pinLabel)
        formCard.addView(pinInput)

        val statusView = TextView(context).apply {
            text = prefs.lastSyncSummary
            textSize = 11f
            setTextColor(COLOR_TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }

        val syncBtn = Button(context).apply {
            text = "Şimdi Eşitle"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = focusableBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS)
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
        root.addView(formCard)
        root.addView(statusView)
        root.addView(syncBtn)
        root.addView(closeBtn)

        val scroll = ScrollView(context).apply { addView(root) }
        val dialog = AlertDialog.Builder(context).setView(scroll).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        syncBtn.setOnClickListener {
            prefs.serverInput = serverInput.text.toString()
            prefs.profilePin = pinInput.text.toString()
            refreshBadge()
            syncBtn.isEnabled = false
            statusView.text = "Eşitleniyor..."
            statusView.setTextColor(COLOR_TEXT_PRIMARY)
            uiScope.launch {
                val res = engine.syncNow()
                syncBtn.isEnabled = true
                res.onSuccess { msg ->
                    statusView.text = msg
                    statusView.setTextColor(COLOR_ONLINE)
                }.onFailure { err ->
                    statusView.text = err.message ?: "Eşitleme başarısız"
                    statusView.setTextColor(0xFFFCA5A5.toInt())
                }
            }
        }

        closeBtn.setOnClickListener {
            prefs.serverInput = serverInput.text.toString()
            prefs.profilePin = pinInput.text.toString()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            prefs.serverInput = serverInput.text.toString()
            prefs.profilePin = pinInput.text.toString()
            if (prefs.resolveEndpointUrl() != null) {
                engine.triggerAutoSyncNow()
            }
        }

        dialog.show()
        val targetWidth = min(dp(330), (dm.widthPixels * 0.80f).toInt())
        dialog.window?.setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT)
    }
}
