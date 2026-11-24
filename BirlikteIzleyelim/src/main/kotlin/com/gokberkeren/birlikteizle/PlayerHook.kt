package com.gokberkeren.birlikteizle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.IPlayer
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppContextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Hooks CloudStream player lifecycle, captures local playback/media events,
 * auto-opens remote media on the same source, and renders live emoji reactions.
 */
class PlayerHook(private val roomManager: RoomManager) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val emojiOverlay = EmojiOverlay(roomManager)

    private var currentActivityRef: WeakReference<FragmentActivity>? = null
    private var activePlayerFragmentRef: WeakReference<AbstractPlayerFragment<*>>? = null
    private var activeIPlayerRef: WeakReference<IPlayer>? = null

    private var attachedExoPlayerRef: WeakReference<Any>? = null
    private var attachedExoListener: Any? = null
    private var monitorJob: Job? = null

    private val registeredActivities = Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())

    @Volatile
    private var suppressUntilElapsedMs: Long = 0L

    @Volatile
    private var lastObservedPlaying: Boolean? = null

    @Volatile
    private var lastObservedPositionMs: Long = -1L

    @Volatile
    private var latestSyncState: PlaybackState? = null

    @Volatile
    private var lastVisitedResultUrl: String? = null

    @Volatile
    private var lastVisitedResultApiName: String? = null

    @Volatile
    private var lastPublishedMediaKey: String? = null

    @Volatile
    private var pendingRemoteMediaKey: String? = null

    init {
        roomManager.onRemoteState = { state, senderName ->
            mainHandler.post { applyRemoteState(state, senderName) }
        }
        roomManager.onRemoteMedia = { media, senderName ->
            mainHandler.post { openRemoteMediaAutomatically(media, senderName) }
        }
        roomManager.onRemoteReaction = { reaction ->
            mainHandler.post { emojiOverlay.showFloatingReaction(reaction) }
        }
    }

    fun register(application: Application, initialActivity: Activity?) {
        application.registerActivityLifecycleCallbacks(activityCallbacks)
        (initialActivity as? FragmentActivity)?.let { attachToActivity(it) }
    }

    fun currentActivity(): FragmentActivity? = currentActivityRef?.get()?.takeIf {
        !it.isFinishing && !it.isDestroyed
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
                currentActivityRef = WeakReference(it)
                attachToActivity(it)
            }
        }

        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            if (currentActivityRef?.get() === activity) {
                currentActivityRef = null
            }
        }
    }

    private fun attachToActivity(activity: FragmentActivity) {
        currentActivityRef = WeakReference(activity)
        if (registeredActivities.add(activity)) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
        }
    }

    private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            captureResultMetadata(f)
            if (f is AbstractPlayerFragment<*>) {
                attachToPlayerFragment(f)
            }
        }

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            if (f is AbstractPlayerFragment<*> && activePlayerFragmentRef?.get() === f) {
                detachFromPlayer()
            }
        }

        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
            if (f is AbstractPlayerFragment<*> && activePlayerFragmentRef?.get() === f) {
                detachFromPlayer()
            }
        }
    }

    private fun captureResultMetadata(fragment: Fragment) {
        val args = fragment.arguments ?: return
        val url = args.getString("url")?.takeIf { it.isNotBlank() }
        val apiName = args.getString("apiName")?.takeIf { it.isNotBlank() }
        if (url != null && apiName != null) {
            lastVisitedResultUrl = url
            lastVisitedResultApiName = apiName
            return
        }
        if (fragment.javaClass.name.contains("ResultFragment")) {
            @Suppress("DEPRECATION")
            val stored = args.getSerializable("storedData") ?: args.getParcelable<android.os.Parcelable>("storedData")
            if (stored != null) {
                val rUrl = readStringProperty(stored, "url")
                val rApi = readStringProperty(stored, "apiName")
                if (!rUrl.isNullOrBlank() && !rApi.isNullOrBlank()) {
                    lastVisitedResultUrl = rUrl
                    lastVisitedResultApiName = rApi
                }
            }
        }
    }

    private fun attachToPlayerFragment(fragment: AbstractPlayerFragment<*>) {
        detachFromPlayer()
        activePlayerFragmentRef = WeakReference(fragment)
        val iPlayer = fragment.player
        activeIPlayerRef = WeakReference(iPlayer)
        lastObservedPlaying = iPlayer.getIsPlaying()
        lastObservedPositionMs = iPlayer.getPosition() ?: 0L

        (fragment.view as? ViewGroup)?.let { rootView ->
            emojiOverlay.attach(rootView)
        }

        tryAttachExoListener(fragment, iPlayer)
        startMonitorLoop(fragment, iPlayer)
        publishCurrentMediaIfNeeded(fragment)
    }

    private fun detachFromPlayer() {
        monitorJob?.cancel()
        monitorJob = null
        emojiOverlay.detach()

        detachExoListenerOnly()
        activeIPlayerRef = null
        activePlayerFragmentRef = null
        lastObservedPlaying = null
        lastObservedPositionMs = -1L
        lastPublishedMediaKey = null
    }

    private fun detachExoListenerOnly() {
        val exo = attachedExoPlayerRef?.get()
        val listener = attachedExoListener
        if (exo != null && listener != null) {
            try {
                val removeMethod = findMethodByName(exo.javaClass, "removeListener", 1)
                removeMethod?.invoke(exo, listener)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to remove ExoPlayer listener", t)
            }
        }
        attachedExoPlayerRef = null
        attachedExoListener = null
    }

    private fun tryAttachExoListener(fragment: AbstractPlayerFragment<*>, iPlayer: IPlayer): Boolean {
        val exo = resolveExoPlayer(fragment, iPlayer) ?: return false
        if (attachedExoPlayerRef?.get() === exo && attachedExoListener != null) return true

        // Replace listener cleanly if CS3IPlayer recreated its ExoPlayer instance
        detachExoListenerOnly()

        return try {
            val listenerClass = Class.forName(
                "androidx.media3.common.Player\$Listener",
                false,
                exo.javaClass.classLoader
            )
            val proxy = Proxy.newProxyInstance(
                exo.javaClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                when (method.name) {
                    // Use playWhenReady instead of isPlaying so buffering doesn't trigger false pauses
                    "onPlayWhenReadyChanged" -> {
                        val playWhenReady = (args?.getOrNull(0) as? Boolean) ?: return@newProxyInstance null
                        onLocalPlayingChanged(playWhenReady)
                    }
                    "onPositionDiscontinuity" -> {
                        // onPositionDiscontinuity(PositionInfo old, PositionInfo new, int reason)
                        val reason = args?.getOrNull(2) as? Int
                        if (reason == DISCONTINUITY_REASON_SEEK) {
                            onLocalSeekDetected()
                        }
                    }
                    "equals" -> false
                    "hashCode" -> 0
                    "toString" -> "BirlikteIzleyelimPlayerListener"
                }
                null
            }
            val addMethod = exo.javaClass.methods.firstOrNull {
                it.name == "addListener" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(listenerClass)
            } ?: return false
            addMethod.invoke(exo, proxy)
            attachedExoPlayerRef = WeakReference(exo)
            attachedExoListener = proxy
            Log.d(TAG, "Attached Media3 Player.Listener via reflection")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not attach Player.Listener (falling back to IPlayer polling)", t)
            false
        }
    }

    private fun resolveExoPlayer(fragment: AbstractPlayerFragment<*>, iPlayer: IPlayer): Any? {
        findFieldRecursive(iPlayer.javaClass, "exoPlayer")?.let { field ->
            try {
                field.isAccessible = true
                field.get(iPlayer)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return try {
            val getPlayerViewMethod = fragment.javaClass.getMethod("getPlayerView")
            val playerView = getPlayerViewMethod.invoke(fragment) ?: return null
            val getPlayerMethod = playerView.javaClass.getMethod("getPlayer")
            getPlayerMethod.invoke(playerView)
        } catch (_: Throwable) {
            null
        }
    }

    private fun startMonitorLoop(fragment: AbstractPlayerFragment<*>, iPlayer: IPlayer) {
        monitorJob = scope.launch {
            var lastMetaAttemptTick = 0
            while (isActive) {
                delay(MONITOR_INTERVAL_MS)
                emojiOverlay.updateVisibility()

                // Re-attach if CS3IPlayer created a new ExoPlayer instance (e.g., mirror/episode switch)
                val currentExo = resolveExoPlayer(fragment, iPlayer)
                if (currentExo != null && currentExo !== attachedExoPlayerRef?.get()) {
                    tryAttachExoListener(fragment, iPlayer)
                }

                if (lastPublishedMediaKey == null && lastMetaAttemptTick < 6) {
                    lastMetaAttemptTick++
                    publishCurrentMediaIfNeeded(fragment)
                }

                if (!roomManager.isInRoom()) continue
                if (isSuppressed()) {
                    lastObservedPlaying = iPlayer.getIsPlaying()
                    lastObservedPositionMs = iPlayer.getPosition() ?: lastObservedPositionMs
                    continue
                }

                val currentPlaying = iPlayer.getIsPlaying()
                val currentPos = iPlayer.getPosition() ?: continue

                val prevPlaying = lastObservedPlaying
                val prevPos = lastObservedPositionMs
                lastObservedPlaying = currentPlaying
                lastObservedPositionMs = currentPos

                // Only use polling for play/pause if Media3 reflection listener is unavailable
                val hasExoListener = attachedExoPlayerRef?.get() != null && attachedExoListener != null
                if (!hasExoListener && prevPlaying != null && prevPlaying != currentPlaying) {
                    publishLocalState(currentPlaying, currentPos)
                    continue
                }
                if (!hasExoListener && prevPos >= 0L && abs(currentPos - prevPos) > LOCAL_JUMP_THRESHOLD_MS) {
                    publishLocalState(currentPlaying, currentPos)
                    continue
                }

                // Periodic drift check against the latest room state (local OR remote)
                val sync = latestSyncState ?: continue
                if (sync.playing && currentPlaying && sync.updatedAt > 0L) {
                    val elapsed = (roomManager.estimatedServerNowMs() - sync.updatedAt).coerceAtLeast(0L)
                    val expectedPos = sync.position + elapsed
                    val duration = iPlayer.getDuration() ?: Long.MAX_VALUE
                    if (expectedPos < duration && abs(currentPos - expectedPos) > DRIFT_THRESHOLD_MS) {
                        suppressFor(SUPPRESS_WINDOW_MS)
                        iPlayer.seekTo(expectedPos, PlayerEventSource.Sync)
                        lastObservedPositionMs = expectedPos
                    }
                }
            }
        }
    }

    private fun publishLocalState(playing: Boolean, positionMs: Long) {
        lastObservedPlaying = playing
        lastObservedPositionMs = positionMs
        // Update latestSyncState immediately so the drift loop never pulls us back to stale remote time
        roomManager.publishState(playing, positionMs)?.let {
            latestSyncState = it
        }
    }

    private fun onLocalPlayingChanged(playWhenReady: Boolean) {
        if (!roomManager.isInRoom() || isSuppressed()) return
        val pos = activeIPlayerRef?.get()?.getPosition() ?: 0L
        publishLocalState(playWhenReady, pos)
    }

    private fun onLocalSeekDetected() {
        if (!roomManager.isInRoom() || isSuppressed()) return
        val player = activeIPlayerRef?.get() ?: return
        val pos = player.getPosition() ?: return
        val playing = player.getIsPlaying()
        publishLocalState(playing, pos)
    }

    private fun applyRemoteState(state: PlaybackState, senderName: String) {
        latestSyncState = state
        val player = activeIPlayerRef?.get() ?: return

        val targetPos = if (state.playing && state.updatedAt > 0L) {
            val elapsed = (roomManager.estimatedServerNowMs() - state.updatedAt).coerceAtLeast(0L)
            (state.position + elapsed).coerceAtLeast(0L)
        } else {
            state.position.coerceAtLeast(0L)
        }

        suppressFor(SUPPRESS_WINDOW_MS)

        val currentPos = player.getPosition() ?: 0L
        val duration = player.getDuration() ?: Long.MAX_VALUE
        if (targetPos < duration && abs(currentPos - targetPos) > DRIFT_THRESHOLD_MS) {
            player.seekTo(targetPos, PlayerEventSource.Sync)
            lastObservedPositionMs = targetPos
        }

        val currentPlaying = player.getIsPlaying()
        if (currentPlaying != state.playing) {
            val event = if (state.playing) CSPlayerEvent.Play else CSPlayerEvent.Pause
            player.handleEvent(event, PlayerEventSource.Sync)
            lastObservedPlaying = state.playing
            val statusMsg = if (state.playing) "$senderName oynattı" else "$senderName duraklattı"
            showToast(statusMsg)
        } else if (abs(currentPos - targetPos) > DRIFT_THRESHOLD_MS) {
            showToast("$senderName konumu eşitledi")
        }
    }

    private fun publishCurrentMediaIfNeeded(fragment: AbstractPlayerFragment<*>) {
        if (!roomManager.isInRoom()) return
        if (fragment !is GeneratorPlayer) return
        val episode = extractResultEpisode(fragment) ?: return

        val url = when {
            lastVisitedResultApiName == episode.apiName && !lastVisitedResultUrl.isNullOrBlank() ->
                lastVisitedResultUrl!!
            episode.data.startsWith("http", ignoreCase = true) -> episode.data
            else -> return
        }

        val media = MediaInfo(
            url = url,
            apiName = episode.apiName,
            name = episode.headerName?.takeIf { it.isNotBlank() } ?: episode.name.orEmpty(),
            season = episode.season,
            episode = episode.episode,
            episodeId = episode.id,
            by = roomManager.clientId
        )
        val key = media.uniqueKey()
        if (key == pendingRemoteMediaKey) {
            pendingRemoteMediaKey = null
            lastPublishedMediaKey = key
            return
        }
        if (key == lastPublishedMediaKey) return
        lastPublishedMediaKey = key
        roomManager.publishMedia(media)
    }

    private fun extractResultEpisode(player: GeneratorPlayer): ResultEpisode? {
        try {
            val method = player.javaClass.getDeclaredMethod("getCurrentMeta")
            method.isAccessible = true
            (method.invoke(player) as? ResultEpisode)?.let { return it }
        } catch (_: Throwable) {
        }
        try {
            val vmField = findFieldRecursive(player.javaClass, "viewModel") ?: return null
            vmField.isAccessible = true
            val vm = vmField.get(player) ?: return null
            val stateObj = readProperty(vm, "state") ?: return null
            val genState = readProperty(stateObj, "generatorState") ?: return null
            return readProperty(genState, "meta") as? ResultEpisode
        } catch (_: Throwable) {
            return null
        }
    }

    private fun openRemoteMediaAutomatically(media: MediaInfo, senderName: String) {
        val activity = currentActivity() ?: return
        val activeKey = activePlayerFragmentRef?.get()?.let { frag ->
            if (frag is GeneratorPlayer) extractResultEpisode(frag)?.let { ep ->
                "${lastVisitedResultUrl ?: ep.data}|${ep.apiName}|${ep.season ?: -1}|${ep.episode ?: -1}|${ep.id}"
            } else null
        }
        if (activeKey == media.uniqueKey()) return

        pendingRemoteMediaKey = media.uniqueKey()
        showToast("$senderName açtı: ${media.displayTitle()}")

        val startAction = if (media.episodeId != null) START_ACTION_LOAD_EP else START_ACTION_RESUME_LATEST
        val startValue = media.episodeId ?: 0

        try {
            with(AppContextUtils) {
                activity.loadResult(
                    media.url,
                    media.apiName,
                    media.name,
                    startAction,
                    startValue
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to auto-open remote media", t)
            showToast("İçerik otomatik açılamadı")
        }
    }

    private fun showToast(text: String) {
        val activity = currentActivity() ?: return
        try {
            CommonActivity.showToast(activity, text, Toast.LENGTH_SHORT)
        } catch (_: Throwable) {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun suppressFor(durationMs: Long) {
        suppressUntilElapsedMs = SystemClock.elapsedRealtime() + durationMs
    }

    private fun isSuppressed(): Boolean = SystemClock.elapsedRealtime() < suppressUntilElapsedMs

    private fun findFieldRecursive(clazz: Class<*>, name: String): Field? {
        var cur: Class<*>? = clazz
        while (cur != null) {
            try {
                return cur.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                cur = cur.superclass
            }
        }
        return null
    }

    private fun findMethodByName(clazz: Class<*>, name: String, paramCount: Int): Method? {
        return clazz.methods.firstOrNull { it.name == name && it.parameterCount == paramCount }
    }

    private fun readProperty(target: Any, name: String): Any? {
        val getter = "get${name.replaceFirstChar { it.uppercaseChar() }}"
        try {
            val m = target.javaClass.getMethod(getter)
            m.isAccessible = true
            return m.invoke(target)
        } catch (_: Throwable) {
        }
        findFieldRecursive(target.javaClass, name)?.let { f ->
            try {
                f.isAccessible = true
                return f.get(target)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun readStringProperty(target: Any, name: String): String? = readProperty(target, name) as? String

    companion object {
        private const val TAG = "BirlikteIzleyelimHook"
        private const val DISCONTINUITY_REASON_SEEK = 1
        private const val START_ACTION_RESUME_LATEST = 1
        private const val START_ACTION_LOAD_EP = 2
        private const val SUPPRESS_WINDOW_MS = 1_200L
        private const val MONITOR_INTERVAL_MS = 1_500L
        private const val DRIFT_THRESHOLD_MS = 2_000L
        private const val LOCAL_JUMP_THRESHOLD_MS = 3_500L
    }
}
