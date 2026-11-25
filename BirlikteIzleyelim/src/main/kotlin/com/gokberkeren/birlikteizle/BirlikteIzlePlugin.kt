package com.gokberkeren.birlikteizle

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BirlikteIzlePlugin : Plugin() {

    private var roomManager: RoomManager? = null
    private var playerHook: PlayerHook? = null

    override fun load(context: Context) {
        val privateUrl = decodeB64(BuildConfig.PRIVATE_FIREBASE_URL_B64)
        val privateShortCode = decodeB64(BuildConfig.PRIVATE_SHORT_CODE_B64)
        val firebase = FirebaseClient()
        val manager = RoomManager(context, firebase, privateUrl, privateShortCode)
        val hook = PlayerHook(manager)

        roomManager = manager
        playerHook = hook

        val app = context.applicationContext as? Application
        val activity = context as? Activity
        if (app != null) {
            hook.register(app, activity)
        } else {
            Log.w(TAG, "Application context unavailable; player lifecycle hook inactive")
        }

        openSettings = { ctx ->
            val targetCtx = hook.currentActivity() ?: ctx
            RoomDialog.show(targetCtx, manager)
        }

        Log.i(TAG, "Birlikteİzleyelim loaded (clientId=${manager.clientId})")
    }

    override fun beforeUnload() {
        roomManager?.leaveRoom()
        playerHook?.unregister()
        roomManager = null
        playerHook = null
        Log.i(TAG, "Birlikteİzleyelim unloaded")
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
        private const val TAG = "BirlikteIzleyelim"
    }
}
