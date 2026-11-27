package com.gokberkeren.fragmanizle

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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
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
 * Injects a native-feeling "🎬 Fragman" button right inside `result_play_parent`
 * on CloudStream's detail pages (ResultFragmentPhone & ResultFragmentTv).
 */
class ResultTrailerHook(
    private val prefs: FragmanPrefs,
    private val searchEngine: TrailerSearchEngine
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val registeredActivities = Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())

    private var registeredApp: Application? = null
    private var currentActivityRef: WeakReference<FragmentActivity>? = null
    private var activeResultFragmentRef: WeakReference<Fragment>? = null
    private var injectedButtonRef: WeakReference<View>? = null
    private var attachJob: Job? = null
    private var searchJob: Job? = null

    fun register(application: Application, initialActivity: Activity?) {
        registeredApp = application
        application.registerActivityLifecycleCallbacks(activityCallbacks)
        (initialActivity as? FragmentActivity)?.let { attachToActivity(it) }
    }

    fun unregister() {
        attachJob?.cancel()
        attachJob = null
        searchJob?.cancel()
        searchJob = null
        registeredApp?.let {
            try { it.unregisterActivityLifecycleCallbacks(activityCallbacks) } catch (_: Throwable) {}
        }
        registeredApp = null
        for (act in registeredActivities.toList()) {
            try { act.supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragmentCallbacks) } catch (_: Throwable) {}
        }
        registeredActivities.clear()
        removeInjectedButton()
    }

    fun currentActivity(): FragmentActivity? = currentActivityRef?.get()?.takeIf {
        !it.isFinishing && !it.isDestroyed
    }

    fun refreshVisibility() {
        val btn = injectedButtonRef?.get()
        if (btn != null) {
            btn.visibility = if (prefs.enabled) View.VISIBLE else View.GONE
        } else if (prefs.enabled) {
            activeResultFragmentRef?.get()?.let { scheduleButtonInjection(it) }
        }
    }

    private fun attachToActivity(activity: FragmentActivity) {
        currentActivityRef = WeakReference(activity)
        if (registeredActivities.add(activity)) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
        }
    }

    private fun isResultFragment(f: Fragment): Boolean {
        val name = f.javaClass.simpleName
        return name.startsWith("ResultFragment")
    }

    private fun scheduleButtonInjection(fragment: Fragment) {
        attachJob?.cancel()
        activeResultFragmentRef = WeakReference(fragment)
        if (!prefs.enabled) return

        attachJob = scope.launch {
            // Wait up to ~8 seconds for the detail page to finish loading title & play parent
            repeat(20) {
                if (!isActive || !fragment.isAdded) return@launch
                if (tryInjectButton(fragment)) return@launch
                delay(400L)
            }
        }
    }

    private fun tryInjectButton(fragment: Fragment): Boolean {
        val root = fragment.view as? ViewGroup ?: return false
        val ctx = root.context
        val pkg = ctx.packageName

        val playParentId = ctx.resources.getIdentifier("result_play_parent", "id", pkg)
        val titleId = ctx.resources.getIdentifier("result_title", "id", pkg)
        val yearId = ctx.resources.getIdentifier("result_meta_year", "id", pkg)

        if (titleId == 0) return false

        val titleView = root.findViewById<TextView>(titleId) ?: return false
        val rawTitle = titleView.text?.toString()?.trim().orEmpty()
        if (rawTitle.isEmpty()) return false // Page still loading

        val playParent = if (playParentId != 0) root.findViewById<ViewGroup>(playParentId) else null

        // Already injected in this view tree?
        val existing = root.findViewWithTag<View>(BUTTON_TAG)
        if (existing != null) {
            existing.visibility = if (prefs.enabled) View.VISIBLE else View.GONE
            injectedButtonRef = WeakReference(existing)
            return true
        }

        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        val normalBg = GradientDrawable().apply {
            setColor(0xFF252F48.toInt())
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), 0x88818CF8.toInt())
        }
        val focusedBg = GradientDrawable().apply {
            setColor(0xFF6366F1.toInt())
            cornerRadius = dp(8).toFloat()
            setStroke(dp(2), Color.WHITE)
        }

        val btn = TextView(ctx).apply {
            tag = BUTTON_TAG
            id = View.generateViewId()
            text = "🎬 Fragman"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focusedBg)
                addState(intArrayOf(android.R.attr.state_pressed), focusedBg)
                addState(intArrayOf(), normalBg)
            }
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = false
            setOnFocusChangeListener { v, hasFocus ->
                val s = if (hasFocus) 1.04f else 1.0f
                v.animate().scaleX(s).scaleY(s).setDuration(110).start()
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_UP &&
                    (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                        keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) {
                    performClick()
                    true
                } else {
                    false
                }
            }
            setOnClickListener {
                val currentTitle = root.findViewById<TextView>(titleId)?.text?.toString()?.trim().orEmpty()
                val currentYear = if (yearId != 0) {
                    root.findViewById<TextView>(yearId)?.text?.toString()?.trim()
                } else null
                onTrailerButtonClicked(this, currentTitle.ifEmpty { rawTitle }, currentYear)
            }
        }

        // Prefer result_play_parent when visible & laid out; otherwise insert right below titleView's parent
        val usePlayParent = playParent != null && playParent.visibility == View.VISIBLE
        if (usePlayParent && playParent != null) {
            val isHorizontal = (playParent as? LinearLayout)?.orientation == LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                if (isHorizontal) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (isHorizontal) {
                    marginStart = dp(8)
                    gravity = Gravity.CENTER_VERTICAL
                } else {
                    topMargin = dp(6)
                }
            }
            playParent.addView(btn, lp)
        } else {
            val titleParent = titleView.parent as? ViewGroup ?: return false
            val idx = titleParent.indexOfChild(titleView).coerceAtLeast(0) + 1
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
            titleParent.addView(btn, idx.coerceAtMost(titleParent.childCount), lp)
        }

        injectedButtonRef = WeakReference(btn)
        return true
    }

    private fun onTrailerButtonClicked(btn: TextView, rawTitle: String, year: String?) {
        val act = currentActivity() ?: (btn.context as? Activity) ?: return
        if (searchJob?.isActive == true) return

        val originalLabel = btn.text
        btn.text = "⏳ Aranıyor..."
        searchJob = scope.launch {
            try {
                val found = searchEngine.findTrailer(rawTitle, year, prefs.turkishFirst)
                btn.text = originalLabel
                if (found == null) {
                    Toast.makeText(act, "Bu içerik için fragman bulunamadı", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val cleanName = searchEngine.cleanTitle(rawTitle).ifEmpty { rawTitle }
                if (prefs.openExternal) {
                    TrailerPlayerDialog.openExternalYouTube(act, found.videoId)
                } else {
                    TrailerPlayerDialog.showInApp(act, cleanName, found)
                }
            } catch (_: Throwable) {
                btn.text = originalLabel
                Toast.makeText(act, "Fragman yüklenirken hata oluştu", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeInjectedButton() {
        val btn = injectedButtonRef?.get() ?: return
        val parent = btn.parent as? ViewGroup
        if (parent != null) {
            try { parent.removeView(btn) } catch (_: Throwable) {}
        }
        injectedButtonRef = null
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
            if (isResultFragment(f)) {
                scheduleButtonInjection(f)
            }
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (activeResultFragmentRef?.get() === f) {
                attachJob?.cancel()
                searchJob?.cancel()
                injectedButtonRef = null
                activeResultFragmentRef = null
            }
        }
    }

    companion object {
        private const val BUTTON_TAG = "superen_fragman_btn"
    }
}
