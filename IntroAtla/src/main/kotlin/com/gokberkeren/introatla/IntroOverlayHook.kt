package com.gokberkeren.introatla

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
import java.util.Collections
import java.util.WeakHashMap

/**
 * Displays a Netflix-style "İntro Atla" button at the bottom-left of CloudStream's player.
 * - Never auto-skips; always waits for user click / TV OK press.
 * - When IntroDB / AniSkip returns an exact intro window, shows during [startMs..endMs]
 *   even when HUD is hidden, and seeks directly to endMs.
 * - When no API data exists, shows "+85sn İntro Atla" during the first 5 minutes
 *   whenever the player HUD is visible.
 */
class IntroOverlayHook(
    private val prefs: IntroPrefs,
    private val apiClient: IntroApiClient
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val registeredActivities = Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())

    private var registeredApp: Application? = null
    private var currentActivityRef: WeakReference<FragmentActivity>? = null
    private var activeFragmentRef: WeakReference<AbstractPlayerFragment<*>>? = null
    private var hostParent: ViewGroup? = null
    private var overlayRoot: FrameLayout? = null
    private var skipButton: TextView? = null
    private var pollJob: Job? = null
    private var fetchJob: Job? = null

    private var lastQueryKey: String? = null
    private var activeIntro: IntroSegment? = null
    private var skippedCurrentIntro: Boolean = false

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
        val btn = skipButton ?: return
        if (!prefs.enabled) {
            btn.visibility = View.GONE
        } else {
            btn.alpha = prefs.alpha
            updateButtonText(isExactApi = activeIntro != null)
        }
    }

    private fun attachToActivity(activity: FragmentActivity) {
        currentActivityRef = WeakReference(activity)
        if (registeredActivities.add(activity)) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
        }
    }

    private fun attachToPlayer(fragment: AbstractPlayerFragment<*>) {
        if (activeFragmentRef?.get() === fragment && overlayRoot?.isAttachedToWindow == true) {
            return
        }
        detachFromPlayer()

        val rootView = fragment.view as? ViewGroup ?: return
        val ctx = rootView.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        val overlay = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            elevation = 960f
            isClickable = false
            isFocusable = false
        }

        val normalBg = GradientDrawable().apply {
            setColor(0xDD141824.toInt())
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), 0xCCFFFFFF.toInt())
        }
        val focusedBg = GradientDrawable().apply {
            setColor(0xFF6366F1.toInt())
            cornerRadius = dp(10).toFloat()
            setStroke(dp(2), Color.WHITE)
        }

        val btn = TextView(ctx).apply {
            text = "⏭ İntro Atla"
            textSize = 13.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focusedBg)
                addState(intArrayOf(android.R.attr.state_pressed), focusedBg)
                addState(intArrayOf(), normalBg)
            }
            alpha = prefs.alpha
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.06f else 1.0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            }
            setOnClickListener { performSkip() }
        }

        // Bottom-left position with clean margin above Gosterge's bottom-left label
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        ).apply {
            marginStart = dp(24)
            bottomMargin = dp(78)
        }
        overlay.addView(btn, lp)
        rootView.addView(overlay)
        overlay.bringToFront()

        activeFragmentRef = WeakReference(fragment)
        hostParent = rootView
        overlayRoot = overlay
        skipButton = btn
        lastQueryKey = null
        activeIntro = null
        skippedCurrentIntro = false

        pollJob = scope.launch {
            while (isActive) {
                tick(fragment)
                delay(500L)
            }
        }
    }

    private fun detachFromPlayer() {
        pollJob?.cancel()
        pollJob = null
        fetchJob?.cancel()
        fetchJob = null
        val ov = overlayRoot
        val host = hostParent
        if (ov != null && host != null) {
            try { host.removeView(ov) } catch (_: Throwable) {}
        }
        overlayRoot = null
        hostParent = null
        skipButton = null
        activeFragmentRef = null
        lastQueryKey = null
        activeIntro = null
        skippedCurrentIntro = false
    }

    private fun tick(fragment: AbstractPlayerFragment<*>) {
        val btn = skipButton ?: return
        if (!prefs.enabled) {
            btn.visibility = View.GONE
            return
        }

        // Detect episode metadata changes & fetch intro timestamps once per episode
        val query = extractEpisodeQuery(fragment)
        if (query != null && query.isValid()) {
            val key = query.cacheKey()
            if (key != lastQueryKey) {
                lastQueryKey = key
                activeIntro = null
                skippedCurrentIntro = false
                fetchJob?.cancel()
                fetchJob = scope.launch {
                    val seg = apiClient.fetchIntro(query)
                    if (lastQueryKey == key) {
                        activeIntro = seg
                    }
                }
            }
        }

        val posMs = getPlayerPositionMs(fragment) ?: return
        val hudVisible = isHudShowing(fragment)
        val intro = activeIntro

        if (intro != null) {
            val inWindow = posMs in intro.startMs..intro.endMs
            if (inWindow && !skippedCurrentIntro) {
                updateButtonText(isExactApi = true)
                if (btn.visibility != View.VISIBLE) {
                    btn.alpha = prefs.alpha
                    btn.visibility = View.VISIBLE
                }
            } else {
                if (btn.visibility != View.GONE) btn.visibility = View.GONE
                if (posMs < intro.startMs) skippedCurrentIntro = false
            }
        } else {
            // Fallback mode: first 5 minutes when HUD is visible
            val inFallbackWindow = posMs in 0L..FALLBACK_WINDOW_MS
            if (inFallbackWindow && hudVisible) {
                updateButtonText(isExactApi = false)
                if (btn.visibility != View.VISIBLE) {
                    btn.alpha = prefs.alpha
                    btn.visibility = View.VISIBLE
                }
            } else {
                if (btn.visibility != View.GONE) btn.visibility = View.GONE
            }
        }
    }

    private fun updateButtonText(isExactApi: Boolean) {
        val btn = skipButton ?: return
        val label = if (isExactApi) {
            "⏭ İntro Atla"
        } else {
            "⏭ +${prefs.fallbackSeconds}sn İntro Atla"
        }
        if (btn.text != label) btn.text = label
    }

    private fun performSkip() {
        val fragment = activeFragmentRef?.get() ?: return
        val intro = activeIntro
        if (intro != null) {
            seekPlayerTo(fragment, intro.endMs)
            skippedCurrentIntro = true
        } else {
            val pos = getPlayerPositionMs(fragment) ?: return
            seekPlayerTo(fragment, pos + prefs.fallbackSeconds * 1000L)
        }
        skipButton?.visibility = View.GONE
    }

    private fun getPlayerObject(fragment: AbstractPlayerFragment<*>): Any? {
        return try {
            fragment.player
        } catch (_: Throwable) {
            null
        }
    }

    private fun getPlayerPositionMs(fragment: AbstractPlayerFragment<*>): Long? {
        val p = getPlayerObject(fragment) ?: return null
        return try {
            val m = p.javaClass.methods.firstOrNull { it.name == "getPosition" && it.parameterTypes.isEmpty() }
            (m?.invoke(p) as? Long)?.takeIf { it >= 0L }
        } catch (_: Throwable) {
            null
        }
    }

    private fun seekPlayerTo(fragment: AbstractPlayerFragment<*>, targetMs: Long) {
        val p = getPlayerObject(fragment) ?: return
        try {
            val m = p.javaClass.methods.firstOrNull {
                it.name == "seekTo" && it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0] == Long::class.javaPrimitiveType || it.parameterTypes[0] == Long::class.javaObjectType)
            }
            m?.invoke(p, targetMs.coerceAtLeast(0L))
        } catch (_: Throwable) {
        }
    }

    private fun isHudShowing(fragment: AbstractPlayerFragment<*>): Boolean {
        return try {
            val m = fragment.javaClass.methods.firstOrNull { it.name == "isUIShowing" && it.parameterTypes.isEmpty() }
            (m?.invoke(fragment) as? Boolean) == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun extractEpisodeQuery(fragment: AbstractPlayerFragment<*>): EpisodeQuery? {
        return try {
            // Inspect viewModel / currentEpisode / sync data via reflection (version-safe)
            val fields = generateSequence<Class<*>>(fragment.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .toList()

            var imdbId: String? = null
            var malId: Int? = null
            var season: Int? = null
            var episode: Int? = null

            for (f in fields) {
                val n = f.name.lowercase()
                if (n.contains("viewmodel") || n.contains("sync") || n.contains("generator") || n.contains("episode")) {
                    f.isAccessible = true
                    val obj = try { f.get(fragment) } catch (_: Throwable) { null } ?: continue
                    val info = inspectMetadataObject(obj)
                    if (imdbId == null) imdbId = info.imdbId
                    if (malId == null) malId = info.malId
                    if (season == null) season = info.season
                    if (episode == null) episode = info.episode
                }
            }
            if (episode != null && (imdbId != null || malId != null)) {
                EpisodeQuery(imdbId = imdbId, malId = malId, season = season, episode = episode)
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun inspectMetadataObject(obj: Any): EpisodeQuery {
        var imdbId: String? = null
        var malId: Int? = null
        var season: Int? = null
        var episode: Int? = null
        try {
            for (m in obj.javaClass.methods) {
                if (m.parameterTypes.isNotEmpty()) continue
                when (m.name) {
                    "getImdbId", "getImdb" -> {
                        val v = try { m.invoke(obj) as? String } catch (_: Throwable) { null }
                        if (!v.isNullOrBlank() && v.startsWith("tt")) imdbId = v
                    }
                    "getMalId" -> {
                        val v = try { (m.invoke(obj) as? Number)?.toInt() } catch (_: Throwable) { null }
                        if (v != null && v > 0) malId = v
                    }
                    "getSeason" -> {
                        val v = try { (m.invoke(obj) as? Number)?.toInt() } catch (_: Throwable) { null }
                        if (v != null && v > 0) season = v
                    }
                    "getEpisode" -> {
                        val v = try { (m.invoke(obj) as? Number)?.toInt() } catch (_: Throwable) { null }
                        if (v != null && v > 0) episode = v
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return EpisodeQuery(imdbId, malId, season, episode)
    }

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            (activity as? FragmentActivity)?.let { attachToActivity(it) }
        }
        override fun onActivityStarted(activity: Activity) {
            (activity as? FragmentActivity)?.let { attachToActivity(it) }
        }
        override fun onActivityResumed(activity: Activity) {
            (activity as? FragmentActivity)?.let { attachToActivity(it) }
        }
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (currentActivityRef?.get() === activity) currentActivityRef = null
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

    companion object {
        private const val FALLBACK_WINDOW_MS = 5L * 60_000L
    }
}
