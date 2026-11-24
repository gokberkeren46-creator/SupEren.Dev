package com.gokberkeren.birlikteizle

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID
import kotlin.math.max
import kotlin.random.Random

data class RoomUiState(
    val roomCode: String? = null,
    val connected: Boolean = false,
    val members: List<RoomMember> = emptyList(),
    val latestState: PlaybackState? = null,
    val latestMedia: MediaInfo? = null
)

class RoomManager(
    context: Context,
    private val firebase: FirebaseClient,
    private val privateFirebaseUrl: String
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val clientId: String = prefs.getString(KEY_CLIENT_ID, null) ?: run {
        val generated = UUID.randomUUID().toString().replace("-", "").take(12)
        prefs.edit().putString(KEY_CLIENT_ID, generated).apply()
        generated
    }

    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, null)?.takeIf { it.isNotBlank() }
            ?: defaultDeviceName()
        set(value) {
            val clean = value.trim().take(24).ifBlank { defaultDeviceName() }
            prefs.edit().putString(KEY_DISPLAY_NAME, clean).apply()
            val code = _uiState.value.roomCode
            if (code != null) {
                scope.launch { updateMemberPresence(code) }
            }
        }

    var serverInput: String
        get() = prefs.getString(KEY_SERVER_INPUT, "")?.trim().orEmpty()
        set(value) {
            val clean = value.trim()
            prefs.edit().putString(KEY_SERVER_INPUT, clean).apply()
            applyServerConfig(clean)
        }

    private val _uiState = MutableStateFlow(RoomUiState())
    val uiState: StateFlow<RoomUiState> = _uiState.asStateFlow()

    val currentRoomCode: String? get() = _uiState.value.roomCode

    private val roomLock = Any()
    private var roomJson = JSONObject()
    private var sseHandle: Closeable? = null
    private var heartbeatJob: Job? = null
    private var stateFallbackJob: Job? = null

    private var lastKnownSeq: Long = 0L
    private var lastAppliedRemoteSeq: Long = -1L
    private var lastAppliedMediaMatchKey: String? = null
    private var lastAppliedMediaAt: Long = 0L
    private var lastAppliedReactionId: String? = null

    var onRemoteState: ((PlaybackState, senderName: String) -> Unit)? = null
    var onRemoteMedia: ((MediaInfo, senderName: String) -> Unit)? = null
    var onRemoteReaction: ((EmojiReaction) -> Unit)? = null

    init {
        applyServerConfig(serverInput)
    }

    fun isServerConfigured(): Boolean = firebase.isConfigured()

    fun isUsingPrivateShortCode(): Boolean {
        return serverInput.equals(PRIVATE_SHORT_CODE, ignoreCase = true) && privateFirebaseUrl.isNotBlank()
    }

    private fun applyServerConfig(input: String) {
        val clean = input.trim()
        when {
            clean.equals(PRIVATE_SHORT_CODE, ignoreCase = true) && privateFirebaseUrl.isNotBlank() -> {
                firebase.configure(privateFirebaseUrl, prefix = PRIVATE_SHORT_CODE)
            }
            clean.startsWith("https://", ignoreCase = true) || clean.startsWith("http://", ignoreCase = true) -> {
                firebase.configure(clean, prefix = "")
            }
            else -> {
                firebase.configure("", prefix = "")
            }
        }
    }

    fun estimatedServerNowMs(): Long = firebase.estimatedServerNowMs()

    fun isInRoom(): Boolean = _uiState.value.roomCode != null

    suspend fun createRoom(): Result<String> {
        if (!isServerConfigured()) {
            return Result.failure(
                IllegalStateException("Lütfen önce Firebase sunucu adresinizi (https://...) girin.")
            )
        }
        leaveRoom()
        repeat(6) {
            val code = Random.nextInt(100_000, 1_000_000).toString()
            val existing = firebase.get("rooms/$code/createdAt")
            if (existing == null) {
                val initial = JSONObject().apply {
                    put("createdAt", RoomSnapshot.serverTimestampJson())
                    put("lastActive", RoomSnapshot.serverTimestampJson())
                    put(
                        "members",
                        JSONObject().put(
                            clientId,
                            JSONObject().apply {
                                put("name", displayName)
                                put("ts", RoomSnapshot.serverTimestampJson())
                            }
                        )
                    )
                    put(
                        "state",
                        PlaybackState(
                            playing = false,
                            position = 0L,
                            by = clientId,
                            seq = 0L
                        ).toJson(useServerTimestamp = true)
                    )
                }
                val ok = firebase.put("rooms/$code", initial.toString())
                if (ok) {
                    startSession(code)
                    return Result.success(code)
                }
            }
        }
        return Result.failure(
            IllegalStateException("Oda oluşturulamadı. İnternet bağlantısını veya Firebase kurallarını kontrol edin.")
        )
    }

    suspend fun joinRoom(rawCode: String): Result<String> {
        if (!isServerConfigured()) {
            return Result.failure(
                IllegalStateException("Lütfen önce Firebase sunucu adresinizi (https://...) girin.")
            )
        }
        val code = rawCode.trim()
        if (!CODE_REGEX.matches(code)) {
            return Result.failure(IllegalArgumentException("Oda kodu 6 haneli sayı olmalıdır."))
        }
        val createdAt = firebase.get("rooms/$code/createdAt")
            ?: return Result.failure(IllegalStateException("Oda bulunamadı: $code"))

        if (createdAt.isBlank()) {
            return Result.failure(IllegalStateException("Oda bulunamadı: $code"))
        }

        leaveRoom()
        val ok = updateMemberPresence(code)
        if (!ok) {
            return Result.failure(IllegalStateException("Odaya katılınamadı (yazma izni veya ağ hatası)."))
        }
        startSession(code)
        return Result.success(code)
    }

    fun leaveRoom() {
        val prevCode = _uiState.value.roomCode
        sseHandle?.close()
        sseHandle = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        stateFallbackJob?.cancel()
        stateFallbackJob = null

        synchronized(roomLock) {
            roomJson = JSONObject()
            lastKnownSeq = 0L
            lastAppliedRemoteSeq = -1L
            lastAppliedMediaMatchKey = null
            lastAppliedMediaAt = 0L
            lastAppliedReactionId = null
        }
        _uiState.value = RoomUiState()

        if (prevCode != null) {
            scope.launch {
                firebase.delete("rooms/$prevCode/members/$clientId")
            }
        }
    }

    fun publishState(playing: Boolean, positionMs: Long): PlaybackState? {
        val code = currentRoomCode ?: return null
        val clampedPos = positionMs.coerceAtLeast(0L)
        val nextSeq = synchronized(roomLock) {
            lastKnownSeq += 1
            lastKnownSeq
        }
        val state = PlaybackState(
            playing = playing,
            position = clampedPos,
            updatedAt = firebase.estimatedServerNowMs(),
            by = clientId,
            seq = nextSeq
        )
        val payload = state.toJson(useServerTimestamp = true)

        scope.launch {
            firebase.patch("rooms/$code/state", payload.toString())
            firebase.patch(
                "rooms/$code",
                JSONObject().put("lastActive", RoomSnapshot.serverTimestampJson()).toString()
            )
        }
        return state
    }

    fun publishMedia(media: MediaInfo) {
        val code = currentRoomCode ?: return
        if (media.url.isBlank() || media.apiName.isBlank()) return
        val outgoing = media.copy(by = clientId)
        synchronized(roomLock) {
            lastAppliedMediaMatchKey = outgoing.matchKey()
            lastAppliedMediaAt = firebase.estimatedServerNowMs()
        }
        scope.launch {
            firebase.put("rooms/$code/media", outgoing.toJson(useServerTimestamp = true).toString())
            firebase.patch(
                "rooms/$code",
                JSONObject().put("lastActive", RoomSnapshot.serverTimestampJson()).toString()
            )
        }
    }

    fun sendReaction(emoji: String) {
        val code = currentRoomCode ?: return
        val reaction = EmojiReaction(
            id = UUID.randomUUID().toString().replace("-", "").take(10),
            emoji = emoji,
            by = clientId,
            senderName = displayName,
            ts = firebase.estimatedServerNowMs()
        )
        synchronized(roomLock) {
            lastAppliedReactionId = reaction.id
        }
        onRemoteReaction?.invoke(reaction)
        scope.launch {
            firebase.put("rooms/$code/reaction", reaction.toJson(useServerTimestamp = true).toString())
        }
    }

    fun memberName(cid: String): String {
        return _uiState.value.members.firstOrNull { it.clientId == cid }?.name
            ?: if (cid == clientId) displayName else "Katılımcı"
    }

    private fun startSession(code: String) {
        synchronized(roomLock) {
            roomJson = JSONObject()
            lastKnownSeq = 0L
            lastAppliedRemoteSeq = -1L
            lastAppliedMediaMatchKey = null
            lastAppliedMediaAt = 0L
            lastAppliedReactionId = null
        }
        _uiState.value = RoomUiState(roomCode = code, connected = false)

        sseHandle = firebase.listenRoom(
            scope = scope,
            code = code,
            onEvent = { event, path, data ->
                handleSseEvent(code, event, path, data)
            },
            onConnectionChanged = { isConnected ->
                val cur = _uiState.value
                if (cur.roomCode == code && cur.connected != isConnected) {
                    _uiState.value = cur.copy(connected = isConnected)
                }
            }
        )

        heartbeatJob = scope.launch {
            while (isActive && currentRoomCode == code) {
                delay(HEARTBEAT_MS)
                if (currentRoomCode == code) {
                    updateMemberPresence(code)
                }
            }
        }

        // Lightweight REST safety net so state sync never silently stalls if SSE hiccups
        stateFallbackJob = scope.launch {
            while (isActive && currentRoomCode == code) {
                delay(STATE_FALLBACK_MS)
                if (currentRoomCode != code) break
                val raw = firebase.get("rooms/$code/state") ?: continue
                try {
                    val st = PlaybackState.fromJson(JSONObject(raw)) ?: continue
                    processIncomingState(st)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private suspend fun updateMemberPresence(code: String): Boolean {
        val body = JSONObject().apply {
            put("name", displayName)
            put("ts", RoomSnapshot.serverTimestampJson())
        }
        return firebase.put("rooms/$code/members/$clientId", body.toString())
    }

    private fun handleSseEvent(code: String, event: String, path: String, data: Any?) {
        if (currentRoomCode != code) return

        var snapshot: RoomSnapshot? = null
        synchronized(roomLock) {
            applyTreeUpdate(path, data, isPatch = (event == "patch"))
            snapshot = RoomSnapshot.fromJson(code, roomJson)
        }

        val snap = snapshot ?: return
        val now = firebase.estimatedServerNowMs()
        val activeMembers = snap.members.values
            .filter { it.ts == 0L || (now - it.ts) < MEMBER_TTL_MS }
            .sortedBy { it.name.lowercase() }

        _uiState.value = _uiState.value.copy(
            roomCode = code,
            members = activeMembers,
            latestState = snap.state,
            latestMedia = snap.media
        )

        snap.state?.let { processIncomingState(it) }

        snap.media?.let { med ->
            var shouldEmit = false
            val mKey = med.matchKey()
            synchronized(roomLock) {
                if (med.by.isNotBlank() && med.by != clientId) {
                    val isDifferentMedia = mKey != lastAppliedMediaMatchKey
                    val isStrictlyNewer = med.updatedAt > lastAppliedMediaAt + MEDIA_RETRIGGER_MIN_DELTA_MS
                    if (isDifferentMedia || isStrictlyNewer) {
                        lastAppliedMediaMatchKey = mKey
                        lastAppliedMediaAt = max(lastAppliedMediaAt, med.updatedAt)
                        shouldEmit = true
                    }
                }
            }
            if (shouldEmit) {
                onRemoteMedia?.invoke(med, memberName(med.by))
            }
        }

        snap.reaction?.let { react ->
            var shouldEmit = false
            val ageMs = if (react.ts > 0L) (now - react.ts) else 0L
            synchronized(roomLock) {
                if (react.id != lastAppliedReactionId) {
                    lastAppliedReactionId = react.id
                    if (react.by != clientId && ageMs < REACTION_MAX_AGE_MS) {
                        shouldEmit = true
                    }
                }
            }
            if (shouldEmit) {
                onRemoteReaction?.invoke(react)
            }
        }
    }

    private fun processIncomingState(st: PlaybackState) {
        var shouldEmit = false
        synchronized(roomLock) {
            lastKnownSeq = max(lastKnownSeq, st.seq)
            if (st.by.isNotBlank() && st.by != clientId && st.seq > lastAppliedRemoteSeq) {
                lastAppliedRemoteSeq = st.seq
                shouldEmit = true
            }
        }
        if (shouldEmit) {
            onRemoteState?.invoke(st, memberName(st.by))
        }
    }

    private fun applyTreeUpdate(rawPath: String, data: Any?, isPatch: Boolean) {
        val segments = rawPath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            when (data) {
                is JSONObject -> {
                    if (isPatch) {
                        mergePatch(roomJson, data)
                    } else {
                        roomJson = data
                    }
                }
                null -> roomJson = JSONObject()
            }
            return
        }

        var cursor = roomJson
        for (i in 0 until segments.lastIndex) {
            val seg = segments[i]
            val next = cursor.optJSONObject(seg) ?: JSONObject().also { cursor.put(seg, it) }
            cursor = next
        }
        val leaf = segments.last()
        when {
            data == null -> cursor.remove(leaf)
            isPatch && data is JSONObject -> {
                val target = cursor.optJSONObject(leaf) ?: JSONObject().also { cursor.put(leaf, it) }
                mergePatch(target, data)
            }
            else -> cursor.put(leaf, data)
        }
    }

    private fun mergePatch(target: JSONObject, patch: JSONObject) {
        val keys = patch.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (patch.isNull(k)) {
                target.remove(k)
            } else {
                target.put(k, patch.get(k))
            }
        }
    }

    private fun defaultDeviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isNotEmpty()) model.take(18) else "Misafir"
    }

    companion object {
        private const val PREFS_NAME = "birlikte_izle_prefs"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_SERVER_INPUT = "server_input"
        private const val PRIVATE_SHORT_CODE = "gokbey4616"
        private const val HEARTBEAT_MS = 30_000L
        private const val STATE_FALLBACK_MS = 12_000L
        private const val MEMBER_TTL_MS = 180_000L
        private const val REACTION_MAX_AGE_MS = 15_000L
        private const val MEDIA_RETRIGGER_MIN_DELTA_MS = 20_000L
        private val CODE_REGEX = Regex("^\\d{6}$")
    }
}
