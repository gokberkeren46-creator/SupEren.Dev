package com.gokberkeren.birlikteizle

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BirlikteIzlePlugin : Plugin() {

    private var roomManager: RoomManager? = null
    private var playerHook: PlayerHook? = null

    override fun load(context: Context) {
        val firebaseUrl = BuildConfig.FIREBASE_URL
        val firebase = FirebaseClient(firebaseUrl)
        val manager = RoomManager(context, firebase)
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

        Log.i(TAG, "Birlikte İzle loaded (clientId=${manager.clientId})")
    }

    companion object {
        private const val TAG = "BirlikteIzle"
    }
}
