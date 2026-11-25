package com.gokberkeren.bitrategoster

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import com.lagradost.cloudstream3.ui.player.CSPlayerLoading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Shows video bitrate at the top-left corner of CloudStream's player ONLY while paused.
 * - Reads real bitrate from ExoPlayer's videoFormat (HLS/DASH/MKV).
 * - Falls back to lightweight byte-counter estimate (prefixed with '≈') for plain MP4 streams.
 */
class BitrateOverlayHook(private val prefs: BitratePrefs) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val registeredActivities = Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())

    private var registeredApp: Application? = null
    private var currentActivityRef: WeakReference<FragmentActivity>? = null
    private var activeFragmentRef: WeakReference<AbstractPlayerFragment<*>>? = null
    private var hostParent: ViewGroup? = null
    private var overlayRoot: FrameLayout? = null
    private var bitrateLabel: TextView? = null
    private var updateJob: Job? = null
    private var isCurrentlyShown: Boolean = false

    // Lightweight reflection handles for ExoPlayer AnalyticsListener
    private var hookedExoRef: WeakReference<Any>? = null
    private var analyticsProxy: Any? = null
    private val exoBytesLoaded = AtomicLong(0L)
    private var startPosMs: Long = -1L
    private var startUidRxBytes: Long = -1L

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
        exoBytesLoaded.set(0L)
        startPosMs = -1L
        startUidRxBytes = safeUidRxBytes()

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

        val exo = getExoPlayer(fragment)
        if (exo != null && hookedExoRef?.get() !== exo) {
            hookExoAnalytics(exo)
            exoBytesLoaded.set(0L)
            startPosMs = -1L
            startUidRxBytes = safeUidRxBytes()
        }

        val posMs = (fragment.player.getPosition() ?: 0L).coerceAtLeast(0L)
        if (startPosMs < 0L && posMs > 0L) {
            startPosMs = posMs
        }

        val isPaused = fragment.currentPlayerStatus == CSPlayerLoading.IsPaused
        if (isPaused) {
            if (!isCurrentlyShown) {
                // Read bitrate once when entering paused state (zero work while playing)
                val text = resolveBitrateText(exo, posMs)
                bitrateLabel?.text = text
                overlayRoot?.bringToFront()
            }
            setLabelShown(true)
        } else {
            setLabelShown(false)
        }
    }

    private fun resolveBitrateText(exo: Any?, currentPosMs: Long): String {
        val exactBps = readExactVideoBitrate(exo)
        if (exactBps > 0L) {
            return formatBitrate(exactBps, estimated = false)
        }

        // MP4 fallback: bytesLoaded / mediaSpanSec (using bufferedPosition so pre-buffered data is accounted for)
        val bufferedPosMs = readBufferedPositionMs(exo).coerceAtLeast(currentPosMs)
        val basePos = if (startPosMs >= 0L) startPosMs else 0L
        val spanMs = (bufferedPosMs - basePos).coerceAtLeast(0L)
        if (spanMs >= 2_000L) {
            var bytes = exoBytesLoaded.get()
            if (bytes <= 0L && startUidRxBytes >= 0L) {
                val nowRx = safeUidRxBytes()
                if (nowRx > startUidRxBytes) bytes = nowRx - startUidRxBytes
            }
            if (bytes > 0L) {
                val estBps = ((bytes.toDouble() * 8.0) / (spanMs.toDouble() / 1000.0)).toLong()
                if (estBps in 50_000L..200_000_000L) {
                    return formatBitrate(estBps, estimated = true)
                }
            }
        }
        return "Bitrate bilinmiyor"
    }

    private fun formatBitrate(bps: Long, estimated: Boolean): String {
        val prefix = if (estimated) "≈" else ""
        return if (bps >= 1_000_000L) {
            String.format(Locale.US, "%s%.1f Mbps", prefix, bps / 1_000_000.0)
        } else {
            String.format(Locale.US, "%s%d kbps", prefix, bps / 1_000L)
        }
    }

    private fun getExoPlayer(fragment: AbstractPlayerFragment<*>): Any? {
        val p = try { fragment.player } catch (_: Throwable) { return null }
        var cls: Class<*>? = p.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField("exoPlayer")
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
            val bitrate = (try { cls.getField("bitrate").getInt(fmt) } catch (_: Throwable) { -1 })
            if (bitrate > 0) return bitrate.toLong()
            val avg = (try { cls.getField("averageBitrate").getInt(fmt) } catch (_: Throwable) { -1 })
            if (avg > 0) return avg.toLong()
            val peak = (try { cls.getField("peakBitrate").getInt(fmt) } catch (_: Throwable) { -1 })
            if (peak > 0) peak.toLong() else -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun readBufferedPositionMs(exo: Any?): Long {
        if (exo == null) return 0L
        return try {
            (exo.javaClass.getMethod("getBufferedPosition").invoke(exo) as? Long) ?: 0L
        } catch (_: Throwable) {
            0L
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
                if (method.name == "onLoadCompleted" && args != null && args.size >= 2) {
                    val loadEventInfo = args[1]
                    if (loadEventInfo != null) {
                        try {
                            val bytes = loadEventInfo.javaClass.getField("bytesLoaded").getLong(loadEventInfo)
                            if (bytes > 0L) exoBytesLoaded.addAndGet(bytes)
                        } catch (_: Throwable) {
                        }
                    }
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

    private fun safeUidRxBytes(): Long {
        return try {
            val v = TrafficStats.getUidRxBytes(Process.myUid())
            if (v == TrafficStats.UNSUPPORTED.toLong()) -1L else v
        } catch (_: Throwable) {
            -1L
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
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 400L
        private const val FADE_MS = 220L
    }
}
