package com.gokberkeren.bitrategoster

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BitrateGosterPlugin : Plugin() {

    private var hook: BitrateOverlayHook? = null

    override fun load(context: Context) {
        val prefs = BitratePrefs(context)
        val overlayHook = BitrateOverlayHook(prefs)
        hook = overlayHook

        val app = context.applicationContext as? Application
        val activity = context as? Activity
        if (app != null) {
            overlayHook.register(app, activity)
        } else {
            Log.w(TAG, "Application context unavailable")
        }

        openSettings = { ctx ->
            val targetCtx = overlayHook.currentActivity() ?: ctx
            BitrateSettingsDialog.show(targetCtx, prefs) {
                overlayHook.refreshStyle()
            }
        }

        Log.i(TAG, "BitrateGoster loaded")
    }

    override fun beforeUnload() {
        hook?.unregister()
        hook = null
        Log.i(TAG, "BitrateGoster unloaded")
    }

    companion object {
        private const val TAG = "BitrateGoster"
    }
}
