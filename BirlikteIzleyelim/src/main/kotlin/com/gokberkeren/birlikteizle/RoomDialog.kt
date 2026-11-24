package com.gokberkeren.birlikteizle

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Compact dark-themed programmatic dialog for Birlikteİzleyelim.
 * Auto-dismisses once a newly joined room connects (or when a partner joins a newly created room).
 */
object RoomDialog {

    private const val COLOR_BG = 0xFF121622.toInt()
    private const val COLOR_CARD = 0xFF1B2234.toInt()
    private const val COLOR_INPUT = 0xFF0E121C.toInt()
    private const val COLOR_STROKE = 0xFF2A344D.toInt()
    private const val COLOR_PRIMARY = 0xFF6366F1.toInt()
    private const val COLOR_PRIMARY_FOCUS = 0xFF818CF8.toInt()
    private const val COLOR_SECONDARY = 0xFF0EA5E9.toInt()
    private const val COLOR_SECONDARY_FOCUS = 0xFF38BDF8.toInt()
    private const val COLOR_DANGER = 0xFFEF4444.toInt()
    private const val COLOR_DANGER_FOCUS = 0xFFF87171.toInt()
    private const val COLOR_MUTED = 0xFF252E44.toInt()
    private const val COLOR_MUTED_FOCUS = 0xFF364261.toInt()
    private const val COLOR_TEXT_PRIMARY = 0xFFF8FAFC.toInt()
    private const val COLOR_TEXT_SECONDARY = 0xFF94A3B8.toInt()
    private const val COLOR_ONLINE = 0xFF22C55E.toInt()
    private const val COLOR_CONNECTING = 0xFFF59E0B.toInt()
    private const val COLOR_OFFLINE = 0xFF64748B.toInt()

    fun show(context: Context, roomManager: RoomManager) {
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val dm = context.resources.displayMetrics
        val density = dm.density
        fun dp(v: Int): Int = (v * density).toInt()

        fun cardDrawable(bg: Int = COLOR_CARD, stroke: Int = COLOR_STROKE, radiusDp: Int = 12): GradientDrawable {
            return GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(radiusDp).toFloat()
                setStroke(dp(1), stroke)
            }
        }

        fun focusableButtonDrawable(normalColor: Int, focusedColor: Int, radiusDp: Int = 10): StateListDrawable {
            val normal = GradientDrawable().apply {
                setColor(normalColor)
                cornerRadius = dp(radiusDp).toFloat()
            }
            val focused = GradientDrawable().apply {
                setColor(focusedColor)
                cornerRadius = dp(radiusDp).toFloat()
                setStroke(dp(2), Color.WHITE)
            }
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(android.R.attr.state_pressed), focused)
                addState(intArrayOf(), normal)
            }
        }

        fun focusableInputDrawable(): StateListDrawable {
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

        fun styledButton(label: String, normalColor: Int, focusedColor: Int): Button {
            return Button(context).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                background = focusableButtonDrawable(normalColor, focusedColor)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                minHeight = dp(36)
                minimumHeight = dp(36)
                isFocusable = true
            }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(COLOR_BG, COLOR_STROKE, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        // --- Header row ---
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleView = TextView(context).apply {
            text = "Birlikteİzleyelim"
            textSize = 17f
            setTextColor(COLOR_TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitleView = TextView(context).apply {
            text = "SupEren.Dev"
            textSize = 10f
            setTextColor(COLOR_TEXT_SECONDARY)
        }
        titleCol.addView(titleView)
        titleCol.addView(subtitleView)

        val statusPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = cardDrawable(COLOR_CARD, COLOR_STROKE, 16)
        }
        val statusDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                marginEnd = dp(5)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_OFFLINE)
            }
        }
        val statusText = TextView(context).apply {
            text = "Oda Yok"
            textSize = 11f
            setTextColor(COLOR_TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
        }
        statusPill.addView(statusDot)
        statusPill.addView(statusText)

        headerRow.addView(titleCol)
        headerRow.addView(statusPill)

        // --- Profile & Server card ---
        val profileCard = LinearLayout(context).apply {
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
        val nameLabel = TextView(context).apply {
            text = "ADINIZ"
            textSize = 10f
            setTextColor(COLOR_TEXT_SECONDARY)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        val nameInput = EditText(context).apply {
            setText(roomManager.displayName)
            hint = "Takma ad"
            setHintTextColor(COLOR_TEXT_SECONDARY)
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 13f
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(24))
            background = focusableInputDrawable()
            setPadding(dp(10), dp(7), dp(10), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val serverHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
        serverHeaderRow.addView(serverLabel)
        serverHeaderRow.addView(serverBadge)

        val serverInput = EditText(context).apply {
            setText(roomManager.serverInput)
            hint = "https://...firebasedatabase.app"
            setHintTextColor(COLOR_TEXT_SECONDARY)
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 12f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = focusableInputDrawable()
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }

        fun refreshServerBadge() {
            when {
                roomManager.isUsingPrivateShortCode() -> {
                    serverBadge.text = "● Özel Sunucu"
                    serverBadge.setTextColor(COLOR_ONLINE)
                }
                roomManager.isServerConfigured() -> {
                    serverBadge.text = "● Hazır"
                    serverBadge.setTextColor(COLOR_SECONDARY_FOCUS)
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
                roomManager.serverInput = s?.toString().orEmpty()
                refreshServerBadge()
            }
        })
        refreshServerBadge()

        profileCard.addView(nameLabel)
        profileCard.addView(nameInput)
        profileCard.addView(serverHeaderRow)
        profileCard.addView(serverInput)

        val errorText = TextView(context).apply {
            textSize = 12f
            setTextColor(0xFFFCA5A5.toInt())
            visibility = View.GONE
            background = cardDrawable(0x33EF4444, 0x88EF4444.toInt(), 8)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        // --- No-room card ---
        val noRoomBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable()
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val createBtn = styledButton("Yeni Oda Oluştur", COLOR_PRIMARY, COLOR_PRIMARY_FOCUS).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
        val codeInput = EditText(context).apply {
            hint = "6 Haneli Oda Kodu"
            setHintTextColor(COLOR_TEXT_SECONDARY)
            setTextColor(COLOR_TEXT_PRIMARY)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            gravity = Gravity.CENTER
            textSize = 17f
            letterSpacing = 0.22f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            background = focusableInputDrawable()
            setPadding(dp(10), dp(7), dp(10), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        val joinBtn = styledButton("Odaya Katıl", COLOR_SECONDARY, COLOR_SECONDARY_FOCUS).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        noRoomBox.addView(createBtn)
        noRoomBox.addView(codeInput)
        noRoomBox.addView(joinBtn)

        // --- In-room card ---
        val inRoomBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = cardDrawable()
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val codeBanner = TextView(context).apply {
            textSize = 24f
            letterSpacing = 0.28f
            setTextColor(COLOR_TEXT_PRIMARY)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = cardDrawable(COLOR_INPUT, COLOR_PRIMARY, 10)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        val copyBtn = styledButton("Kodu Kopyala", COLOR_PRIMARY, COLOR_PRIMARY_FOCUS).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        val membersHeader = TextView(context).apply {
            text = "ODADAKİLER"
            textSize = 10f
            setTextColor(COLOR_TEXT_SECONDARY)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        val membersContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        val leaveBtn = styledButton("Odadan Ayrıl", COLOR_DANGER, COLOR_DANGER_FOCUS).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        inRoomBox.addView(codeBanner)
        inRoomBox.addView(copyBtn)
        inRoomBox.addView(membersHeader)
        inRoomBox.addView(membersContainer)
        inRoomBox.addView(leaveBtn)

        val closeBtn = styledButton("Kapat", COLOR_MUTED, COLOR_MUTED_FOCUS).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        root.addView(headerRow)
        root.addView(profileCard)
        root.addView(errorText)
        root.addView(noRoomBox)
        root.addView(inRoomBox)
        root.addView(closeBtn)

        val scroll = ScrollView(context).apply { addView(root) }

        val dialog = AlertDialog.Builder(context)
            .setView(scroll)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        var collectJob: Job? = null
        var autoCloseMode = AutoCloseMode.NONE

        fun setBusy(busy: Boolean) {
            createBtn.isEnabled = !busy
            joinBtn.isEnabled = !busy
            leaveBtn.isEnabled = !busy
        }

        fun showError(msg: String?) {
            if (msg.isNullOrBlank()) {
                errorText.visibility = View.GONE
            } else {
                errorText.text = msg
                errorText.visibility = View.VISIBLE
            }
        }

        fun setDotColor(color: Int) {
            (statusDot.background as? GradientDrawable)?.setColor(color)
        }

        fun renderMembers(members: List<RoomMember>) {
            membersContainer.removeAllViews()
            val list = if (members.isEmpty()) {
                listOf(RoomMember(roomManager.clientId, roomManager.displayName))
            } else {
                members
            }
            membersHeader.text = "ODADAKİLER (${list.size})"
            list.forEach { member ->
                val chip = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = cardDrawable(COLOR_INPUT, COLOR_STROKE, 8)
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(4)
                    }
                }
                val dot = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                        marginEnd = dp(6)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(COLOR_ONLINE)
                    }
                }
                val label = TextView(context).apply {
                    text = if (member.clientId == roomManager.clientId) "${member.name} (Sen)" else member.name
                    textSize = 12f
                    setTextColor(COLOR_TEXT_PRIMARY)
                }
                chip.addView(dot)
                chip.addView(label)
                membersContainer.addView(chip)
            }
        }

        createBtn.setOnClickListener {
            roomManager.displayName = nameInput.text.toString()
            roomManager.serverInput = serverInput.text.toString()
            refreshServerBadge()
            showError(null)
            setBusy(true)
            uiScope.launch {
                val res = roomManager.createRoom()
                setBusy(false)
                res.onSuccess {
                    // Keep dialog open until the other device joins, then auto-close
                    autoCloseMode = AutoCloseMode.ON_PARTNER_JOIN
                }
                res.onFailure { showError(it.message ?: "Oda oluşturulamadı") }
            }
        }

        joinBtn.setOnClickListener {
            roomManager.displayName = nameInput.text.toString()
            roomManager.serverInput = serverInput.text.toString()
            refreshServerBadge()
            val code = codeInput.text.toString().trim()
            showError(null)
            setBusy(true)
            uiScope.launch {
                val res = roomManager.joinRoom(code)
                setBusy(false)
                res.onSuccess {
                    // Auto-close as soon as SSE connects
                    autoCloseMode = AutoCloseMode.ON_CONNECT
                }
                res.onFailure { showError(it.message ?: "Odaya katılınamadı") }
            }
        }

        copyBtn.setOnClickListener {
            val code = roomManager.currentRoomCode ?: return@setOnClickListener
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Oda Kodu", code))
            Toast.makeText(context, "Oda kodu kopyalandı: $code", Toast.LENGTH_SHORT).show()
        }

        leaveBtn.setOnClickListener {
            autoCloseMode = AutoCloseMode.NONE
            roomManager.leaveRoom()
        }

        closeBtn.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            val editedName = nameInput.text.toString().trim()
            if (editedName.isNotEmpty() && editedName != roomManager.displayName) {
                roomManager.displayName = editedName
            }
            val editedServer = serverInput.text.toString().trim()
            if (editedServer != roomManager.serverInput) {
                roomManager.serverInput = editedServer
            }
            collectJob?.cancel()
        }

        collectJob = uiScope.launch {
            roomManager.uiState.collectLatest { state ->
                if (state.roomCode == null) {
                    statusText.text = "Oda Yok"
                    setDotColor(COLOR_OFFLINE)
                    noRoomBox.visibility = View.VISIBLE
                    inRoomBox.visibility = View.GONE
                    serverInput.isEnabled = true
                } else {
                    if (state.connected) {
                        statusText.text = "Bağlı"
                        setDotColor(COLOR_ONLINE)
                    } else {
                        statusText.text = "Bağlanıyor"
                        setDotColor(COLOR_CONNECTING)
                    }
                    noRoomBox.visibility = View.GONE
                    inRoomBox.visibility = View.VISIBLE
                    serverInput.isEnabled = false
                    codeBanner.text = state.roomCode
                    renderMembers(state.members)

                    if (state.connected && autoCloseMode == AutoCloseMode.ON_CONNECT) {
                        autoCloseMode = AutoCloseMode.NONE
                        Toast.makeText(context, "Odaya bağlanıldı: ${state.roomCode}", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else if (state.connected && autoCloseMode == AutoCloseMode.ON_PARTNER_JOIN && state.members.size >= 2) {
                        autoCloseMode = AutoCloseMode.NONE
                        Toast.makeText(context, "Eşleşme tamamlandı! (${state.roomCode})", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
            }
        }

        dialog.show()
        // Keep popup compact on both Tablet & TV screens
        val targetWidth = min(dp(330), (dm.widthPixels * 0.80f).toInt())
        dialog.window?.setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private enum class AutoCloseMode {
        NONE,
        ON_CONNECT,
        ON_PARTNER_JOIN
    }
}
