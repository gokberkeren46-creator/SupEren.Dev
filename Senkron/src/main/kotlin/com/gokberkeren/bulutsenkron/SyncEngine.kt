package com.gokberkeren.bulutsenkron

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import com.lagradost.cloudstream3.utils.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/**
 * "Last uploader wins" snapshot sync for Continue Watching (live TV & downloads excluded).
 * Syncs exact CloudStream keys:
 *  - download_header_cache/{pid} & BACKUP_download_header_cache/{pid} (card poster/title/url/apiName)
 *  - {account}/result_resume_watching_2/{pid}
 *  - {account}/video_pos_dur/{epId}
 *  - {account}/result_watch_state_data/{pid}
 *
 * Also listens to local DataStore removals on Home screen so deleting an item pushes immediately
 * and never comes back, and cleans up completely on beforeUnload().
 */
class SyncEngine(
    private val appContext: Context,
    private val prefs: SyncPrefs
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(18, TimeUnit.SECONDS)
        .build()

    private val state = appContext.getSharedPreferences("senkron_state_v5", Context.MODE_PRIVATE)
    private val deviceId: String = state.getString("device_id", null) ?: run {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        state.edit().putString("device_id", id).apply()
        id
    }

    private var registeredApp: Application? = null
    private val registeredActivities = Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())
    private var currentActivityRef: WeakReference<FragmentActivity>? = null
    private var activeHomeFragmentRef: WeakReference<Fragment>? = null
    private var dataStorePrefs: SharedPreferences? = null
    private var pendingJob: Job? = null

    private var badgeHost: ViewGroup? = null
    private var badgeRoot: FrameLayout? = null
    private var badgeDot: View? = null
    private var badgeText: TextView? = null

    @Volatile
    private var unloaded: Boolean = false

    @Volatile
    private var isPlayerOpen: Boolean = false

    @Volatile
    private var initialPullCompleted: Boolean = false

    @Volatile
    private var applyingRemoteSnapshot: Boolean = false

    @Volatile
    private var ignorePrefChangesUntilMs: Long = 0L

    @Volatile
    private var pushPending: Boolean = false

    @Volatile
    private var httpState: HttpState = HttpState.PENDING

    @Volatile
    private var cloudUpdatedAtMs: Long = 0L

    @Volatile
    private var cloudItemCount: Int = 0

    private enum class HttpState { PENDING, OK, ERROR }

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (unloaded || key == null) return@OnSharedPreferenceChangeListener
        if (applyingRemoteSnapshot || SystemClock.elapsedRealtime() < ignorePrefChangesUntilMs) {
            return@OnSharedPreferenceChangeListener
        }
        // Wait until first cloud pull finishes so opening the app never clobbers newer remote data
        if (!initialPullCompleted) return@OnSharedPreferenceChangeListener

        val isResumeMutation = key.contains("$FOLDER_RESUME/")
        val isPosMutation = key.contains("$FOLDER_POS_DUR/")
        if (!isResumeMutation && !isPosMutation) return@OnSharedPreferenceChangeListener

        // While inside player, wait for player exit so we don't upload every second;
        // Outside player (e.g. deleting an item from Continue Watching on Home screen!), push right away!
        if (!isPlayerOpen && prefs.resolveEndpointUrl() != null) {
            schedulePush(delayMs = 1_200L)
        }
    }

    fun register(application: Application, initialActivity: Activity?) {
        registeredApp = application
        application.registerActivityLifecycleCallbacks(activityCallbacks)
        (initialActivity as? FragmentActivity)?.let { attachToActivity(it) }

        try {
            val sp = with(DataStore) { appContext.getSharedPrefs() }
            dataStorePrefs = sp
            sp.registerOnSharedPreferenceChangeListener(prefChangeListener)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not register DataStore listener", t)
        }

        if (prefs.resolveEndpointUrl() != null) {
            launchPull(delayMs = 800L)
        }
    }

    fun unregister() {
        unloaded = true
        pendingJob?.cancel()
        pendingJob = null

        try {
            dataStorePrefs?.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        } catch (_: Throwable) {
        }
        dataStorePrefs = null

        registeredApp?.let { app ->
            try {
                app.unregisterActivityLifecycleCallbacks(activityCallbacks)
            } catch (_: Throwable) {
            }
        }
        registeredApp = null

        mainHandler.post {
            for (act in registeredActivities.toList()) {
                try {
                    act.supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragmentCallbacks)
                } catch (_: Throwable) {
                }
            }
            registeredActivities.clear()
            detachBadge()
        }
        scope.cancel()
    }

    fun currentActivity(): FragmentActivity? = currentActivityRef?.get()?.takeIf {
        !it.isFinishing && !it.isDestroyed
    }

    fun triggerAutoSyncNow() {
        if (unloaded) return
        launchPull(delayMs = 200L)
    }

    suspend fun syncNow(): Result<String> = pull()

    private fun launchPull(delayMs: Long) {
        if (unloaded) return
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(delayMs)
            pull()
        }
    }

    private fun schedulePush(delayMs: Long) {
        if (unloaded) return
        pushPending = true
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(delayMs)
            push()
        }
    }

    private suspend fun pull(): Result<String> = withContext(Dispatchers.IO) {
        if (unloaded) return@withContext Result.failure(IllegalStateException("Eklenti kaldırıldı"))
        val url = prefs.resolveEndpointUrl()
        if (url == null) {
            httpState = HttpState.ERROR
            updateBadge()
            return@withContext Result.failure(
                IllegalStateException("Lütfen sunucu/kısa kod ve 6 haneli Profil Kodu (PIN) girin.")
            )
        }
        if (pushPending) {
            return@withContext Result.success("Gönderim bekleniyor")
        }

        mutex.withLock {
            try {
                val resp = httpGet(url)
                if (!resp.ok) {
                    httpState = HttpState.ERROR
                    updateBadge()
                    return@withLock Result.failure(IllegalStateException("Firebase okuma hatası"))
                }

                val remote = resp.body?.let { try { JSONObject(it) } catch (_: Throwable) { null } }
                if (remote == null) {
                    // Cloud file does not exist yet on v5: upload this device's current list once
                    val initRes = doPush(url)
                    initialPullCompleted = true
                    return@withLock initRes
                }

                val remoteTime = remote.optLong("updatedAt", 0L)
                val remoteDevice = remote.optString("deviceId", "")
                val entries = parseEntries(remote.optJSONObject("resume"))
                val lastAppliedTime = state.getLong("last_applied_updated_at", 0L)

                if (remoteDevice != deviceId && remoteTime > lastAppliedTime) {
                    replaceLocal(entries)
                    state.edit().putLong("last_applied_updated_at", remoteTime).apply()
                    reloadHomeUi()
                } else if (remoteTime > lastAppliedTime) {
                    state.edit().putLong("last_applied_updated_at", remoteTime).apply()
                }

                initialPullCompleted = true
                markCloudVerified(remoteTime, entries.size)
                Result.success("Senkron ${formatClock(remoteTime)} • ${entries.size} içerik")
            } catch (t: Throwable) {
                Log.e(TAG, "Pull failed", t)
                httpState = HttpState.ERROR
                updateBadge()
                Result.failure(t)
            }
        }
    }

    private suspend fun push(): Result<String> = withContext(Dispatchers.IO) {
        if (unloaded) {
            pushPending = false
            return@withContext Result.failure(IllegalStateException("Eklenti kaldırıldı"))
        }
        val url = prefs.resolveEndpointUrl()
        if (url == null) {
            pushPending = false
            httpState = HttpState.ERROR
            updateBadge()
            return@withContext Result.failure(IllegalStateException("Sunucu/PIN yok"))
        }
        mutex.withLock {
            try {
                doPush(url)
            } catch (t: Throwable) {
                Log.e(TAG, "Push failed", t)
                httpState = HttpState.ERROR
                updateBadge()
                Result.failure(t)
            } finally {
                pushPending = false
            }
        }
    }

    private fun doPush(url: String): Result<String> {
        val local = readLocal()
        val now = System.currentTimeMillis()
        val out = JSONObject().apply {
            put("updatedAt", now)
            put("deviceId", deviceId)
            put("resume", serializeEntries(local.entries))
        }
        val ok = httpPut(url, out.toString())
        if (!ok) {
            httpState = HttpState.ERROR
            updateBadge()
            return Result.failure(IllegalStateException("Firebase yazma hatası (kuralları veya ağı kontrol edin)."))
        }
        state.edit().putLong("last_applied_updated_at", now).apply()
        initialPullCompleted = true
        markCloudVerified(now, local.entries.size)
        return Result.success("Buluta yazıldı ${formatClock(now)} • ${local.entries.size} içerik")
    }

    private fun markCloudVerified(actualCloudTimestampMs: Long, itemCount: Int) {
        cloudUpdatedAtMs = actualCloudTimestampMs
        cloudItemCount = itemCount
        httpState = HttpState.OK
        val label = if (actualCloudTimestampMs > 0L) {
            "Son kayıt ${formatClock(actualCloudTimestampMs)} • $itemCount içerik"
        } else {
            "Bulut hazır • 0 içerik"
        }
        prefs.lastSyncSummary = label
        updateBadge()
    }

    private fun formatClock(tsMs: Long): String {
        if (tsMs <= 0L) return "--:--:--"
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(tsMs))
    }

    // ---- Local raw DataStore access ----

    private fun detectPrefix(all: Map<String, *>): String {
        for (key in all.keys) {
            val idx = key.indexOf("$FOLDER_RESUME/")
            if (idx >= 0) {
                val raw = key.substring(0, idx)
                return if (raw.isEmpty() || raw.endsWith("/")) raw else "$raw/"
            }
        }
        return "0/"
    }

    private fun isLiveType(rawJson: String?): Boolean {
        if (rawJson.isNullOrBlank()) return false
        return try {
            val t = JSONObject(rawJson).optString("type", "")
            t.equals("Live", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun readLocal(): LocalSnapshot {
        val sp = with(DataStore) { appContext.getSharedPrefs() }
        val all = sp.all
        val prefix = detectPrefix(all)
        val entries = LinkedHashMap<String, RawEntry>()
        val marker = "$FOLDER_RESUME/"

        for (e in all.entries) {
            val key = e.key
            val rawResume = e.value as? String ?: continue
            val mi = key.indexOf(marker)
            if (mi < 0) continue
            val pfx = key.substring(0, mi)
            val pid = key.substring(mi + marker.length)
            if (pid.isBlank()) continue

            try {
                val r = JSONObject(rawResume)
                if (r.optBoolean("isFromDownload", false)) continue
                val epId = if (r.has("episodeId") && !r.isNull("episodeId")) {
                    r.optInt("episodeId").toString()
                } else {
                    pid
                }

                // CRITICAL: HomeViewModel reads card metadata from download_header_cache/{pid} (NO account prefix!)
                val headerCache = (all["$FOLDER_HEADER_CACHE/$pid"] as? String)
                    ?: (all["$FOLDER_HEADER_CACHE_BACKUP/$pid"] as? String)
                val bookmarked = all["$pfx$FOLDER_BOOKMARKED/$pid"] as? String

                if (isLiveType(headerCache) || isLiveType(bookmarked)) continue
                // Skip orphan resume entries that have no header cache at all (cannot render on Home)
                if (headerCache.isNullOrBlank()) continue

                val updateTime = r.optLong("updateTime", 0L)
                val existing = entries[pid]
                if (existing == null || updateTime > existing.updateTime) {
                    entries[pid] = RawEntry(
                        parentId = pid,
                        episodeId = epId,
                        updateTime = updateTime,
                        resumeRaw = rawResume,
                        headerCacheRaw = headerCache,
                        bookmarkedRaw = bookmarked,
                        posDurRaw = all["$pfx$FOLDER_POS_DUR/$epId"] as? String
                    )
                }
            } catch (_: Throwable) {
            }
        }
        return LocalSnapshot(prefix, entries)
    }

    private fun replaceLocal(snapshot: Map<String, RawEntry>) {
        applyingRemoteSnapshot = true
        ignorePrefChangesUntilMs = SystemClock.elapsedRealtime() + 2_500L
        try {
            val sp = with(DataStore) { appContext.getSharedPrefs() }
            val all = sp.all
            val prefix = detectPrefix(all)
            val marker = "$FOLDER_RESUME/"
            val editor = sp.edit()

            // Remove any local non-download, non-live resume item that was deleted in the latest snapshot
            for (k in all.keys) {
                val mi = k.indexOf(marker)
                if (mi < 0) continue
                val pid = k.substring(mi + marker.length)
                if (!snapshot.containsKey(pid)) {
                    val raw = all[k] as? String
                    val isDownload = try {
                        raw != null && JSONObject(raw).optBoolean("isFromDownload", false)
                    } catch (_: Throwable) {
                        false
                    }
                    if (!isDownload) {
                        editor.remove(k)
                    }
                }
            }

            // Write all entries including download_header_cache/{pid} so brand-new movies render on Home!
            for (e in snapshot.values) {
                editor.putString("$prefix$FOLDER_RESUME/${e.parentId}", e.resumeRaw)
                if (!e.headerCacheRaw.isNullOrBlank()) {
                    editor.putString("$FOLDER_HEADER_CACHE/${e.parentId}", e.headerCacheRaw)
                    editor.putString("$FOLDER_HEADER_CACHE_BACKUP/${e.parentId}", e.headerCacheRaw)
                }
                if (!e.bookmarkedRaw.isNullOrBlank()) {
                    editor.putString("$prefix$FOLDER_BOOKMARKED/${e.parentId}", e.bookmarkedRaw)
                }
                if (!e.posDurRaw.isNullOrBlank()) {
                    editor.putString("$prefix$FOLDER_POS_DUR/${e.episodeId}", e.posDurRaw)
                }
            }
            editor.commit()
        } finally {
            ignorePrefChangesUntilMs = SystemClock.elapsedRealtime() + 1_500L
            applyingRemoteSnapshot = false
        }
    }

    private fun parseEntries(obj: JSONObject?): Map<String, RawEntry> {
        if (obj == null) return emptyMap()
        val map = LinkedHashMap<String, RawEntry>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val pid = keys.next()
            val item = obj.optJSONObject(pid) ?: continue
            val resumeRaw = item.optString("resumeRaw", "")
            val headerCacheRaw = item.optString("headerCacheRaw", "")
            if (resumeRaw.isBlank() || headerCacheRaw.isBlank()) continue
            map[pid] = RawEntry(
                parentId = pid,
                episodeId = item.optString("episodeId", pid),
                updateTime = item.optLong("updateTime", 0L),
                resumeRaw = resumeRaw,
                headerCacheRaw = headerCacheRaw,
                bookmarkedRaw = item.optString("bookmarkedRaw", "").takeIf { it.isNotBlank() },
                posDurRaw = item.optString("posDurRaw", "").takeIf { it.isNotBlank() }
            )
        }
        return map
    }

    private fun serializeEntries(map: Map<String, RawEntry>): JSONObject {
        val obj = JSONObject()
        map.values.sortedByDescending { it.updateTime }.take(MAX_ITEMS).forEach { e ->
            obj.put(
                e.parentId,
                JSONObject().apply {
                    put("episodeId", e.episodeId)
                    put("updateTime", e.updateTime)
                    put("resumeRaw", e.resumeRaw)
                    if (e.headerCacheRaw != null) put("headerCacheRaw", e.headerCacheRaw)
                    if (e.bookmarkedRaw != null) put("bookmarkedRaw", e.bookmarkedRaw)
                    if (e.posDurRaw != null) put("posDurRaw", e.posDurRaw)
                }
            )
        }
        return obj
    }

    // ---- Home UI refresh ----

    private fun unwrap(o: Any?): Any? = if (o is Lazy<*>) o.value else o

    private fun reloadHomeUi() {
        mainHandler.post {
            if (unloaded) return@post
            val homeFrag = activeHomeFragmentRef?.get() ?: return@post
            try {
                var cur: Class<*>? = homeFrag.javaClass
                while (cur != null) {
                    for (f in cur.declaredFields) {
                        if (!f.name.contains("viewModel", ignoreCase = true)) continue
                        f.isAccessible = true
                        val vm = unwrap(f.get(homeFrag)) ?: continue
                        val m = vm.javaClass.declaredMethods.firstOrNull {
                            it.name == "loadResumeWatching" && it.parameterCount == 0
                        } ?: continue
                        m.isAccessible = true
                        m.invoke(vm)
                        return@post
                    }
                    cur = cur.superclass
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Home reload failed", t)
            }
        }
    }

    // ---- Honest Top-Left Badge ----

    private fun attachBadge(fragment: Fragment) {
        if (unloaded) return
        detachBadge()
        val container = fragment.view as? ViewGroup ?: return
        val ctx = container.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        val root = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            elevation = 999f
            isClickable = false
            isFocusable = false
        }
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(9), dp(4))
            background = GradientDrawable().apply {
                setColor(0xAA121622.toInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0x44FFFFFF)
            }
            isClickable = true
            isFocusable = false
            setOnClickListener { launchPull(delayMs = 0L) }
        }
        val dot = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(5) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_RED)
            }
        }
        val label = TextView(ctx).apply {
            textSize = 10.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        pill.addView(dot)
        pill.addView(label)
        root.addView(
            pill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                marginStart = dp(10)
                topMargin = dp(8)
            }
        )
        container.addView(root)
        root.bringToFront()
        badgeHost = container
        badgeRoot = root
        badgeDot = dot
        badgeText = label
        updateBadge()
    }

    private fun detachBadge() {
        val root = badgeRoot
        val host = badgeHost
        if (root != null && host != null) {
            try {
                host.removeView(root)
            } catch (_: Throwable) {
            }
        }
        badgeRoot = null
        badgeHost = null
        badgeDot = null
        badgeText = null
    }

    private fun updateBadge() {
        mainHandler.post {
            if (unloaded) return@post
            val dotBg = badgeDot?.background as? GradientDrawable ?: return@post
            val label = badgeText ?: return@post
            val configured = prefs.resolveEndpointUrl() != null
            when {
                !configured -> {
                    dotBg.setColor(COLOR_RED)
                    label.text = "Senkron değil"
                }
                httpState == HttpState.OK -> {
                    dotBg.setColor(COLOR_GREEN)
                    label.text = if (cloudUpdatedAtMs > 0L) {
                        "${formatClock(cloudUpdatedAtMs)} • $cloudItemCount"
                    } else {
                        "Hazır • 0"
                    }
                }
                httpState == HttpState.PENDING -> {
                    dotBg.setColor(COLOR_RED)
                    label.text = "Kontrol..."
                }
                else -> {
                    dotBg.setColor(COLOR_RED)
                    label.text = "Senkron değil"
                }
            }
            badgeRoot?.bringToFront()
        }
    }

    // ---- HTTP ----

    private data class HttpGetResult(val ok: Boolean, val body: String?)

    private fun httpGet(url: String): HttpGetResult {
        val req = Request.Builder().url(url).get().build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return HttpGetResult(ok = false, body = null)
            val raw = resp.body?.string()?.trim()
            val clean = if (raw.isNullOrEmpty() || raw == "null") null else raw
            HttpGetResult(ok = true, body = clean)
        }
    }

    private fun httpPut(url: String, json: String): Boolean {
        val req = Request.Builder().url(url).put(json.toRequestBody(jsonMediaType)).build()
        return http.newCall(req).execute().use { it.isSuccessful }
    }

    // ---- Lifecycle ----

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (!unloaded) (activity as? FragmentActivity)?.let { attachToActivity(it) }
        }
        override fun onActivityStarted(activity: Activity) {
            if (!unloaded) (activity as? FragmentActivity)?.let { attachToActivity(it) }
        }
        override fun onActivityResumed(activity: Activity) {
            if (!unloaded) {
                (activity as? FragmentActivity)?.let {
                    currentActivityRef = WeakReference(it)
                    attachToActivity(it)
                }
            }
        }
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (currentActivityRef?.get() === activity) currentActivityRef = null
        }
    }

    private fun attachToActivity(activity: FragmentActivity) {
        if (unloaded) return
        currentActivityRef = WeakReference(activity)
        if (registeredActivities.add(activity)) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
        }
    }

    private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (unloaded) return
            if (f is AbstractPlayerFragment<*>) {
                isPlayerOpen = true
            } else if (f.javaClass.name.contains("HomeFragment")) {
                activeHomeFragmentRef = WeakReference(f)
                attachBadge(f)
                if (!pushPending) launchPull(delayMs = 400L)
            }
        }

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            if (unloaded) return
            if (f.javaClass.name.contains("HomeFragment") && activeHomeFragmentRef?.get() === f) {
                detachBadge()
                activeHomeFragmentRef = null
            } else if (f is AbstractPlayerFragment<*>) {
                isPlayerOpen = false
                if (prefs.resolveEndpointUrl() != null) {
                    schedulePush(delayMs = 2_000L)
                }
            }
        }
    }

    private data class LocalSnapshot(val prefix: String, val entries: Map<String, RawEntry>)

    private data class RawEntry(
        val parentId: String,
        val episodeId: String,
        val updateTime: Long,
        val resumeRaw: String,
        val headerCacheRaw: String?,
        val bookmarkedRaw: String?,
        val posDurRaw: String?
    )

    companion object {
        private const val TAG = "Senkron"
        private const val FOLDER_RESUME = "result_resume_watching_2"
        private const val FOLDER_HEADER_CACHE = "download_header_cache"
        private const val FOLDER_HEADER_CACHE_BACKUP = "BACKUP_download_header_cache"
        private const val FOLDER_BOOKMARKED = "result_watch_state_data"
        private const val FOLDER_POS_DUR = "video_pos_dur"
        private const val MAX_ITEMS = 50
        private const val COLOR_GREEN = 0xFF22C55E.toInt()
        private const val COLOR_RED = 0xFFEF4444.toInt()
    }
}
