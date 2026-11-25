package com.gokberkeren.bitrategoster

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Shows video bitrate at the top-left corner of CloudStream's player whenever the
 * player control menu (HUD) is visible, hiding automatically when the HUD disappears.
 *
 * Bitrate resolution priority:
 *  1. Exact video bitrate from ExoPlayer's videoFormat (HLS/DASH/MKV).
 *  2. Zero-extra-request MP4 total file size from ExoPlayer's responseHeaders (Content-Range / Content-Length)
 *     divided by video duration -> stable average bitrate (prefixed with '≈').
 *  3. Lightweight single HEAD request per URL (cached in memory, 4s timeout, 0 video bytes downloaded).
 *  4. "Bitrate bilinmiyor" if none available (no erratic byte/time spikes).
 */
class BitrateOverlayHook(private val prefs: BitratePrefs) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val registeredActivities = Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())
    private val headClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Cached total file size (bytes) per URL; -1L means checked and unavailable
    private val urlSizeCache = ConcurrentHashMap<String, Long>()
    private val inFlightHeadUrls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var registeredApp: Application? = null
    private var currentActivityRef: WeakReference<FragmentActivity>? = null
    private var activeFragmentRef: WeakReference<AbstractPlayerFragment<*>>? = null
    private var hostParent: ViewGroup? = null
    private var overlayRoot: FrameLayout? = null
    private var bitrateLabel: TextView? = null
    private var updateJob: Job? = null
    private var isCurrentlyShown: Boolean = false

    private var hookedExoRef: WeakReference<Any>? = null
    private var analyticsProxy: Any? = null
    private var activeMediaUrl: String? = null
    private val headerTotalBytes = AtomicLong(-1L)

    fun register(application: Application, initialActivity: Activity?) {
        registeredApp = application
        application.registerActivityLifecycleCallbacks(activityCallbacks)
        (initialActivity as? FragmentActivity)?.let { attachToActivity(it) }
    }

    fun unregister() {
        registeredApp?.let {
            try { it.unregisterActivityLifecycleCallbacks(activityCallbacks) } catch (_: Throwable) {}
        }
        registeredApp = null
        for (act in registeredActivities.toList()) {
            try { act.supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragmentCallbacks) } catch (_: Throwable) {}
        }
        registeredActivities.clear()
        detachFromPlayer()
    }

    fun currentActivity(): FragmentActivity? = currentActivityRef?.get()?.takeIf {
        !it.isFinishing && !it.isDestroyed
    }

    fun refreshStyle() {
        val root = overlayRoot ?: return
        val ctx = root.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        root.visibility = if (prefs.enabled) View.VISIBLE else View.GONE
        val size = prefs.textSizeSp
        val padH = if (size <= BitratePrefs.SIZE_XS + 0.5f) dp(6) else dp(8)
        val padV = if (size <= BitratePrefs.SIZE_XS + 0.5f) dp(2) else dp(3)

        bitrateLabel?.let { tv ->
            tv.textSize = size
            tv.setPadding(padH, padV, padH, padV)
            if (isCurrentlyShown && prefs.enabled) {
                tv.alpha = prefs.alpha
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
            if (f is AbstractPlayerFragment<*>) {
                attachToPlayer(f)
            }
        }
        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            if (f is AbstractPlayerFragment<*> && activeFragmentRef?.get() === f) {
                detachFromPlayer()
            }
        }
        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
            if (f is AbstractPlayerFragment<*> && activeFragmentRef?.get() === f) {
                detachFromPlayer()
            }
        }
    }

    private fun attachToPlayer(fragment: AbstractPlayerFragment<*>) {
        detachFromPlayer()
        activeFragmentRef = WeakReference(fragment)
        val container = fragment.view as? ViewGroup ?: return
        val ctx = container.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        val size = prefs.textSizeSp
        val padH = if (size <= BitratePrefs.SIZE_XS + 0.5f) dp(6) else dp(8)
        val padV = if (size <= BitratePrefs.SIZE_XS + 0.5f) dp(2) else dp(3)

        val pill = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = size
            alpha = 0f
            setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(3f, 0f, 1f, 0xCC000000.toInt())
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(0x88121622.toInt())
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), 0x33FFFFFF)
            }
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }

        val root = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            elevation = 999f
            isClickable = false
            isFocusable = false
            visibility = if (prefs.enabled) View.VISIBLE else View.GONE
        }

        root.addView(
            pill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                marginStart = dp(14)
                topMargin = dp(12)
            }
        )

        container.addView(root)
        root.bringToFront()
        hostParent = container
        overlayRoot = root
        bitrateLabel = pill
        isCurrentlyShown = false
        activeMediaUrl = null
        headerTotalBytes.set(-1L)

        updateJob = scope.launch {
            while (isActive) {
                tick(fragment)
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun tick(fragment: AbstractPlayerFragment<*>) {
        if (!prefs.enabled) {
            overlayRoot?.visibility = View.GONE
            return
        }
        overlayRoot?.visibility = View.VISIBLE

        val exo = getPlayerField(fragment, "exoPlayer")
        val link = getPlayerField(fragment, "currentLink") as? ExtractorLink
        val linkUrl = link?.url

        // Reset per-source measurements immediately when mirror/source or ExoPlayer instance changes
        if (exo != null && hookedExoRef?.get() !== exo) {
            hookExoAnalytics(exo)
            headerTotalBytes.set(-1L)
        }
        if (linkUrl != activeMediaUrl) {
            activeMediaUrl = linkUrl
            headerTotalBytes.set(-1L)
        }

        val hudShowing = isControllerShowing(fragment)
        if (hudShowing) {
            val durationMs = (try { fragment.player.getDuration() } catch (_: Throwable) { 0L }) ?: 0L
            val text = resolveBitrateText(exo, link, durationMs)
            bitrateLabel?.text = text
            overlayRoot?.bringToFront()
            setLabelShown(true)
        } else {
            setLabelShown(false)
        }
    }

    private fun resolveBitrateText(exo: Any?, link: ExtractorLink?, durationMs: Long): String {
        // 1. Exact bitrate from ExoPlayer videoFormat (HLS / DASH / MKV)
        val exactBps = readExactVideoBitrate(exo)
        if (exactBps > 0L) {
            return formatBitrate(exactBps, estimated = false)
        }

        // Need valid VOD duration for MP4 fileSize * 8 / duration calculation
        if (durationMs < 5_000L || durationMs > MAX_VOD_DURATION_MS) {
            return "Bitrate bilinmiyor"
        }

        val url = link?.url
        val isPlaylist = url != null && isAdaptivePlaylistUrl(url)

        // 2. Zero-extra-request total file size from ExoPlayer's own HTTP responseHeaders
        val fromHeaders = headerTotalBytes.get()
        if (!isPlaylist && fromHeaders > MIN_VIDEO_FILE_BYTES) {
            val bps = computeAvgBps(fromHeaders, durationMs)
            if (bps > 0L) return formatBitrate(bps, estimated = true)
        }

        // 3. Cached or single lightweight HEAD request per URL (0 video bytes downloaded)
        if (link != null && !isPlaylist && url != null && url.startsWith("http", ignoreCase = true)) {
            val cached = urlSizeCache[url]
            if (cached != null) {
                if (cached > MIN_VIDEO_FILE_BYTES) {
                    val bps = computeAvgBps(cached, durationMs)
                    if (bps > 0L) return formatBitrate(bps, estimated = true)
                }
                return "Bitrate bilinmiyor"
            }
            fetchContentLengthAsync(link)
            return "Bitrate ölçülüyor..."
        }

        return "Bitrate bilinmiyor"
    }

    private fun computeAvgBps(totalBytes: Long, durationMs: Long): Long {
        val bps = ((totalBytes.toDouble() * 8.0) / (durationMs.toDouble() / 1000.0)).toLong()
        return if (bps in 50_000L..200_000_000L) bps else -1L
    }

    private fun isAdaptivePlaylistUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains("/manifest")
    }

    private fun fetchContentLengthAsync(link: ExtractorLink) {
        val url = link.url
        if (!inFlightHeadUrls.add(url)) return
        scope.launch(Dispatchers.IO) {
            var resolved = -1L
            try {
                val reqBuilder = Request.Builder().url(url).head()
                if (link.referer.isNotBlank()) {
                    reqBuilder.header("Referer", link.referer)
                }
                link.headers.forEach { (k, v) ->
                    if (k.isNotBlank() && v.isNotBlank()) {
                        reqBuilder.header(k, v)
                    }
                }
                headClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val cr = parseContentRangeTotal(resp.header("Content-Range"))
                        val cl = resp.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                        resolved = when {
                            cr > MIN_VIDEO_FILE_BYTES -> cr
                            cl > MIN_VIDEO_FILE_BYTES -> cl
                            else -> -1L
                        }
                    }
                }
            } catch (_: Throwable) {
                resolved = -1L
            } finally {
                urlSizeCache[url] = resolved
                inFlightHeadUrls.remove(url)
            }
        }
    }

    private fun parseContentRangeTotal(header: String?): Long {
        if (header.isNullOrBlank()) return -1L
        val slashIdx = header.lastIndexOf('/')
        if (slashIdx < 0 || slashIdx + 1 >= header.length) return -1L
        return header.substring(slashIdx + 1).trim().toLongOrNull() ?: -1L
    }

    private fun formatBitrate(bps: Long, estimated: Boolean): String {
        val prefix = if (estimated) "≈" else ""
        return if (bps >= 1_000_000L) {
            String.format(Locale.US, "%s%.1f Mbps", prefix, bps / 1_000_000.0)
        } else {
            String.format(Locale.US, "%s%d kbps", prefix, bps / 1_000L)
        }
    }

    private fun getPlayerField(fragment: AbstractPlayerFragment<*>, fieldName: String): Any? {
        val p = try { fragment.player } catch (_: Throwable) { return null }
        var cls: Class<*>? = p.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(p)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun readExactVideoBitrate(exo: Any?): Long {
        if (exo == null) return -1L
        return try {
            val fmt = exo.javaClass.getMethod("getVideoFormat").invoke(exo) ?: return -1L
            val cls = fmt.javaClass
            val bitrate = try { cls.getField("bitrate").getInt(fmt) } catch (_: Throwable) { -1 }
            if (bitrate > 0) return bitrate.toLong()
            val avg = try { cls.getField("averageBitrate").getInt(fmt) } catch (_: Throwable) { -1 }
            if (avg > 0) return avg.toLong()
            val peak = try { cls.getField("peakBitrate").getInt(fmt) } catch (_: Throwable) { -1 }
            if (peak > 0) peak.toLong() else -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun hookExoAnalytics(exo: Any) {
        unhookExoAnalytics()
        try {
            val listenerClass = Class.forName(
                "androidx.media3.exoplayer.analytics.AnalyticsListener",
                false,
                exo.javaClass.classLoader
            )
            val proxy = Proxy.newProxyInstance(
                exo.javaClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                if ((method.name == "onLoadCompleted" || method.name == "onLoadStarted") &&
                    args != null && args.size >= 2
                ) {
                    extractTotalSizeFromLoadEvent(args[1])
                }
                null
            }
            exo.javaClass.getMethod("addAnalyticsListener", listenerClass).invoke(exo, proxy)
            hookedExoRef = WeakReference(exo)
            analyticsProxy = proxy
        } catch (_: Throwable) {
            hookedExoRef = WeakReference(exo)
            analyticsProxy = null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractTotalSizeFromLoadEvent(loadEventInfo: Any?) {
        if (loadEventInfo == null) return
        try {
            val headers = loadEventInfo.javaClass.getField("responseHeaders").get(loadEventInfo) as? Map<*, *> ?: return
            var contentRange: String? = null
            var contentLength: Long = -1L
            for ((k, v) in headers) {
                val key = (k as? String)?.lowercase(Locale.US) ?: continue
                val firstVal = (v as? List<*>)?.firstOrNull() as? String ?: continue
                when (key) {
                    "content-range" -> contentRange = firstVal
                    "content-length" -> contentLength = firstVal.trim().toLongOrNull() ?: -1L
                }
            }
            val crTotal = parseContentRangeTotal(contentRange)
            val candidate = when {
                crTotal > MIN_VIDEO_FILE_BYTES -> crTotal
                contentRange == null && contentLength > MIN_VIDEO_FILE_BYTES -> contentLength
                else -> -1L
            }
            if (candidate > headerTotalBytes.get()) {
                headerTotalBytes.set(candidate)
            }
        } catch (_: Throwable) {
        }
    }

    private fun unhookExoAnalytics() {
        val exo = hookedExoRef?.get()
        val proxy = analyticsProxy
        if (exo != null && proxy != null) {
            try {
                val listenerClass = Class.forName(
                    "androidx.media3.exoplayer.analytics.AnalyticsListener",
                    false,
                    exo.javaClass.classLoader
                )
                exo.javaClass.getMethod("removeAnalyticsListener", listenerClass).invoke(exo, proxy)
            } catch (_: Throwable) {
            }
        }
        hookedExoRef = null
        analyticsProxy = null
    }

    private fun isControllerShowing(fragment: AbstractPlayerFragment<*>): Boolean {
        var cur: Class<*>? = fragment.javaClass
        while (cur != null) {
            try {
                val m = cur.getDeclaredMethod("isShowing")
                m.isAccessible = true
                if (m.invoke(fragment) == true) return true
                break
            } catch (_: NoSuchMethodException) {
                cur = cur.superclass
            } catch (_: Throwable) {
                break
            }
        }
        return try {
            val btn = fragment.playerPausePlay
            btn != null && btn.isShown && btn.alpha > 0.1f
        } catch (_: Throwable) {
            false
        }
    }

    private fun setLabelShown(show: Boolean) {
        if (show == isCurrentlyShown) return
        isCurrentlyShown = show
        val tv = bitrateLabel ?: return
        val targetAlpha = if (show) prefs.alpha else 0f
        tv.animate().cancel()
        if (show) {
            tv.visibility = View.VISIBLE
            tv.animate().alpha(targetAlpha).setDuration(FADE_MS).start()
        } else {
            tv.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                if (!isCurrentlyShown) tv.visibility = View.GONE
            }.start()
        }
    }

    private fun detachFromPlayer() {
        updateJob?.cancel()
        updateJob = null
        unhookExoAnalytics()
        val root = overlayRoot
        val parent = hostParent
        if (root != null && parent != null) {
            try { parent.removeView(root) } catch (_: Throwable) {}
        }
        overlayRoot = null
        hostParent = null
        bitrateLabel = null
        activeFragmentRef = null
        isCurrentlyShown = false
        activeMediaUrl = null
        headerTotalBytes.set(-1L)
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 350L
        private const val FADE_MS = 220L
        private const val MIN_VIDEO_FILE_BYTES = 1_000_000L // >= 1 MB (ignores tiny playlists/thumbs)
        private const val MAX_VOD_DURATION_MS = 24L * 60L * 60L * 1000L
    }
}
