package com.nuvio.app.features.livetv.pause

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [LiveCaptureLoop] with [LiveRetention.KeepAll] (what a recording runs on): nothing is ever dropped, the playlist
 * is EVENT, [LiveCaptureLoop.stop] finishes it and keeps the files, and a full disk ends it as NO_SPACE with
 * everything recorded so far still on disk. The pause adapter's own behaviour is pinned by AndroidLivePauseCaptureTest.
 */
class LiveCaptureLoopTest {
    private val server = MockWebServer()
    private val root: File = Files.createTempDirectory("live-capture-loop-test").toFile()
    private val dir = File(root, "rec")
    private val freeBytes = AtomicLong(50L * 1024 * 1024 * 1024)
    private val loops = mutableListOf<LiveCaptureLoop>()

    @AfterTest
    fun tearDown() {
        loops.forEach { it.dispose() }
        runCatching { server.shutdown() }
        root.deleteRecursively()
    }

    private fun tsBody(halfSeconds: Int = 39): ByteArray = Ts.concat(
        Ts.pat(), Ts.pmt(),
        *Array(halfSeconds + 1) { k -> Ts.media(pcrBase = k * 45_000L, rai = true, pusi = true, tag = 0x20 + k % 20) },
    )

    /** Set to make the call factory throw a non-Exception Throwable on its next open (the loop's own catch-all). */
    private val explodeOnNextOpen = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun start(retention: LiveRetention, keepMs: Long = 6_000L): Pair<LiveCaptureLoop, CopyOnWriteArrayList<LiveCaptureEvent>> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(tsBody()))
        }
        server.start()
        val env = LiveCaptureEnv(
            callFactoryFor = { _, _ ->
                if (explodeOnNextOpen.get()) throw LinkageError("unexpected failure inside the capture")
                OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
            },
            freeBytes = { freeBytes.get() },
            reconnectDelayMs = { 5L },
            minPollMs = 20L,
            targetPieceMs = 2_000L,   // short pieces: a 6 s window would hold about three of them
            firstPieceMs = 2_000L,
        )
        val request = LiveCaptureRequest(
            instanceId = "rec",
            firstLink = LiveLink(server.url("/live/1.ts").toString()),
            singleUseLink = false,
            minter = LiveLinkMinter { error("must not mint") },
            keepMs = keepMs,
            bitrateBps = null,
            lowMemoryTier = false,
        )
        val loop = LiveCaptureLoop(env, request, dir, retention).begin().also { loops += it }
        val seen = CopyOnWriteArrayList<LiveCaptureEvent>()
        CoroutineScope(Dispatchers.Default).launch { loop.events.collect { seen += it } }
        return loop to seen
    }

    private suspend fun CopyOnWriteArrayList<LiveCaptureEvent>.await(timeoutMs: Long = 15_000, predicate: (LiveCaptureEvent) -> Boolean): LiveCaptureEvent =
        withTimeout(timeoutMs) {
            var found: LiveCaptureEvent? = null
            while (found == null) {
                found = firstOrNull(predicate)
                if (found == null) delay(20)
            }
            found
        }

    private fun playlist(): String = File(dir, "index.m3u8").readText()
    private fun pieces(): List<String> = dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".ts") }.sorted()

    @Test
    fun `keep all keeps every piece past the keep window and writes an event playlist`() = runBlocking {
        val (_, seen) = start(LiveRetention.KeepAll)
        seen.await { it is LiveCaptureEvent.Buffered && it.bufferedMs >= 16_000 }
        val text = playlist()
        assertTrue("#EXT-X-PLAYLIST-TYPE:EVENT" in text, "a recording grows only, so it is an EVENT playlist")
        assertTrue("#EXT-X-MEDIA-SEQUENCE:0" in text, "the front is never trimmed")
        assertTrue(pieces().size >= 8, "every piece stays on disk: ${pieces()}")
        assertTrue(seen.none { it is LiveCaptureEvent.Buffered && it.full }, "a recording never reports a full window")
    }

    @Test
    fun `keep all stop leaves the files and ends the playlist`() = runBlocking {
        val (loop, seen) = start(LiveRetention.KeepAll)
        seen.await { it is LiveCaptureEvent.Buffered && it.bufferedMs >= 8_000 }
        loop.stop()
        withTimeout(5_000) { while ("#EXT-X-ENDLIST" !in playlist()) delay(20) }
        assertTrue("#EXT-X-PLAYLIST-TYPE:EVENT" in playlist())
        assertTrue(pieces().isNotEmpty(), "stop keeps what was recorded")
        loop.dispose()
        withTimeout(5_000) { loop.cleanup!!.join() }
        assertTrue(dir.exists(), "a recording is never deleted by dispose")
        assertTrue(pieces().isNotEmpty(), "dispose keeps the recorded pieces")
    }

    @Test
    fun `keep all dispose while recording keeps the files and ends the playlist`() = runBlocking {
        val (loop, seen) = start(LiveRetention.KeepAll)
        seen.await { it is LiveCaptureEvent.Buffered && it.bufferedMs >= 8_000 }
        loop.dispose()
        withTimeout(5_000) { loop.cleanup!!.join() }
        assertTrue(dir.exists(), "dispose must not delete a recording")
        assertTrue(pieces().isNotEmpty(), "the recorded pieces stay")
        assertTrue("#EXT-X-ENDLIST" in playlist(), "an abandoned recording is still a finished, playable one")
    }

    @Test
    fun `trim dispose still deletes the directory`() = runBlocking {
        val (loop, seen) = start(LiveRetention.Trim(LivePausePolicy.MIN_KEEP_MS), keepMs = LivePausePolicy.MIN_KEEP_MS)
        seen.await { it is LiveCaptureEvent.Buffered }
        loop.dispose()
        withTimeout(5_000) { loop.cleanup!!.join() }
        assertFalse(dir.exists(), "live pause is a scratch buffer: dispose deletes it")
    }

    @Test
    fun `an unexpected failure mid-recording still ends the playlist and keeps the files`() = runBlocking {
        val (_, seen) = start(LiveRetention.KeepAll)
        seen.await { it is LiveCaptureEvent.Buffered && it.bufferedMs >= 8_000 }
        explodeOnNextOpen.set(true)   // the body ends, the loop reopens, and the open blows up
        seen.await { it is LiveCaptureEvent.Failed }
        withTimeout(5_000) { while ("#EXT-X-ENDLIST" !in playlist()) delay(20) }
        assertTrue(pieces().isNotEmpty(), "what was recorded stays")
    }

    @Test
    fun `keep all with no room ends as no space and keeps everything recorded`() = runBlocking {
        val (_, seen) = start(LiveRetention.KeepAll)
        seen.await { it is LiveCaptureEvent.Buffered && it.bufferedMs >= 8_000 }
        val before = pieces()
        freeBytes.set(10L * 1024 * 1024)   // the disk filled up under us
        val failed = seen.await { it is LiveCaptureEvent.Failed } as LiveCaptureEvent.Failed
        assertEquals(LivePauseFailure.NO_SPACE, failed.reason)
        withTimeout(5_000) { while ("#EXT-X-ENDLIST" !in playlist()) delay(20) }
        assertTrue(pieces().containsAll(before), "no oldest piece was dropped to make room: before=$before after=${pieces()}")
        assertTrue("#EXT-X-MEDIA-SEQUENCE:0" in playlist())
    }

    @Test
    fun `trim still drops the oldest piece and keeps a sliding window`() = runBlocking {
        val (_, seen) = start(LiveRetention.Trim(LivePausePolicy.MIN_KEEP_MS), keepMs = LivePausePolicy.MIN_KEEP_MS)
        seen.await(30_000) { it is LiveCaptureEvent.Buffered && it.full }
        assertFalse("#EXT-X-PLAYLIST-TYPE:EVENT" in playlist())
        assertFalse("#EXT-X-MEDIA-SEQUENCE:0\n" in playlist(), "the front has slid")
    }
}
