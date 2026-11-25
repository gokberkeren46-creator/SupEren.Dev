package com.gokberkeren.suregosterge

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
import com.lagradost.cloudstream3.ui.player.CSPlayerLoading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

/**
 * Attaches unobtrusive bottom-left (speed-adjusted remaining time) and
 * bottom-right (real wall-clock finish time) pills to CloudStream's active player.
 * Visible during: first 3 minutes, every 10 minutes for 30 seconds, or whenever paused.
 */
class TimeOverlayHook(private val prefs: SurePrefs) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val registeredActivities = Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var registeredApp: Application? = null
    private var currentActivityRef: WeakReference<FragmentActivity>? = null
    private var activeFragmentRef: WeakReference<AbstractPlayerFragment<*>>? = null
    private var hostParent: ViewGroup? = null
    private var overlayRoot: FrameLayout? = null
    private var leftLabel: TextView? = null
    private var rightLabel: TextView? = null
    private var updateJob: Job? = null
    private var isCurrentlyShown: Boolean = false

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
        val padH = if (size <= SurePrefs.SIZE_XS + 0.5f) dp(6) else dp(8)
        val padV = if (size <= SurePrefs.SIZE_XS + 0.5f) dp(2) else dp(3)

        listOfNotNull(leftLabel, rightLabel).forEach { tv ->
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
            if (currentActivityRef?.get() === activity) {
                currentActivityRef = null
            }
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
        val padH = if (size <= SurePrefs.SIZE_XS + 0.5f) dp(6) else dp(8)
        val padV = if (size <= SurePrefs.SIZE_XS + 0.5f) dp(2) else dp(3)

        fun makePill(): TextView {
            return TextView(ctx).apply {
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

        val left = makePill()
        val right = makePill()

        root.addView(
            left,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                marginStart = dp(12)
                bottomMargin = dp(8)
            }
        )

        root.addView(
            right,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                marginEnd = dp(12)
                bottomMargin = dp(8)
            }
        )

        container.addView(root)
        root.bringToFront()
        hostParent = container
        overlayRoot = root
        leftLabel = left
        rightLabel = right
        isCurrentlyShown = false

        updateJob = scope.launch {
            while (isActive) {
                updateTimeLabels(fragment)
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun updateTimeLabels(fragment: AbstractPlayerFragment<*>) {
        if (!prefs.enabled) {
            overlayRoot?.visibility = View.GONE
            return
        }
        overlayRoot?.visibility = View.VISIBLE

        val player = fragment.player
        val duration = player.getDuration() ?: 0L
        val position = (player.getPosition() ?: 0L).coerceAtLeast(0L)

        // Hide on live streams or before media duration is known
        if (duration <= 0L || duration > MAX_VOD_DURATION_MS) {
            setLabelsShown(false)
            return
        }

        val isPaused = fragment.currentPlayerStatus == CSPlayerLoading.IsPaused
        val isHudShowing = isControllerShowing(fragment)
        val inInitialWindow = position < INITIAL_SHOW_MS
        val inPeriodicWindow = (position % PERIODIC_INTERVAL_MS) < PERIODIC_SHOW_MS
        val shouldShow = isPaused || isHudShowing || inInitialWindow || inPeriodicWindow

        if (shouldShow) {
            overlayRoot?.bringToFront()
            val remainingRawMs = (duration - position).coerceAtLeast(0L)
            val speed = player.getPlaybackSpeed().coerceIn(0.25f, 4.0f)
            val realRemainingMs = (remainingRawMs / speed).toLong()
            val finishClock = clockFormat.format(Date(System.currentTimeMillis() + realRemainingMs))

            leftLabel?.text = formatRemaining(realRemainingMs)
            rightLabel?.text = "Bitiş $finishClock"
        }

        setLabelsShown(shouldShow)
    }

    private fun setLabelsShown(show: Boolean) {
        if (show == isCurrentlyShown) return
        isCurrentlyShown = show
        val targetAlpha = if (show) prefs.alpha else 0f
        listOfNotNull(leftLabel, rightLabel).forEach { tv ->
            tv.animate().cancel()
            if (show) {
                tv.visibility = View.VISIBLE
                tv.animate().alpha(targetAlpha).setDuration(FADE_MS).start()
            } else {
                tv.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                    if (!isCurrentlyShown) {
                        tv.visibility = View.GONE
                    }
                }.start()
            }
        }
    }

    private fun formatRemaining(ms: Long): String {
        val totalMinutes = (ms + 30_000L) / 60_000L
        if (totalMinutes <= 0L) return "< 1 dk kaldı"
        val hours = totalMinutes / 60L
        val mins = totalMinutes % 60L
        return when {
            hours > 0L && mins > 0L -> "${hours}s ${mins}dk kaldı"
            hours > 0L -> "${hours}s kaldı"
            else -> "$mins dk kaldı"
        }
    }

    private fun detachFromPlayer() {
        updateJob?.cancel()
        updateJob = null
        val root = overlayRoot
        val parent = hostParent
        if (root != null && parent != null) {
            try {
                parent.removeView(root)
            } catch (_: Throwable) {
            }
        }
        overlayRoot = null
        hostParent = null
        leftLabel = null
        rightLabel = null
        activeFragmentRef = null
        isCurrentlyShown = false
    }

    private fun isControllerShowing(fragment: AbstractPlayerFragment<*>): Boolean {
        // 1. Check FullScreenPlayer.isShowing() via reflection (works on both Phone/Tablet & Android TV)
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
        // 2. Fallback: check if playerPausePlay control button is currently visible
        return try {
            val btn = fragment.playerPausePlay
            btn != null && btn.isShown && btn.alpha > 0.1f
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 500L
        private const val INITIAL_SHOW_MS = 1L * 60_000L       // İlk 1 dakika
        private const val PERIODIC_INTERVAL_MS = 10L * 60_000L // Her 10 dakikada bir
        private const val PERIODIC_SHOW_MS = 30_000L           // 30 saniye görünür
        private const val FADE_MS = 350L
        private const val MAX_VOD_DURATION_MS = 24L * 60L * 60L * 1000L
    }
}
