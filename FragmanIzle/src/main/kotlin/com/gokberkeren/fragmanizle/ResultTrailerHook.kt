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
 * Provides a working "Fragman" button on both TV and Tablet/Phone detail pages:
 * - On TV (`ResultFragmentTv`): Activates CloudStream's built-in `result_play_trailer` /
 *   `result_play_trailer_button` whenever the provider didn't supply native trailers,
 *   preserving CloudStream's exact D-pad `nextFocusLeft` / `nextFocusRight` chain & styling!
 * - On Tablet/Phone (`ResultFragmentPhone`): Inserts a clean full-width "🎬 Fragman İzle"
 *   button directly inside the vertical `result_data_holder` (avoiding `result_play_parent`,
 *   whose `MATCH_PARENT` child pushed views off-screen on movies and whose parent
 *   `result_movie_parent` is `GONE` on series).
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
    private var injectedTabletButtonRef: WeakReference<View>? = null
    private var hookedTvContainerRef: WeakReference<View>? = null
    private var monitorJob: Job? = null
    private var searchJob: Job? = null

    fun register(application: Application, initialActivity: Activity?) {
        registeredApp = application
        application.registerActivityLifecycleCallbacks(activityCallbacks)
        (initialActivity as? FragmentActivity)?.let { attachToActivity(it) }
    }

    fun unregister() {
        monitorJob?.cancel()
        monitorJob = null
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
        cleanupViews()
    }

    fun currentActivity(): FragmentActivity? = currentActivityRef?.get()?.takeIf {
        !it.isFinishing && !it.isDestroyed
    }

    fun refreshVisibility() {
        injectedTabletButtonRef?.get()?.visibility = if (prefs.enabled) View.VISIBLE else View.GONE
        hookedTvContainerRef?.get()?.visibility = if (prefs.enabled) View.VISIBLE else View.GONE
        if (prefs.enabled) {
            activeResultFragmentRef?.get()?.let { startPageMonitor(it) }
        }
    }

    private fun attachToActivity(activity: FragmentActivity) {
        currentActivityRef = WeakReference(activity)
        if (registeredActivities.add(activity)) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
        }
    }

    private fun isResultFragment(f: Fragment): Boolean {
        return f.javaClass.simpleName.startsWith("ResultFragment")
    }

    private fun hasNativeTrailers(fragment: Fragment): Boolean {
        return try {
            val m = fragment.javaClass.methods.firstOrNull {
                it.name == "getCurrentTrailers" && it.parameterTypes.isEmpty()
            }
            val list = m?.invoke(fragment) as? Collection<*>
            list != null && list.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private fun startPageMonitor(fragment: Fragment) {
        monitorJob?.cancel()
        activeResultFragmentRef = WeakReference(fragment)
        if (!prefs.enabled) return

        monitorJob = scope.launch {
            // Keep checking while on ResultFragment because LiveData updates can re-hide TV's trailer view
            while (isActive && fragment.isAdded) {
                if (prefs.enabled) {
                    ensureTrailerUi(fragment)
                }
                delay(500L)
            }
        }
    }

    private fun ensureTrailerUi(fragment: Fragment) {
        val root = fragment.view as? ViewGroup ?: return
        val ctx = root.context
        val pkg = ctx.packageName

        val titleId = ctx.resources.getIdentifier("result_title", "id", pkg)
        val yearId = ctx.resources.getIdentifier("result_meta_year", "id", pkg)
        if (titleId == 0) return

        val titleView = root.findViewById<TextView>(titleId) ?: return
        val rawTitle = titleView.text?.toString()?.trim().orEmpty()
        if (rawTitle.isEmpty()) return // Detail page still loading

        // Check if this is TV layout (`result_play_trailer` + `result_play_trailer_button` exist)
        val tvTrailerContainerId = ctx.resources.getIdentifier("result_play_trailer", "id", pkg)
        val tvTrailerBtnId = ctx.resources.getIdentifier("result_play_trailer_button", "id", pkg)
        val tvTrailerTextId = ctx.resources.getIdentifier("result_play_trailer_text", "id", pkg)

        val tvContainer = if (tvTrailerContainerId != 0) root.findViewById<View>(tvTrailerContainerId) else null
        val tvBtn = if (tvTrailerBtnId != 0) root.findViewById<View>(tvTrailerBtnId) else null

        if (tvContainer != null && tvBtn != null) {
            bindTvNativeButton(fragment, root, tvContainer, tvBtn, tvTrailerTextId, titleId, yearId)
        } else {
            bindTabletButton(root, titleView, titleId, yearId)
        }
    }

    private fun bindTvNativeButton(
        fragment: Fragment,
        root: ViewGroup,
        tvContainer: View,
        tvBtn: View,
        tvTrailerTextId: Int,
        titleId: Int,
        yearId: Int
    ) {
        // If provider already supplied real trailers, leave CloudStream's native handler intact
        if (hasNativeTrailers(fragment) && tvContainer.tag != HOOKED_TV_TAG) {
            return
        }

        val tvText = if (tvTrailerTextId != 0) root.findViewById<TextView>(tvTrailerTextId) else null
        if (tvContainer.visibility != View.VISIBLE) {
            tvContainer.visibility = View.VISIBLE
        }
        tvBtn.visibility = View.VISIBLE
        tvBtn.isEnabled = true
        tvBtn.isClickable = true
        tvBtn.isFocusable = true

        if (tvContainer.tag != HOOKED_TV_TAG) {
            tvContainer.tag = HOOKED_TV_TAG
            if (tvText != null && tvText.text.isNullOrBlank()) {
                tvText.text = "Fragman"
            }
            tvBtn.setOnClickListener {
                val currentTitle = root.findViewById<TextView>(titleId)?.text?.toString()?.trim().orEmpty()
                val currentYear = if (yearId != 0) {
                    root.findViewById<TextView>(yearId)?.text?.toString()?.trim()
                } else null
                if (currentTitle.isNotEmpty()) {
                    onTrailerRequested(tvText, currentTitle, currentYear)
                }
            }
        }
        hookedTvContainerRef = WeakReference(tvContainer)
    }

    private fun bindTabletButton(
        root: ViewGroup,
        titleView: TextView,
        titleId: Int,
        yearId: Int
    ) {
        val existing = root.findViewWithTag<View>(BUTTON_TAG)
        if (existing != null) {
            existing.visibility = if (prefs.enabled) View.VISIBLE else View.GONE
            injectedTabletButtonRef = WeakReference(existing)
            return
        }

        val ctx = root.context
        val pkg = ctx.packageName
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        // Find vertical `result_data_holder` (holds title, meta, movie/series play rows, description)
        val dataHolderId = ctx.resources.getIdentifier("result_data_holder", "id", pkg)
        val descId = ctx.resources.getIdentifier("result_description", "id", pkg)
        val dataHolder = (if (dataHolderId != 0) root.findViewById<LinearLayout>(dataHolderId) else null)
            ?: (titleView.parent as? LinearLayout)
            ?: return

        val normalBg = GradientDrawable().apply {
            setColor(0xFF252F48.toInt())
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), 0x99818CF8.toInt())
        }
        val focusedBg = GradientDrawable().apply {
            setColor(0xFF6366F1.toInt())
            cornerRadius = dp(8).toFloat()
            setStroke(dp(2), Color.WHITE)
        }

        val btn = TextView(ctx).apply {
            tag = BUTTON_TAG
            id = View.generateViewId()
            text = "🎬 Fragman İzle"
            textSize = 13.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focusedBg)
                addState(intArrayOf(android.R.attr.state_pressed), focusedBg)
                addState(intArrayOf(), normalBg)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val currentTitle = root.findViewById<TextView>(titleId)?.text?.toString()?.trim().orEmpty()
                val currentYear = if (yearId != 0) {
                    root.findViewById<TextView>(yearId)?.text?.toString()?.trim()
                } else null
                if (currentTitle.isNotEmpty()) {
                    onTrailerRequested(this, currentTitle, currentYear)
                }
            }
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
            bottomMargin = dp(6)
        }

        // Insert right before `result_description` if direct child of `dataHolder`, else after title
        val descView = if (descId != 0) dataHolder.findViewById<View>(descId) else null
        val insertIndex = if (descView != null && descView.parent === dataHolder) {
            dataHolder.indexOfChild(descView)
        } else {
            val titleIdx = dataHolder.indexOfChild(titleView)
            if (titleIdx >= 0) (titleIdx + 2).coerceAtMost(dataHolder.childCount) else dataHolder.childCount
        }

        dataHolder.addView(btn, insertIndex.coerceIn(0, dataHolder.childCount), lp)
        injectedTabletButtonRef = WeakReference(btn)
    }

    private fun onTrailerRequested(labelView: TextView?, rawTitle: String, year: String?) {
        val act = currentActivity() ?: (labelView?.context as? Activity) ?: return
        if (searchJob?.isActive == true) return

        val prevText = labelView?.text
        if (labelView != null) {
            labelView.text = "⏳ Aranıyor..."
        } else {
            Toast.makeText(act, "⏳ Fragman aranıyor...", Toast.LENGTH_SHORT).show()
        }

        searchJob = scope.launch {
            try {
                val found = searchEngine.findTrailer(rawTitle, year, prefs.turkishFirst)
                if (labelView != null && prevText != null) labelView.text = prevText
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
                if (labelView != null && prevText != null) labelView.text = prevText
                Toast.makeText(act, "Fragman yüklenirken hata oluştu", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cleanupViews() {
        val btn = injectedTabletButtonRef?.get()
        val parent = btn?.parent as? ViewGroup
        if (btn != null && parent != null) {
            try { parent.removeView(btn) } catch (_: Throwable) {}
        }
        injectedTabletButtonRef = null
        hookedTvContainerRef?.get()?.visibility = View.GONE
        hookedTvContainerRef = null
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
                startPageMonitor(f)
            }
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (activeResultFragmentRef?.get() === f) {
                monitorJob?.cancel()
                searchJob?.cancel()
                injectedTabletButtonRef = null
                hookedTvContainerRef = null
                activeResultFragmentRef = null
            }
        }
    }

    companion object {
        private const val BUTTON_TAG = "superen_fragman_btn"
        private const val HOOKED_TV_TAG = "superen_fragman_tv_hooked"
    }
}
