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
import com.lagradost.cloudstream3.ui.player.CSPlayerLoading
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
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Hooks CloudStream player lifecycle using live fragment.player and CSPlayerLoading
 * (ignoring IsBuffering/IsEnded to prevent false pauses or pull-backs),
 * smooth ±5% micro-speed sync (0.8s–15s), speed & mirror sync, and floating emojis.
 */
class PlayerHook(private val roomManager: RoomManager) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val emojiOverlay = EmojiOverlay(roomManager)

    private var currentActivityRef: WeakReference<FragmentActivity>? = null
    private var activePlayerFragmentRef: WeakReference<AbstractPlayerFragment<*>>? = null

    private var attachedExoPlayerRef: WeakReference<Any>? = null
    private var attachedExoListener: Any? = null
    private var monitorJob: Job? = null

    private val registeredActivities = Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())

    @Volatile
    private var suppressUntilElapsedMs: Long = 0L

    @Volatile
    private var verifySpeedUntilMs: Long = 0L

    @Volatile
    private var lastStableStatus: CSPlayerLoading? = null

    @Volatile
    private var lastObservedPositionMs: Long = -1L

    @Volatile
    private var lastObservedSourceName: String = ""

    @Volatile
    private var targetBaseSpeed: Float = 1.0f

    @Volatile
    private var isNudgingSpeed: Boolean = false

    @Volatile
    private var lastAppliedNudgeSpeed: Float = 1.0f

    @Volatile
    private var lastAppliedRemoteSourceName: String = ""

    @Volatile
    private var latestSyncState: PlaybackState? = null

    @Volatile
    private var lastVisitedResultUrl: String? = null

    @Volatile
    private var lastVisitedResultApiName: String? = null

    @Volatile
    private var lastPublishedMatchKey: String? = null

    @Volatile
    private var lastOpenedRemoteMatchKey: String? = null

    @Volatile
    private var lastRemoteOpenElapsedMs: Long = 0L

    @Volatile
    private var remoteMediaOpenLockUntilMs: Long = 0L

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

    private fun livePlayer(): IPlayer? = activePlayerFragmentRef?.get()?.player

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
        val status = fragment.currentPlayerStatus
        lastStableStatus = if (status == CSPlayerLoading.IsPlaying || status == CSPlayerLoading.IsPaused) status else null
        lastObservedPositionMs = iPlayer.getPosition() ?: 0L
        lastObservedSourceName = extractCurrentSourceName(fragment)
        targetBaseSpeed = iPlayer.getPlaybackSpeed().coerceIn(0.25f, 4.0f)
        isNudgingSpeed = false

        (fragment.view as? ViewGroup)?.let { rootView ->
            emojiOverlay.attach(rootView)
        }

        tryAttachExoListener(fragment, iPlayer)
        startMonitorLoop(fragment)
        publishCurrentMediaIfNeeded(fragment)
    }

    private fun detachFromPlayer() {
        monitorJob?.cancel()
        monitorJob = null
        emojiOverlay.detach()

        detachExoListenerOnly()
        activePlayerFragmentRef = null
        lastStableStatus = null
        lastObservedPositionMs = -1L
        lastObservedSourceName = ""
        isNudgingSpeed = false
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
                    "onPlayWhenReadyChanged" -> {
                        val playWhenReady = (args?.getOrNull(0) as? Boolean) ?: return@newProxyInstance null
                        onLocalPlayingChanged(playWhenReady)
                    }
                    "onPositionDiscontinuity" -> {
                        val reason = args?.getOrNull(2) as? Int
                        if (reason == DISCONTINUITY_REASON_SEEK) {
                            onLocalSeekDetected()
                        }
                    }
                    "onPlaybackParametersChanged" -> {
                        val params = args?.getOrNull(0)
                        val newSpeed = extractSpeedFromParams(params)
                        if (newSpeed != null) {
                            onLocalSpeedChanged(newSpeed)
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
            Log.w(TAG, "Could not attach Player.Listener (using CSPlayerLoading polling)", t)
            false
        }
    }

    private fun extractSpeedFromParams(params: Any?): Float? {
        if (params == null) return null
        try {
            val field = findFieldRecursive(params.javaClass, "speed")
            if (field != null) {
                field.isAccessible = true
                return (field.get(params) as? Float)?.coerceIn(0.25f, 4.0f)
            }
        } catch (_: Throwable) {
        }
        return null
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

    private fun startMonitorLoop(fragment: AbstractPlayerFragment<*>) {
        monitorJob = scope.launch {
            var metaAttempted = false
            while (isActive) {
                delay(MONITOR_INTERVAL_MS)
                emojiOverlay.updateVisibility()

                // Always read live fragment.player (never hold a stale IPlayer instance)
                val iPlayer = fragment.player

                // Re-attach if CS3IPlayer replaced its ExoPlayer instance (e.g., source/mirror switch)
                val currentExo = resolveExoPlayer(fragment, iPlayer)
                if (currentExo != null && currentExo !== attachedExoPlayerRef?.get()) {
                    tryAttachExoListener(fragment, iPlayer)
                    verifySpeedUntilMs = SystemClock.elapsedRealtime() + POST_RESUME_VERIFY_MS
                }

                if (!metaAttempted) {
                    metaAttempted = publishCurrentMediaIfNeeded(fragment)
                }

                if (!roomManager.isInRoom()) continue

                val status = fragment.currentPlayerStatus
                // CRITICAL: Completely ignore buffering/ended states so temporary buffering NEVER pauses or seeks!
                if (status == CSPlayerLoading.IsBuffering || status == CSPlayerLoading.IsEnded) {
                    continue
                }

                val nowElapsed = SystemClock.elapsedRealtime()
                val currentPlaying = (status == CSPlayerLoading.IsPlaying)
                val currentPos = iPlayer.getPosition() ?: continue
                val currentSource = extractCurrentSourceName(fragment)

                if (isSuppressed()) {
                    lastStableStatus = status
                    lastObservedPositionMs = currentPos
                    if (currentSource.isNotBlank()) lastObservedSourceName = currentSource
                    continue
                }

                // 1. Detect local mirror/source switch
                if (currentSource.isNotBlank() && currentSource != lastObservedSourceName) {
                    lastObservedSourceName = currentSource
                    if (!currentSource.equals(lastAppliedRemoteSourceName, ignoreCase = true)) {
                        publishLocalState(
                            playing = currentPlaying,
                            positionMs = currentPos,
                            speed = targetBaseSpeed,
                            sourceName = currentSource,
                            explicitSeek = false
                        )
                        verifySpeedUntilMs = nowElapsed + POST_RESUME_VERIFY_MS
                        continue
                    }
                }

                // 2. Detect local speed changes on live iPlayer (ignoring our own ±5% micro-nudge)
                val observedSpeed = iPlayer.getPlaybackSpeed().coerceIn(0.25f, 4.0f)
                val isOurNudge = isNudgingSpeed && abs(observedSpeed - lastAppliedNudgeSpeed) < 0.02f
                if (!isOurNudge && abs(observedSpeed - targetBaseSpeed) >= USER_SPEED_CHANGE_MIN_DELTA) {
                    val inVerifyWindow = nowElapsed < verifySpeedUntilMs
                    if (inVerifyWindow && abs(observedSpeed - 1.0f) < 0.02f && abs(targetBaseSpeed - 1.0f) > 0.04f) {
                        // CloudStream reset speed to 1.0x during source reload; re-apply room speed
                        suppressFor(600L)
                        applySpeedToPlayer(fragment, iPlayer, targetBaseSpeed)
                    } else {
                        targetBaseSpeed = observedSpeed
                        isNudgingSpeed = false
                        publishLocalState(
                            playing = currentPlaying,
                            positionMs = currentPos,
                            speed = observedSpeed,
                            sourceName = currentSource,
                            explicitSeek = false
                        )
                        continue
                    }
                }

                // 3. Detect genuine IsPlaying <-> IsPaused transitions (even if Media3 listener isn't attached)
                val prevStatus = lastStableStatus
                val prevPos = lastObservedPositionMs
                lastStableStatus = status
                lastObservedPositionMs = currentPos

                if (prevStatus != null && prevStatus != status) {
                    publishLocalState(
                        playing = currentPlaying,
                        positionMs = currentPos,
                        speed = targetBaseSpeed,
                        sourceName = currentSource,
                        explicitSeek = false
                    )
                    continue
                }

                // Detect manual large seek (>5s jump in 1s tick) if Media3 listener isn't attached
                val hasExoListener = attachedExoPlayerRef?.get() != null && attachedExoListener != null
                if (!hasExoListener && prevPos >= 0L && abs(currentPos - prevPos) > LOCAL_JUMP_THRESHOLD_MS) {
                    publishLocalState(
                        playing = currentPlaying,
                        positionMs = currentPos,
                        speed = targetBaseSpeed,
                        sourceName = currentSource,
                        explicitSeek = true
                    )
                    continue
                }

                // 4. Smooth drift control during active playback (zero cutting for 0.8s–15s!)
                val sync = latestSyncState ?: continue
                if (sync.playing && currentPlaying && sync.updatedAt > 0L) {
                    val elapsed = (roomManager.estimatedServerNowMs() - sync.updatedAt).coerceAtLeast(0L)
                    val expectedPos = sync.position + (elapsed * sync.speed).toLong()
                    val duration = iPlayer.getDuration() ?: Long.MAX_VALUE
                    if (expectedPos >= duration) continue

                    val drift = expectedPos - currentPos // >0: behind; <0: ahead
                    val absDrift = abs(drift)

                    when {
                        absDrift > HARD_SEEK_DRIFT_MS -> {
                            // Only hard-seek on huge desync (>15s)
                            resetSpeedNudge(iPlayer)
                            suppressFor(SUPPRESS_WINDOW_MS)
                            iPlayer.seekTo(expectedPos, PlayerEventSource.Sync)
                            lastObservedPositionMs = expectedPos
                        }
                        absDrift > NUDGE_START_DRIFT_MS -> {
                            // Smooth ±5% micro-speed adjustment (0.8s–15s): zero cutting!
                            val factor = if (drift > 0L) NUDGE_FAST_FACTOR else NUDGE_SLOW_FACTOR
                            val nudgedSpeed = (targetBaseSpeed * factor).coerceIn(0.25f, 3.5f)
                            if (abs(iPlayer.getPlaybackSpeed() - nudgedSpeed) > 0.01f) {
                                isNudgingSpeed = true
                                lastAppliedNudgeSpeed = nudgedSpeed
                                iPlayer.setPlaybackSpeed(nudgedSpeed)
                            }
                        }
                        absDrift < NUDGE_STOP_DRIFT_MS && isNudgingSpeed -> {
                            resetSpeedNudge(iPlayer)
                        }
                    }
                } else if (isNudgingSpeed) {
                    resetSpeedNudge(iPlayer)
                }
            }
        }
    }

    private fun applySpeedToPlayer(fragment: AbstractPlayerFragment<*>?, iPlayer: IPlayer, speed: Float) {
        iPlayer.setPlaybackSpeed(speed)
        if (fragment != null) {
            try {
                // Also notify FullScreenPlayer.setPlayBackSpeed(Float) if present so UI speed badge updates
                val m = findMethodRecursive(fragment.javaClass, "setPlayBackSpeed", Float::class.javaPrimitiveType!!)
                if (m != null) {
                    m.isAccessible = true
                    m.invoke(fragment, speed)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun resetSpeedNudge(iPlayer: IPlayer) {
        if (!isNudgingSpeed) return
        isNudgingSpeed = false
        lastAppliedNudgeSpeed = targetBaseSpeed
        if (abs(iPlayer.getPlaybackSpeed() - targetBaseSpeed) > 0.01f) {
            iPlayer.setPlaybackSpeed(targetBaseSpeed)
        }
    }

    private fun publishLocalState(
        playing: Boolean,
        positionMs: Long,
        speed: Float,
        sourceName: String,
        explicitSeek: Boolean
    ) {
        lastStableStatus = if (playing) CSPlayerLoading.IsPlaying else CSPlayerLoading.IsPaused
        lastObservedPositionMs = positionMs
        if (sourceName.isNotBlank()) lastObservedSourceName = sourceName
        roomManager.publishState(
            playing = playing,
            positionMs = positionMs,
            speed = speed,
            sourceName = sourceName,
            explicitSeek = explicitSeek
        )?.let {
            latestSyncState = it
        }
    }

    private fun onLocalPlayingChanged(playWhenReady: Boolean) {
        if (!roomManager.isInRoom() || isSuppressed()) return
        val fragment = activePlayerFragmentRef?.get() ?: return
        val player = fragment.player
        val pos = player.getPosition() ?: 0L
        val src = extractCurrentSourceName(fragment)
        if (playWhenReady) {
            verifySpeedUntilMs = SystemClock.elapsedRealtime() + POST_RESUME_VERIFY_MS
        }
        publishLocalState(
            playing = playWhenReady,
            positionMs = pos,
            speed = targetBaseSpeed,
            sourceName = src,
            explicitSeek = false
        )
    }

    private fun onLocalSeekDetected() {
        if (!roomManager.isInRoom() || isSuppressed()) return
        val fragment = activePlayerFragmentRef?.get() ?: return
        val player = fragment.player
        val pos = player.getPosition() ?: return
        val playing = (fragment.currentPlayerStatus != CSPlayerLoading.IsPaused)
        val src = extractCurrentSourceName(fragment)
        resetSpeedNudge(player)
        publishLocalState(
            playing = playing,
            positionMs = pos,
            speed = targetBaseSpeed,
            sourceName = src,
            explicitSeek = true
        )
    }

    private fun onLocalSpeedChanged(newSpeed: Float) {
        if (!roomManager.isInRoom() || isSuppressed()) return
        if (isNudgingSpeed && abs(newSpeed - lastAppliedNudgeSpeed) < 0.02f) return
        if (abs(newSpeed - targetBaseSpeed) < USER_SPEED_CHANGE_MIN_DELTA) return

        val fragment = activePlayerFragmentRef?.get() ?: return
        val player = fragment.player
        val pos = player.getPosition() ?: 0L
        val playing = (fragment.currentPlayerStatus != CSPlayerLoading.IsPaused)
        val src = extractCurrentSourceName(fragment)
        targetBaseSpeed = newSpeed
        isNudgingSpeed = false
        publishLocalState(
            playing = playing,
            positionMs = pos,
            speed = newSpeed,
            sourceName = src,
            explicitSeek = false
        )
    }

    private fun applyRemoteState(state: PlaybackState, senderName: String) {
        latestSyncState = state
        val fragment = activePlayerFragmentRef?.get() ?: return
        val player = fragment.player

        val targetPos = if (state.playing && state.updatedAt > 0L) {
            val elapsed = (roomManager.estimatedServerNowMs() - state.updatedAt).coerceAtLeast(0L)
            (state.position + (elapsed * state.speed).toLong()).coerceAtLeast(0L)
        } else {
            state.position.coerceAtLeast(0L)
        }

        val speedChanged = abs(state.speed - targetBaseSpeed) >= 0.03f
        if (speedChanged) {
            targetBaseSpeed = state.speed
            isNudgingSpeed = false
            suppressFor(SUPPRESS_WINDOW_MS)
            applySpeedToPlayer(fragment, player, state.speed)
            val speedStr = String.format(Locale.US, "%.2gx", state.speed)
            showToast("$senderName hızı $speedStr yaptı")
        }

        // Handle remote source/mirror change
        if (fragment is GeneratorPlayer && state.sourceName.isNotBlank() &&
            !state.sourceName.equals(lastObservedSourceName, ignoreCase = true) &&
            !state.sourceName.equals(lastAppliedRemoteSourceName, ignoreCase = true)
        ) {
            lastAppliedRemoteSourceName = state.sourceName
            val switched = trySwitchMirror(fragment, state.sourceName)
            if (switched) {
                lastObservedSourceName = state.sourceName
                suppressFor(SOURCE_SWITCH_SUPPRESS_MS)
                verifySpeedUntilMs = SystemClock.elapsedRealtime() + POST_RESUME_VERIFY_MS
                showToast("$senderName kaynağa geçti: ${state.sourceName}")
                return
            } else {
                showToast("$senderName kaynağı değiştirdi: ${state.sourceName}")
            }
        }

        val currentPos = player.getPosition() ?: 0L
        val duration = player.getDuration() ?: Long.MAX_VALUE
        val absDrift = abs(currentPos - targetPos)
        val currentPlaying = (fragment.currentPlayerStatus != CSPlayerLoading.IsPaused)
        val playStateChanged = currentPlaying != state.playing

        // Hard seek ONLY on explicit manual seek OR huge desync (>15s)
        val shouldHardSeek = (state.explicitSeek && absDrift > EXPLICIT_SEEK_MIN_DRIFT_MS) ||
            absDrift > HARD_SEEK_DRIFT_MS

        if (shouldHardSeek && targetPos < duration) {
            resetSpeedNudge(player)
            suppressFor(SUPPRESS_WINDOW_MS)
            player.seekTo(targetPos, PlayerEventSource.Sync)
            lastObservedPositionMs = targetPos
        }

        if (playStateChanged) {
            suppressFor(SUPPRESS_WINDOW_MS)
            val event = if (state.playing) CSPlayerEvent.Play else CSPlayerEvent.Pause
            player.handleEvent(event, PlayerEventSource.Sync)
            lastStableStatus = if (state.playing) CSPlayerLoading.IsPlaying else CSPlayerLoading.IsPaused
            if (state.playing) {
                verifySpeedUntilMs = SystemClock.elapsedRealtime() + POST_RESUME_VERIFY_MS
            }
            val statusMsg = if (state.playing) "$senderName oynattı" else "$senderName duraklattı"
            showToast(statusMsg)
        } else if (shouldHardSeek) {
            showToast("$senderName konumu eşitledi")
        }
    }

    private fun extractCurrentSourceName(fragment: AbstractPlayerFragment<*>): String {
        if (fragment !is GeneratorPlayer) return ""
        return try {
            val field = findFieldRecursive(fragment.javaClass, "currentSelectedLink") ?: return ""
            field.isAccessible = true
            val pair = field.get(fragment) as? Pair<*, *> ?: return ""
            val firstName = pair.first?.let { readStringProperty(it, "name") }
            val secondName = pair.second?.let { readStringProperty(it, "name") }
            (firstName ?: secondName).orEmpty().trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun trySwitchMirror(player: GeneratorPlayer, targetSourceName: String): Boolean {
        return try {
            val vmField = findFieldRecursive(player.javaClass, "viewModel") ?: return false
            vmField.isAccessible = true
            val vm = vmField.get(player) ?: return false

            val currentLinksLive = readProperty(vm, "currentLinks") ?: return false
            val videoLive = readProperty(currentLinksLive, "value") ?: return false
            val linksSet = (readProperty(videoLive, "data") ?: readProperty(videoLive, "value")) as? Iterable<*>
                ?: return false

            val match = linksSet.firstOrNull { item ->
                val pair = item as? Pair<*, *> ?: return@firstOrNull false
                val name = pair.first?.let { readStringProperty(it, "name") }
                    ?: pair.second?.let { readStringProperty(it, "name") }
                name != null && name.trim().equals(targetSourceName.trim(), ignoreCase = true)
            } as? Pair<*, *> ?: return false

            val loadLinkMethod = player.javaClass.declaredMethods.firstOrNull {
                it.name == "loadLink" && it.parameterCount == 2
            } ?: return false
            loadLinkMethod.isAccessible = true
            loadLinkMethod.invoke(player, match, true)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not switch mirror to $targetSourceName", t)
            false
        }
    }

    private fun publishCurrentMediaIfNeeded(fragment: AbstractPlayerFragment<*>): Boolean {
        if (!roomManager.isInRoom()) return false
        if (fragment !is GeneratorPlayer) return true
        val episode = extractResultEpisode(fragment) ?: return false

        val pageUrl = if (lastVisitedResultApiName == episode.apiName && !lastVisitedResultUrl.isNullOrBlank()) {
            lastVisitedResultUrl!!
        } else {
            return false
        }

        val media = MediaInfo(
            url = pageUrl,
            apiName = episode.apiName,
            name = episode.headerName?.takeIf { it.isNotBlank() } ?: episode.name.orEmpty(),
            season = episode.season,
            episode = episode.episode,
            episodeId = episode.id,
            by = roomManager.clientId
        )
        val mKey = media.matchKey()

        if (SystemClock.elapsedRealtime() < remoteMediaOpenLockUntilMs) {
            lastPublishedMatchKey = mKey
            return true
        }
        if (mKey == lastOpenedRemoteMatchKey || mKey == lastPublishedMatchKey) {
            return true
        }

        lastPublishedMatchKey = mKey
        roomManager.publishMedia(media)
        return true
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
        val incomingMatchKey = media.matchKey()
        val nowElapsed = SystemClock.elapsedRealtime()

        if (incomingMatchKey == lastOpenedRemoteMatchKey &&
            (nowElapsed - lastRemoteOpenElapsedMs) < REMOTE_MEDIA_LOCK_MS
        ) {
            return
        }

        val activeMatchKey = activePlayerFragmentRef?.get()?.let { frag ->
            if (frag is GeneratorPlayer) {
                extractResultEpisode(frag)?.let { ep ->
                    MediaInfo(
                        url = media.url,
                        apiName = ep.apiName,
                        name = ep.headerName?.takeIf { it.isNotBlank() } ?: ep.name.orEmpty(),
                        season = ep.season,
                        episode = ep.episode
                    ).matchKey()
                }
            } else null
        }
        if (activeMatchKey == incomingMatchKey) {
            lastOpenedRemoteMatchKey = incomingMatchKey
            lastRemoteOpenElapsedMs = nowElapsed
            return
        }

        remoteMediaOpenLockUntilMs = nowElapsed + REMOTE_MEDIA_LOCK_MS
        lastOpenedRemoteMatchKey = incomingMatchKey
        lastRemoteOpenElapsedMs = nowElapsed
        lastPublishedMatchKey = incomingMatchKey

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

    private fun findMethodRecursive(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        var cur: Class<*>? = clazz
        while (cur != null) {
            try {
                return cur.getDeclaredMethod(name, *paramTypes)
            } catch (_: NoSuchMethodException) {
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
        private const val SOURCE_SWITCH_SUPPRESS_MS = 3_000L
        private const val POST_RESUME_VERIFY_MS = 5_000L
        private const val REMOTE_MEDIA_LOCK_MS = 15_000L
        private const val MONITOR_INTERVAL_MS = 1_000L
        private const val NUDGE_START_DRIFT_MS = 800L
        private const val NUDGE_STOP_DRIFT_MS = 400L
        private const val EXPLICIT_SEEK_MIN_DRIFT_MS = 1_500L
        private const val HARD_SEEK_DRIFT_MS = 15_000L
        private const val LOCAL_JUMP_THRESHOLD_MS = 5_000L
        private const val NUDGE_FAST_FACTOR = 1.05f
        private const val NUDGE_SLOW_FACTOR = 0.95f
        private const val USER_SPEED_CHANGE_MIN_DELTA = 0.04f
    }
}
