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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/**
 * Automatic cloud sync for Continue Watching (non-live) using raw CloudStream DataStore JSON.
 * Displays a compact top-left status badge on HomeFragment (green + HH:mm:ss when active, red otherwise).
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

    private val registeredActivities = Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())
    private var currentActivityRef: WeakReference<FragmentActivity>? = null
    private var activeHomeFragmentRef: WeakReference<Fragment>? = null
    private var pendingSyncJob: Job? = null
    private var homePollJob: Job? = null

    private var badgeHost: ViewGroup? = null
    private var badgeRoot: FrameLayout? = null
    private var badgeDot: View? = null
    private var badgeText: TextView? = null

    @Volatile
    private var lastAutoPullElapsedMs: Long = 0L

    @Volatile
    private var lastSyncClockSec: String = ""

    @Volatile
    private var lastSyncOk: Boolean = false

    fun register(application: Application, initialActivity: Activity?) {
        application.registerActivityLifecycleCallbacks(activityCallbacks)
        (initialActivity as? FragmentActivity)?.let { attachToActivity(it) }
        scheduleSync(delayMs = 1_800L, force = true)
    }

    fun currentActivity(): FragmentActivity? = currentActivityRef?.get()?.takeIf {
        !it.isFinishing && !it.isDestroyed
    }

    fun triggerAutoSyncNow() {
        scheduleSync(delayMs = 300L, force = true)
    }

    private fun scheduleSync(delayMs: Long, force: Boolean) {
        if (prefs.resolveEndpointUrl() == null) {
            lastSyncOk = false
            updateHomeBadge()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && (now - lastAutoPullElapsedMs) < AUTO_SYNC_THROTTLE_MS) return
        lastAutoPullElapsedMs = now
        pendingSyncJob?.cancel()
        pendingSyncJob = scope.launch {
            delay(delayMs)
            syncNow()
        }
    }

    suspend fun syncNow(): Result<String> = withContext(Dispatchers.IO) {
        val url = prefs.resolveEndpointUrl()
        if (url == null) {
            lastSyncOk = false
            updateHomeBadge()
            return@withContext Result.failure(
                IllegalStateException("Lütfen sunucu/kısa kod ve 6 haneli Profil Kodu (PIN) girin.")
            )
        }

        mutex.withLock {
            try {
                val remoteJson = httpGet(url)?.let { raw ->
                    try { JSONObject(raw) } catch (_: Throwable) { null }
                }

                val localSnapshot = readLocalRawResume()
                val remoteResume = parseRemoteResume(remoteJson?.optJSONObject("resume"))

                val merged = LinkedHashMap<String, RawResumeEntry>()
                val allIds = localSnapshot.entries.keys + remoteResume.keys
                var pulledCount = 0

                for (pid in allIds) {
                    val loc = localSnapshot.entries[pid]
                    val rem = remoteResume[pid]
                    when {
                        rem != null && (loc == null || rem.updateTime > loc.updateTime) -> {
                            writeRawResumeToLocal(localSnapshot.accountPrefix, rem)
                            merged[pid] = rem
                            pulledCount++
                        }
                        loc != null -> merged[pid] = loc
                    }
                }

                val outJson = JSONObject().apply {
                    put("updatedAt", System.currentTimeMillis())
                    put("resume", serializeResume(merged))
                }
                val ok = httpPut(url, outJson.toString())
                if (!ok) {
                    lastSyncOk = false
                    updateHomeBadge()
                    return@withLock Result.failure(
                        IllegalStateException("Firebase yazma hatası (kuralları veya ağı kontrol edin).")
                    )
                }

                val clockSec = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                lastSyncClockSec = clockSec
                lastSyncOk = true
                val summary = "Son eşitleme $clockSec • ${merged.size} içerik"
                prefs.lastSyncSummary = summary
                updateHomeBadge()

                if (pulledCount > 0) {
                    reloadHomeUi()
                }

                Log.i(TAG, "Sync OK ($summary; pulled=$pulledCount)")
                Result.success(summary)
            } catch (t: Throwable) {
                Log.e(TAG, "Sync failed", t)
                lastSyncOk = false
                updateHomeBadge()
                Result.failure(t)
            }
        }
    }

    private fun readLocalRawResume(): LocalResumeSnapshot {
        val sp = with(DataStore) { appContext.getSharedPrefs() }
        val all = sp.all
        var detectedPrefix = "0/"

        for (key in all.keys) {
            val idx = key.indexOf(FOLDER_RESUME)
            if (idx > 0) {
                detectedPrefix = key.substring(0, idx)
                break
            } else if (idx == 0) {
                detectedPrefix = ""
                break
            }
        }

        val entries = LinkedHashMap<String, RawResumeEntry>()
        val resumeMarker = "$FOLDER_RESUME/"

        for (entry in all.entries) {
            val key = entry.key
            val rawResume = entry.value as? String ?: continue
            val markerIdx = key.indexOf(resumeMarker)
            if (markerIdx < 0) continue

            val prefix = key.substring(0, markerIdx)
            val pid = key.substring(markerIdx + resumeMarker.length)
            if (pid.isBlank()) continue

            try {
                val rObj = JSONObject(rawResume)
                if (rObj.optBoolean("isFromDownload", false)) continue
                val updateTime = rObj.optLong("updateTime", 0L)
                val epId = if (rObj.isNull("episodeId") || !rObj.has("episodeId")) pid else rObj.optInt("episodeId").toString()

                val rawBookmarked = all["$prefix$FOLDER_BOOKMARKED/$pid"] as? String
                if (rawBookmarked != null) {
                    val bObj = try { JSONObject(rawBookmarked) } catch (_: Throwable) { null }
                    val tvType = bObj?.optString("type", "").orEmpty()
                    if (tvType.equals("Live", ignoreCase = true)) continue
                }

                val rawPosDur = all["$prefix$FOLDER_POS_DUR/$epId"] as? String

                val existing = entries[pid]
                if (existing == null || updateTime > existing.updateTime) {
                    entries[pid] = RawResumeEntry(
                        parentId = pid,
                        episodeId = epId,
                        updateTime = updateTime,
                        resumeRaw = rawResume,
                        bookmarkedRaw = rawBookmarked,
                        posDurRaw = rawPosDur
                    )
                }
            } catch (_: Throwable) {
            }
        }

        return LocalResumeSnapshot(detectedPrefix, entries)
    }

    private fun writeRawResumeToLocal(accountPrefix: String, entry: RawResumeEntry) {
        val sp = with(DataStore) { appContext.getSharedPrefs() }
        val editor = sp.edit()
        editor.putString("$accountPrefix$FOLDER_RESUME/${entry.parentId}", entry.resumeRaw)
        if (!entry.bookmarkedRaw.isNullOrBlank()) {
            editor.putString("$accountPrefix$FOLDER_BOOKMARKED/${entry.parentId}", entry.bookmarkedRaw)
        }
        if (!entry.posDurRaw.isNullOrBlank()) {
            editor.putString("$accountPrefix$FOLDER_POS_DUR/${entry.episodeId}", entry.posDurRaw)
        }
        editor.apply()
    }

    private fun parseRemoteResume(obj: JSONObject?): Map<String, RawResumeEntry> {
        if (obj == null) return emptyMap()
        val map = LinkedHashMap<String, RawResumeEntry>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val pid = keys.next()
            val item = obj.optJSONObject(pid) ?: continue
            val resumeRaw = item.optString("resumeRaw", "")
            if (resumeRaw.isBlank()) continue
            val epId = item.optString("episodeId", pid)
            val bookmarkedRaw = if (item.isNull("bookmarkedRaw")) null else item.optString("bookmarkedRaw", "")
            val posDurRaw = if (item.isNull("posDurRaw")) null else item.optString("posDurRaw", "")
            map[pid] = RawResumeEntry(
                parentId = pid,
                episodeId = epId,
                updateTime = item.optLong("updateTime", 0L),
                resumeRaw = resumeRaw,
                bookmarkedRaw = bookmarkedRaw?.takeIf { it.isNotBlank() },
                posDurRaw = posDurRaw?.takeIf { it.isNotBlank() }
            )
        }
        return map
    }

    private fun serializeResume(map: Map<String, RawResumeEntry>): JSONObject {
        val obj = JSONObject()
        map.values.sortedByDescending { it.updateTime }.take(MAX_RESUME_ITEMS).forEach { e ->
            obj.put(
                e.parentId,
                JSONObject().apply {
                    put("episodeId", e.episodeId)
                    put("updateTime", e.updateTime)
                    put("resumeRaw", e.resumeRaw)
                    if (e.bookmarkedRaw != null) put("bookmarkedRaw", e.bookmarkedRaw)
                    if (e.posDurRaw != null) put("posDurRaw", e.posDurRaw)
                }
            )
        }
        return obj
    }

    private fun reloadHomeUi() {
        mainHandler.post {
            val homeFrag = activeHomeFragmentRef?.get() ?: return@post
            try {
                var cur: Class<*>? = homeFrag.javaClass
                while (cur != null) {
                    val vmField = cur.declaredFields.firstOrNull { it.name.contains("homeViewModel", ignoreCase = true) }
                    if (vmField != null) {
                        vmField.isAccessible = true
                        val vm = vmField.get(homeFrag)
                        if (vm != null) {
                            val reloadMethod = vm.javaClass.methods.firstOrNull {
                                it.name == "reloadStored" && it.parameterCount == 0
                            }
                            if (reloadMethod != null) {
                                reloadMethod.invoke(vm)
                                return@post
                            }
                        }
                        break
                    }
                    cur = cur.superclass
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun attachHomeBadge(fragment: Fragment) {
        detachHomeBadge()
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
            isClickable = false
            isFocusable = false
        }

        val dot = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                marginEnd = dp(5)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_RED)
            }
        }

        val label = TextView(ctx).apply {
            textSize = 10.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            isClickable = false
            isFocusable = false
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
        updateHomeBadge()
    }

    private fun detachHomeBadge() {
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

    private fun updateHomeBadge() {
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

    private fun startHomePolling() {
        homePollJob?.cancel()
        homePollJob = scope.launch {
            while (isActive) {
                delay(HOME_POLL_INTERVAL_MS)
                if (activeHomeFragmentRef?.get() != null && prefs.resolveEndpointUrl() != null) {
                    syncNow()
                }
            }
        }
    }

    private fun stopHomePolling() {
        homePollJob?.cancel()
        homePollJob = null
    }

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
                scheduleSync(delayMs = 800L, force = false)
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
                attachHomeBadge(f)
                startHomePolling()
                scheduleSync(delayMs = 400L, force = true)
            }
        }

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            if (f.javaClass.name.contains("HomeFragment") && activeHomeFragmentRef?.get() === f) {
                stopHomePolling()
                detachHomeBadge()
                activeHomeFragmentRef = null
            } else if (f is AbstractPlayerFragment<*>) {
                scheduleSync(delayMs = 1_200L, force = true)
            }
        }
    }

    private data class LocalResumeSnapshot(
        val accountPrefix: String,
        val entries: Map<String, RawResumeEntry>
    )

    private data class RawResumeEntry(
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
        private const val AUTO_SYNC_THROTTLE_MS = 10_000L
        private const val HOME_POLL_INTERVAL_MS = 30_000L
        private const val MAX_RESUME_ITEMS = 50
        private const val COLOR_GREEN = 0xFF22C55E.toInt()
        private const val COLOR_RED = 0xFFEF4444.toInt()
    }
}
