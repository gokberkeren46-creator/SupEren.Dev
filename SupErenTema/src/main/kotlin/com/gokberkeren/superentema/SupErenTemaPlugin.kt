package com.gokberkeren.superentema

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SupErenTemaPlugin : Plugin() {

    private var hook: FloatingDockHook? = null

    override fun load(context: Context) {
        val prefs = TemaPrefs(context)
        val dockHook = FloatingDockHook(prefs)
        hook = dockHook

        val app = context.applicationContext as? Application
        val activity = context as? Activity
        if (app != null) {
            dockHook.register(app, activity)
        } else {
            Log.w(TAG, "Application context unavailable")
        }

        openSettings = { ctx ->
            val targetCtx = dockHook.currentActivity() ?: ctx
            TemaSettingsDialog.show(targetCtx, prefs, dockHook.isTvMode(targetCtx)) {
                dockHook.refreshTheme()
            }
        }

        Log.i(TAG, "SupEren v2.0 loaded")
    }

    override fun beforeUnload() {
        hook?.unregister()
        hook = null
        Log.i(TAG, "SupEren v2.0 unloaded")
    }

    companion object {
        private const val TAG = "SupErenTema"
    }
}
