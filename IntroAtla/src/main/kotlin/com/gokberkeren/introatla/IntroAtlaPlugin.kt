package com.gokberkeren.introatla

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IntroAtlaPlugin : Plugin() {

    private var hook: IntroOverlayHook? = null
    private var apiClient: IntroApiClient? = null

    override fun load(context: Context) {
        val prefs = IntroPrefs(context)
        val client = IntroApiClient()
        apiClient = client

        val overlayHook = IntroOverlayHook(prefs, client)
        hook = overlayHook

        val app = context.applicationContext as? Application
        val initialAct = context as? Activity
        if (app != null) {
            overlayHook.register(app, initialAct)
        }

        openSettings = { ctx ->
            val targetAct = overlayHook.currentActivity() ?: (ctx as? Activity)
            if (targetAct != null) {
                IntroSettingsDialog.show(targetAct, prefs) {
                    overlayHook.refreshStyle()
                }
            }
        }

        Log.i(TAG, "IntroAtla loaded")
    }

    override fun beforeUnload() {
        hook?.unregister()
        hook = null
        apiClient?.clear()
        apiClient = null
        Log.i(TAG, "IntroAtla unloaded")
    }

    companion object {
        private const val TAG = "IntroAtla"
    }
}
