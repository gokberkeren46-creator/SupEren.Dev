package com.gokberkeren.birlikteizle

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
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
 * Programmatic settings dialog for creating, joining, and monitoring watch-party rooms.
 * Built without XML layouts to avoid resource ID clashes inside dynamically loaded .cs3 plugins.
 */
object RoomDialog {

    fun show(context: Context, roomManager: RoomManager) {
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }

        val nameLabel = TextView(context).apply {
            text = "Görünecek Adınız"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }
        val nameInput = EditText(context).apply {
            setText(roomManager.displayName)
            hint = "Takma ad"
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(24))
        }

        val statusText = TextView(context).apply {
            textSize = 14f
            setPadding(0, dp(10), 0, dp(6))
        }

        val errorText = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFFFF5252.toInt())
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(6))
        }

        // --- No-room section ---
        val noRoomBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val createBtn = Button(context).apply {
            text = "Oda Oluştur (6 Haneli Kod)"
        }
        val codeInput = EditText(context).apply {
            hint = "6 haneli oda kodu (örn. 482910)"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            gravity = Gravity.CENTER
            textSize = 18f
        }
        val joinBtn = Button(context).apply {
            text = "Odaya Katıl"
        }
        noRoomBox.addView(createBtn)
        noRoomBox.addView(codeInput)
        noRoomBox.addView(joinBtn)

        // --- In-room section ---
        val inRoomBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val codeBanner = TextView(context).apply {
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        val copyBtn = Button(context).apply {
            text = "Oda Kodunu Kopyala"
        }
        val membersText = TextView(context).apply {
            textSize = 13f
            setPadding(0, dp(10), 0, dp(10))
        }
        val leaveBtn = Button(context).apply {
            text = "Odadan Ayrıl"
        }
        inRoomBox.addView(codeBanner)
        inRoomBox.addView(copyBtn)
        inRoomBox.addView(membersText)
        inRoomBox.addView(leaveBtn)

        root.addView(nameLabel)
        root.addView(nameInput)
        root.addView(statusText)
        root.addView(errorText)
        root.addView(noRoomBox)
        root.addView(inRoomBox)

        val scroll = ScrollView(context).apply { addView(root) }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Birlikte İzle")
            .setView(scroll)
            .setNegativeButton("Kapat", null)
            .create()

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

        createBtn.setOnClickListener {
            roomManager.displayName = nameInput.text.toString()
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

        dialog.setOnDismissListener {
            val editedName = nameInput.text.toString().trim()
            if (editedName.isNotEmpty() && editedName != roomManager.displayName) {
                roomManager.displayName = editedName
            }
            collectJob?.cancel()
        }

        collectJob = uiScope.launch {
            roomManager.uiState.collectLatest { state ->
                if (state.roomCode == null) {
                    statusText.text = "Durum: Herhangi bir odada değilsiniz."
                    noRoomBox.visibility = View.VISIBLE
                    inRoomBox.visibility = View.GONE
                } else {
                    val connLabel = if (state.connected) "Bağlı" else "Bağlanıyor..."
                    statusText.text = "Durum: $connLabel"
                    noRoomBox.visibility = View.GONE
                    inRoomBox.visibility = View.VISIBLE
                    codeBanner.text = state.roomCode
                    val names = if (state.members.isEmpty()) {
                        roomManager.displayName
                    } else {
                        state.members.joinToString("\n") { "• ${it.name}" }
                    }
                    membersText.text = "Odadakiler (${state.members.size.coerceAtLeast(1)}):\n$names"
                }
            }
        }

        dialog.show()
    }
}
