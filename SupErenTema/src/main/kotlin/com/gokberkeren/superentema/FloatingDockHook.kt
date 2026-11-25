package com.gokberkeren.superentema

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
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
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AbsSeekBar
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import com.lagradost.cloudstream3.ui.settings.Globals
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * SupEren v2.0 (v3) layout & accent hook:
 *  - Tablet / Phone: Always-visible Floating Bottom Dock on non-fullscreen screens (live fragment scan, never stuck hidden).
 *  - Android TV: Full-screen edge-to-edge (zeros nav_host_fragment 72dp start margin so no black strip),
 *    auto-hidden while browsing posters, pops up smoothly via remote:
 *      1) Left (◀) at leftmost item,
 *      2) Down (▼) at bottommost row,
 *      3) Back on the 5 root tabs (2nd Back triggers normal CloudStream exit/back).
 */
class FloatingDockHook(private val prefs: TemaPrefs) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var registeredApp: Application? = null
    private val registeredActivities = Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())
    private val wrappedWindows = WeakHashMap<FragmentActivity, DockWindowCallback>()
    private var currentActivityRef: WeakReference<FragmentActivity>? = null

    private var dockHost: ViewGroup? = null
    private var dockOverlay: FrameLayout? = null
    private var dockPill: LinearLayout? = null
    private var navRailRef: WeakReference<View>? = null
    private var navBottomRef: WeakReference<View>? = null
    private var navHostRef: WeakReference<View>? = null
    private var focusOutlineRef: WeakReference<ImageView>? = null
    private var lastContentFocusRef: WeakReference<View>? = null

    private var navItems: List<DockItem> = emptyList()
    private val tabViews = mutableMapOf<Int, TabViewHolder>()
    private var builtScaleKey: String = ""

    @Volatile
    private var unloaded: Boolean = false

    @Volatile
    private var selectedItemId: Int = -1

    @Volatile
    private var focusedDockItemId: Int = -1

    @Volatile
    private var tvDockVisible: Boolean = false

    private var origRailVisibility: Int? = null
    private var origBottomVisibility: Int? = null
    private var origHostMarginStart: Int? = null

    private val hostLayoutGuard = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        if (unloaded || !prefs.enabled) return@OnLayoutChangeListener
        val act = currentActivity() ?: return@OnLayoutChangeListener
        if (!isFullscreenActive(act)) {
            zeroHostStartMargin(v)
        }
    }

    private val railVisibilityGuard = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        if (unloaded || !prefs.enabled) return@OnLayoutChangeListener
        if (v.visibility != View.GONE) {
            v.visibility = View.GONE
        }
    }

    private val enforceRunnable = object : Runnable {
        override fun run() {
            if (unloaded) return
            enforceState()
            mainHandler.postDelayed(this, 450L)
        }
    }

    fun register(application: Application, initialActivity: Activity?) {
        registeredApp = application
        application.registerActivityLifecycleCallbacks(activityCallbacks)
        (initialActivity as? FragmentActivity)?.let { attachToActivity(it) }
        mainHandler.postDelayed(enforceRunnable, 300L)
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
                unwrapWindowCallback(act)
            }
            registeredActivities.clear()
            wrappedWindows.clear()
            restoreNativeNav()
            detachDock()
        }
    }

    fun currentActivity(): FragmentActivity? = currentActivityRef?.get()?.takeIf {
        !it.isFinishing && !it.isDestroyed
    }

    fun isTvMode(context: Context? = currentActivity()): Boolean {
        try {
            if (Globals.isLayout(Globals.TV or Globals.EMULATOR)) return true
        } catch (_: Throwable) {
        }
        if (focusOutlineRef?.get() != null) return true
        val uiMode = context?.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun refreshTheme() {
        mainHandler.post {
            if (unloaded) return@post
            val act = currentActivity()
            if (!prefs.enabled) {
                tvDockVisible = false
                restoreNativeNav()
                dockOverlay?.visibility = View.GONE
                return@post
            }
            if (act != null) {
                if (dockOverlay != null && builtScaleKey != prefs.scaleKey) {
                    detachDock()
                }
                ensureAttached(act)
            }
            applyAccentAndStyle()
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
            wrapWindowCallback(activity)
            mainHandler.post { ensureAttached(activity) }
        }
    }

    private fun wrapWindowCallback(activity: FragmentActivity) {
        val window = activity.window ?: return
        val current = window.callback ?: return
        if (current is DockWindowCallback) return
        val wrapper = DockWindowCallback(current, activity)
        window.callback = wrapper
        wrappedWindows[activity] = wrapper
    }

    private fun unwrapWindowCallback(activity: FragmentActivity) {
        val window = activity.window ?: return
        val current = window.callback
        if (current is DockWindowCallback) {
            window.callback = current.base
        }
        wrappedWindows.remove(activity)
    }

    private fun ensureAttached(activity: FragmentActivity) {
        if (unloaded || !prefs.enabled) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val rail = findViewByEntryName(content, "nav_rail_view")
        val bottom = findViewByEntryName(content, "nav_view")
        val navHost = findViewByEntryName(content, "nav_host_fragment")
        val outline = findViewByEntryName(content, "focus_outline") as? ImageView

        if (outline != null) {
            focusOutlineRef = WeakReference(outline)
        }
        if (rail != null && navRailRef?.get() !== rail) {
            navRailRef?.get()?.removeOnLayoutChangeListener(railVisibilityGuard)
            navRailRef = WeakReference(rail)
            if (origRailVisibility == null) origRailVisibility = rail.visibility
            rail.addOnLayoutChangeListener(railVisibilityGuard)
        }
        if (bottom != null && navBottomRef?.get() !== bottom) {
            navBottomRef = WeakReference(bottom)
            if (origBottomVisibility == null) origBottomVisibility = bottom.visibility
        }
        if (navHost != null && navHostRef?.get() !== navHost) {
            navHostRef?.get()?.removeOnLayoutChangeListener(hostLayoutGuard)
            navHostRef = WeakReference(navHost)
            val lp = navHost.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null && lp.marginStart > 0 && origHostMarginStart == null) {
                origHostMarginStart = lp.marginStart
            }
            navHost.addOnLayoutChangeListener(hostLayoutGuard)
        }

        val sourceNav = rail ?: bottom ?: return
        val extracted = extractMenuItems(sourceNav)
        if (extracted.isEmpty()) return

        val existingOverlay = dockOverlay
        if (dockHost === content &&
            existingOverlay != null &&
            existingOverlay.isAttachedToWindow &&
            existingOverlay.parent === content &&
            navItems.size == extracted.size &&
            builtScaleKey == prefs.scaleKey
        ) {
            applyAccentAndStyle()
            enforceState()
            return
        }

        navItems = extracted
        selectedItemId = readSelectedItemId(sourceNav) ?: extracted.first().id
        if (focusedDockItemId == -1) focusedDockItemId = selectedItemId
        buildDock(content)
        applyAccentAndStyle()
        enforceState()
    }

    private fun buildDock(container: ViewGroup) {
        detachDock()
        val ctx = container.context
        val d = ctx.resources.displayMetrics.density
        val scale = prefs.currentScale().factor
        builtScaleKey = prefs.scaleKey
        fun sdp(v: Float): Int = (v * d * scale).toInt()
        fun dp(v: Float): Int = (v * d).toInt()

        val overlay = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            elevation = dp(120f).toFloat()
            translationZ = dp(120f).toFloat()
            isClickable = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(sdp(10f), sdp(7f), sdp(10f), sdp(7f))
            elevation = dp(24f).toFloat()
            translationZ = dp(24f).toFloat()
        }

        tabViews.clear()
        navItems.forEach { item ->
            val tab = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                tag = "tv_no_focus_tag" // Hides CloudStream TV square focus_outline on rounded pill
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = false
                setPadding(sdp(13f), sdp(9f), sdp(13f), sdp(9f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = sdp(3f)
                    marginEnd = sdp(3f)
                }
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        focusedDockItemId = item.id
                        applyAccentAndStyle()
                    } else if (tvDockVisible) {
                        mainHandler.post {
                            if (tvDockVisible && dockPill?.hasFocus() != true) {
                                closeTvDock(restoreFocus = false)
                            } else {
                                applyAccentAndStyle()
                            }
                        }
                    }
                }
            }

            val iconPx = sdp(20f)
            val iconView = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (item.icon != null) {
                    setImageDrawable(item.icon.constantState?.newDrawable()?.mutate() ?: item.icon)
                }
            }

            val titleView = TextView(ctx).apply {
                text = item.title
                textSize = 11.5f * scale
                setTypeface(typeface, Typeface.BOLD)
                setSingleLine(true)
                setPadding(sdp(6f), 0, 0, 0)
            }

            tab.addView(iconView)
            tab.addView(titleView)

            tab.setOnClickListener {
                if (isTvMode(ctx)) {
                    focusedDockItemId = item.id
                    activateFocusedDockTab()
                } else {
                    selectTab(item.id)
                }
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
                bottomMargin = dp(18f)
            }
        )

        if (isTvMode(ctx) && !tvDockVisible) {
            overlay.visibility = View.GONE
        }

        container.addView(overlay)
        overlay.bringToFront()
        dockHost = container
        dockOverlay = overlay
        dockPill = pill
    }

    private fun selectTab(itemId: Int) {
        selectedItemId = itemId
        focusedDockItemId = itemId
        applyAccentAndStyle()
        val act = currentActivity()
        val isLand = act?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
        val targetNav = (if (isLand == true) navRailRef?.get() else navBottomRef?.get())
            ?: navRailRef?.get()
            ?: navBottomRef?.get()
            ?: return
        try {
            val setter = targetNav.javaClass.getMethod("setSelectedItemId", Int::class.javaPrimitiveType)
            setter.invoke(targetNav, itemId)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to switch tab $itemId", t)
        }
    }

    private fun openTvDock(fromFocus: View?) {
        val overlay = dockOverlay ?: return
        val pill = dockPill ?: return
        if (fromFocus != null && !isDescendantOf(fromFocus, overlay)) {
            lastContentFocusRef = WeakReference(fromFocus)
        }
        tvDockVisible = true
        focusedDockItemId = selectedItemId.takeIf { tabViews.containsKey(it) }
            ?: navItems.firstOrNull()?.id
            ?: -1

        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        applyAccentAndStyle()

        val d = pill.resources.displayMetrics.density
        pill.animate().cancel()
        pill.translationY = 26f * d
        pill.alpha = 0f
        pill.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(150L)
            .start()

        val targetTab = tabViews[focusedDockItemId]?.container ?: tabViews.values.firstOrNull()?.container
        targetTab?.requestFocus()
    }

    private fun closeTvDock(restoreFocus: Boolean) {
        if (!tvDockVisible && dockOverlay?.visibility != View.VISIBLE) return
        tvDockVisible = false
        val overlay = dockOverlay
        dockPill?.animate()?.cancel()
        overlay?.visibility = View.GONE
        applyAccentAndStyle()

        if (restoreFocus) {
            val prev = lastContentFocusRef?.get()?.takeIf { it.isAttachedToWindow && it.isShown && it.isFocusable }
            if (prev != null && prev.requestFocus()) {
                return
            }
            focusFirstInHost()
        }
    }

    private fun focusDockAdjacent(delta: Int) {
        if (navItems.isEmpty()) return
        val curIdx = navItems.indexOfFirst { it.id == focusedDockItemId }.let { if (it < 0) 0 else it }
        val nextIdx = (curIdx + delta).coerceIn(0, navItems.lastIndex)
        val nextId = navItems[nextIdx].id
        focusedDockItemId = nextId
        applyAccentAndStyle()
        tabViews[nextId]?.container?.requestFocus()
    }

    private fun activateFocusedDockTab() {
        val targetId = focusedDockItemId.takeIf { tabViews.containsKey(it) } ?: selectedItemId
        val sameTab = (targetId == selectedItemId)
        if (!sameTab) {
            lastContentFocusRef = null
            selectTab(targetId)
        }
        closeTvDock(restoreFocus = sameTab)
        if (!sameTab) {
            mainHandler.postDelayed({ focusFirstInHost() }, 180L)
        }
    }

    private fun focusFirstInHost() {
        val host = navHostRef?.get() as? ViewGroup ?: return
        if (host.hasFocus()) return
        val next = FocusFinder.getInstance().findNextFocus(host, null, View.FOCUS_DOWN)
            ?: FocusFinder.getInstance().findNextFocus(host, null, View.FOCUS_RIGHT)
        if (next != null) {
            next.requestFocus()
        } else {
            host.requestFocus()
        }
    }

    private fun applyTvFocusTint() {
        val accent = prefs.currentAccent()
        focusOutlineRef?.get()?.setColorFilter(accent.glowColor, PorterDuff.Mode.SRC_IN)
    }

    private fun applyAccentAndStyle() {
        applyTvFocusTint()
        val pill = dockPill ?: return
        val ctx = pill.context
        val d = ctx.resources.displayMetrics.density
        val scale = prefs.currentScale().factor
        val tv = isTvMode(ctx)
        fun sdp(v: Float): Float = v * d * scale
        fun dp(v: Float): Int = (v * d).toInt()

        val accent = prefs.currentAccent()
        val bgPreset = prefs.currentOpacity()

        pill.background = GradientDrawable().apply {
            setColor(bgPreset.bgColor)
            cornerRadius = sdp(30f)
            setStroke(dp(1.5f).coerceAtLeast(1), (accent.color and 0x00FFFFFF) or 0xAA000000.toInt())
        }

        tabViews.forEach { (id, holder) ->
            val isSelected = (id == selectedItemId)
            val isTvFocused = tv && tvDockVisible && (id == focusedDockItemId)

            val normalBg = GradientDrawable().apply {
                cornerRadius = sdp(22f)
                when {
                    isTvFocused -> {
                        setColor(accent.glowColor)
                        setStroke(dp(2f).coerceAtLeast(2), Color.WHITE)
                    }
                    isSelected -> {
                        setColor(if (tv && tvDockVisible) (accent.color and 0x00FFFFFF) or 0x66000000 else accent.color)
                    }
                    else -> setColor(Color.TRANSPARENT)
                }
            }
            val pressedBg = GradientDrawable().apply {
                cornerRadius = sdp(22f)
                setColor(if (isSelected || isTvFocused) accent.glowColor else 0x44FFFFFF)
                setStroke(dp(2f).coerceAtLeast(2), Color.WHITE)
            }
            holder.container.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), pressedBg)
                addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
                addState(intArrayOf(), normalBg)
            }

            val showTitle = if (tv && tvDockVisible) (isTvFocused || isSelected) else isSelected
            val fgColor = if (isSelected || isTvFocused) Color.WHITE else 0xFFD0D9EC.toInt()
            holder.icon.setColorFilter(fgColor, PorterDuff.Mode.SRC_IN)
            holder.title.setTextColor(fgColor)
            holder.title.visibility = if (showTitle) View.VISIBLE else View.GONE
        }
    }

    private fun zeroHostStartMargin(hostView: View?) {
        val v = hostView ?: return
        val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.marginStart != 0) {
            if (origHostMarginStart == null && lp.marginStart > 0) {
                origHostMarginStart = lp.marginStart
            }
            lp.marginStart = 0
            v.layoutParams = lp
        }
    }

    private fun enforceState() {
        if (unloaded || !prefs.enabled) return
        val act = currentActivity() ?: return
        if (!act.javaClass.name.contains("MainActivity")) return

        val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
        val overlay = dockOverlay
        if (overlay == null || !overlay.isAttachedToWindow || overlay.parent !== content || dockHost !== content) {
            ensureAttached(act)
        }

        val fullscreen = isFullscreenActive(act)
        val rail = navRailRef?.get()
        val bottom = navBottomRef?.get()
        val navHost = navHostRef?.get()

        if (rail != null && rail.visibility != View.GONE) rail.visibility = View.GONE
        if (bottom != null && bottom.visibility != View.GONE) bottom.visibility = View.GONE
        if (!fullscreen) {
            zeroHostStartMargin(navHost)
        }

        applyTvFocusTint()

        val primaryNav = (if (act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) rail else bottom)
            ?: rail
            ?: bottom
        val liveSelected = readSelectedItemId(primaryNav)
        if (liveSelected != null && liveSelected != selectedItemId && tabViews.containsKey(liveSelected)) {
            selectedItemId = liveSelected
            if (!tvDockVisible) focusedDockItemId = liveSelected
            applyAccentAndStyle()
        }

        val currentOverlay = dockOverlay ?: return
        if (isTvMode(act)) {
            if (fullscreen && tvDockVisible) {
                closeTvDock(restoreFocus = false)
            }
            val showTv = !fullscreen && tvDockVisible
            currentOverlay.visibility = if (showTv) View.VISIBLE else View.GONE
            if (showTv && content.indexOfChild(currentOverlay) != content.childCount - 1) {
                currentOverlay.bringToFront()
            }
        } else {
            val showTablet = !fullscreen
            currentOverlay.visibility = if (showTablet) View.VISIBLE else View.GONE
            if (showTablet && content.indexOfChild(currentOverlay) != content.childCount - 1) {
                currentOverlay.bringToFront()
            }
        }
    }

    /**
     * Stateless live scan of currently visible fragments so the dock can NEVER get stuck hidden
     * on Tablet or TV after closing dialogs, trailers, or sub-screens.
     */
    private fun isFullscreenActive(activity: FragmentActivity): Boolean {
        return hasVisibleFullscreenFragment(activity.supportFragmentManager)
    }

    private fun hasVisibleFullscreenFragment(fm: FragmentManager): Boolean {
        val list = try { fm.fragments } catch (_: Throwable) { return false }
        for (f in list) {
            if (f == null || !f.isAdded || f.isHidden) continue
            if (f is DialogFragment) continue
            val v = f.view
            if (v != null && v.visibility == View.VISIBLE && v.isShown) {
                val simple = f.javaClass.simpleName
                if (f is AbstractPlayerFragment<*> ||
                    simple.startsWith("ResultFragment") ||
                    simple.startsWith("ResultTrailer") ||
                    simple == "WebviewFragment" ||
                    simple.startsWith("SetupFragment") ||
                    simple == "EasterEggMonkeFragment"
                ) {
                    return true
                }
            }
            val childFm = try { f.childFragmentManager } catch (_: Throwable) { null }
            if (childFm != null && hasVisibleFullscreenFragment(childFm)) {
                return true
            }
        }
        return false
    }

    /**
     * Returns true ONLY when one of the 5 root tabs is currently displayed and NO sub-screen
     * (like Extensions, Plugins, SettingsPlayer, DownloadChild) is visible on top,
     * so pressing Back on sub-pages always navigates up normally.
     */
    private fun isRootTabActive(activity: FragmentActivity): Boolean {
        val visibleNames = ArrayList<String>()
        collectVisibleFragmentNames(activity.supportFragmentManager, visibleNames)
        if (visibleNames.isEmpty()) return false
        var hasRoot = false
        for (simple in visibleNames) {
            if (simple == "NavHostFragment") continue
            val isRoot = simple == "HomeFragment" ||
                simple == "SearchFragment" ||
                simple == "LibraryFragment" ||
                simple == "DownloadFragment" ||
                simple == "SettingsFragment" ||
                simple == "SettingsFragment2"
            if (isRoot) {
                hasRoot = true
            } else {
                // Any non-root sub-page visible on screen -> let Back navigate up!
                return false
            }
        }
        return hasRoot
    }

    private fun collectVisibleFragmentNames(fm: FragmentManager, out: MutableList<String>) {
        val list = try { fm.fragments } catch (_: Throwable) { return }
        for (f in list) {
            if (f == null || !f.isAdded || f.isHidden || f is DialogFragment) continue
            val v = f.view
            if (v != null && v.visibility == View.VISIBLE && v.isShown) {
                out.add(f.javaClass.simpleName)
            }
            val childFm = try { f.childFragmentManager } catch (_: Throwable) { null }
            if (childFm != null) {
                collectVisibleFragmentNames(childFm, out)
            }
        }
    }

    private fun restoreNativeNav() {
        navRailRef?.get()?.let {
            it.removeOnLayoutChangeListener(railVisibilityGuard)
            it.visibility = origRailVisibility ?: View.VISIBLE
        }
        navBottomRef?.get()?.let {
            it.visibility = origBottomVisibility ?: View.GONE
        }
        navHostRef?.get()?.let { host ->
            host.removeOnLayoutChangeListener(hostLayoutGuard)
            val orig = origHostMarginStart
            if (orig != null) {
                val lp = host.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null) {
                    lp.marginStart = orig
                    host.layoutParams = lp
                }
            }
        }
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
        builtScaleKey = ""
        tvDockVisible = false
    }

    private fun isDescendantOf(view: View?, ancestor: ViewGroup?): Boolean {
        if (view == null || ancestor == null) return false
        var cur: Any? = view
        while (cur is View) {
            if (cur === ancestor) return true
            cur = cur.parent
        }
        return false
    }

    private fun canAncestorScroll(view: View, stopAt: ViewGroup, horizontal: Boolean, dir: Int): Boolean {
        var cur: Any? = view
        while (cur is View && cur !== stopAt) {
            val scrolled = if (horizontal) cur.canScrollHorizontally(dir) else cur.canScrollVertically(dir)
            if (scrolled) return true
            cur = cur.parent
        }
        return false
    }

    @SuppressLint("NewApi")
    private inner class DockWindowCallback(
        val base: Window.Callback,
        private val activity: FragmentActivity
    ) : Window.Callback by base {

        private var consumedKeyUpCode: Int = -1

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (unloaded || !prefs.enabled || !isTvMode(activity)) {
                return base.dispatchKeyEvent(event)
            }
            if (isFullscreenActive(activity)) {
                if (tvDockVisible) closeTvDock(restoreFocus = false)
                return base.dispatchKeyEvent(event)
            }

            if (dockOverlay == null || dockOverlay?.isAttachedToWindow != true) {
                ensureAttached(activity)
            }
            if (navItems.isEmpty()) {
                return base.dispatchKeyEvent(event)
            }

            val keyCode = event.keyCode
            val action = event.action

            // Consume matching ACTION_UP for keys we handled on ACTION_DOWN
            if (action == KeyEvent.ACTION_UP && keyCode == consumedKeyUpCode) {
                consumedKeyUpCode = -1
                return true
            }

            // 1. DOCK IS CURRENTLY OPEN ON TV
            if (tvDockVisible) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (action == KeyEvent.ACTION_DOWN) focusDockAdjacent(-1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (action == KeyEvent.ACTION_DOWN) focusDockAdjacent(+1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (action == KeyEvent.ACTION_DOWN) {
                            consumedKeyUpCode = keyCode
                            closeTvDock(restoreFocus = true)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            consumedKeyUpCode = keyCode
                            activateFocusedDockTab()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_ESCAPE -> {
                        // 2nd Back while dock is open: let CloudStream run its native Back/Exit dialog,
                        // keeping dock focus during dispatch so HomeFragment triggers exit confirmation cleanly!
                        val res = base.dispatchKeyEvent(event)
                        if (action == KeyEvent.ACTION_UP) {
                            mainHandler.post { closeTvDock(restoreFocus = false) }
                        }
                        return res
                    }
                }
                return base.dispatchKeyEvent(event)
            }

            // 2. DOCK IS HIDDEN ON TV — NEVER hijack DPAD_DOWN / DPAD_UP / DPAD_RIGHT!
            //    Only summon on:
            //      a) Back on the 5 root tabs (1st Back = dock, 2nd Back = normal exit/back)
            //      b) Left (◀) ONLY AFTER base.dispatchKeyEvent returns false AND focused.focusSearch(FOCUS_LEFT) finds nothing!
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val focused = activity.currentFocus
                val host = navHostRef?.get() as? ViewGroup

                when (keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        if (focused !is EditText && isRootTabActive(activity)) {
                            consumedKeyUpCode = keyCode
                            openTvDock(focused)
                            return true
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (focused is EditText && focused.selectionStart > 0) {
                            return base.dispatchKeyEvent(event)
                        }
                        if (focused is AbsSeekBar) {
                            return base.dispatchKeyEvent(event)
                        }
                        if (focused == null) {
                            consumedKeyUpCode = keyCode
                            openTvDock(null)
                            return true
                        }
                        // Give ComposeView / RecyclerView / Fragment FIRST chance to move focus or scroll left!
                        val handledByApp = base.dispatchKeyEvent(event)
                        if (handledByApp || activity.currentFocus !== focused) {
                            return handledByApp
                        }
                        // App/Compose did NOT consume Left. Check if ViewRootImpl would find a valid left focus inside host:
                        if (host != null && isDescendantOf(focused, host)) {
                            val nextLeft = try {
                                focused.focusSearch(View.FOCUS_LEFT)
                            } catch (_: Throwable) {
                                null
                            }
                            val hasValidLeft = nextLeft != null &&
                                nextLeft !== focused &&
                                nextLeft.isShown &&
                                isDescendantOf(nextLeft, host)
                            if (!hasValidLeft && !canAncestorScroll(focused, host, horizontal = true, dir = -1)) {
                                consumedKeyUpCode = keyCode
                                openTvDock(focused)
                                return true
                            }
                        }
                        return false
                    }
                }
            }

            return base.dispatchKeyEvent(event)
        }
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
            (activity as? FragmentActivity)?.let {
                attachToActivity(it)
                mainHandler.post { enforceState() }
            }
        }
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            (activity as? FragmentActivity)?.let { unwrapWindowCallback(it) }
            if (currentActivityRef?.get() === activity) currentActivityRef = null
        }
    }

    private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (!unloaded) mainHandler.post { enforceState() }
        }
        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            if (!unloaded) mainHandler.post { enforceState() }
        }
        override fun onFragmentStopped(fm: FragmentManager, f: Fragment) {
            if (!unloaded) mainHandler.post { enforceState() }
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (!unloaded) mainHandler.post { enforceState() }
        }
    }

    private data class DockItem(val id: Int, val title: String, val icon: Drawable?)
    private data class TabViewHolder(val container: LinearLayout, val icon: ImageView, val title: TextView)

    companion object {
        private const val TAG = "SupErenTema"
    }
}
