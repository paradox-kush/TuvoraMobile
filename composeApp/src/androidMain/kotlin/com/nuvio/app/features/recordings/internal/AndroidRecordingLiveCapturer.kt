package com.nuvio.app.features.recordings.internal

import com.nuvio.app.features.livetv.pause.LiveCaptureEnv
import com.nuvio.app.features.livetv.pause.LiveCaptureEvent
import com.nuvio.app.features.livetv.pause.LiveCaptureLoop
import com.nuvio.app.features.livetv.pause.LiveCaptureReconnect
import com.nuvio.app.features.livetv.pause.LiveCaptureRequest
import com.nuvio.app.features.livetv.pause.LiveLink
import com.nuvio.app.features.livetv.pause.LiveLinkMinter
import com.nuvio.app.features.livetv.pause.LivePauseFailure
import com.nuvio.app.features.livetv.pause.LivePausePolicy
import com.nuvio.app.features.livetv.pause.LiveRetention
import com.nuvio.app.features.recordings.api.LiveCaptureJob
import com.nuvio.app.features.recordings.api.LiveCaptureOutcome
import com.nuvio.app.features.recordings.api.LiveFootage
import com.nuvio.app.features.recordings.api.RecordingLinkResolver
import com.nuvio.app.features.recordings.api.RecordingLiveCapturer
import com.nuvio.app.features.recordings.api.RecordingProblem
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Records a live channel for [RecordingsRepository] by driving live-pause's shared [LiveCaptureLoop] with
 * [LiveRetention.KeepAll] (nothing is trimmed, the playlist is EVENT, `stop()` keeps the files). The loop is
 * live-pause's: this owns only what a recording adds on top of it.
 *
 *  - ONE directory per recording (`<job.dir>`), one sub-directory per ATTEMPT (`p0`, `p1`, ...): the loop wipes
 *    the directory it is given when it starts, so a retry cannot continue inside an attempt's directory. Each
 *    attempt ends by publishing `<job.dir>/index.m3u8`, one ended EVENT playlist over every attempt
 *    ([RecordingLivePlaylist]) with a discontinuity between attempts. Pieces are never deleted here.
 *  - The stop time is WALL clock; the time left becomes a delay, then `stop()` (ENDLIST, files kept).
 *    `dispose()` is never called: for KeepAll it also keeps the files (ENDLIST), so `stop()` is the one way a capture
 *    ends here; the directory is removed only by the user's Delete / Retry (`RecordingFiles.deleteTree`).
 *  - Cancellation stops the loop and publishes the footage under [NonCancellable] BEFORE rethrowing, so the
 *    repository can keep it (the user stopped it) or retry it (the system tore it down).
 *  - A single-use provider (Stalker) mints a link for every open through a SESSION-BOUND minter: it does not ask
 *    the provider once the attempt's session is over, and drops an answer that arrives after it ended. The mint
 *    budget ([LiveCaptureReconnect]) is kept per recording across attempts, so retries cannot buy fresh links.
 *  - The app's HTTP stack and per-playlist User-Agent come from [env], built the way live pause builds its own.
 */
internal class AndroidRecordingLiveCapturer(
    private val env: LiveCaptureEnv,
    private val resolver: RecordingLinkResolver,
    private val isOnline: () -> Boolean = { true },
    private val wallMs: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** After the stop time (or a cancel) how long to wait for the loop to wind down before giving up on it. */
    private val stopGraceMs: Long = STOP_GRACE_MS,
) : RecordingLiveCapturer {

    /** One attempt's life: closed when its slot is over, cancelled or failed. A mint checks it on both sides of the provider call. */
    private class Session {
        @Volatile var open = true
    }

    private val reconnects = ConcurrentHashMap<String, LiveCaptureReconnect>()

    override fun forget(recordingId: String) {
        reconnects.remove(recordingId)
    }

    override suspend fun capture(req: LiveCaptureJob, progress: (bytes: Long, durationMs: Long) -> Unit): LiveCaptureOutcome {
        val root = File(req.dir)
        val singleUse = req.channel.singleUse
        val reconnect = reconnects[req.recordingId]?.also { it.onRetry() }
            ?: LiveCaptureReconnect(singleUse).also { reconnects[req.recordingId] = it }
        if (!reconnect.canRetry) {
            // Every link this recording may mint is spent: another attempt would only ask the provider for more.
            return failureOutcome(RecordingLiveOutcomePolicy.Mapped(RecordingProblem.PROVIDER_REFUSED, false), io { settleBlocking(root) })
        }

        val before = io { footageOf(root) }
        val partDir = io { nextPartDir(root) }
        val session = Session()
        val minter = LiveLinkMinter {
            if (!session.open) throw CancellationException("the recording session is over")
            val link = resolver.resolve(req.channel, true) ?: throw IOException("the playlist is no longer on this device")
            if (!session.open) throw CancellationException("the recording session ended while the provider was answering")
            LiveLink(link.url, link.headers)
        }
        val first = if (singleUse) {
            LiveLink(req.channel.liveUrl, req.channel.headers)   // spent by definition: the loop mints instead
        } else {
            val resolved = try {
                resolver.resolve(req.channel, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (resolved != null) LiveLink(resolved.url, resolved.headers) else LiveLink(req.channel.liveUrl, req.channel.headers)
        }
        val request = LiveCaptureRequest(
            instanceId = "rec-" + req.recordingId.filter { it.isLetterOrDigit() },
            firstLink = first,
            singleUseLink = singleUse,
            minter = minter,
            keepMs = LivePausePolicy.DEFAULT_KEEP_MS,   // KeepAll has no window; this only sizes the start-of-capture disk check
            bitrateBps = req.bitrateBps,
            lowMemoryTier = false,
            reconnect = reconnect,
        )

        val handle = LiveCaptureLoop(env, request, partDir, LiveRetention.KeepAll).begin()
        var failure: LivePauseFailure? = null
        var stoppedAtSlotEnd = false
        try {
            coroutineScope {
                val collector = launch {
                    handle.events.collect { event ->
                        when (event) {
                            is LiveCaptureEvent.Ready -> report(partDir, before, event.bufferedMs, progress)
                            is LiveCaptureEvent.Buffered -> report(partDir, before, event.bufferedMs, progress)
                            is LiveCaptureEvent.Failed -> failure = event.reason
                        }
                    }
                }
                val timer = launch {
                    delay((req.stopAtMs - wallMs()).coerceAtLeast(0L))
                    stoppedAtSlotEnd = true
                    session.open = false
                    handle.stop()
                    // A provider call that ignores cancellation must not hold the slot open for ever.
                    delay(stopGraceMs)
                    collector.cancel()
                }
                collector.join()
                timer.cancel()
            }
        } catch (e: Throwable) {
            // Cancelled, or something broke: either way the loop stops and what was recorded is published first.
            withContext(NonCancellable) {
                session.open = false
                handle.stop()
                withTimeoutOrNull(stopGraceMs) { handle.events.collect { } }
                io { settleBlocking(root) }
            }
            throw e
        }
        session.open = false

        val footage = io { settleBlocking(root) }
        val reason = failure
        return when {
            reason != null -> failureOutcome(RecordingLiveOutcomePolicy.map(reason, isOnline()), footage)
            footage == null -> failureOutcome(RecordingLiveOutcomePolicy.map(LivePauseFailure.STALLED, isOnline()), null)
            // A provider that ended the stream long before the slot is over has not delivered the programme.
            !stoppedAtSlotEnd && RecordingLiveOutcomePolicy.endedEarly(wallMs(), req.stopAtMs) ->
                failureOutcome(RecordingLiveOutcomePolicy.map(LivePauseFailure.STALLED, isOnline()), footage)
            else -> LiveCaptureOutcome.Done(footage.bytes, footage.durationMs, File(root, RecordingLiveLayout.PLAYLIST).path)
        }
    }

    override suspend fun settle(dir: String): LiveFootage? = io { settleBlocking(File(dir)) }

    // ---- disk -------------------------------------------------------------------------------

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { block() }

    private fun failureOutcome(mapped: RecordingLiveOutcomePolicy.Mapped, footage: LiveFootage?): LiveCaptureOutcome.Failed =
        LiveCaptureOutcome.Failed(mapped.problem, mapped.offline, footage?.bytes ?: 0L, footage?.durationMs ?: 0L)

    private suspend fun report(partDir: File, before: LiveFootage?, bufferedMs: Long, progress: (Long, Long) -> Unit) {
        val bytes = io { pieceBytes(partDir) }
        progress((before?.bytes ?: 0L) + bytes, (before?.durationMs ?: 0L) + bufferedMs)
    }

    private fun pieceBytes(dir: File): Long =
        dir.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".tmp") && !it.name.endsWith(".m3u8") }.sumOf { it.length() }

    private fun partDirs(root: File): List<File> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { d -> PART_NAME.matchEntire(d.name)?.groupValues?.get(1)?.toIntOrNull()?.let { it to d } }
            .sortedBy { it.first }
            .map { it.second }

    private fun nextPartDir(root: File): File {
        root.mkdirs()
        val next = (partDirs(root).lastOrNull()?.name?.removePrefix("p")?.toIntOrNull() ?: -1) + 1
        return File(root, "p$next")
    }

    private fun readParts(root: File): List<RecordingLivePlaylist.Part> =
        partDirs(root).mapNotNull { d ->
            File(d, RecordingLiveLayout.PLAYLIST).takeIf { it.isFile }?.let { RecordingLivePlaylist.Part(d.name, it.readText()) }
        }

    /** What the attempts on disk hold, without touching anything. */
    private fun footageOf(root: File): LiveFootage? {
        val combined = RecordingLivePlaylist.combine(readParts(root)) ?: return null
        return LiveFootage(sizeOfFiles(root, combined.files), combined.durationMs)
    }

    private fun sizeOfFiles(root: File, files: List<String>): Long =
        files.sumOf { File(root, it).takeIf { f -> f.isFile }?.length() ?: 0L }

    /**
     * Publishes `<root>/index.m3u8` over every attempt that recorded a piece, removes attempts that recorded
     * nothing, and reports the footage. Idempotent; never deletes a piece.
     */
    private fun settleBlocking(root: File): LiveFootage? {
        if (!root.isDirectory) return null
        val parts = partDirs(root)
        val playable = readParts(root)
        parts.filter { d -> playable.none { it.dir == d.name } }.forEach { it.deleteRecursively() }
        val target = File(root, RecordingLiveLayout.PLAYLIST)
        val combined = RecordingLivePlaylist.combine(playable)
        if (combined == null) {
            target.delete()
            return null
        }
        val temp = File(root, "${RecordingLiveLayout.PLAYLIST}.tmp")
        temp.writeText(combined.text)
        // rename(2) replaces atomically on one filesystem: a player never reads half a playlist.
        if (!temp.renameTo(target)) throw IOException("cannot publish the recording playlist")
        return LiveFootage(sizeOfFiles(root, combined.files), combined.durationMs)
    }

    private companion object {
        const val STOP_GRACE_MS = 10_000L
        val PART_NAME = Regex("p(\\d+)")
    }
}
