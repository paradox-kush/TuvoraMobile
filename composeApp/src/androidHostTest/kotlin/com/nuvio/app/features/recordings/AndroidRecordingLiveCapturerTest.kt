package com.nuvio.app.features.recordings

import com.nuvio.app.features.livetv.pause.LiveCaptureEnv
import com.nuvio.app.features.livetv.pause.LiveCaptureEvent
import com.nuvio.app.features.livetv.pause.LiveCaptureHandle
import com.nuvio.app.features.livetv.pause.LiveCaptureReconnect
import com.nuvio.app.features.livetv.pause.Ts
import com.nuvio.app.features.recordings.api.LiveCaptureJob
import com.nuvio.app.features.recordings.api.LiveCaptureOutcome
import com.nuvio.app.features.recordings.api.RecordingChannel
import com.nuvio.app.features.recordings.api.RecordingLinkResolver
import com.nuvio.app.features.recordings.api.RecordingLiveLink
import com.nuvio.app.features.recordings.api.RecordingProblem
import com.nuvio.app.features.recordings.internal.AndroidRecordingLiveCapturer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/**
 * The Android live capturer against a real HTTP server: a TS stream and an HLS live playlist each record to a
 * wall-clock stop time, the playlist ends (ENDLIST) and every file stays; attempts join into one playable
 * playlist; a single-use channel mints within its budget through a session-bound minter.
 */
class AndroidRecordingLiveCapturerTest {
    private val server = MockWebServer()
    private val root: File = Files.createTempDirectory("rec-live-test").toFile()
    private val dir = File(root, "live-x")
    private val freeBytes = AtomicLong(50L * 1024 * 1024 * 1024)
    private val hits = CopyOnWriteArrayList<String>()
    private var online = true

    @AfterTest
    fun tearDown() {
        runCatching { server.shutdown() }
        root.deleteRecursively()
    }

    private fun env() = LiveCaptureEnv(
        callFactoryFor = { _, _ -> OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build() },
        freeBytes = { freeBytes.get() },
        reconnectDelayMs = { 5L },
        minPollMs = 20L,
        targetPieceMs = 2_000L,
        firstPieceMs = 2_000L,
    )

    private fun capturer(resolver: RecordingLinkResolver = RecordingLinkResolver { ch, _ -> RecordingLiveLink(ch.liveUrl, ch.headers) }) =
        AndroidRecordingLiveCapturer(env(), resolver, isOnline = { online }, spaceCheckMs = 50L)

    /** A writer whose stop() does nothing and whose event stream never ends: the wedged-cancel case. */
    private class WedgedHandle : LiveCaptureHandle {
        var stops = 0
        override val events: Flow<LiveCaptureEvent> = flow { awaitCancellation() }
        override fun stop() { stops++ }
        override fun dispose() {}
    }

    private fun wedgedCapturer(handle: WedgedHandle) = AndroidRecordingLiveCapturer(
        env(), { ch, _ -> RecordingLiveLink(ch.liveUrl, ch.headers) }, isOnline = { online }, spaceCheckMs = 50L,
        stopGraceMs = 300L, startLoop = { _, _, _ -> handle },
    )

    private fun url(path: String) = server.url(path).toString()

    private fun channel(path: String = "/live/1.ts", singleUse: Boolean = false) =
        RecordingChannel(key = "ch", sourceId = "src", name = "Ch", logo = null, liveUrl = url(path), headers = mapOf("User-Agent" to "tuvora-test"), singleUse = singleUse)

    private fun job(forMs: Long, ch: RecordingChannel = channel(), id: String = "rec:L") =
        LiveCaptureJob(recordingId = id, dir = dir.path, stopAtMs = System.currentTimeMillis() + forMs, channel = ch)

    private fun serve(handler: (String) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                hits += path
                return handler(path)
            }
        }
        server.start()
    }

    private fun tsBody(): ByteArray = Ts.concat(
        Ts.pat(), Ts.pmt(),
        *Array(41) { k -> Ts.media(pcrBase = k * 45_000L, rai = true, pusi = true, tag = 0x20 + k % 20) },
    )

    /** A TS stream that arrives at about real time, so a 2 s slot sees a few pieces instead of the whole body at once. */
    private fun slowTs() = MockResponse().setHeader("Content-Type", "video/mp2t")
        .throttleBody(600, 100, TimeUnit.MILLISECONDS).setBody(Buffer().write(tsBody()))

    private fun mediaPlaylist(firstSeq: Int, count: Int, extra: String = "") = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:$firstSeq\n$extra")
        for (i in firstSeq until firstSeq + count) append("#EXTINF:1.000,\n/seg$i.ts\n")
    }

    private fun segmentBytes(seq: Int) = Ts.concat(Ts.pat(), Ts.pmt(), Ts.media(pcrBase = seq * 90_000L, rai = true, pusi = true, tag = 0x30 + seq % 10))

    private fun playlist() = File(dir, "index.m3u8").readText()
    private fun pieceFiles() = dir.walkTopDown().filter { it.isFile && (it.extension == "ts" || it.extension == "m4s") }.toList()

    // ---- TS ----------------------------------------------------------------------------------

    @Test
    fun `a ts channel records to the stop time, ends the playlist and keeps every file`() = runBlocking {
        serve { slowTs() }
        val seen = CopyOnWriteArrayList<Pair<Long, Long>>()
        val started = System.currentTimeMillis()
        val outcome = capturer().capture(job(2_500)) { bytes, ms -> seen += bytes to ms }
        val took = System.currentTimeMillis() - started

        val done = assertNotNull(outcome as? LiveCaptureOutcome.Done, "$outcome")
        assertTrue(took in 2_000..9_000, "it stops at the stop time, not before and not never: $took ms")
        val text = playlist()
        assertTrue("#EXT-X-ENDLIST" in text, text)
        assertTrue("#EXT-X-PLAYLIST-TYPE:EVENT" in text)
        assertTrue("p0/" in text, "pieces are named through their attempt's directory: $text")
        val files = pieceFiles()
        assertTrue(files.isNotEmpty(), "the files remain after stop()")
        assertEquals(files.sumOf { it.length() }, done.bytes, "size = the sum of the pieces")
        assertTrue(done.durationMs > 0)
        assertEquals(File(dir, "index.m3u8").path, done.playlistFile)
        assertTrue(seen.isNotEmpty() && seen.last().first > 0, "progress reported bytes: $seen")
        assertTrue(seen.zipWithNext().all { (a, b) -> b.first >= a.first }, "bytes only grow")
    }

    @Test
    fun `the first link of a reusable channel comes from the resolver, not the stale snapshot`() = runBlocking {
        serve { path -> if (path == "/resolved.ts") slowTs() else MockResponse().setResponseCode(404) }
        val asked = CopyOnWriteArrayList<Boolean>()
        val c = capturer { ch, mint -> asked += mint; RecordingLiveLink(url("/resolved.ts"), ch.headers) }
        val outcome = c.capture(job(1_800, channel("/stale.ts"))) { _, _ -> }
        assertTrue(outcome is LiveCaptureOutcome.Done, "$outcome")
        assertEquals(listOf(false), asked, "one plain resolve, no mint for a reusable link")
        assertFalse("/stale.ts" in hits)
    }

    @Test
    fun `a provider that refuses is a refusal, or the network when the device is offline`() = runBlocking {
        serve { MockResponse().setResponseCode(403) }
        val refused = capturer().capture(job(20_000)) { _, _ -> }
        assertEquals(LiveCaptureOutcome.Failed(RecordingProblem.PROVIDER_REFUSED, offline = false, bytesKept = 0L), refused)
        online = false
        val offline = capturer().capture(job(20_000, id = "rec:M")) { _, _ -> }
        assertEquals(LiveCaptureOutcome.Failed(RecordingProblem.NETWORK, offline = true, bytesKept = 0L), offline)
    }

    @Test
    fun `a full disk keeps what was recorded and finishes the playlist`() = runBlocking {
        serve { slowTs() }
        val outcome = capturer().capture(job(20_000)) { bytes, _ -> if (bytes > 0) freeBytes.set(10L * 1024 * 1024) }
        val failed = assertNotNull(outcome as? LiveCaptureOutcome.Failed, "$outcome")
        assertEquals(RecordingProblem.NO_SPACE, failed.problem)
        assertTrue(failed.bytesKept > 0 && failed.durationKeptMs > 0, "$failed")
        assertTrue("#EXT-X-ENDLIST" in playlist())
        assertTrue(pieceFiles().isNotEmpty())
    }

    @Test
    fun `free space falling to the 500 MB floor mid-capture stops the capture and keeps the footage`() = runBlocking {
        serve { slowTs() }
        // 400 MB is above the live loop's own 300 MB reserve, so only the recording floor can end this capture.
        val outcome = capturer().capture(job(20_000)) { bytes, _ -> if (bytes > 0) freeBytes.set(400L * 1024 * 1024) }
        val failed = assertNotNull(outcome as? LiveCaptureOutcome.Failed, "$outcome")
        assertEquals(RecordingProblem.NO_SPACE, failed.problem)
        assertTrue(failed.bytesKept > 0 && failed.durationKeptMs > 0, "$failed")
        assertTrue("#EXT-X-ENDLIST" in playlist())
        assertTrue(pieceFiles().isNotEmpty())
    }

    @Test
    fun `cancelling publishes the footage, keeps the files and rethrows`() = runBlocking {
        serve { slowTs() }
        val c = capturer()
        val first = CompletableDeferred<Unit>()
        val run = async(start = CoroutineStart.UNDISPATCHED) {
            c.capture(job(60_000)) { bytes, _ -> if (bytes > 0) first.complete(Unit) }
        }
        withTimeout(10_000) { first.await() }
        run.cancel()
        assertFailsWith<CancellationException> { run.await() }
        assertTrue("#EXT-X-ENDLIST" in playlist(), "a player can reach the end of what was recorded")
        assertTrue(pieceFiles().isNotEmpty())
        val footage = assertNotNull(c.settle(dir.path))
        assertEquals(pieceFiles().sumOf { it.length() }, footage.bytes)
    }

    // ---- HLS ---------------------------------------------------------------------------------

    @Test
    fun `an hls live channel records to the stop time and ends the playlist`() = runBlocking {
        val polls = AtomicInteger()
        serve { path ->
            when {
                path == "/live.m3u8" -> MockResponse().setBody(mediaPlaylist(0, minOf(2 + polls.getAndIncrement(), 30)))
                path.startsWith("/seg") -> MockResponse().setBody(Buffer().write(segmentBytes(path.removePrefix("/seg").removeSuffix(".ts").toInt())))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val outcome = capturer().capture(job(2_600, channel("/live.m3u8"))) { _, _ -> }
        val done = assertNotNull(outcome as? LiveCaptureOutcome.Done, "$outcome")
        val text = playlist()
        assertTrue("#EXT-X-ENDLIST" in text, text)
        assertTrue("p0/seg0.ts" in text && "p0/seg1.ts" in text, text)
        assertTrue(File(dir, "p0/seg0.ts").length() > 0)
        assertEquals(1, hits.count { it == "/seg0.ts" }, "each segment is fetched once")
        assertTrue(done.durationMs >= 2_000, "${done.durationMs}")
        assertTrue(File(dir, "p0").listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `a provider that ends the stream long before the slot is an early end that keeps the footage`() = runBlocking {
        serve { path ->
            when {
                path == "/vod.m3u8" -> MockResponse().setBody(mediaPlaylist(0, 3) + "#EXT-X-ENDLIST\n")
                path.startsWith("/seg") -> MockResponse().setBody(Buffer().write(segmentBytes(path.removePrefix("/seg").removeSuffix(".ts").toInt())))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val outcome = capturer().capture(job(60_000, channel("/vod.m3u8"))) { _, _ -> }
        val failed = assertNotNull(outcome as? LiveCaptureOutcome.Failed, "$outcome")
        assertEquals(RecordingProblem.STALLED, failed.problem)
        assertTrue(failed.bytesKept > 0)
        assertTrue("#EXT-X-ENDLIST" in playlist())
    }

    // ---- attempts join -----------------------------------------------------------------------

    @Test
    fun `a second attempt records into a new part of the same directory and the playlist covers both`() = runBlocking {
        serve { slowTs() }
        val c = capturer()
        val one = c.capture(job(1_800)) { _, _ -> } as LiveCaptureOutcome.Done
        val two = c.capture(job(1_800)) { _, _ -> } as LiveCaptureOutcome.Done
        assertTrue(File(dir, "p0").isDirectory && File(dir, "p1").isDirectory)
        val text = playlist()
        assertTrue("p0/" in text && "p1/" in text, text)
        val lines = text.lines()
        val joinAt = lines.indexOfFirst { it.startsWith("p1/") } - 2
        assertEquals("#EXT-X-DISCONTINUITY", lines[joinAt], "the second attempt starts after a marked gap: $text")
        assertTrue(lines.indexOf("p0/seg0.ts") < joinAt, "and the first attempt comes first")
        assertEquals(1, text.lines().count { it == "#EXT-X-ENDLIST" })
        assertTrue(two.bytes > one.bytes, "the second outcome counts the first attempt's footage: ${one.bytes} -> ${two.bytes}")
        assertTrue(two.durationMs > one.durationMs)
        assertTrue(File(dir, "p0").listFiles().orEmpty().isNotEmpty(), "the first attempt's pieces are still there")
    }

    @Test
    fun `settle rebuilds one playlist from the parts on disk and drops parts that recorded nothing`() = runBlocking {
        fun part(name: String, vararg pieces: String) {
            val d = File(dir, name).also { it.mkdirs() }
            pieces.forEach { File(d, it).writeBytes(ByteArray(1_000)) }
            if (pieces.isNotEmpty()) {
                File(d, "index.m3u8").writeText(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n" + pieces.joinToString("") { "#EXTINF:6.000,\n$it\n" },
                )
            }
        }
        part("p0", "seg0.ts", "seg1.ts")
        part("p1")   // an attempt that never got a piece
        part("p2", "seg0.ts")
        val footage = assertNotNull(capturer().settle(dir.path))
        assertEquals(3_000L, footage.bytes)
        assertEquals(18_000L, footage.durationMs)
        val text = playlist()
        assertTrue("p0/seg1.ts" in text && "p2/seg0.ts" in text && "p1/" !in text, text)
        assertTrue("#EXT-X-ENDLIST" in text)
        assertFalse(File(dir, "p1").exists(), "an empty attempt is cleaned up")
    }

    @Test
    fun `a writer that ignores stop after the disk fills does not hold the slot open`() = runBlocking {
        val earlier = File(dir, "p0").also { it.mkdirs() }
        File(earlier, "seg0.ts").writeBytes(ByteArray(2_000))
        File(earlier, "index.m3u8").writeText("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:6.000,\nseg0.ts\n")
        freeBytes.set(1_000L)   // under the recording floor from the first check
        val handle = WedgedHandle()
        val started = System.currentTimeMillis()
        val outcome = withTimeout(5_000) { wedgedCapturer(handle).capture(job(60_000)) { _, _ -> } }
        val took = System.currentTimeMillis() - started
        assertTrue(took < 3_000, "it gave up on the writer after the grace, not at the slot end: $took ms")
        assertTrue(handle.stops >= 1)
        val failed = assertNotNull(outcome as? LiveCaptureOutcome.Failed, "$outcome")
        assertEquals(RecordingProblem.NO_SPACE, failed.problem)
        assertEquals(2_000L, failed.bytesKept, "the footage is settled so the row can finish as DONE")
        assertTrue("#EXT-X-ENDLIST" in playlist())
    }

    @Test
    fun `a writer that ignores stop at the slot end with nothing recorded fails promptly`() = runBlocking {
        val handle = WedgedHandle()
        val started = System.currentTimeMillis()
        val outcome = withTimeout(5_000) { wedgedCapturer(handle).capture(job(100)) { _, _ -> } }
        assertTrue(System.currentTimeMillis() - started < 3_000)
        val failed = assertNotNull(outcome as? LiveCaptureOutcome.Failed, "$outcome")
        assertEquals(0L, failed.bytesKept)
    }

    @Test
    fun `settle keeps the pieces of an attempt that died before its first playlist and makes them playable`() = runBlocking {
        val d = File(dir, "p0").also { it.mkdirs() }
        File(d, "seg0.ts").writeBytes(ByteArray(1_000))
        File(d, "seg1.ts").writeBytes(ByteArray(1_500))
        File(d, "seg2.ts.part").writeBytes(ByteArray(10))
        val footage = assertNotNull(capturer().settle(dir.path), "the pieces were recorded, they are footage")
        assertEquals(2_500L, footage.bytes)
        assertTrue(File(d, "seg0.ts").isFile && File(d, "seg1.ts").isFile, "pieces are never deleted")
        val text = playlist()
        assertTrue(text.indexOf("p0/seg0.ts") in 0 until text.indexOf("p0/seg1.ts"), text)
        assertTrue("#EXT-X-ENDLIST" in text, text)
        assertEquals(2, footage.durationMs / 2_000L, "approximate durations: the target piece length per piece")
    }

    @Test
    fun `settle orders rebuilt pieces by sequence not by name`() = runBlocking {
        val d = File(dir, "p0").also { it.mkdirs() }
        listOf("seg10.ts", "seg2.ts", "seg9.ts").forEach { File(d, it).writeBytes(ByteArray(100)) }
        capturer().settle(dir.path)
        val text = playlist()
        assertTrue(text.indexOf("seg2.ts") < text.indexOf("seg9.ts") && text.indexOf("seg9.ts") < text.indexOf("seg10.ts"), text)
    }

    @Test
    fun `settle removes an attempt that holds only unfinished writes`() = runBlocking {
        val d = File(dir, "p0").also { it.mkdirs() }
        File(d, "seg0.ts.part").writeBytes(ByteArray(10))
        File(d, "index.m3u8.tmp").writeText("x")
        assertNull(capturer().settle(dir.path))
        assertFalse(d.exists())
    }

    @Test
    fun `settle on an empty or missing directory is null`() = runBlocking {
        assertNull(capturer().settle(dir.path))
        dir.mkdirs()
        assertNull(capturer().settle(dir.path))
        assertFalse(File(dir, "index.m3u8").exists())
    }

    // ---- single-use links --------------------------------------------------------------------

    @Test
    fun `a single-use channel mints for the first open and never opens the snapshot link`() = runBlocking {
        serve { path -> if (path == "/minted.ts") slowTs() else MockResponse().setResponseCode(404) }
        val asked = CopyOnWriteArrayList<Boolean>()
        val c = capturer { ch, mint -> asked += mint; RecordingLiveLink(url("/minted.ts"), ch.headers) }
        val outcome = c.capture(job(1_800, channel("/spent.ts", singleUse = true))) { _, _ -> }
        assertTrue(outcome is LiveCaptureOutcome.Done, "$outcome")
        assertFalse("/spent.ts" in hits, "a spent link is never opened")
        assertEquals(true, asked.first())
        assertTrue(asked.all { it }, "every open of a single-use link mints: $asked")
    }

    @Test
    fun `the mint budget is spent per recording across attempts, not per attempt`() = runBlocking {
        serve { MockResponse().setResponseCode(403) }
        val mints = AtomicInteger()
        val c = capturer { ch, _ -> mints.incrementAndGet(); RecordingLiveLink(url("/x.ts"), ch.headers) }
        val ch = channel(singleUse = true)
        val first = c.capture(job(30_000, ch)) { _, _ -> }
        assertTrue(first is LiveCaptureOutcome.Failed)
        assertTrue(mints.get() in 1..LiveCaptureReconnect.MAX_MINTS, "${mints.get()}")
        val spent = mints.get()
        c.capture(job(30_000, ch)) { _, _ -> }
        assertTrue(mints.get() - spent < LiveCaptureReconnect.MAX_MINTS, "a retry cannot buy a fresh budget: $spent -> ${mints.get()}")
        // another recording has its own budget
        c.capture(job(30_000, ch, id = "rec:OTHER")) { _, _ -> }
        assertTrue(mints.get() > spent)
    }

    @Test
    fun `a mint still in flight when the session ends is dropped and its link never opened`() = runBlocking {
        serve { slowTs() }
        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val c = capturer { ch, _ ->
            inFlight.complete(Unit)
            withContext(NonCancellable) { release.await() }   // a provider call that ignores cancellation
            RecordingLiveLink(url("/late.ts"), ch.headers)
        }
        val run = async { c.capture(job(600, channel(singleUse = true))) { _, _ -> } }
        withTimeout(5_000) { inFlight.await() }
        delay(900)   // the slot ends while the provider is still thinking
        release.complete(Unit)
        val outcome = withTimeout(10_000) { run.await() }
        assertTrue(outcome is LiveCaptureOutcome.Failed, "$outcome")
        assertEquals(0, server.requestCount, "the stale answer was dropped, not opened")
        assertEquals(0L, (outcome as LiveCaptureOutcome.Failed).bytesKept)
    }
}
