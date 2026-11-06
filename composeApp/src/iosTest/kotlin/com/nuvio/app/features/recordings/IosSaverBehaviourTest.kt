package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.api.RecordingProblem
import com.nuvio.app.features.recordings.api.SaveOutcome
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The iOS saver's decisions, with a scripted downloader: no network, real files in a temp folder. */
@OptIn(ExperimentalForeignApi::class)
class IosSaverBehaviourTest {
    private val rig = SaverRig()
    private val a = "http://a.example/show.ts"
    private val b = "http://b.example/show.ts"
    private val part get() = "${rig.recs}/show.ts.part"
    private val target get() = "${rig.recs}/show.ts"
    private val workPart get() = "${rig.downloads}/rec-show.ts.part"
    private val finished get() = "${rig.downloads}/rec-show.ts"

    @AfterTest
    fun tearDown() = rig.close()

    // ---- candidates must not delete each other's partials -------------------------------------

    @Test
    fun a_link_that_fails_at_once_does_not_cost_the_earlier_links_partial() = runBlocking<Unit> {
        val saver = rig.saver(fakeDownloader { url, cb ->
            if (url == a) { rig.put(workPart, "01234"); cb.onProgress(5, null) }
            cb.onFailure("boom")
            FakeHandle()
        })
        val out = saver.save(rig.request(a, b), {})
        val failed = assertIs<SaveOutcome.Failed>(out)
        assertEquals(5L, rig.size(part), "A's 5 bytes are still on disk after B failed with nothing")
        assertEquals(rig.size(part), failed.writtenBytes, "what is reported is what exists")
        assertEquals(a, IosPartialStagingProbe.pinUrl(part))
    }

    @Test
    fun a_smaller_partial_from_a_later_link_does_not_replace_a_bigger_one() = runBlocking<Unit> {
        val saver = rig.saver(fakeDownloader { url, cb ->
            rig.put(workPart, if (url == a) "01234" else "012")
            cb.onProgress(rig.size(workPart), null)
            cb.onFailure("boom")
            FakeHandle()
        })
        val failed = assertIs<SaveOutcome.Failed>(saver.save(rig.request(a, b), {}))
        assertEquals(5L, rig.size(part))
        assertEquals(5L, failed.writtenBytes)
        assertEquals(a, IosPartialStagingProbe.pinUrl(part), "the kept bytes still belong to A")
    }

    @Test
    fun a_bigger_partial_from_a_later_link_replaces_the_earlier_one() = runBlocking<Unit> {
        val saver = rig.saver(fakeDownloader { url, cb ->
            rig.put(workPart, if (url == a) "01234" else "01234567")
            cb.onProgress(rig.size(workPart), null)
            cb.onFailure("boom")
            FakeHandle()
        })
        val failed = assertIs<SaveOutcome.Failed>(saver.save(rig.request(a, b), {}))
        assertEquals(8L, rig.size(part))
        assertEquals(8L, failed.writtenBytes)
        assertEquals(b, IosPartialStagingProbe.pinUrl(part))
    }

    @Test
    fun a_fresh_start_clears_any_old_partial_before_the_first_link() = runBlocking<Unit> {
        rig.put(part, "stale"); rig.put("$part.meta", a)
        val saver = rig.saver(fakeDownloader { _, cb -> cb.onFailure("boom"); FakeHandle() })
        val failed = assertIs<SaveOutcome.Failed>(saver.save(rig.request(b, resumeFrom = 0), {}))
        assertFalse(rig.exists(part)); assertFalse(rig.exists("$part.meta"))
        assertEquals(0L, failed.writtenBytes)
    }

    // ---- C4: out of space stops the download ---------------------------------------------------

    @Test
    fun the_space_check_stops_the_download_and_no_other_link_is_tried() = runBlocking<Unit> {
        val started = ArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        var handle: FakeHandle? = null
        val saver = rig.saver(fakeDownloader { url, cb ->
            started += url
            val ticker = scope.launch {
                delay(30); cb.onProgress(0, null)
                delay(30); cb.onProgress(17L * 1024 * 1024, null)
            }
            FakeHandle(onCancel = { ticker.cancel() }).also { handle = it }
        })
        val out = withTimeout(20_000) {
            saver.save(rig.request(a, b, spaceCheck = { RecordingProblem.NO_SPACE }), {})
        }
        scope.coroutineContext[Job]?.cancel()
        val failed = assertIs<SaveOutcome.Failed>(out)
        assertEquals(RecordingProblem.NO_SPACE, failed.problem)
        assertEquals(listOf(a), started, "B must not start once the disk is full")
        assertTrue((handle?.cancels ?: 0) >= 1, "the running download was stopped")
    }

    // ---- early end -------------------------------------------------------------------------------

    @Test
    fun a_clean_end_far_short_of_the_estimate_is_not_done() = runBlocking<Unit> {
        val saver = rig.saver(fakeDownloader { _, cb ->
            rig.put(finished, "x".repeat(100))
            cb.onProgress(100, null)
            cb.onSuccess("file://$finished", 100)
            FakeHandle()
        })
        val out = saver.save(rig.request(a, estimated = 100_000), {})
        val failed = assertIs<SaveOutcome.Failed>(out, "100 bytes of a ~100 kB programme is a cut stream")
        assertFalse(rig.exists(target), "no half recording under the real name")
        assertEquals(100L, rig.size(part), "the bytes are kept for a resume")
        assertEquals(100L, failed.writtenBytes)
        assertEquals(emptyList(), rig.recFilesInDownloads())
    }

    @Test
    fun a_clean_end_short_of_the_declared_length_is_not_done() = runBlocking<Unit> {
        val saver = rig.saver(fakeDownloader { _, cb ->
            rig.put(finished, "x".repeat(100))
            cb.onProgress(100, 1_000)
            cb.onSuccess("file://$finished", 100)
            FakeHandle()
        })
        assertIs<SaveOutcome.Failed>(saver.save(rig.request(a), {}))
        assertFalse(rig.exists(target))
    }

    @Test
    fun a_whole_file_is_done_and_lands_under_the_real_name() = runBlocking<Unit> {
        val saver = rig.saver(fakeDownloader { _, cb ->
            rig.put(finished, "x".repeat(1_000))
            cb.onProgress(1_000, 1_000)
            cb.onSuccess("file://$finished", 1_000)
            FakeHandle()
        })
        val done = assertIs<SaveOutcome.Done>(saver.save(rig.request(a), {}))
        assertEquals(1_000L, done.bytes)
        assertTrue(rig.exists(target)); assertFalse(rig.exists(part)); assertFalse(rig.exists("$part.meta"))
    }

    @Test
    fun a_modest_shortfall_without_a_declared_length_is_still_done() = runBlocking<Unit> {
        // The estimate is a bitrate guess: only a gross shortfall counts as a cut stream.
        val saver = rig.saver(fakeDownloader { _, cb ->
            rig.put(finished, "x".repeat(100))
            cb.onProgress(100, null)
            cb.onSuccess("file://$finished", 100)
            FakeHandle()
        })
        assertIs<SaveOutcome.Done>(saver.save(rig.request(a, estimated = 500), {}))
    }

    // ---- C2: a cancel leaves nothing behind in the downloader's folder -------------------------

    @Test
    fun a_cancel_after_the_downloader_already_finished_leaves_nothing_in_downloads() = runBlocking<Unit> {
        val running = CompletableDeferred<Unit>()
        val saver = rig.saver(fakeDownloader { _, cb ->
            rig.put(workPart, "abcdef")
            cb.onProgress(6, null)
            running.complete(Unit)
            // The downloader won the race: its completed file is moved into place as the cancel lands.
            FakeHandle(onCancel = { rig.move(workPart, finished) })
        })
        val job = launch(Dispatchers.Default) { saver.save(rig.request(a), {}) }
        withTimeout(20_000) { running.await() }
        delay(50)
        job.cancelAndJoin()
        assertEquals(emptyList(), rig.recFilesInDownloads(), "nothing may linger in downloads/rec-*")
        assertEquals(6L, rig.size(part), "the bytes went home, so a resume or a delete can find them")
    }

    @Test
    fun a_cancelled_save_waits_for_the_downloader_to_stop_before_it_returns() = runBlocking<Unit> {
        val running = CompletableDeferred<Unit>()
        var stopped = false
        val saver = rig.saver(fakeDownloader { _, cb ->
            rig.put(workPart, "abcdef")
            cb.onProgress(6, null)
            running.complete(Unit)
            val late = CoroutineScope(Dispatchers.Default).launch { delay(200); rig.move(workPart, finished); stopped = true }
            FakeHandle(stopped = { late.join() })
        })
        val job = launch(Dispatchers.Default) { saver.save(rig.request(a), {}) }
        withTimeout(20_000) { running.await() }
        delay(50)
        job.cancelAndJoin()
        assertTrue(stopped, "the save returned while the downloader was still moving files")
        assertEquals(emptyList(), rig.recFilesInDownloads())
        assertEquals(6L, rig.size(part))
    }

    @Test
    fun a_downloader_that_never_stops_does_not_hold_the_save_forever() = runBlocking<Unit> {
        val running = CompletableDeferred<Unit>()
        val saver = rig.saver(fakeDownloader { _, cb ->
            rig.put(workPart, "abc")
            cb.onProgress(3, null)
            running.complete(Unit)
            FakeHandle(stopped = { delay(Long.MAX_VALUE) })
        }, stopWaitMs = 200)
        val job = launch(Dispatchers.Default) { saver.save(rig.request(a), {}) }
        withTimeout(20_000) { running.await() }
        delay(50)
        withTimeout(10_000) { job.cancelAndJoin() }
        assertEquals(3L, rig.size(part))
    }
}
