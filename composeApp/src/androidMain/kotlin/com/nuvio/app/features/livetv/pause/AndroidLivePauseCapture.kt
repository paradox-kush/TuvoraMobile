package com.nuvio.app.features.livetv.pause

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android disk buffer for live pause: records the channel to `cacheDir/live-pause/<instanceId>/` and publishes a
 * local `index.m3u8` the ExoPlayer plays behind live. A thin adapter over [LiveCaptureLoop] with
 * [LiveRetention.Trim] (the rewind window slides); the loop holds the I/O, the reconnect rule and the space gate.
 *
 * Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0), LiveTvTimeshift.kt, with its defects fixed:
 *  - the app's OkHttp stack ([callFactoryFor]) instead of HttpURLConnection;
 *  - [CancellationException] is always rethrown, and an in-flight call is cancelled with its coroutine;
 *  - ONE capped reconnect rule ([LiveCaptureReconnect]) for TS and HLS: no uncapped refetch loop;
 *  - a single-use link is never re-opened: every open of one goes through the [LiveCaptureRequest.minter];
 *  - free space is checked before every write ([LivePausePolicy.beforeWrite]); the oldest pieces are trimmed first;
 *  - a master playlist picks a variant under a bitrate cap ([LivePausePolicy.pickVariant]), not the highest;
 *  - each HLS segment is fetched once (HlsLiveSegmentTracker) into `.part` and renamed; a provider that restarts
 *    its numbering is handled (more than a few resets fails visibly instead of freezing);
 *  - no activity for max(3 x target, 30 s) on the monotonic clock fails as STALLED.
 */
internal class AndroidLivePauseCapture(
    private val rootDir: File,
    callFactoryFor: (singleUse: Boolean, link: LiveLink) -> Call.Factory,
    headerPolicy: (Map<String, String>) -> Map<String, String> = { it },
    freeBytes: (File) -> Long = { it.usableSpace },
    monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    reconnectDelayMs: (Long) -> Long = { it },
    minPollMs: Long = 1_000L,
    targetPieceMs: Long = 6_000L,
    firstPieceMs: Long = 2_000L,
) : LivePauseCapturePort {
    private val env = LiveCaptureEnv(
        callFactoryFor, headerPolicy, freeBytes, monotonicMs, ioDispatcher,
        reconnectDelayMs, minPollMs, targetPieceMs, firstPieceMs,
    )

    override fun start(request: LiveCaptureRequest): LiveCaptureHandle {
        val dir = File(rootDir, request.instanceId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "player" })
        return Handle(LiveCaptureLoop(env, request, dir, LiveRetention.Trim(request.keepMs)).begin())
    }

    /** The pause capture handle: the loop itself, plus the cleanup job tests await. */
    class Handle internal constructor(private val loop: LiveCaptureLoop) : LiveCaptureHandle by loop {
        /** Completes when the directory is gone after [dispose] (tests await it). */
        val cleanup: Job? get() = loop.cleanup
    }
}
