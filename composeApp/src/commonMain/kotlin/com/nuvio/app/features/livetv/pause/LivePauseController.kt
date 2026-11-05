package com.nuvio.app.features.livetv.pause

import com.nuvio.app.features.common.lifecycle.EffectScope
import com.nuvio.app.features.iptv.BoundedLoad
import com.nuvio.app.features.iptv.LoadOutcome
import com.nuvio.app.features.iptv.LoadSurface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Live pause / rewind for ONE player instance (multiview-ready: nothing here is global, everything is keyed by
 * [instanceId]). The state holder the screen drives and the UI renders; no Composable owns any of this logic.
 *
 * The mechanism is chosen per pause by [LivePausePolicy.mechanism] from the channel and the ACTIVE engine:
 *  - DISK_BUFFER (ExoPlayer, Android): pause releases the live connection, a capture records the stream to disk,
 *    and the surface swaps to the local playlist ([surface]), parked at the pause point.
 *  - ENGINE_BACK_BUFFER (mpv): the engine's own demuxer cache holds the past; pause/rewind raise its budget.
 *  - PROVIDER_CATCHUP: nothing is buffered; a long pause resumes through the provider archive.
 *
 * Confinement: every method is called from, and every coroutine runs on, the dispatcher of the [scope] given
 * (the UI thread). The capture's own work runs behind its port. Loading is entered only through
 * [BoundedLoad] (ArchitectureTest R7): [LivePauseState.Preparing] carries its deadline.
 */
class LivePauseController(
    val instanceId: String,
    scope: CoroutineScope,
    private val clock: LivePauseClock,
    private val capture: LivePauseCapturePort?,
    private val minter: LiveLinkMinter,
    private val host: LivePauseHost,
    private val config: LivePauseConfig = LivePauseConfig(),
    private val onRevertFailure: (Throwable) -> Unit = {},
) {
    private val root = SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + root)

    private val _state = MutableStateFlow<LivePauseState>(LivePauseState.Off)
    val state: StateFlow<LivePauseState> = _state

    private val _surface = MutableStateFlow<LivePauseSurface>(LivePauseSurface.Live)
    val surface: StateFlow<LivePauseSurface> = _surface

    // What is on screen.
    private var contentId: String? = null
    private var channel: LivePauseChannel? = null
    private var link: LiveLink? = null
    private var singleUse = false
    private var reconnect = LiveCaptureReconnect(singleUseLink = false)
    private var bitrateBps: Long? = null
    private var engine: LivePauseEnginePort? = null
    private var caps = LivePauseCapabilities(diskBuffer = false, engineBackBuffer = false)

    // The session (one pause, until Live).
    private var mechanism = LivePauseMechanism.UNAVAILABLE
    private var wantPaused = false
    private var pausedAtWallMs = 0L
    private var bufferedMs = 0L
    private var bufferFull = false
    private var generation = 0
    private var startJob: Job? = null
    private var captureHandle: LiveCaptureHandle? = null
    private var effects = newEffects()
    private var rePauseArmed = false
    private var disposed = false

    /** True when the pause button drives this controller (otherwise the caller keeps the plain pause). */
    val handlesPlayPause: Boolean get() = !disposed && activeMechanism() != LivePauseMechanism.UNAVAILABLE

    // --- events from the screen ------------------------------------------------------------------

    /**
     * A live source was resolved and is about to play (a tune, a zap, a re-resolve). Ends any session: the
     * buffer belongs to the stream it was recorded from. A pending Retry survives a re-resolve of the SAME
     * channel (that is what Retry does) but not a switch to another one.
     */
    fun onLiveSourceOpened(
        contentId: String,
        channel: LivePauseChannel,
        link: LiveLink,
        singleUseLink: Boolean,
        bitrateBps: Long? = null,
    ) {
        if (disposed) return
        val retrying = rePauseArmed && this.contentId == contentId
        if (this.contentId != contentId) rePauseArmed = false
        endSession()
        // One attempt counter per channel run: a Retry continues it, anything else starts over.
        if (!retrying) reconnect = LiveCaptureReconnect(singleUseLink)
        this.contentId = contentId
        this.channel = channel
        this.link = link
        this.singleUse = singleUseLink
        this.bitrateBps = bitrateBps
        // A new source is a new player: the old engine is gone, [attachEngine] brings the next one.
        this.engine = null
        _surface.value = LivePauseSurface.Live
        _state.value = LivePauseState.Off
    }

    /** The surface's player is ready (again): remember what it can do. Null when it went away. */
    fun attachEngine(engine: LivePauseEnginePort?, capabilities: LivePauseCapabilities) {
        if (disposed) return
        this.engine = engine
        this.caps = capabilities
        if (engine == null) return
        if (_surface.value is LivePauseSurface.Local) {
            if (!wantPaused) engine.play()   // the local source is prepared paused; the viewer asked to watch
        } else if (_state.value is LivePauseState.Off) {
            applyLivePlan()
        }
    }

    /** Called on every player snapshot while a pause session may be running. */
    fun onSnapshot(positionMs: Long, isPlaying: Boolean) {
        if (disposed) return
        if (rePauseArmed && isPlaying && _state.value is LivePauseState.Off && _surface.value is LivePauseSurface.Live) {
            rePauseArmed = false
            onPause()
            return
        }
        val current = _state.value as? LivePauseState.Shifted ?: return
        when (mechanism) {
            LivePauseMechanism.DISK_BUFFER -> {
                if (_surface.value !is LivePauseSurface.Local) return   // a late snapshot of the released live player
                _state.value = current.copy(
                    positionMs = positionMs,
                    windowEndMs = bufferedMs,
                    behindLiveMs = (bufferedMs - positionMs).coerceAtLeast(0L),
                    bufferFull = bufferFull,
                )
            }
            LivePauseMechanism.ENGINE_BACK_BUFFER -> {
                val window = engine?.liveWindow() ?: return
                if (!wantPaused && isPlaying && window.behindLiveMs <= config.edgeSlackMs) {
                    finishSession()   // caught up with the live edge on its own
                    return
                }
                _state.value = engineShifted(window)
            }
            else -> Unit
        }
    }

    // --- viewer intents --------------------------------------------------------------------------

    /**
     * The play/pause button. While a session runs the controller's own state decides (the engine's snapshot is
     * about a player that is released or has not started), otherwise the engine's [isPlaying] does.
     */
    fun togglePlayPause(isPlaying: Boolean) {
        when (val running = _state.value) {
            is LivePauseState.Shifted -> if (running.paused) onPlay() else onPause()
            is LivePauseState.Preparing -> if (wantPaused) onPlay() else onPause()
            is LivePauseState.Failed -> onPlay()   // Play on a failed pause goes live (the surface may be released)
            LivePauseState.Off -> if (isPlaying) onPause() else onPlay()
        }
    }

    /** The channel's provider-archive fact arrived after the tune (the guide loads later). Only between sessions. */
    fun onArchiveKnown(hasProviderArchive: Boolean) {
        if (disposed || _state.value !is LivePauseState.Off) return
        channel = channel?.copy(hasProviderArchive = hasProviderArchive)
    }

    /** The channel on screen went away (a zap, a replay started): the buffer belongs to it, so it goes too. */
    fun reset() {
        if (disposed) return
        rePauseArmed = false
        endSession()
        channel = null
        contentId = null
        _surface.value = LivePauseSurface.Live
        _state.value = LivePauseState.Off
    }

    fun onPause() {
        if (disposed) return
        val running = _state.value
        if (running is LivePauseState.Failed) return
        if (running !is LivePauseState.Off) {
            wantPaused = true
            if (running is LivePauseState.Shifted) {
                engine?.pause()
                _state.value = running.copy(paused = true)
                if (mechanism == LivePauseMechanism.ENGINE_BACK_BUFFER) applyPausedPlan()
            }
            return
        }
        when (val m = activeMechanism()) {
            LivePauseMechanism.UNAVAILABLE -> engine?.pause()
            LivePauseMechanism.PROVIDER_CATCHUP -> {
                mechanism = m
                wantPaused = true
                pausedAtWallMs = clock.wallMs()
                engine?.pause()
                _state.value = LivePauseState.Shifted(true, 0L, 0L, 0L, 0L, false)
            }
            LivePauseMechanism.ENGINE_BACK_BUFFER -> {
                mechanism = m
                wantPaused = true
                applyPausedPlan()
                engine?.pause()
                _state.value = engineShifted(engine?.liveWindow())
            }
            LivePauseMechanism.DISK_BUFFER -> startDisk()
        }
    }

    fun onPlay() {
        if (disposed) return
        val running = _state.value
        wantPaused = false
        if (running is LivePauseState.Failed) {
            retune()   // a failed pause can leave nothing on screen: Play means "back to the picture"
            return
        }
        if (running is LivePauseState.Off) {
            engine?.play()
            return
        }
        if (running is LivePauseState.Preparing) return   // plays the moment the buffer is ready (attachEngine)
        when (mechanism) {
            LivePauseMechanism.PROVIDER_CATCHUP -> {
                val pausedFor = clock.wallMs() - pausedAtWallMs
                if (pausedFor < config.catchUpResumeThresholdMs) {
                    engine?.play()
                    finishSession()
                } else {
                    val start = pausedAtWallMs
                    finishSession()
                    host.openCatchUp(start, start + config.keepMs)
                }
            }
            LivePauseMechanism.ENGINE_BACK_BUFFER -> {
                applyLivePlan()
                engine?.play()
                _state.value = (running as LivePauseState.Shifted).copy(paused = false)
            }
            else -> {
                engine?.play()
                _state.value = (running as LivePauseState.Shifted).copy(paused = false)
            }
        }
    }

    /** Scrub to [positionMs] inside the window the state reports (window clock: 0 = pause point for DISK_BUFFER). */
    fun seekInWindow(positionMs: Long) {
        if (disposed) return
        val running = _state.value as? LivePauseState.Shifted ?: return
        val target = when (mechanism) {
            LivePauseMechanism.DISK_BUFFER -> positionMs.coerceIn(0L, bufferedMs)
            LivePauseMechanism.ENGINE_BACK_BUFFER -> {
                val w = engine?.liveWindow() ?: return
                positionMs.coerceIn(w.startMs, w.endMs)
            }
            else -> return
        }
        if (target == running.positionMs) return   // a held key at the edge asks for the same seek again and again
        engine?.seekTo(target)
        _state.value = running.copy(
            positionMs = target,
            behindLiveMs = (running.windowEndMs - target).coerceAtLeast(0L),
        )
    }

    fun goLive() {
        if (disposed) return
        if (_state.value is LivePauseState.Off) return
        val action = LivePausePolicy.goLive(mechanism, captureHealthy = engine?.liveWindow() != null)
        when (action) {
            LivePausePolicy.GoLiveAction.SEEK_TO_EDGE -> {
                engine?.liveWindow()?.let { engine?.seekTo(it.endMs) }
                engine?.play()
                finishSession()
            }
            LivePausePolicy.GoLiveAction.RETUNE -> retune()
        }
    }

    /** From Failed: go live and pause again once the picture is back. */
    fun retry() {
        if (disposed) return
        if (_state.value !is LivePauseState.Failed) return
        rePauseArmed = true
        reconnect.onRetry()
        retune()
    }

    /** Ends the session and deletes the buffer. The controller is unusable afterwards. */
    fun dispose() {
        if (disposed) return
        disposed = true
        endSession()
        root.cancel()
        scope.cancel()
    }

    // --- disk buffer -----------------------------------------------------------------------------

    private fun startDisk() {
        val port = capture ?: return
        val live = link ?: return
        mechanism = LivePauseMechanism.DISK_BUFFER
        wantPaused = true
        bufferedMs = 0L
        bufferFull = false
        val mine = ++generation
        _surface.value = LivePauseSurface.Released
        _state.value = LivePauseState.Preparing(BoundedLoad.begin(LoadSurface.LIVE_PAUSE))
        val ready = CompletableDeferred<LiveCaptureEvent.Ready>()
        val fx = effects   // this session's scope: a superseding session replaces [effects] under a running start
        val request = LiveCaptureRequest(
            instanceId = instanceId,
            firstLink = live,
            singleUseLink = singleUse,
            minter = minter,
            keepMs = config.keepMs,
            bitrateBps = bitrateBps,
            lowMemoryTier = config.lowMemoryTier,
            reconnect = reconnect,
        )
        startJob = scope.launch {
            val outcome = BoundedLoad.run(
                surface = LoadSurface.LIVE_PAUSE,
                report = mapOf("mechanism" to "disk_buffer"),
            ) {
                // The provider connection is released first (a max_connections=1 account), then the capture opens.
                delay(config.captureStartDelayMs)
                val handle = port.start(request)
                captureHandle = handle
                fx.onRevert(handle::dispose)
                scope.launch {
                    handle.events.collect { event -> onCaptureEvent(mine, event, ready) }
                    ready.completeExceptionally(CaptureFailure(LivePauseFailure.STALLED))   // the flow ended: no recovery coming
                }
                ready.await()
            }
            if (mine != generation) return@launch   // a newer session or a Live press superseded this one
            when (outcome) {
                is LoadOutcome.Failed -> fail(
                    when {
                        outcome.timedOut -> LivePauseFailure.TIMED_OUT
                        else -> (outcome.error as? CaptureFailure)?.reason ?: LivePauseFailure.PROVIDER_REFUSED
                    },
                )
                else -> {
                    val readyEvent = outcome.valueOrNull() ?: return@launch
                    bufferedMs = maxOf(bufferedMs, readyEvent.bufferedMs)
                    _surface.value = LivePauseSurface.Local(readyEvent.playlistUrl)
                    _state.value = LivePauseState.Shifted(
                        paused = wantPaused,
                        behindLiveMs = bufferedMs,
                        windowStartMs = 0L,
                        windowEndMs = bufferedMs,
                        positionMs = 0L,
                        bufferFull = bufferFull,
                    )
                }
            }
        }
    }

    private fun onCaptureEvent(forGeneration: Int, event: LiveCaptureEvent, ready: CompletableDeferred<LiveCaptureEvent.Ready>) {
        if (forGeneration != generation || disposed) return
        when (event) {
            is LiveCaptureEvent.Ready -> {
                bufferedMs = event.bufferedMs
                ready.complete(event)
            }
            is LiveCaptureEvent.Buffered -> {
                bufferedMs = event.bufferedMs
                bufferFull = event.full
                val current = _state.value
                if (current is LivePauseState.Shifted && mechanism == LivePauseMechanism.DISK_BUFFER) {
                    _state.value = current.copy(
                        windowEndMs = bufferedMs,
                        behindLiveMs = (bufferedMs - current.positionMs).coerceAtLeast(0L),
                        bufferFull = bufferFull,
                    )
                }
            }
            is LiveCaptureEvent.Failed ->
                if (!ready.isCompleted) ready.completeExceptionally(CaptureFailure(event.reason)) else fail(event.reason)
        }
    }

    private fun fail(reason: LivePauseFailure) {
        // Whatever else this session's capture says is stale: a Ready queued behind this Failed must not resurrect
        // a Local surface on a dead capture.
        generation++
        val handle = captureHandle
        if (handle != null && _surface.value is LivePauseSurface.Local) {
            // The viewer is watching this buffer: stop recording but keep the files playable until Live / Retry /
            // leaving ends the session (endSession disposes the capture and deletes them).
            handle.stop()
        } else {
            effects.dispose()
            effects = newEffects()
            captureHandle = null
        }
        _state.value = LivePauseState.Failed(reason)
    }

    // --- ending a session ------------------------------------------------------------------------

    /** Back to live because the viewer is already there (nothing to retune). */
    private fun finishSession() {
        endSession()
        _surface.value = LivePauseSurface.Live
        _state.value = LivePauseState.Off
        applyLivePlan()
    }

    private fun retune() {
        endSession()
        _surface.value = LivePauseSurface.Live
        _state.value = LivePauseState.Off
        host.retune()
    }

    /** Cancels the start job and disposes the capture (which deletes its directory). */
    private fun endSession() {
        generation++
        startJob?.cancel()
        startJob = null
        captureHandle = null
        effects.dispose()
        effects = newEffects()
        mechanism = LivePauseMechanism.UNAVAILABLE
        wantPaused = false
        bufferedMs = 0L
        bufferFull = false
    }

    // --- mpv cache budget ------------------------------------------------------------------------

    private fun activeMechanism(): LivePauseMechanism {
        if (_state.value !is LivePauseState.Off && mechanism != LivePauseMechanism.UNAVAILABLE) return mechanism
        val c = channel ?: return LivePauseMechanism.UNAVAILABLE
        return LivePausePolicy.mechanism(
            c,
            LivePauseCapabilities(
                diskBuffer = caps.diskBuffer && capture != null,
                engineBackBuffer = caps.engineBackBuffer && engine != null,
            ),
        )
    }

    /** Raising the caches only when the device budget covers the normal plan too (spec: wave-2 caller rule). */
    private fun canRaise(): Boolean =
        config.engineMemoryBudgetBytes >= config.engineNormalPlan.maxBytes + config.engineNormalPlan.maxBackBytes &&
            config.engineMemoryBudgetBytes > 0L

    private fun applyLivePlan() {
        val e = engine ?: return
        if (!caps.engineBackBuffer || !canRaise() || activeMechanism() != LivePauseMechanism.ENGINE_BACK_BUFFER) return
        val plan = LivePausePolicy.engineLivePlan(config.engineMemoryBudgetBytes, config.engineNormalPlan, bitrateBps, config.keepMs)
        e.setLiveCacheBudget(plan.maxBytes, plan.maxBackBytes, plan.cacheSecs)
    }

    private fun applyPausedPlan() {
        val e = engine ?: return
        if (!canRaise()) return
        val plan = LivePausePolicy.enginePausedPlan(config.engineMemoryBudgetBytes, config.engineNormalPlan, bitrateBps, config.keepMs)
        e.setLiveCacheBudget(plan.maxBytes, plan.maxBackBytes, plan.cacheSecs)
    }

    private fun engineShifted(window: com.nuvio.app.features.player.LiveSeekWindow?) = LivePauseState.Shifted(
        paused = wantPaused,
        behindLiveMs = window?.behindLiveMs ?: 0L,
        windowStartMs = window?.startMs ?: 0L,
        windowEndMs = window?.endMs ?: 0L,
        positionMs = window?.positionMs ?: 0L,
        bufferFull = false,
    )

    private fun newEffects() = EffectScope(onRevertFailure)

    /** A capture failure carried through the start work, so [BoundedLoad] reports it as a failed (not empty) load. */
    private class CaptureFailure(val reason: LivePauseFailure) : RuntimeException("live pause capture failed: $reason")
}
