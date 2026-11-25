package com.gokberkeren.superentema

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Replaces CloudStream's left NavigationRailView / classic BottomNavigationView with a sleek
 * floating glass bottom dock (Tablet + Android TV D-Pad friendly) and tints TV focusOutline.
 */
class FloatingDockHook(private val prefs: TemaPrefs) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var registeredApp: Application? = null
    private val registeredActivities = Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())
    private var currentActivityRef: WeakReference<FragmentActivity>? = null

    private var dockHost: ViewGroup? = null
    private var dockOverlay: FrameLayout? = null
    private var dockPill: LinearLayout? = null
    private var navRailRef: WeakReference<View>? = null
    private var navBottomRef: WeakReference<View>? = null
    private var focusOutlineRef: WeakReference<ImageView>? = null

    private var navItems: List<DockItem> = emptyList()
    private val tabViews = mutableMapOf<Int, TabViewHolder>()

    @Volatile
    private var unloaded: Boolean = false

    @Volatile
    private var selectedItemId: Int = -1

    @Volatile
    private var hideForFullscreen: Boolean = false

    private var origRailVisibility: Int? = null
    private var origBottomVisibility: Int? = null

    private val enforceRunnable = object : Runnable {
        override fun run() {
            if (unloaded) return
            enforceState()
            mainHandler.postDelayed(this, 600L)
        }
    }

    fun register(application: Application, initialActivity: Activity?) {
        registeredApp = application
        application.registerActivityLifecycleCallbacks(activityCallbacks)
        (initialActivity as? FragmentActivity)?.let { attachToActivity(it) }
        mainHandler.postDelayed(enforceRunnable, 400L)
    }

    fun unregister() {
        unloaded = true
        mainHandler.removeCallbacks(enforceRunnable)
        registeredApp?.let {
            try { it.unregisterActivityLifecycleCallbacks(activityCallbacks) } catch (_: Throwable) {}
        }
        registeredApp = null
        mainHandler.post {
            for (act in registeredActivities.toList()) {
                try { act.supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragmentCallbacks) } catch (_: Throwable) {}
            }
            registeredActivities.clear()
            restoreNativeNav()
            detachDock()
        }
    }

    fun currentActivity(): FragmentActivity? = currentActivityRef?.get()?.takeIf {
        !it.isFinishing && !it.isDestroyed
    }

    fun refreshTheme() {
        mainHandler.post {
            if (unloaded) return@post
            if (!prefs.enabled) {
                restoreNativeNav()
                dockOverlay?.visibility = View.GONE
                return@post
            }
            val act = currentActivity() ?: return@post
            ensureAttached(act)
            applyAccentColors()
            enforceState()
        }
    }

    private fun attachToActivity(activity: FragmentActivity) {
        if (unloaded) return
        currentActivityRef = WeakReference(activity)
        if (registeredActivities.add(activity)) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
        }
        if (activity.javaClass.name.contains("MainActivity")) {
            mainHandler.post { ensureAttached(activity) }
        }
    }

    private fun ensureAttached(activity: FragmentActivity) {
        if (unloaded || !prefs.enabled) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val rail = findViewByEntryName(content, "nav_rail_view")
        val bottom = findViewByEntryName(content, "nav_view")
        val outline = findViewByEntryName(content, "focus_outline") as? ImageView
        val homeRoot = findViewByEntryName(content, "home_root") as? ViewGroup ?: content

        if (rail != null && navRailRef?.get() !== rail) {
            navRailRef = WeakReference(rail)
            if (origRailVisibility == null) origRailVisibility = rail.visibility
        }
        if (bottom != null && navBottomRef?.get() !== bottom) {
            navBottomRef = WeakReference(bottom)
            if (origBottomVisibility == null) origBottomVisibility = bottom.visibility
        }
        if (outline != null) {
            focusOutlineRef = WeakReference(outline)
        }

        val sourceNav = rail ?: bottom ?: return
        val extracted = extractMenuItems(sourceNav)
        if (extracted.isEmpty()) return

        if (dockHost === homeRoot && dockOverlay != null && navItems.size == extracted.size) {
            applyAccentColors()
            return
        }

        navItems = extracted
        selectedItemId = readSelectedItemId(sourceNav) ?: extracted.first().id
        buildDock(homeRoot)
        applyAccentColors()
        enforceState()
    }

    private fun buildDock(container: ViewGroup) {
        detachDock()
        val ctx = container.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        val overlay = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            elevation = 990f
            isClickable = false
            isFocusable = false
        }

        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            elevation = dp(12).toFloat()
        }

        tabViews.clear()
        navItems.forEach { item ->
            val tab = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            }

            val iconView = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (item.icon != null) {
                    setImageDrawable(item.icon.constantState?.newDrawable()?.mutate() ?: item.icon)
                }
            }

            val titleView = TextView(ctx).apply {
                text = item.title
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setSingleLine(true)
                setPadding(dp(6), 0, 0, 0)
            }

            tab.addView(iconView)
            tab.addView(titleView)

            tab.setOnClickListener {
                selectTab(item.id)
            }

            pill.addView(tab)
            tabViews[item.id] = TabViewHolder(tab, iconView, titleView)
        }

        overlay.addView(
            pill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(14)
            }
        )

        container.addView(overlay)
        overlay.bringToFront()
        dockHost = container
        dockOverlay = overlay
        dockPill = pill
    }

    private fun selectTab(itemId: Int) {
        selectedItemId = itemId
        applyAccentColors()
        val targetNav = navBottomRef?.get() ?: navRailRef?.get() ?: return
        try {
            val setter = targetNav.javaClass.getMethod("setSelectedItemId", Int::class.javaPrimitiveType)
            setter.invoke(targetNav, itemId)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to switch tab $itemId", t)
        }
    }

    private fun applyAccentColors() {
        val pill = dockPill ?: return
        val ctx = pill.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()
        val accent = prefs.currentAccent()

        pill.background = GradientDrawable().apply {
            setColor(0xE6101420.toInt())
            cornerRadius = dp(28).toFloat()
            setStroke(dp(1), (accent.color and 0x00FFFFFF) or 0x66000000)
        }

        // Tint TV focus outline to match accent color
        focusOutlineRef?.get()?.setColorFilter(accent.glowColor, PorterDuff.Mode.SRC_IN)

        tabViews.forEach { (id, holder) ->
            val isSelected = (id == selectedItemId)
            val normalBg = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                if (isSelected) {
                    setColor(accent.color)
                } else {
                    setColor(Color.TRANSPARENT)
                }
            }
            val focusedBg = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(if (isSelected) accent.glowColor else 0x44FFFFFF)
                setStroke(dp(2), Color.WHITE)
            }
            holder.container.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focusedBg)
                addState(intArrayOf(android.R.attr.state_pressed), focusedBg)
                addState(intArrayOf(), normalBg)
            }

            val fgColor = if (isSelected) Color.WHITE else 0xFFB4C0D8.toInt()
            holder.icon.setColorFilter(fgColor, PorterDuff.Mode.SRC_IN)
            holder.title.setTextColor(fgColor)
            holder.title.visibility = if (isSelected) View.VISIBLE else View.GONE
        }
    }

    private fun enforceState() {
        if (!prefs.enabled) return
        val act = currentActivity()
        if (act != null && act.javaClass.name.contains("MainActivity") && dockOverlay == null) {
            ensureAttached(act)
        }

        val rail = navRailRef?.get()
        val bottom = navBottomRef?.get()

        // Always keep CloudStream's native rail & classic bottom bar hidden while theme is enabled
        if (rail != null && rail.visibility != View.GONE) rail.visibility = View.GONE
        if (bottom != null && bottom.visibility != View.GONE) bottom.visibility = View.GONE

        // Sync active tab highlight if user navigated via Back button
        val liveSelected = readSelectedItemId(bottom ?: rail)
        if (liveSelected != null && liveSelected != selectedItemId && tabViews.containsKey(liveSelected)) {
            selectedItemId = liveSelected
            applyAccentColors()
        }

        val showDock = !hideForFullscreen
        dockOverlay?.visibility = if (showDock) View.VISIBLE else View.GONE
        if (showDock) dockOverlay?.bringToFront()
    }

    private fun restoreNativeNav() {
        navRailRef?.get()?.let { it.visibility = origRailVisibility ?: View.VISIBLE }
        navBottomRef?.get()?.let { it.visibility = origBottomVisibility ?: View.GONE }
        focusOutlineRef?.get()?.clearColorFilter()
    }

    private fun detachDock() {
        val overlay = dockOverlay
        val host = dockHost
        if (overlay != null && host != null) {
            try { host.removeView(overlay) } catch (_: Throwable) {}
        }
        dockOverlay = null
        dockHost = null
        dockPill = null
        tabViews.clear()
    }

    private fun extractMenuItems(navView: View): List<DockItem> {
        return try {
            val getMenu = navView.javaClass.getMethod("getMenu")
            val menu = getMenu.invoke(navView) as? Menu ?: return emptyList()
            val list = ArrayList<DockItem>()
            for (i in 0 until menu.size()) {
                val mi = menu.getItem(i)
                if (mi.isVisible) {
                    list.add(DockItem(mi.itemId, mi.title?.toString().orEmpty(), mi.icon))
                }
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readSelectedItemId(navView: View?): Int? {
        if (navView == null) return null
        return try {
            val m = navView.javaClass.getMethod("getSelectedItemId")
            m.invoke(navView) as? Int
        } catch (_: Throwable) {
            null
        }
    }

    private fun findViewByEntryName(root: View, entryName: String): View? {
        if (root.id != View.NO_ID) {
            try {
                if (root.resources.getResourceEntryName(root.id) == entryName) return root
            } catch (_: Throwable) {
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findViewByEntryName(root.getChildAt(i), entryName)
                if (found != null) return found
            }
        }
        return null
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
            if (unloaded) return
            val name = f.javaClass.name
            val isFullscreen = f is AbstractPlayerFragment<*> || name.contains("ResultFragment")
            val isTopTab = name.contains("HomeFragment") ||
                name.contains("SearchFragment") ||
                name.contains("LibraryFragment") ||
                name.contains("DownloadFragment") ||
                name.contains("SettingsFragment")
            if (isFullscreen) {
                hideForFullscreen = true
                enforceState()
            } else if (isTopTab) {
                hideForFullscreen = false
                enforceState()
            }
        }
    }

    private data class DockItem(val id: Int, val title: String, val icon: Drawable?)
    private data class TabViewHolder(val container: LinearLayout, val icon: ImageView, val title: TextView)

    companion object {
        private const val TAG = "SupErenTema"
    }
}
