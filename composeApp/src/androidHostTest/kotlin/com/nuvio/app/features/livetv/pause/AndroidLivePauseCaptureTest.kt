package com.nuvio.app.features.livetv.pause

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Android disk buffer against a real HTTP server: it must produce a playable local playlist, fetch every HLS
 * segment once, never re-open a single-use link, stop after a capped number of reconnects and end visibly when
 * the disk is full.
 */
class AndroidLivePauseCaptureTest {
    private val server = MockWebServer()
    private val root: File = Files.createTempDirectory("live-pause-test").toFile()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val freeBytes = AtomicLong(50L * 1024 * 1024 * 1024)
    private val mono = AtomicLong(0L)
    private val handles = mutableListOf<LiveCaptureHandle>()

    @AfterTest
    fun tearDown() {
        handles.forEach { it.dispose() }
        runCatching { server.shutdown() }
        root.deleteRecursively()
    }

    private fun hit(path: String) = hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
    private fun hitCount(path: String) = hits[path]?.get() ?: 0

    private fun client(singleUse: Boolean = false) = OkHttpClient.Builder()
        .retryOnConnectionFailure(!singleUse)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun capture(factory: (Boolean) -> Call.Factory = { client(it) }) = AndroidLivePauseCapture(
        rootDir = root,
        callFactoryFor = factory,
        freeBytes = { freeBytes.get() },
        monotonicMs = { mono.get() + System.nanoTime() / 1_000_000L },
        reconnectDelayMs = { 5L },
        minPollMs = 20L,
    )

    private fun request(
        url: String,
        singleUse: Boolean = false,
        minter: LiveLinkMinter = LiveLinkMinter { error("must not mint") },
    ) = LiveCaptureRequest(
        instanceId = "p1",
        firstLink = LiveLink(url, mapOf("User-Agent" to "tuvora-test")),
        singleUseLink = singleUse,
        minter = minter,
        keepMs = LivePausePolicy.DEFAULT_KEEP_MS,
        bitrateBps = null,
        lowMemoryTier = false,
    )

    /** Starts [request], collecting its events on a background scope. */
    private class Running(val handle: LiveCaptureHandle, val seen: CopyOnWriteArrayList<LiveCaptureEvent>, val collector: Job)

    private fun AndroidLivePauseCapture.begin(request: LiveCaptureRequest): Running {
        val handle = start(request).also { handles += it }
        val seen = CopyOnWriteArrayList<LiveCaptureEvent>()
        val job = CoroutineScope(Dispatchers.Default).launch { handle.events.collect { seen += it } }
        return Running(handle, seen, job)
    }

    private suspend fun Running.await(what: String, timeoutMs: Long = 15_000, predicate: (LiveCaptureEvent) -> Boolean): LiveCaptureEvent =
        withTimeout(timeoutMs) {
            var found: LiveCaptureEvent? = null
            while (found == null) {
                found = seen.firstOrNull(predicate)
                if (found == null) delay(20)
            }
            found
        }.also { println("awaited $what -> $it") }

    private fun tsBody(halfSeconds: Int = 39): ByteArray = Ts.concat(
        Ts.pat(), Ts.pmt(),
        *Array(halfSeconds + 1) { k -> Ts.media(pcrBase = k * 45_000L, rai = true, pusi = true, tag = 0x20 + k % 20) },
    )

    private fun response(bytes: ByteArray, type: String = "video/mp2t") =
        MockResponse().setHeader("Content-Type", type).setBody(Buffer().write(bytes))

    private fun serve(handler: (path: String) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                hit(path)
                return handler(path)
            }
        }
        server.start()
    }

    private fun url(path: String) = server.url(path).toString()
    private fun localPlaylist(): String = File(File(root, "p1"), "index.m3u8").readText()

    // --- TS ----------------------------------------------------------------------------------

    @Test
    fun `a ts channel becomes a playable local playlist and dispose deletes it`() = runBlocking {
        serve { response(tsBody()) }
        val run = capture().begin(request(url("/live/1.ts")))
        val ready = run.await("ready") { it is LiveCaptureEvent.Ready } as LiveCaptureEvent.Ready
        assertTrue(ready.playlistUrl.startsWith("file://"))
        assertTrue(ready.playlistUrl.endsWith("/p1/index.m3u8"))
        run.await("buffered 8s") { it is LiveCaptureEvent.Buffered && it.bufferedMs >= 8_000 }

        val playlist = localPlaylist()
        assertTrue(playlist.startsWith("#EXTM3U"), playlist)
        assertTrue("#EXT-X-TARGETDURATION:" in playlist)
        assertFalse("#EXT-X-ENDLIST" in playlist, "a live buffer is never finished while capturing")
        assertFalse("#EXT-X-PLAYLIST-TYPE:EVENT" in playlist, "a rewind buffer drops from the front, so it is not EVENT")
        val first = playlist.lines().first { it.startsWith("seg") }
        val bytes = File(File(root, "p1"), first).readBytes()
        assertEquals(0, bytes.size % Ts.PACKET)
        assertEquals(0x47, bytes[0].toInt())
        assertTrue(hitCount("/live/1.ts") >= 1, "the first open uses the link the player had")

        run.handle.dispose()
        (run.handle as AndroidLivePauseCapture.Handle).cleanup!!.join()
        assertFalse(File(root, "p1").exists(), "dispose deletes the capture directory")
    }

    @Test
    fun `a reusable link is opened as given and the minter is not asked`() = runBlocking {
        serve { response(tsBody()) }
        val run = capture().begin(request(url("/live/2.ts")))   // minter would throw
        run.await("ready") { it is LiveCaptureEvent.Ready }
        assertTrue(hitCount("/live/2.ts") >= 1)
    }

    @Test
    fun `a single-use link is never re-requested and the spent one is never opened`() = runBlocking {
        val minted = AtomicInteger()
        serve { path ->
            if (hitCount(path) > 1) MockResponse().setResponseCode(500)   // a repeat of a spent URL
            else if (path.startsWith("/fresh")) response(tsBody(5)) else MockResponse().setResponseCode(500)
        }
        val minter = LiveLinkMinter { LiveLink(url("/fresh${minted.incrementAndGet()}.ts")) }
        val run = capture().begin(request(url("/spent.ts"), singleUse = true, minter = minter))
        run.await("ready") { it is LiveCaptureEvent.Ready }
        // Each body ends after a few seconds of stream, which is a drop: the next open must be a NEW link.
        withTimeout(10_000) { while (minted.get() < 3) delay(20) }
        assertEquals(0, hitCount("/spent.ts"), "the URL the player already spent is not opened by the capture")
        for ((path, count) in hits) assertTrue(count.get() <= 1, "$path was requested ${count.get()} times")
    }

    @Test
    fun `a dead server gives up after the capped attempts instead of retrying forever`() = runBlocking {
        serve { MockResponse() }
        server.shutdown()
        val opened = AtomicInteger()
        val counting = Call.Factory { r: Request -> opened.incrementAndGet(); client().newCall(r) }
        val run = capture { counting }.begin(request(url("/live/3.ts")))
        val failed = run.await("failed") { it is LiveCaptureEvent.Failed } as LiveCaptureEvent.Failed
        assertEquals(LivePauseFailure.STALLED, failed.reason)
        delay(300)
        assertEquals(1 + 3, opened.get(), "one open plus three capped retries, then GiveUp")
    }

    @Test
    fun `a refused link ends as provider refused after the capped attempts`() = runBlocking {
        serve { MockResponse().setResponseCode(403) }
        val run = capture().begin(request(url("/live/4.ts")))
        val failed = run.await("failed") { it is LiveCaptureEvent.Failed } as LiveCaptureEvent.Failed
        assertEquals(LivePauseFailure.PROVIDER_REFUSED, failed.reason)
        assertEquals(1 + 3, hitCount("/live/4.ts"))
    }

    @Test
    fun `not enough free space fails as no space before any request`() = runBlocking {
        freeBytes.set(100L * 1024 * 1024)   // below the 300 MB reserve
        serve { response(tsBody()) }
        val run = capture().begin(request(url("/live/5.ts")))
        val failed = run.await("failed") { it is LiveCaptureEvent.Failed } as LiveCaptureEvent.Failed
        assertEquals(LivePauseFailure.NO_SPACE, failed.reason)
        assertEquals(0, hitCount("/live/5.ts"))
    }

    @Test
    fun `space running out mid capture trims then fails as no space`() = runBlocking {
        serve { response(tsBody()) }
        val run = capture().begin(request(url("/live/6.ts")))
        run.await("ready") { it is LiveCaptureEvent.Ready }
        freeBytes.set(10L * 1024 * 1024)   // the disk filled up under us
        val failed = run.await("failed") { it is LiveCaptureEvent.Failed } as LiveCaptureEvent.Failed
        assertEquals(LivePauseFailure.NO_SPACE, failed.reason)
    }

    @Test
    fun `a stream with no pieces for the stall window fails as stalled`() = runBlocking {
        // Bytes keep arriving but never form a transport stream (no sync byte): no piece can ever be cut.
        val noise = ByteArray(188 * 120) { 0x11 }
        serve { response(noise).throttleBody(188L * 2, 100, TimeUnit.MILLISECONDS) }
        val run = capture().begin(request(url("/live/7.ts")))
        withTimeout(10_000) { while (hitCount("/live/7.ts") == 0) delay(10) }   // the capture is connected and reading
        delay(250)
        mono.addAndGet(120_000)   // two minutes of monotonic time pass with no piece
        val failed = run.await("failed") { it is LiveCaptureEvent.Failed } as LiveCaptureEvent.Failed
        assertEquals(LivePauseFailure.STALLED, failed.reason)
    }

    // --- HLS ---------------------------------------------------------------------------------

    private fun mediaPlaylist(firstSeq: Int, count: Int, extra: String = "") = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:$firstSeq\n$extra")
        for (i in firstSeq until firstSeq + count) append("#EXTINF:1.000,\n/seg$i.ts\n")
    }

    private fun segmentBytes(seq: Int) = Ts.concat(Ts.pat(), Ts.pmt(), Ts.media(pcrBase = seq * 90_000L, rai = true, pusi = true, tag = 0x30 + seq % 10))

    @Test
    fun `hls segments are fetched once and the master picks a variant under the cap`() = runBlocking {
        val polls = AtomicInteger()
        serve { path ->
            when {
                path == "/master.m3u8" -> MockResponse().setBody(
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\n/low.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=20000000\n/high.m3u8\n",
                )
                path == "/low.m3u8" -> MockResponse().setBody(if (polls.getAndIncrement() == 0) mediaPlaylist(0, 3) else mediaPlaylist(0, 5))
                path.startsWith("/seg") -> MockResponse().setBody(Buffer().write(segmentBytes(path.removePrefix("/seg").removeSuffix(".ts").toInt())))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val run = capture().begin(request(url("/master.m3u8")))
        run.await("ready") { it is LiveCaptureEvent.Ready }
        run.await("5 s buffered") { it is LiveCaptureEvent.Buffered && it.bufferedMs >= 5_000 }

        assertEquals(0, hitCount("/high.m3u8"), "the 20 Mbps variant is over the 15 Mbps cap")
        for (i in 0 until 5) assertEquals(1, hitCount("/seg$i.ts"), "segment $i is downloaded exactly once")
        val playlist = localPlaylist()
        assertTrue("seg0.ts" in playlist && "seg4.ts" in playlist, playlist)
        assertEquals(5, playlist.lines().count { it.startsWith("#EXTINF:1.000") })
        for (i in 0 until 5) assertTrue(File(File(root, "p1"), "seg$i.ts").length() > 0)
        assertTrue(File(root, "p1").listFiles().orEmpty().none { it.name.endsWith(".part") }, "no half-written segment is left behind")
    }

    @Test
    fun `an hls provider that restarts its numbering more than three times fails as sequence reset`() = runBlocking {
        val polls = AtomicInteger()
        serve { path ->
            when {
                path == "/live.m3u8" -> {
                    val n = polls.getAndIncrement()
                    MockResponse().setBody(mediaPlaylist(10_000 - n * 1_000, 2))
                }
                path.startsWith("/seg") -> MockResponse().setBody(Buffer().write(segmentBytes(path.removePrefix("/seg").removeSuffix(".ts").toInt())))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val run = capture().begin(request(url("/live.m3u8")))
        val failed = run.await("failed") { it is LiveCaptureEvent.Failed } as LiveCaptureEvent.Failed
        assertEquals(LivePauseFailure.SEQUENCE_RESET, failed.reason)
    }

    @Test
    fun `an encrypted hls stream is unsupported and never recorded as garbage`() = runBlocking {
        serve { MockResponse().setBody(mediaPlaylist(0, 3, "#EXT-X-KEY:METHOD=AES-128,URI=\"/k\"\n")) }
        val run = capture().begin(request(url("/enc.m3u8")))
        val failed = run.await("failed") { it is LiveCaptureEvent.Failed } as LiveCaptureEvent.Failed
        assertEquals(LivePauseFailure.UNSUPPORTED_STREAM, failed.reason)
        assertEquals(0, hitCount("/seg0.ts"))
    }

    // --- housekeeping ------------------------------------------------------------------------

    @Test
    fun `the startup sweep removes only directories older than six hours`() {
        val old = File(root, "old").apply { mkdirs(); File(this, "a.ts").writeText("x") }
        val fresh = File(root, "fresh").apply { mkdirs() }
        val now = System.currentTimeMillis()
        old.setLastModified(now - 7 * 60 * 60 * 1000L)
        fresh.setLastModified(now - 60 * 1000L)
        assertEquals(1, LivePauseStorage.sweepStale(root, now))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }
}
