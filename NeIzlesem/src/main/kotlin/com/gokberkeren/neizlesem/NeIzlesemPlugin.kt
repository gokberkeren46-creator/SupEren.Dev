package com.gokberkeren.neizlesem

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

@CloudstreamPlugin
class NeIzlesemPlugin : Plugin() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val registeredActivities = Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())
    private var registeredApp: Application? = null
    private var currentActivityRef: WeakReference<FragmentActivity>? = null
    private var fallbackHost: ViewGroup? = null
    private var fallbackOverlay: FrameLayout? = null
    private var engine: PoolEngine? = null

    @Volatile
    private var unloaded: Boolean = false

    override fun load(context: Context) {
        unloaded = false
        val poolEngine = PoolEngine()
        engine = poolEngine

        val app = context.applicationContext as? Application
        val initialAct = context as? FragmentActivity
        if (initialAct != null) currentActivityRef = WeakReference(initialAct)
        if (app != null) {
            registeredApp = app
            app.registerActivityLifecycleCallbacks(activityCallbacks)
            initialAct?.let { attachToActivity(it) }
        }

        registerCenterDockBridge()

        openSettings = { ctx ->
            val targetAct = currentActivity() ?: (ctx as? FragmentActivity)
            if (targetAct != null) {
                NeIzlesemDialog.show(targetAct, poolEngine)
            }
        }

        Log.i(TAG, "Ne İzlesem loaded")
    }

    override fun beforeUnload() {
        unloaded = true
        unregisterCenterDockBridge()
        registeredApp?.let {
            try { it.unregisterActivityLifecycleCallbacks(activityCallbacks) } catch (_: Throwable) {}
        }
        registeredApp = null
        mainHandler.post {
            for (act in registeredActivities.toList()) {
                try { act.supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragmentCallbacks) } catch (_: Throwable) {}
            }
            registeredActivities.clear()
            detachFallbackButton()
        }
        engine?.clear()
        engine = null
        Log.i(TAG, "Ne İzlesem unloaded")
    }

    private fun currentActivity(): FragmentActivity? = currentActivityRef?.get()?.takeIf {
        !it.isFinishing && !it.isDestroyed
    }

    private fun openDialogOnMain() {
        mainHandler.post {
            if (unloaded) return@post
            val act = currentActivity() ?: return@post
            val pool = engine ?: return@post
            NeIzlesemDialog.show(act, pool)
        }
    }

    private fun registerCenterDockBridge() {
        val map = ConcurrentHashMap<String, Any>()
        map["title"] = "Ne İzlesem"
        map["action"] = Runnable { openDialogOnMain() }
        System.getProperties()[PROP_CENTER_ACTION] = map
        notifyDockRefresh()
    }

    private fun unregisterCenterDockBridge() {
        System.getProperties().remove(PROP_CENTER_ACTION)
        notifyDockRefresh()
    }

    private fun notifyDockRefresh() {
        val refresher = System.getProperties()[PROP_DOCK_REFRESH] as? Runnable
        if (refresher != null) {
            mainHandler.post { try { refresher.run() } catch (_: Throwable) {} }
        }
    }

    private fun isSupErenDockActive(): Boolean {
        return System.getProperties()[PROP_DOCK_ACTIVE] == true
    }

    private fun attachToActivity(activity: FragmentActivity) {
        currentActivityRef = WeakReference(activity)
        if (registeredActivities.add(activity)) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
        }
    }

    private fun attachFallbackButton(fragment: Fragment) {
        if (unloaded) return
        if (isSupErenDockActive()) {
            detachFallbackButton()
            return
        }
        val container = fragment.view as? ViewGroup ?: return
        if (fallbackHost === container && fallbackOverlay?.isAttachedToWindow == true) {
            fallbackOverlay?.bringToFront()
            return
        }
        detachFallbackButton()

        val ctx = container.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        val root = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            elevation = 980f
            isClickable = false
            isFocusable = false
        }

        val normalBg = GradientDrawable().apply {
            setColor(0xDD1B2234.toInt())
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), 0xAA6366F1.toInt())
        }
        val focusedBg = GradientDrawable().apply {
            setColor(0xFF6366F1.toInt())
            cornerRadius = dp(18).toFloat()
            setStroke(dp(2), Color.WHITE)
        }

        val btn = TextView(ctx).apply {
            text = "🎲 Ne İzlesem"
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focusedBg)
                addState(intArrayOf(android.R.attr.state_pressed), focusedBg)
                addState(intArrayOf(), normalBg)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { openDialogOnMain() }
        }

        root.addView(
            btn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                marginEnd = dp(12)
                topMargin = dp(10)
            }
        )

        container.addView(root)
        root.bringToFront()
        fallbackHost = container
        fallbackOverlay = root
    }

    private fun detachFallbackButton() {
        val ov = fallbackOverlay
        val host = fallbackHost
        if (ov != null && host != null) {
            try { host.removeView(ov) } catch (_: Throwable) {}
        }
        fallbackOverlay = null
        fallbackHost = null
    }

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (!unloaded) (activity as? FragmentActivity)?.let { attachToActivity(it) }
        }
        override fun onActivityStarted(activity: Activity) {
            if (!unloaded) (activity as? FragmentActivity)?.let { attachToActivity(it) }
        }
        override fun onActivityResumed(activity: Activity) {
            if (!unloaded) (activity as? FragmentActivity)?.let { attachToActivity(it) }
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
            if (f.javaClass.simpleName == "HomeFragment") {
                mainHandler.post { attachFallbackButton(f) }
            }
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f.javaClass.simpleName == "HomeFragment") {
                detachFallbackButton()
            }
        }
    }

    companion object {
        private const val TAG = "NeIzlesem"
        const val PROP_CENTER_ACTION = "superen.dock.center_action"
        const val PROP_DOCK_REFRESH = "superen.dock.refresh"
        const val PROP_DOCK_ACTIVE = "superen.dock.active"
    }
}
