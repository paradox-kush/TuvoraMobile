package com.nuvio.app.features.livetv.pause

import com.nuvio.app.features.player.LiveSeekWindow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class FakeLiveClock(var mono: Long = 0L, var wall: Long = 1_000_000L) : LivePauseClock {
    override fun monotonicMs(): Long = mono
    override fun wallMs(): Long = wall
}

class FakeLiveEngine : LivePauseEnginePort {
    val calls = mutableListOf<String>()
    var window: LiveSeekWindow? = null
    val budgets = mutableListOf<LivePausePolicy.EngineCachePlan>()
    override fun play() { calls += "play" }
    override fun pause() { calls += "pause" }
    override fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
    override fun liveWindow(): LiveSeekWindow? = window
    override fun setLiveCacheBudget(maxBytes: Long, maxBackBytes: Long, cacheSecs: Long) {
        budgets += LivePausePolicy.EngineCachePlan(maxBytes, maxBackBytes, cacheSecs)
    }
}

class FakeLiveHost : LivePauseHost {
    var retunes = 0
    val catchUps = mutableListOf<Pair<Long, Long>>()
    override fun retune() { retunes++ }
    override fun openCatchUp(startMs: Long, endMs: Long) { catchUps += startMs to endMs }
}

class FakeCaptureHandle : LiveCaptureHandle {
    val channel = Channel<LiveCaptureEvent>(Channel.UNLIMITED)
    var disposed = 0
    var stopped = 0
    override val events: Flow<LiveCaptureEvent> = channel.receiveAsFlow()
    override fun stop() { stopped++ }
    override fun dispose() {
        disposed++
        channel.close()
    }
    fun emit(event: LiveCaptureEvent) { channel.trySend(event) }
}

class FakeCapturePort : LivePauseCapturePort {
    val requests = mutableListOf<LiveCaptureRequest>()
    val handles = mutableListOf<FakeCaptureHandle>()
    var throwOnStart: Throwable? = null
    override fun start(request: LiveCaptureRequest): LiveCaptureHandle {
        throwOnStart?.let { throw it }
        requests += request
        return FakeCaptureHandle().also { handles += it }
    }
}
