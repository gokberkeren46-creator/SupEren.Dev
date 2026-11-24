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

/**
 * Modern dark-themed programmatic dialog for Birlikteİzleyelim.
 * Uses pure Android drawables (zero XML) for 100% crash-free .cs3 loading on both Tablet & Android TV.
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
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        fun cardDrawable(bg: Int = COLOR_CARD, stroke: Int = COLOR_STROKE, radiusDp: Int = 14): GradientDrawable {
            return GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(radiusDp).toFloat()
                setStroke(dp(1), stroke)
            }
        }

        fun focusableButtonDrawable(normalColor: Int, focusedColor: Int, radiusDp: Int = 12): StateListDrawable {
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
            val normal = cardDrawable(COLOR_INPUT, COLOR_STROKE, 10)
            val focused = GradientDrawable().apply {
                setColor(COLOR_INPUT)
                cornerRadius = dp(10).toFloat()
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
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                background = focusableButtonDrawable(normalColor, focusedColor)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                minHeight = dp(44)
                minimumHeight = dp(44)
                isFocusable = true
            }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(COLOR_BG, COLOR_STROKE, 18)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }

        // --- Header row ---
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleView = TextView(context).apply {
            text = "Birlikteİzleyelim"
            textSize = 20f
            setTextColor(COLOR_TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitleView = TextView(context).apply {
            text = "GokBey-Develop • Watch Party"
            textSize = 11f
            setTextColor(COLOR_TEXT_SECONDARY)
        }
        titleCol.addView(titleView)
        titleCol.addView(subtitleView)

        val statusPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = cardDrawable(COLOR_CARD, COLOR_STROKE, 20)
        }
        val statusDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_OFFLINE)
            }
        }
        val statusText = TextView(context).apply {
            text = "Oda Yok"
            textSize = 12f
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
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
        val nameLabel = TextView(context).apply {
            text = "GÖRÜNECEK ADINIZ"
            textSize = 11f
            setTextColor(COLOR_TEXT_SECONDARY)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        val nameInput = EditText(context).apply {
            setText(roomManager.displayName)
            hint = "Takma adınız"
            setHintTextColor(COLOR_TEXT_SECONDARY)
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 15f
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(24))
            background = focusableInputDrawable()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

        val serverHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val serverLabel = TextView(context).apply {
            text = "SUNUCU ADRESİ / KISA KOD"
            textSize = 11f
            setTextColor(COLOR_TEXT_SECONDARY)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val serverBadge = TextView(context).apply {
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        }
        serverHeaderRow.addView(serverLabel)
        serverHeaderRow.addView(serverBadge)

        val serverInput = EditText(context).apply {
            setText(roomManager.serverInput)
            hint = "https://...firebasedatabase.app"
            setHintTextColor(COLOR_TEXT_SECONDARY)
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 13f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = focusableInputDrawable()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        fun refreshServerBadge() {
            when {
                roomManager.isUsingPrivateShortCode() -> {
                    serverBadge.text = "● Özel Sunucu Aktif"
                    serverBadge.setTextColor(COLOR_ONLINE)
                }
                roomManager.isServerConfigured() -> {
                    serverBadge.text = "● Firebase Hazır"
                    serverBadge.setTextColor(COLOR_SECONDARY_FOCUS)
                }
                else -> {
                    serverBadge.text = "● Sunucu Gerekli"
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
            textSize = 13f
            setTextColor(0xFFFCA5A5.toInt())
            visibility = View.GONE
            background = cardDrawable(0x33EF4444, 0x88EF4444.toInt(), 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        // --- No-room card ---
        val noRoomBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable()
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val createBtn = styledButton("Yeni Oda Oluştur", COLOR_PRIMARY, COLOR_PRIMARY_FOCUS).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
        val dividerLabel = TextView(context).apply {
            text = "VEYA MEVCUT ODAYA KATIL"
            textSize = 11f
            setTextColor(COLOR_TEXT_SECONDARY)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        val codeInput = EditText(context).apply {
            hint = "6 Haneli Oda Kodu"
            setHintTextColor(COLOR_TEXT_SECONDARY)
            setTextColor(COLOR_TEXT_PRIMARY)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            gravity = Gravity.CENTER
            textSize = 20f
            letterSpacing = 0.25f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            background = focusableInputDrawable()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
        val joinBtn = styledButton("Odaya Katıl", COLOR_SECONDARY, COLOR_SECONDARY_FOCUS).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        noRoomBox.addView(createBtn)
        noRoomBox.addView(dividerLabel)
        noRoomBox.addView(codeInput)
        noRoomBox.addView(joinBtn)

        // --- In-room card ---
        val inRoomBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = cardDrawable()
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val codeHeader = TextView(context).apply {
            text = "ODA KODU"
            textSize = 11f
            setTextColor(COLOR_TEXT_SECONDARY)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val codeBanner = TextView(context).apply {
            textSize = 30f
            letterSpacing = 0.3f
            setTextColor(COLOR_TEXT_PRIMARY)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = cardDrawable(COLOR_INPUT, COLOR_PRIMARY, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(10)
            }
        }
        val copyBtn = styledButton("Oda Kodunu Kopyala", COLOR_PRIMARY, COLOR_PRIMARY_FOCUS).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
        val membersHeader = TextView(context).apply {
            text = "ODADAKİLER"
            textSize = 11f
            setTextColor(COLOR_TEXT_SECONDARY)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        val membersContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
        val leaveBtn = styledButton("Odadan Ayrıl", COLOR_DANGER, COLOR_DANGER_FOCUS).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        inRoomBox.addView(codeHeader)
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
                topMargin = dp(12)
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
                    background = cardDrawable(COLOR_INPUT, COLOR_STROKE, 10)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(6)
                    }
                }
                val dot = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                        marginEnd = dp(8)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(COLOR_ONLINE)
                    }
                }
                val label = TextView(context).apply {
                    text = if (member.clientId == roomManager.clientId) "${member.name} (Sen)" else member.name
                    textSize = 14f
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
                }
            }
        }

        dialog.show()
    }
}
