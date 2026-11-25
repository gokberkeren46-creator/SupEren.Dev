package com.gokberkeren.bulutsenkron

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import com.lagradost.cloudstream3.ui.search.SEARCH_HISTORY_KEY
import com.lagradost.cloudstream3.ui.search.SearchHistoryItem
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.DataStoreHelper
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
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/**
 * Event-driven cloud sync for Continue Watching (non-live) and Search History.
 * Zero continuous polling: syncs only on app launch, player exit, search exit, or manual button.
 */
class SyncEngine(
    private val appContext: Context,
    private val prefs: SyncPrefs
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val registeredActivities = Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())
    private var currentActivityRef: WeakReference<FragmentActivity>? = null
    private var pendingSyncJob: Job? = null

    @Volatile
    private var lastAutoPullElapsedMs: Long = 0L

    fun register(application: Application, initialActivity: Activity?) {
        application.registerActivityLifecycleCallbacks(activityCallbacks)
        (initialActivity as? FragmentActivity)?.let { attachToActivity(it) }

        // Initial pull on app launch
        scheduleSync(delayMs = 2_000L, force = true)
    }

    fun currentActivity(): FragmentActivity? = currentActivityRef?.get()?.takeIf {
        !it.isFinishing && !it.isDestroyed
    }

    fun triggerAutoSyncNow() {
        scheduleSync(delayMs = 300L, force = true)
    }

    private fun scheduleSync(delayMs: Long, force: Boolean) {
        if (prefs.resolveEndpointUrl() == null) return
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
            ?: return@withContext Result.failure(
                IllegalStateException("Lütfen sunucu/kısa kod ve 6 haneli Profil Kodu (PIN) girin.")
            )

        mutex.withLock {
            try {
                // 1. Fetch remote snapshot
                val remoteJson = httpGet(url)?.let { raw ->
                    try { JSONObject(raw) } catch (_: Throwable) { null }
                }

                // 2. Collect local Continue Watching (excluding Live & Downloads)
                val localResume = readLocalResumeMap()
                val remoteResume = parseResumeMap(remoteJson?.optJSONObject("resume"))

                // 3. Merge Continue Watching (newest updateTime wins per parentId)
                val mergedResume = LinkedHashMap<Int, ResumeEntry>()
                val allIds = (localResume.keys + remoteResume.keys)
                var appliedRemoteResume = 0
                for (pid in allIds) {
                    val loc = localResume[pid]
                    val rem = remoteResume[pid]
                    when {
                        rem != null && (loc == null || rem.updateTime > loc.updateTime) -> {
                            applyRemoteResume(rem)
                            mergedResume[pid] = rem
                            appliedRemoteResume++
                        }
                        loc != null -> mergedResume[pid] = loc
                    }
                }

                // 4. Collect & merge Search History (union by lowercase query, newest searchedAt wins, max 40)
                val localHistory = readLocalSearchHistory()
                val remoteHistory = parseSearchHistory(remoteJson?.optJSONArray("searchHistory"))
                val byQuery = LinkedHashMap<String, SearchHistoryItem>()
                (localHistory + remoteHistory)
                    .sortedByDescending { it.searchedAt }
                    .forEach { item ->
                        val q = item.searchText.trim().lowercase()
                        if (q.isNotEmpty() && !byQuery.containsKey(q)) {
                            byQuery[q] = item
                        }
                    }
                val mergedHistory = byQuery.values.take(MAX_SEARCH_HISTORY)
                val localKeys = localHistory.map { it.searchText.trim().lowercase() }.toSet()
                var appliedRemoteSearch = 0
                mergedHistory.forEach { item ->
                    if (item.searchText.trim().lowercase() !in localKeys) {
                        try {
                            writeLocalSearchHistoryItem(item)
                            appliedRemoteSearch++
                        } catch (_: Throwable) {
                        }
                    }
                }

                // 5. Push merged state back to Firebase
                val outJson = JSONObject().apply {
                    put("updatedAt", System.currentTimeMillis())
                    put("resume", serializeResumeMap(mergedResume))
                    put("searchHistory", serializeSearchHistory(mergedHistory))
                }
                val ok = httpPut(url, outJson.toString())
                if (!ok) {
                    return@withLock Result.failure(IllegalStateException("Firebase yazma hatası (kuralları veya ağı kontrol edin)."))
                }

                val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val summary = "Son eşitleme $clock • ${mergedResume.size} içerik, ${mergedHistory.size} arama"
                prefs.lastSyncSummary = summary
                Log.i(TAG, "Sync OK ($summary; pulled resume=$appliedRemoteResume, search=$appliedRemoteSearch)")
                if (appliedRemoteResume > 0) {
                    notifyHomeReload()
                }
                Result.success(summary)
            } catch (t: Throwable) {
                Log.e(TAG, "Sync failed", t)
                Result.failure(t)
            }
        }
    }

    private fun readLocalResumeMap(): Map<Int, ResumeEntry> {
        val map = LinkedHashMap<Int, ResumeEntry>()
        val ids: List<Int> = try {
            DataStoreHelper.getAllResumeStateIds() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        for (pid in ids) {
            try {
                val rw = DataStoreHelper.getLastWatched(pid) ?: continue
                if (rw.isFromDownload) continue
                val bm = DataStoreHelper.getBookmarkedData(pid)
                // Strictly skip Live TV streams
                if (bm?.type == TvType.Live) continue

                val epId = rw.episodeId ?: pid
                val posDur = DataStoreHelper.getViewPos(epId)
                val bmJson = bm?.let {
                    try { mapper.writeValueAsString(it) } catch (_: Throwable) { null }
                }
                map[pid] = ResumeEntry(
                    parentId = pid,
                    episodeId = epId,
                    episode = rw.episode,
                    season = rw.season,
                    updateTime = rw.updateTime,
                    position = posDur?.position ?: 0L,
                    duration = posDur?.duration ?: 0L,
                    bookmarkedJson = bmJson
                )
            } catch (_: Throwable) {
            }
        }
        return map
    }

    private fun applyRemoteResume(entry: ResumeEntry) {
        try {
            if (!entry.bookmarkedJson.isNullOrBlank()) {
                val bm = mapper.readValue(
                    entry.bookmarkedJson,
                    DataStoreHelper.BookmarkedData::class.java
                )
                if (bm != null && bm.type != TvType.Live) {
                    DataStoreHelper.setBookmarkedData(entry.parentId, bm)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to restore BookmarkedData for ${entry.parentId}", t)
        }
        try {
            DataStoreHelper.setLastWatched(
                entry.parentId,
                entry.episodeId,
                entry.episode,
                entry.season,
                false,
                entry.updateTime
            )
            if (entry.duration > 0L && entry.position >= 0L) {
                DataStoreHelper.setViewPos(entry.episodeId, entry.position, entry.duration)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to restore ResumeWatching for ${entry.parentId}", t)
        }
    }

    private fun parseResumeMap(obj: JSONObject?): Map<Int, ResumeEntry> {
        if (obj == null) return emptyMap()
        val map = LinkedHashMap<Int, ResumeEntry>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val pid = k.toIntOrNull() ?: continue
            val item = obj.optJSONObject(k) ?: continue
            val bmJson = if (item.isNull("bookmarkedJson")) null else item.optString("bookmarkedJson", "")
            map[pid] = ResumeEntry(
                parentId = pid,
                episodeId = item.optInt("episodeId", pid),
                episode = if (item.isNull("episode")) null else item.optInt("episode"),
                season = if (item.isNull("season")) null else item.optInt("season"),
                updateTime = item.optLong("updateTime", 0L),
                position = item.optLong("position", 0L),
                duration = item.optLong("duration", 0L),
                bookmarkedJson = bmJson?.takeIf { it.isNotBlank() }
            )
        }
        return map
    }

    private fun serializeResumeMap(map: Map<Int, ResumeEntry>): JSONObject {
        val obj = JSONObject()
        map.values.sortedByDescending { it.updateTime }.take(MAX_RESUME_ITEMS).forEach { e ->
            obj.put(
                e.parentId.toString(),
                JSONObject().apply {
                    put("episodeId", e.episodeId)
                    if (e.episode != null) put("episode", e.episode) else put("episode", JSONObject.NULL)
                    if (e.season != null) put("season", e.season) else put("season", JSONObject.NULL)
                    put("updateTime", e.updateTime)
                    put("position", e.position)
                    put("duration", e.duration)
                    if (e.bookmarkedJson != null) put("bookmarkedJson", e.bookmarkedJson)
                }
            )
        }
        return obj
    }

    private fun readLocalSearchHistory(): List<SearchHistoryItem> {
        val list = ArrayList<SearchHistoryItem>()
        try {
            val sp = with(DataStore) { appContext.getSharedPrefs() }
            val prefix = "$SEARCH_HISTORY_KEY/"
            for (entry in sp.all.entries) {
                val k = entry.key
                val v = entry.value
                if (k.startsWith(prefix) && v is String) {
                    try {
                        mapper.readValue(v, SearchHistoryItem::class.java)?.let { list.add(it) }
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return list
    }

    private fun writeLocalSearchHistoryItem(item: SearchHistoryItem) {
        val sp = with(DataStore) { appContext.getSharedPrefs() }
        val fullKey = "$SEARCH_HISTORY_KEY/${item.key}"
        val json = mapper.writeValueAsString(item)
        sp.edit().putString(fullKey, json).apply()
    }

    private fun parseSearchHistory(arr: JSONArray?): List<SearchHistoryItem> {
        if (arr == null) return emptyList()
        val list = ArrayList<SearchHistoryItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("searchText", "").trim()
            val key = o.optString("key", "").ifBlank { text.hashCode().toString() }
            if (text.isEmpty()) continue
            list.add(
                SearchHistoryItem(
                    searchedAt = o.optLong("searchedAt", 0L),
                    searchText = text,
                    type = emptyList(),
                    key = key
                )
            )
        }
        return list
    }

    private fun serializeSearchHistory(items: List<SearchHistoryItem>): JSONArray {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("searchedAt", item.searchedAt)
                    put("searchText", item.searchText)
                    put("key", item.key)
                }
            )
        }
        return arr
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

    private fun notifyHomeReload() {
        mainHandler.post {
            try {
                val mainActClass = Class.forName("com.lagradost.cloudstream3.MainActivity")
                val companionField = mainActClass.getDeclaredField("Companion")
                val companion = companionField.get(null) ?: return@post
                val getter = companion.javaClass.getMethod("getReloadHomeEvent")
                val eventObj = getter.invoke(companion) ?: return@post
                val invokeMethod = eventObj.javaClass.methods.firstOrNull {
                    it.name == "invoke" && it.parameterCount == 1
                }
                invokeMethod?.invoke(eventObj, true)
            } catch (_: Throwable) {
            }
        }
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
                scheduleSync(delayMs = 1_000L, force = false)
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
            // Pull latest watch history automatically when opening/returning to Home screen (throttled to 15s)
            if (f.javaClass.name.contains("HomeFragment")) {
                scheduleSync(delayMs = 600L, force = false)
            }
        }

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            // Push immediately after exiting a video or leaving search
            if (f is AbstractPlayerFragment<*>) {
                scheduleSync(delayMs = 1_200L, force = true)
            } else if (f.javaClass.name.contains("SearchFragment")) {
                scheduleSync(delayMs = 800L, force = true)
            }
        }
    }

    private data class ResumeEntry(
        val parentId: Int,
        val episodeId: Int,
        val episode: Int?,
        val season: Int?,
        val updateTime: Long,
        val position: Long,
        val duration: Long,
        val bookmarkedJson: String?
    )

    companion object {
        private const val TAG = "Senkron"
        private const val AUTO_SYNC_THROTTLE_MS = 15_000L
        private const val MAX_RESUME_ITEMS = 50
        private const val MAX_SEARCH_HISTORY = 40
    }
}
