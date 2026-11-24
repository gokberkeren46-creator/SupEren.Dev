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
 */
class TimeOverlayHook(private val prefs: SurePrefs) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val registeredActivities = Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var currentActivityRef: WeakReference<FragmentActivity>? = null
    private var activeFragmentRef: WeakReference<AbstractPlayerFragment<*>>? = null
    private var hostParent: ViewGroup? = null
    private var overlayRoot: FrameLayout? = null
    private var leftLabel: TextView? = null
    private var rightLabel: TextView? = null
    private var updateJob: Job? = null

    fun register(application: Application, initialActivity: Activity?) {
        application.registerActivityLifecycleCallbacks(activityCallbacks)
        (initialActivity as? FragmentActivity)?.let { attachToActivity(it) }
    }

    fun currentActivity(): FragmentActivity? = currentActivityRef?.get()?.takeIf {
        !it.isFinishing && !it.isDestroyed
    }

    fun refreshStyle() {
        val visible = if (prefs.enabled) View.VISIBLE else View.GONE
        overlayRoot?.visibility = visible
        val size = prefs.textSizeSp
        val a = prefs.alpha
        leftLabel?.apply {
            textSize = size
            alpha = a
        }
        rightLabel?.apply {
            textSize = size
            alpha = a
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

        fun makePill(): TextView {
            return TextView(ctx).apply {
                setTextColor(Color.WHITE)
                textSize = prefs.textSizeSp
                alpha = prefs.alpha
                setTypeface(typeface, Typeface.BOLD)
                setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
                setPadding(dp(9), dp(4), dp(9), dp(4))
                background = GradientDrawable().apply {
                    setColor(0x88121622.toInt())
                    cornerRadius = dp(10).toFloat()
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
                marginStart = dp(14)
                bottomMargin = dp(10)
            }
        )

        root.addView(
            right,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                marginEnd = dp(14)
                bottomMargin = dp(10)
            }
        )

        container.addView(root)
        hostParent = container
        overlayRoot = root
        leftLabel = left
        rightLabel = right

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
        val position = player.getPosition() ?: 0L

        // Hide on live streams or before media duration is known
        if (duration <= 0L || duration > MAX_VOD_DURATION_MS) {
            leftLabel?.visibility = View.GONE
            rightLabel?.visibility = View.GONE
            return
        }

        val remainingRawMs = (duration - position).coerceAtLeast(0L)
        val speed = player.getPlaybackSpeed().coerceIn(0.25f, 4.0f)
        val realRemainingMs = (remainingRawMs / speed).toLong()

        val finishMs = System.currentTimeMillis() + realRemainingMs
        val finishClock = clockFormat.format(Date(finishMs))

        leftLabel?.apply {
            text = formatRemaining(realRemainingMs)
            visibility = View.VISIBLE
        }
        rightLabel?.apply {
            text = "Bitiş $finishClock"
            visibility = View.VISIBLE
        }
    }

    private fun formatRemaining(ms: Long): String {
        val totalMinutes = (ms + 30_000L) / 60_000L // round to nearest minute
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
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 2_000L
        private const val MAX_VOD_DURATION_MS = 24L * 60L * 60L * 1000L
    }
}
