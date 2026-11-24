package com.gokberkeren.birlikteizle

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * Lightweight Firebase Realtime Database REST + Server-Sent Events (SSE) client.
 * Supports runtime server configuration and optional root path prefix for private rules.
 */
class FirebaseClient {

    @Volatile
    private var baseUrl: String = ""

    @Volatile
    private var rootPrefix: String = ""

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val restClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val sseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Firebase RTDB sends keep-alive every 30s; 45s timeout detects silent Wi-Fi/NAT drops
        .readTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    var serverClockOffsetMs: Long = 0L
        private set

    fun configure(url: String, prefix: String = "") {
        baseUrl = url.trim().trimEnd('/')
        rootPrefix = prefix.trim().trim('/')
    }

    fun isConfigured(): Boolean = baseUrl.startsWith("https://") || baseUrl.startsWith("http://")

    fun estimatedServerNowMs(): Long = System.currentTimeMillis() + serverClockOffsetMs

    private fun buildUrl(path: String): String {
        val clean = path.trimStart('/').removeSuffix(".json")
        val fullPath = if (rootPrefix.isNotEmpty()) "$rootPrefix/$clean" else clean
        return "$baseUrl/$fullPath.json"
    }

    suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        val req = Request.Builder()
            .url(buildUrl(path))
            .get()
            .build()
        try {
            restClient.newCall(req).execute().use { resp ->
                updateClockOffset(resp.header("Date"))
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GET $path failed: HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string()?.trim()
                if (body.isNullOrEmpty() || body == "null") null else body
            }
        } catch (t: Throwable) {
            Log.e(TAG, "GET $path error", t)
            null
        }
    }

    suspend fun put(path: String, json: String): Boolean = executeWrite("PUT", path, json)

    suspend fun patch(path: String, json: String): Boolean = executeWrite("PATCH", path, json)

    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false
        val req = Request.Builder()
            .url(buildUrl(path))
            .delete()
            .build()
        try {
            restClient.newCall(req).execute().use { resp ->
                updateClockOffset(resp.header("Date"))
                resp.isSuccessful
            }
        } catch (t: Throwable) {
            Log.e(TAG, "DELETE $path error", t)
            false
        }
    }

    private suspend fun executeWrite(method: String, path: String, json: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) return@withContext false
            val body = json.toRequestBody(jsonMediaType)
            val req = Request.Builder()
                .url(buildUrl(path))
                .method(method, body)
                .build()
            try {
                restClient.newCall(req).execute().use { resp ->
                    updateClockOffset(resp.header("Date"))
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "$method $path failed: HTTP ${resp.code}")
                    }
                    resp.isSuccessful
                }
            } catch (t: Throwable) {
                Log.e(TAG, "$method $path error", t)
                false
            }
        }

    fun listenRoom(
        scope: CoroutineScope,
        code: String,
        onEvent: (event: String, path: String, dataValue: Any?) -> Unit,
        onConnectionChanged: (Boolean) -> Unit
    ): Closeable {
        val activeCall = AtomicReference<Call?>(null)
        val closed = AtomicBoolean(false)

        val job: Job = scope.launch(Dispatchers.IO) {
            var backoffMs = 1000L
            while (isActive && !closed.get()) {
                if (!isConfigured()) {
                    onConnectionChanged(false)
                    delay(2000L)
                    continue
                }
                val url = buildUrl("rooms/$code")
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()

                try {
                    val call = sseClient.newCall(req)
                    activeCall.set(call)
                    call.execute().use { resp ->
                        updateClockOffset(resp.header("Date"))
                        if (!resp.isSuccessful) {
                            Log.w(TAG, "SSE HTTP ${resp.code} for room $code")
                            onConnectionChanged(false)
                        } else {
                            val stream = resp.body?.byteStream()
                            if (stream != null) {
                                backoffMs = 1000L
                                onConnectionChanged(true)
                                val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                                parseSseStream(reader, onEvent) { closed.get() }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (!closed.get()) {
                        Log.w(TAG, "SSE disconnected for room $code: ${t.message}")
                    }
                } finally {
                    activeCall.set(null)
                }

                if (!closed.get() && isActive) {
                    onConnectionChanged(false)
                    delay(backoffMs)
                    backoffMs = min(backoffMs * 2, 10_000L)
                }
            }
        }

        return Closeable {
            closed.set(true)
            activeCall.getAndSet(null)?.cancel()
            job.cancel()
            onConnectionChanged(false)
        }
    }

    private fun parseSseStream(
        reader: BufferedReader,
        onEvent: (event: String, path: String, dataValue: Any?) -> Unit,
        isClosed: () -> Boolean
    ) {
        var eventName = "message"
        val dataBuilder = StringBuilder()

        while (!isClosed()) {
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> {
                    if (dataBuilder.isNotEmpty()) {
                        val payload = dataBuilder.toString().trim()
                        dataBuilder.setLength(0)
                        dispatchSseEvent(eventName, payload, onEvent)
                    }
                    eventName = "message"
                }
                line.startsWith("event:") -> {
                    eventName = line.substring(6).trim()
                }
                line.startsWith("data:") -> {
                    if (dataBuilder.isNotEmpty()) dataBuilder.append('\n')
                    dataBuilder.append(line.substring(5).trimStart())
                }
            }
        }
    }

    private fun dispatchSseEvent(
        eventName: String,
        payload: String,
        onEvent: (event: String, path: String, dataValue: Any?) -> Unit
    ) {
        if (eventName == "keep-alive") return
        if (eventName == "cancel" || eventName == "auth_revoked") {
            Log.w(TAG, "SSE terminal event: $eventName")
            return
        }
        if (payload.isEmpty() || payload == "null") return
        try {
            val json = JSONObject(payload)
            val path = json.optString("path", "/")
            val data = if (json.isNull("data")) null else json.opt("data")
            onEvent(eventName, path, data)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to parse SSE payload: $payload", t)
        }
    }

    private fun updateClockOffset(dateHeader: String?) {
        if (dateHeader.isNullOrBlank()) return
        try {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val serverMs = fmt.parse(dateHeader)?.time ?: return
            serverClockOffsetMs = serverMs - System.currentTimeMillis()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "BirlikteIzleFB"
    }
}
