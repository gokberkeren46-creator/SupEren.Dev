package com.gokberkeren.bulutsenkron

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * "Last uploader wins" snapshot sync for Continue Watching (live TV excluded).
 *  - PUSH: right after leaving the player, this device uploads its whole list as a single file.
 *  - PULL: when Home opens (or badge tapped), if another device uploaded newer, local list is replaced.
 * No polling, no merging, so devices can never overwrite each other's fresh data.
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val state = appContext.getSharedPreferences("senkron_state", Context.MODE_PRIVATE)
    private val deviceId: String = state.getString("device_id", null) ?: run {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        state.edit().putString("device_id", id).apply()
        id
    }

    private val registeredActivities = Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())
    private var currentActivityRef: WeakReference<FragmentActivity>? = null
    private var activeHomeFragmentRef: WeakReference<Fragment>? = null
    private var pendingJob: Job? = null

    private var badgeHost: ViewGroup? = null
    private var badgeRoot: FrameLayout? = null
    private var badgeDot: View? = null
    private var badgeText: TextView? = null

    @Volatile
    private var pushPending: Boolean = false

    @Volatile
    private var lastSyncClockSec: String = ""

    @Volatile
    private var lastSyncOk: Boolean = false

    fun register(application: Application, initialActivity: Activity?) {
        application.registerActivityLifecycleCallbacks(activityCallbacks)
        (initialActivity as? FragmentActivity)?.let { attachToActivity(it) }
    }

    fun currentActivity(): FragmentActivity? = currentActivityRef?.get()?.takeIf {
        !it.isFinishing && !it.isDestroyed
    }

    /** Called when settings dialog closes: pull (or initialise the cloud file if it doesn't exist yet). */
    fun triggerAutoSyncNow() {
        launchPull(delayMs = 200L)
    }

    /** Manual button / badge tap: pull latest from cloud. */
    suspend fun syncNow(): Result<String> = pull()

    private fun launchPull(delayMs: Long) {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(delayMs)
            pull()
        }
    }

    private fun schedulePush(delayMs: Long) {
        pushPending = true
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(delayMs)
            push()
        }
    }

    private suspend fun pull(): Result<String> = withContext(Dispatchers.IO) {
        val url = prefs.resolveEndpointUrl()
        if (url == null) {
            lastSyncOk = false
            updateBadge()
            return@withContext Result.failure(
                IllegalStateException("Lütfen sunucu/kısa kod ve 6 haneli Profil Kodu (PIN) girin.")
            )
        }
        if (pushPending) {
            return@withContext Result.success("Gönderim bekleniyor, çekme atlandı")
        }
        mutex.withLock {
            try {
                val raw = httpGet(url)
                val remote = raw?.let { try { JSONObject(it) } catch (_: Throwable) { null } }
                if (remote == null) {
                    // Cloud file doesn't exist yet: initialise it with this device's list.
                    return@withLock doPush(url)
                }
                val remoteTime = remote.optLong("updatedAt", 0L)
                val remoteDevice = remote.optString("deviceId", "")
                val lastKnown = state.getLong("last_known_updated_at", 0L)

                if (remoteDevice != deviceId && remoteTime > lastKnown) {
                    val entries = parseEntries(remote.optJSONObject("resume"))
                    replaceLocal(entries)
                    state.edit().putLong("last_known_updated_at", remoteTime).apply()
                    reloadHomeUi()
                    markOk()
                    Result.success("Buluttan ${entries.size} içerik alındı")
                } else {
                    markOk()
                    Result.success("Güncel")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Pull failed", t)
                lastSyncOk = false
                updateBadge()
                Result.failure(t)
            }
        }
    }

    private suspend fun push(): Result<String> = withContext(Dispatchers.IO) {
        val url = prefs.resolveEndpointUrl()
        if (url == null) {
            pushPending = false
            return@withContext Result.failure(IllegalStateException("Sunucu/PIN yok"))
        }
        mutex.withLock {
            try {
                doPush(url)
            } catch (t: Throwable) {
                Log.e(TAG, "Push failed", t)
                lastSyncOk = false
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
            lastSyncOk = false
            updateBadge()
            return Result.failure(IllegalStateException("Firebase yazma hatası (kuralları veya ağı kontrol edin)."))
        }
        state.edit().putLong("last_known_updated_at", now).apply()
        markOk()
        return Result.success("${local.entries.size} içerik buluta gönderildi")
    }

    private fun markOk() {
        val clockSec = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        lastSyncClockSec = clockSec
        lastSyncOk = true
        prefs.lastSyncSummary = "Son eşitleme $clockSec"
        updateBadge()
    }

    // ---- Local raw DataStore access ----

    private fun detectPrefix(all: Map<String, *>): String {
        for (key in all.keys) {
            val idx = key.indexOf(FOLDER_RESUME)
            if (idx >= 0) return key.substring(0, idx).let { if (it.endsWith("/") || it.isEmpty()) it else "$it/" }
        }
        return "0/"
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
                val epId = if (r.has("episodeId") && !r.isNull("episodeId")) r.optInt("episodeId").toString() else pid
                val bookmarked = all["$pfx$FOLDER_BOOKMARKED/$pid"] as? String
                if (bookmarked != null) {
                    val type = try { JSONObject(bookmarked).optString("type", "") } catch (_: Throwable) { "" }
                    if (type.equals("Live", ignoreCase = true)) continue
                }
                entries[pid] = RawEntry(
                    parentId = pid,
                    episodeId = epId,
                    updateTime = r.optLong("updateTime", 0L),
                    resumeRaw = rawResume,
                    bookmarkedRaw = bookmarked,
                    posDurRaw = all["$pfx$FOLDER_POS_DUR/$epId"] as? String
                )
            } catch (_: Throwable) {
            }
        }
        return LocalSnapshot(prefix, entries)
    }

    /** Replace local (non-live, non-download) Continue Watching list with the snapshot. */
    private fun replaceLocal(snapshot: Map<String, RawEntry>) {
        val sp = with(DataStore) { appContext.getSharedPrefs() }
        val local = readLocal()
        val editor = sp.edit()
        for (pid in local.entries.keys) {
            if (!snapshot.containsKey(pid)) {
                editor.remove("${local.prefix}$FOLDER_RESUME/$pid")
            }
        }
        for (e in snapshot.values) {
            editor.putString("${local.prefix}$FOLDER_RESUME/${e.parentId}", e.resumeRaw)
            if (!e.bookmarkedRaw.isNullOrBlank()) {
                editor.putString("${local.prefix}$FOLDER_BOOKMARKED/${e.parentId}", e.bookmarkedRaw)
            }
            if (!e.posDurRaw.isNullOrBlank()) {
                editor.putString("${local.prefix}$FOLDER_POS_DUR/${e.episodeId}", e.posDurRaw)
            }
        }
        editor.commit()
    }

    private fun parseEntries(obj: JSONObject?): Map<String, RawEntry> {
        if (obj == null) return emptyMap()
        val map = LinkedHashMap<String, RawEntry>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val pid = keys.next()
            val item = obj.optJSONObject(pid) ?: continue
            val resumeRaw = item.optString("resumeRaw", "")
            if (resumeRaw.isBlank()) continue
            map[pid] = RawEntry(
                parentId = pid,
                episodeId = item.optString("episodeId", pid),
                updateTime = item.optLong("updateTime", 0L),
                resumeRaw = resumeRaw,
                bookmarkedRaw = item.optString("bookmarkedRaw", "").takeIf { it.isNotBlank() },
                posDurRaw = item.optString("posDurRaw", "").takeIf { it.isNotBlank() }
            )
        }
        return map
    }

    private fun serializeEntries(map: Map<String, RawEntry>): JSONObject {
        val obj = JSONObject()
        map.values.sortedByDescending { it.updateTime }.take(MAX_ITEMS).forEach { e ->
            obj.put(e.parentId, JSONObject().apply {
                put("episodeId", e.episodeId)
                put("updateTime", e.updateTime)
                put("resumeRaw", e.resumeRaw)
                if (e.bookmarkedRaw != null) put("bookmarkedRaw", e.bookmarkedRaw)
                if (e.posDurRaw != null) put("posDurRaw", e.posDurRaw)
            })
        }
        return obj
    }

    // ---- Home UI refresh ----

    private fun unwrap(o: Any?): Any? {
        if (o is Lazy<*>) return o.value
        return o
    }

    private fun reloadHomeUi() {
        mainHandler.post {
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

    // ---- Badge ----

    private fun attachBadge(fragment: Fragment) {
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
            try { host.removeView(root) } catch (_: Throwable) {}
        }
        badgeRoot = null
        badgeHost = null
        badgeDot = null
        badgeText = null
    }

    private fun updateBadge() {
        mainHandler.post {
            val dotBg = badgeDot?.background as? GradientDrawable ?: return@post
            val label = badgeText ?: return@post
            val configured = prefs.resolveEndpointUrl() != null
            if (configured && lastSyncOk && lastSyncClockSec.isNotEmpty()) {
                dotBg.setColor(COLOR_GREEN)
                label.text = "Senkron $lastSyncClockSec"
            } else if (configured) {
                dotBg.setColor(COLOR_RED)
                label.text = "Senkron Bekliyor"
            } else {
                dotBg.setColor(COLOR_RED)
                label.text = "Senkron Kapalı"
            }
            badgeRoot?.bringToFront()
        }
    }

    // ---- HTTP ----

    private fun httpGet(url: String): String? {
        val req = Request.Builder().url(url).get().build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string()?.trim()
            if (body.isNullOrEmpty() || body == "null") null else body
        }
    }

    private fun httpPut(url: String, json: String): Boolean {
        val req = Request.Builder().url(url).put(json.toRequestBody(jsonMediaType)).build()
        return http.newCall(req).execute().use { it.isSuccessful }
    }

    // ---- Lifecycle ----

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            (activity as? FragmentActivity)?.let { attachToActivity(it) }
        }
        override fun onActivityStarted(activity: Activity) {
            (activity as? FragmentActivity)?.let { attachToActivity(it) }
        }
        override fun onActivityResumed(activity: Activity) {
            (activity as? FragmentActivity)?.let {
                currentActivityRef = WeakReference(it)
                attachToActivity(it)
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
        currentActivityRef = WeakReference(activity)
        if (registeredActivities.add(activity)) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
        }
    }

    private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (f.javaClass.name.contains("HomeFragment")) {
                activeHomeFragmentRef = WeakReference(f)
                attachBadge(f)
                if (!pushPending) launchPull(delayMs = 500L)
            }
        }

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            if (f.javaClass.name.contains("HomeFragment") && activeHomeFragmentRef?.get() === f) {
                detachBadge()
                activeHomeFragmentRef = null
            } else if (f is AbstractPlayerFragment<*>) {
                // Leaving the player: upload this device's list (wait so CloudStream finishes saving position)
                if (prefs.resolveEndpointUrl() != null) schedulePush(delayMs = 2_500L)
            }
        }
    }

    private data class LocalSnapshot(val prefix: String, val entries: Map<String, RawEntry>)

    private data class RawEntry(
        val parentId: String,
        val episodeId: String,
        val updateTime: Long,
        val resumeRaw: String,
        val bookmarkedRaw: String?,
        val posDurRaw: String?
    )

    companion object {
        private const val TAG = "Senkron"
        private const val FOLDER_RESUME = "result_resume_watching_2"
        private const val FOLDER_BOOKMARKED = "result_watch_state_data"
        private const val FOLDER_POS_DUR = "video_pos_dur"
        private const val MAX_ITEMS = 50
        private const val COLOR_GREEN = 0xFF22C55E.toInt()
        private const val COLOR_RED = 0xFFEF4444.toInt()
    }
}
