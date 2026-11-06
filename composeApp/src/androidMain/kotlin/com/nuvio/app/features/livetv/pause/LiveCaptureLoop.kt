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

/** The platform seams one capture loop runs on: network, disk space, clocks, dispatcher. */
internal class LiveCaptureEnv(
    /** The call factory for a link; a single-use link must get one that never silently re-sends a request. */
    val callFactoryFor: (singleUse: Boolean, link: LiveLink) -> Call.Factory,
    /** Adds the default User-Agent and drops what must not be sent (the app's playback header policy). */
    val headerPolicy: (Map<String, String>) -> Map<String, String> = { it },
    val freeBytes: (File) -> Long = { it.usableSpace },
    val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Test seam: shortens the reconnect delays (production: identity). */
    val reconnectDelayMs: (Long) -> Long = { it },
    val minPollMs: Long = 1_000L,
    val targetPieceMs: Long = 6_000L,
    val firstPieceMs: Long = 2_000L,
) {
    /** Deletes directories after a [LiveCaptureHandle.dispose] once the writer has stopped. */
    val housekeeping = CoroutineScope(SupervisorJob() + ioDispatcher)
}

/**
 * One live capture to disk: TS read or HLS poll with each segment fetched once, the single capped reconnect rule
 * ([LiveCaptureReconnect], mints through its budget), the stall timer and the free-space gate, writing a local
 * `index.m3u8` into [dir]. Confined to ONE coroutine; callers see immutable [LiveCaptureEvent]s.
 *
 * What differs between users is only [retention]: live pause trims the front ([LiveRetention.Trim], rewind
 * playlist); a recording keeps everything ([LiveRetention.KeepAll], EVENT playlist) and ends as Failed(NO_SPACE)
 * with its files kept when the disk has no room. [stop] ends the playlist with ENDLIST and keeps the files;
 * [dispose] cancels and deletes them.
 *
 * Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0), LiveTvTimeshift.kt, with its defects fixed (see
 * [AndroidLivePauseCapture] for the list).
 */
internal class LiveCaptureLoop(
    private val env: LiveCaptureEnv,
    private val request: LiveCaptureRequest,
    private val dir: File,
    private val retention: LiveRetention,
) : LiveCaptureHandle {
    private val callFactoryFor get() = env.callFactoryFor
    private val headerPolicy get() = env.headerPolicy
    private val freeBytes get() = env.freeBytes
    private val monotonicMs get() = env.monotonicMs
    private val reconnectDelayMs get() = env.reconnectDelayMs
    private val minPollMs get() = env.minPollMs
    private val targetPieceMs get() = env.targetPieceMs
    private val firstPieceMs get() = env.firstPieceMs

    private val channel = Channel<LiveCaptureEvent>(Channel.UNLIMITED)
    override val events: Flow<LiveCaptureEvent> = channel.receiveAsFlow()
    private val scope = CoroutineScope(SupervisorJob() + env.ioDispatcher)
    private var job: Job? = null
    private val disposed = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    /** Completes when the directory is gone after [dispose] (tests await it). */
    @Volatile var cleanup: Job? = null
        private set

    fun begin(): LiveCaptureLoop {
        val run = Run(channel)
        job = scope.launch {
            try {
                run.run()
            } catch (e: CancellationException) {
                if (stopping.get()) run.finishKeepingBuffer()
                throw e
            } catch (t: Throwable) {
                channel.trySend(LiveCaptureEvent.Failed(LivePauseFailure.PROVIDER_REFUSED))
            } finally {
                channel.close()
            }
        }
        return this
    }

    /** Stops recording, KEEPS the directory (finished as an ended playlist) for a player still watching it. */
    override fun stop() {
        if (disposed.get() || !stopping.compareAndSet(false, true)) return
        job?.cancel()
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        val running = job
        running?.cancel()
        // Delete only after the writer has stopped, or a late write would recreate the directory's contents.
        cleanup = env.housekeeping.launch {
            running?.join()
            dir.deleteRecursively()
        }
        channel.close()
        scope.coroutineContext.job.cancel()
    }

    // One capture's whole state, confined to the coroutine that runs it.
    private inner class Run(
        private val out: Channel<LiveCaptureEvent>,
    ) {
        private val store = FileLiveSegmentStore(dir)
        private val reconnect = request.reconnect   // shared with earlier captures of this channel (Retry)
        private var keepMs = (retention as? LiveRetention.Trim)?.keepMs ?: LivePausePolicy.DEFAULT_KEEP_MS
        private var budgetBytes = 0L
        private val playlistUrl = "file://" + File(dir, FileLiveSegmentStore.PLAYLIST_NAME).absolutePath

        private var announcedReady = false
        private var bufferedBytes = 0L
        private var trimmedAny = false
        private var lastProgressAt = 0L
        private var lastRefusal = false

        // TS state.
        private var segmenter: TsSegmenter? = null

        // HLS state (the local playlist is ours, so local sequence numbers never reset).
        private val tracker = HlsLiveSegmentTracker()
        private val hlsPieces = ArrayList<LivePiece>()
        private var localSeq = 0L
        private var nextDiscontinuity = false
        private var initName: String? = null
        private var hlsTargetSec = 0

        suspend fun run() {
            dir.deleteRecursively()
            dir.mkdirs()
            when (val decision = LivePausePolicy.diskStart(freeBytes(dir), request.bitrateBps, keepMs)) {
                is LivePausePolicy.StartDecision.Refuse -> return fail(LivePauseFailure.NO_SPACE)
                is LivePausePolicy.StartDecision.Start -> {
                    keepMs = decision.keepMs
                    // A recording has no byte budget: only the disk's own reserve ends it.
                    budgetBytes = if (retention.keepsEverything) Long.MAX_VALUE else decision.budgetBytes
                }
            }
            var link = request.firstLink
            var mint = reconnect.mintOnFirstOpen
            while (true) {
                try {
                    if (mint) {
                        if (!reconnect.tryMint()) return fail(LivePauseFailure.PROVIDER_REFUSED)   // the run's links are spent
                        link = request.minter.mint()
                        mint = false
                    }
                    lastProgressAt = monotonicMs()
                    if (isHls(link.url)) runHls(link) else runTs(link)
                    return   // ended for good (an HLS ENDLIST); nothing to reconnect
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Fatal) {
                    return fail(e.reason)
                } catch (e: LiveStoreException) {
                    return fail(LivePauseFailure.NO_SPACE)   // the disk refused a write: end visibly, never a playlist to nowhere
                } catch (e: Refused) {
                    lastRefusal = true
                } catch (e: Exception) {
                    lastRefusal = false
                }
                segmenter?.reconnected()
                when (val d = reconnect.onDrop()) {
                    LiveCaptureReconnect.Decision.GiveUp ->
                        return fail(if (lastRefusal) LivePauseFailure.PROVIDER_REFUSED else LivePauseFailure.STALLED)
                    is LiveCaptureReconnect.Decision.Retry -> {
                        delay(reconnectDelayMs(d.delayMs))
                        mint = d.mintFreshLink
                    }
                }
            }
        }

        private fun fail(reason: LivePauseFailure) {
            finishKeepingBuffer()   // a viewer may still be watching what was recorded: it must end, not stall
            out.trySend(LiveCaptureEvent.Failed(reason))
        }

        /** Marks what is on disk as finished (ENDLIST) so a player playing it reaches the end instead of stalling. */
        fun finishKeepingBuffer() {
            if (!announcedReady) return
            try {
                segmenter?.finish(ended = true)
                if (hlsPieces.isNotEmpty()) publishHlsPlaylist(ended = true)
            } catch (e: LiveStoreException) {
                // The disk refused (that may be why we are here): the playlist stays as it was.
            }
        }

        private fun isHls(url: String): Boolean {
            val path = url.substringBefore('?').lowercase()
            return path.endsWith(".m3u8") || path.contains("m3u8")
        }

        private fun stallLimitMs(): Long = maxOf(3 * maxOf(targetPieceMs, tracker.targetDurationMs), STALL_FLOOR_MS)

        private fun checkStall() {
            if (monotonicMs() - lastProgressAt > stallLimitMs()) throw Fatal(LivePauseFailure.STALLED)
        }

        private fun progressed() {
            lastProgressAt = monotonicMs()
            reconnect.onProgress()
        }

        private fun announce(bufferedMs: Long) {
            if (!announcedReady) {
                announcedReady = true
                out.trySend(LiveCaptureEvent.Ready(playlistUrl, bufferedMs))
            } else {
                out.trySend(LiveCaptureEvent.Buffered(bufferedMs, trimmedAny))
            }
        }

        // --- network ------------------------------------------------------------------------

        private fun requestFor(link: LiveLink, url: String): Request {
            val builder = Request.Builder().url(url)
            headerPolicy(link.headers).forEach { (k, v) -> builder.header(k, v) }
            return builder.build()
        }

        /**
         * Executes [call] and runs [block] on the response. The call is cancelled the moment the coroutine is: a
         * sibling coroutine waits for cancellation and cancels the call, because the caller itself is blocked in
         * the socket read and could not.
         */
        private suspend fun <T> execute(call: Call, block: suspend (Response) -> T): T = coroutineScope {
            val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                val response = try {
                    call.execute()
                } catch (e: IOException) {
                    ensureActive()   // cancelled: surface the cancellation, not the socket error it caused
                    throw e
                }
                response.use { block(it) }
            } catch (e: IOException) {
                ensureActive()
                throw e
            } finally {
                canceller.cancel()
            }
        }

        private fun checkStatus(response: Response) {
            if (!response.isSuccessful) {
                val code = response.code
                if (code == 401 || code == 403 || code == 404 || code == 410 || code == 456) throw Refused("HTTP $code")
                throw IOException("HTTP $code")
            }
        }

        // --- space ----------------------------------------------------------------------------

        /** Makes room for [nextBytes] or ends the capture. [trim] drops the oldest piece; it returns false when none is left. */
        private fun gate(nextBytes: Long, trim: () -> Boolean) {
            var guard = 0
            while (true) {
                when (LivePausePolicy.beforeWrite(freeBytes(dir), nextBytes, bufferedBytes, budgetBytes)) {
                    LivePausePolicy.SpaceVerdict.WRITE -> return
                    LivePausePolicy.SpaceVerdict.TRIM_OLDEST ->
                        if (!retention.mayTrim || ++guard > MAX_TRIMS_PER_WRITE || !trim()) throw Fatal(LivePauseFailure.NO_SPACE)
                    LivePausePolicy.SpaceVerdict.STOP_NO_SPACE -> throw Fatal(LivePauseFailure.NO_SPACE)
                }
            }
        }

        // --- TS -----------------------------------------------------------------------------

        private fun newSegmenter(): TsSegmenter = TsSegmenter(
            store = store,
            monotonicMs = monotonicMs,
            targetMs = targetPieceMs,
            firstTargetMs = firstPieceMs,
            keepAll = retention.keepsEverything,
            onPiece = ::onTsPiece,
        )

        private fun onTsPiece(piece: LivePiece) {
            val seg = segmenter ?: return
            bufferedBytes += piece.bytes
            progressed()
            val before = seg.state().pieces
            val drop = retention.piecesToDrop(before.map { it.durationMs }, keepMs)
            if (drop > 0) {
                bufferedBytes -= before.take(drop).sumOf { it.bytes }
                seg.dropOldest(drop)
                trimmedAny = true
            }
            announce(seg.state().totalMs)
        }

        private suspend fun runTs(link: LiveLink) {
            val seg = segmenter ?: newSegmenter().also { segmenter = it }
            val call = callFactoryFor(request.singleUseLink, link).newCall(requestFor(link, link.url))
            execute(call) { response ->
                checkStatus(response)
                val body = response.body ?: throw IOException("empty body")
                val input = body.byteStream()
                val buf = ByteArray(READ_CHUNK_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = try {
                        input.read(buf)
                    } catch (e: IOException) {
                        currentCoroutineContext().ensureActive()
                        throw e
                    }
                    if (n < 0) throw IOException("the provider ended the stream")
                    gate(n.toLong()) {
                        val pieces = seg.state().pieces
                        if (pieces.size <= 1) {
                            false
                        } else {
                            bufferedBytes -= pieces.first().bytes
                            seg.dropOldest(1)
                            trimmedAny = true
                            true
                        }
                    }
                    seg.write(buf, 0, n)
                    checkStall()
                }
            }
        }

        // --- HLS ----------------------------------------------------------------------------

        private suspend fun fetchText(link: LiveLink, url: String): Pair<String, String> {
            val call = callFactoryFor(request.singleUseLink, link).newCall(requestFor(link, url))
            return execute(call) { response ->
                checkStatus(response)
                response.body!!.string() to response.request.url.toString()
            }
        }

        private suspend fun runHls(link: LiveLink) {
            var (text, mediaUrl) = fetchText(link, link.url)
            val variants = parseHlsMaster(text, mediaUrl)
            if (variants.isNotEmpty()) {
                val pick = LivePausePolicy.pickVariant(variants, LivePausePolicy.maxCaptureBitrateBps(request.lowMemoryTier))
                    ?: throw Fatal(LivePauseFailure.UNSUPPORTED_STREAM)
                val fetched = fetchText(link, pick.uri)
                text = fetched.first
                mediaUrl = fetched.second
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                when (val update = tracker.onPlaylist(text, mediaUrl)) {
                    is HlsLiveSegmentTracker.Update.Unsupported -> throw Fatal(LivePauseFailure.UNSUPPORTED_STREAM)
                    is HlsLiveSegmentTracker.Update.Unchanged -> Unit
                    is HlsLiveSegmentTracker.Update.NewSegments -> saveSegments(link, update.segments)
                    is HlsLiveSegmentTracker.Update.SequenceReset -> {
                        if (tracker.resetCount > MAX_RESETS) throw Fatal(LivePauseFailure.SEQUENCE_RESET)
                        saveSegments(link, update.segments)
                    }
                }
                if (tracker.ended) {
                    publishHlsPlaylist(ended = true)
                    return
                }
                checkStall()
                delay((tracker.targetDurationMs / 2).coerceIn(minPollMs, MAX_POLL_MS))
                val fetched = fetchText(link, mediaUrl)
                text = fetched.first
                mediaUrl = fetched.second
            }
        }

        private suspend fun saveSegments(link: LiveLink, segments: List<HlsLiveSegmentTracker.MediaSegment>) {
            tracker.initSegmentUri?.let { uri -> if (initName == null) initName = downloadInit(link, uri) }
            for (segment in segments) {
                val name = "seg$localSeq." + if (initName != null) "m4s" else "ts"
                val bytes = try {
                    downloadSegment(link, segment, name)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Fatal) {
                    throw e
                } catch (e: LiveStoreException) {
                    throw e
                } catch (e: Exception) {
                    // The segment is lost (it is never fetched twice); the gap is marked for the player and the
                    // session drops, which the capped reconnect rule handles.
                    nextDiscontinuity = true
                    throw e
                }
                val piece = LivePiece(localSeq, name, segment.durationMs, bytes, segment.discontinuity || nextDiscontinuity)
                nextDiscontinuity = false
                localSeq++
                hlsPieces += piece
                bufferedBytes += bytes
                hlsTargetSec = maxOf(hlsTargetSec, MIN_TARGET_SEC, ((segment.durationMs + 999) / 1000).toInt(), ((tracker.targetDurationMs + 999) / 1000).toInt())
                val drop = retention.piecesToDrop(hlsPieces.map { it.durationMs }, keepMs)
                val dropped = if (drop > 0) hlsPieces.take(drop) else emptyList()
                if (dropped.isNotEmpty()) {
                    repeat(dropped.size) { hlsPieces.removeAt(0) }
                    bufferedBytes -= dropped.sumOf { it.bytes }
                    trimmedAny = true
                }
                publishHlsPlaylist(ended = false)
                dropped.forEach { store.delete(it.name) }   // after the playlist no longer lists them
                progressed()
                announce(hlsPieces.sumOf { it.durationMs })
            }
        }

        private fun publishHlsPlaylist(ended: Boolean) {
            if (hlsPieces.isEmpty()) return
            store.writePlaylistAtomically(
                LocalHlsPlaylist.render(
                    pieces = hlsPieces,
                    targetDurationSec = hlsTargetSec.coerceAtLeast(MIN_TARGET_SEC),
                    mediaSequence = hlsPieces.first().sequence,
                    ended = ended,
                    eventType = retention.keepsEverything,
                    initSegment = initName,
                ),
            )
        }

        private suspend fun downloadInit(link: LiveLink, uri: String): String {
            val name = "init.mp4"
            downloadTo(link, uri, name, expectedBytes = INIT_ESTIMATE_BYTES)
            return name
        }

        private suspend fun downloadSegment(link: LiveLink, segment: HlsLiveSegmentTracker.MediaSegment, name: String): Long =
            downloadTo(link, segment.uri, name, expectedBytes = LivePausePolicy.bytesFor(segment.durationMs, request.bitrateBps))

        /** One GET into `<name>.part`, renamed when complete. Free space is checked before the write and per chunk. */
        private suspend fun downloadTo(link: LiveLink, uri: String, name: String, expectedBytes: Long): Long {
            gate(expectedBytes) {
                val oldest = hlsPieces.firstOrNull()
                if (hlsPieces.size <= 1 || oldest == null) {
                    false
                } else {
                    hlsPieces.removeAt(0)
                    bufferedBytes -= oldest.bytes
                    trimmedAny = true
                    publishHlsPlaylist(ended = false)
                    store.delete(oldest.name)
                    true
                }
            }
            val part = File(dir, "$name.part")
            val call = callFactoryFor(request.singleUseLink, link).newCall(requestFor(link, uri))
            try {
                val written = execute(call) { response ->
                    checkStatus(response)
                    val input = response.body!!.byteStream()
                    val buf = ByteArray(READ_CHUNK_BYTES)
                    var total = 0L
                    val sink = try {
                        java.io.FileOutputStream(part)
                    } catch (e: IOException) {
                        throw LiveStoreException("cannot open $name", e)
                    }
                    sink.use {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)   // a network error propagates as a drop
                            if (n < 0) break
                            if (freeBytes(dir) - n < LivePausePolicy.FREE_SPACE_RESERVE_BYTES) throw Fatal(LivePauseFailure.NO_SPACE)
                            try {
                                sink.write(buf, 0, n)
                            } catch (e: IOException) {
                                throw LiveStoreException("cannot write $name", e)
                            }
                            total += n
                        }
                    }
                    total
                }
                if (!part.renameTo(File(dir, name))) throw LiveStoreException("cannot publish $name")
                return written
            } finally {
                part.delete()   // a no-op after a successful rename; removes the remains of a failed or cancelled download
            }
        }
    }

    private class Fatal(val reason: LivePauseFailure) : RuntimeException(reason.name)
    private class Refused(message: String) : RuntimeException(message)

    private companion object {
        const val READ_CHUNK_BYTES = 64 * 1024
        const val STALL_FLOOR_MS = 30_000L
        const val MAX_RESETS = 3
        const val MAX_POLL_MS = 6_000L
        const val MIN_TARGET_SEC = 2
        const val MAX_TRIMS_PER_WRITE = 64
        const val INIT_ESTIMATE_BYTES = 64L * 1024
    }
}
