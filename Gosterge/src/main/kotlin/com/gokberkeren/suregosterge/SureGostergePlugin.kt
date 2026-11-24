package com.gokberkeren.suregosterge

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SureGostergePlugin : Plugin() {

    private var hook: TimeOverlayHook? = null

    override fun load(context: Context) {
        val prefs = SurePrefs(context)
        val overlayHook = TimeOverlayHook(prefs)
        hook = overlayHook

        val app = context.applicationContext as? Application
        val activity = context as? Activity
        if (app != null) {
            overlayHook.register(app, activity)
        } else {
            Log.w(TAG, "Application context unavailable; overlay hook inactive")
        }

        openSettings = { ctx ->
            val targetCtx = overlayHook.currentActivity() ?: ctx
            SureSettingsDialog.show(targetCtx, prefs) {
                overlayHook.refreshStyle()
            }
        }

        Log.i(TAG, "SureGosterge loaded")
    }

    companion object {
        private const val TAG = "SureGosterge"
    }
}
