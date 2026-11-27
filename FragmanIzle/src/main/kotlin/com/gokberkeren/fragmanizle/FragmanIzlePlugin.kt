package com.gokberkeren.fragmanizle

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FragmanIzlePlugin : Plugin() {

    private var hook: ResultTrailerHook? = null
    private var engine: TrailerSearchEngine? = null

    override fun load(context: Context) {
        val prefs = FragmanPrefs(context)
        val searchEngine = TrailerSearchEngine()
        engine = searchEngine

        val trailerHook = ResultTrailerHook(prefs, searchEngine)
        hook = trailerHook

        val app = context.applicationContext as? Application
        val initialAct = context as? Activity
        if (app != null) {
            trailerHook.register(app, initialAct)
        }

        openSettings = { ctx ->
            val targetAct = trailerHook.currentActivity() ?: (ctx as? Activity)
            if (targetAct != null) {
                FragmanSettingsDialog.show(targetAct, prefs) {
                    trailerHook.refreshVisibility()
                }
            }
        }

        Log.i(TAG, "FragmanIzle loaded")
    }

    override fun beforeUnload() {
        hook?.unregister()
        hook = null
        engine?.clear()
        engine = null
        Log.i(TAG, "FragmanIzle unloaded")
    }

    companion object {
        private const val TAG = "FragmanIzle"
    }
}
