package com.gokberkeren.bulutsenkron

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BulutSenkronPlugin : Plugin() {

    private var engine: SyncEngine? = null

    override fun load(context: Context) {
        val privateUrl = decodeB64(BuildConfig.PRIVATE_FIREBASE_URL_B64)
        val privateShortCode = decodeB64(BuildConfig.PRIVATE_SHORT_CODE_B64)
        val prefs = SyncPrefs(context, privateUrl, privateShortCode)
        val syncEngine = SyncEngine(context.applicationContext, prefs)
        engine = syncEngine

        val app = context.applicationContext as? Application
        val activity = context as? Activity
        if (app != null) {
            syncEngine.register(app, activity)
        } else {
            Log.w(TAG, "Application context unavailable")
        }

        openSettings = { ctx ->
            val targetCtx = syncEngine.currentActivity() ?: ctx
            SyncDialog.show(targetCtx, prefs, syncEngine)
        }

        Log.i(TAG, "BulutSenkron loaded")
    }

    private fun decodeB64(b64: String): String {
        if (b64.isBlank()) return ""
        return try {
            String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).trim().trimEnd('/')
        } catch (_: Throwable) {
            ""
        }
    }

    companion object {
        private const val TAG = "BulutSenkron"
    }
}
