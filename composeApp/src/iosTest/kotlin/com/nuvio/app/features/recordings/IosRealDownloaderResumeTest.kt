package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.api.SaveOutcome
import com.nuvio.app.features.recordings.internal.IosRecordingPaths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID

/**
 * The saver driving upstream's real downloader (NSURLSession) against a loopback server: what a resume
 * sends, and what it does with each kind of answer. These are the only tests that exercise the append.
 */
@OptIn(ExperimentalForeignApi::class)
class IosRealDownloaderResumeTest {
    private val rig = SaverRig(downloadsDir = IosRecordingPaths.defaultDownloadsDir())
    private val name = "resume-${NSUUID().UUIDString}.ts"
    private val whole = "ABCDEFGHIJ"
    private var server: LoopbackHttpServer? = null
    private val part get() = "${rig.recs}/$name.part"
    private val target get() = "${rig.recs}/$name"

    @AfterTest
    fun tearDown() {
        server?.stop()
        val fm = NSFileManager.defaultManager
        for (suffix in listOf("", ".part")) fm.removeItemAtPath("${rig.downloads}/rec-$name$suffix", null)
        rig.close()
    }

    private suspend fun serve(handler: (Map<String, String>) -> Reply) = LoopbackHttpServer(handler).start().also { server = it }

    private fun pinned(url: String, etag: String? = null) {
        rig.put(part, "0123"); rig.put("$part.meta", listOf(url, etag.orEmpty(), "").joinToString("\n"))
    }

    private suspend fun save(url: String, resumeFrom: Long) =
        withTimeout(60_000) { rig.saver().save(rig.request(url, name = name, resumeFrom = resumeFrom), {}) }

    @Test
    fun a_206_that_starts_elsewhere_restarts_from_zero_instead_of_appending() = runBlocking<Unit> {
        val s = serve { h ->
            if (h.containsKey("range")) Reply(206, mapOf("Content-Range" to "bytes 0-9/10"), whole.encodeToByteArray())
            else Reply(200, body = whole.encodeToByteArray())
        }
        pinned(s.url())
        assertIs<SaveOutcome.Done>(save(s.url(), resumeFrom = 4))
        assertEquals(whole, rig.read(target), "the wrong-offset bytes must not be appended to the 4 we had")
        assertTrue(s.requestsSnapshot().size >= 2 && !s.requestsSnapshot().last().containsKey("range"), "the retry asks for the whole file")
    }

    @Test
    fun a_resume_sends_if_range_with_the_strong_etag_the_partial_came_with() = runBlocking<Unit> {
        val s = serve { h ->
            if (h.containsKey("range")) Reply(206, mapOf("Content-Range" to "bytes 4-9/10"), whole.substring(4).encodeToByteArray())
            else Reply(200, body = whole.encodeToByteArray())
        }
        pinned(s.url(), etag = "\"v1\"")
        assertIs<SaveOutcome.Done>(save(s.url(), resumeFrom = 4))
        assertEquals("\"v1\"", s.requestsSnapshot().first()["if-range"])
        assertEquals("bytes=4-", s.requestsSnapshot().first()["range"])
    }

    @Test
    fun a_weak_etag_is_not_sent_as_if_range() = runBlocking<Unit> {
        val s = serve { h ->
            if (h.containsKey("range")) Reply(206, mapOf("Content-Range" to "bytes 4-9/10"), whole.substring(4).encodeToByteArray())
            else Reply(200, body = whole.encodeToByteArray())
        }
        pinned(s.url(), etag = "W/\"v1\"")
        assertIs<SaveOutcome.Done>(save(s.url(), resumeFrom = 4))
        assertNull(s.requestsSnapshot().first()["if-range"], "a weak validator cannot guard a byte range")
    }

    @Test
    fun a_200_to_a_ranged_request_replaces_the_partial() = runBlocking<Unit> {
        val s = serve { Reply(200, body = whole.encodeToByteArray()) }
        pinned(s.url(), etag = "\"v1\"")
        assertIs<SaveOutcome.Done>(save(s.url(), resumeFrom = 4))
        assertEquals(whole, rig.read(target))
    }

    @Test
    fun a_partial_from_another_link_is_not_resumed() = runBlocking<Unit> {
        val s = serve { Reply(200, body = whole.encodeToByteArray()) }
        pinned("http://other.example/x.ts", etag = "\"v1\"")
        assertIs<SaveOutcome.Done>(save(s.url(), resumeFrom = 4))
        assertNull(s.requestsSnapshot().first()["range"], "the foreign 4 bytes must never be extended")
        assertEquals(whole, rig.read(target))
    }

    @Test
    fun a_dropped_stream_keeps_its_bytes_and_the_validator_for_the_next_resume() = runBlocking<Unit> {
        val s = serve { Reply(200, mapOf("ETag" to "\"v9\"", "Last-Modified" to "Wed, 05 Nov 2026 10:00:00 GMT"), whole.encodeToByteArray(), cutAfter = 4) }
        val failed = assertIs<SaveOutcome.Failed>(save(s.url(), resumeFrom = 0))
        assertEquals(4L, failed.writtenBytes)
        val meta = rig.read("$part.meta").orEmpty()
        assertTrue(meta.startsWith(s.url()), "the link is pinned: $meta")
        assertTrue(meta.contains("\"v9\""), "the ETag it answered with is kept for If-Range: $meta")
    }
}
