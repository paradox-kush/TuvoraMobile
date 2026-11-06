package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.api.ArmKind
import com.nuvio.app.features.recordings.api.CaptureMode
import com.nuvio.app.features.recordings.api.FireResult
import com.nuvio.app.features.recordings.api.ListingInfo
import com.nuvio.app.features.recordings.api.LiveCaptureOutcome
import com.nuvio.app.features.recordings.api.LiveFootage
import com.nuvio.app.features.recordings.api.LowSpacePolicy
import com.nuvio.app.features.recordings.api.RecordingAlarms
import com.nuvio.app.features.recordings.api.RecordingCapabilities
import com.nuvio.app.features.recordings.api.RecordingFiles
import com.nuvio.app.features.recordings.api.RecordingProblem
import com.nuvio.app.features.recordings.api.RecordingSaver
import com.nuvio.app.features.recordings.api.RecordingStatus
import com.nuvio.app.features.recordings.api.SaveOutcome
import com.nuvio.app.features.recordings.api.SaveRequest
import com.nuvio.app.features.recordings.api.ScheduleResult
import com.nuvio.app.features.recordings.internal.FireEntry
import com.nuvio.app.features.recordings.internal.FireLedgerState
import com.nuvio.app.features.recordings.internal.FireState
import com.nuvio.app.features.recordings.internal.RecordingLiveLayout
import com.nuvio.app.features.recordings.internal.RecordingSchedulePolicy
import com.nuvio.app.features.recordings.internal.RecordingSpacePolicy
import com.nuvio.app.features.recordings.internal.RecordingsCodec
import com.nuvio.app.features.recordings.internal.RecordingsDocument
import com.nuvio.app.features.recordings.internal.RecordingsRepository
import com.nuvio.app.features.recordings.internal.StoredRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

private class LiveAlarms : RecordingAlarms {
    val armed = LinkedHashMap<String, Long>()
    override fun arm(scheduleId: String, atMs: Long): ArmKind { armed[scheduleId] = atMs; return ArmKind.EXACT }
    override fun disarm(scheduleId: String) { armed.remove(scheduleId) }
    override fun exactAllowed() = true
}

/** Plain files plus directories: a live recording's pieces live under one directory. */
private class TreeFiles : RecordingFiles {
    val texts = HashMap<String, String>()
    val sizes = HashMap<String, Long>()
    val deletedTrees = ArrayList<String>()
    var free = 100L * 1024 * 1024 * 1024
    override suspend fun read(name: String) = texts[name]
    override suspend fun write(name: String, text: String) { texts[name] = text }
    override suspend fun keepAside(name: String) { texts.remove(name) }
    override fun pathFor(fileName: String) = "/rec/$fileName"
    override fun freeBytes() = free
    override fun sizeOf(path: String) = sizes[path] ?: 0L
    override suspend fun delete(path: String) { sizes.remove(path) }
    override suspend fun deleteTree(path: String) {
        deletedTrees += path
        sizes.keys.removeAll { it == path || it.startsWith("$path/") }
    }
    override fun treeSizeOf(path: String): Long = sizes.filterKeys { it.startsWith("$path/") }.values.sum()
}

private class NoSaver : RecordingSaver {
    override suspend fun save(req: SaveRequest, progress: (Long) -> Unit): SaveOutcome = error("a live recording never goes through the saver")
}

private class LiveRig(
    capabilities: RecordingCapabilities = RecordingCapabilities.ANDROID,
    val files: TreeFiles = TreeFiles(),
    withCapturer: Boolean = true,
) {
    val alarms = LiveAlarms()
    val capturer = FakeLiveCapturer { dir -> files.sizes["$dir/index.m3u8"] = 900L }
    var wall = NOW
    val repo = RecordingsRepository(
        files, alarms, NoSaver(), capabilities, lowSpacePolicy = { LowSpacePolicy.STOP }, timeSource = TestTimeSource(), clock = { wall },
        liveCapturer = if (withCapturer) capturer else null,
    )
}

/** On air now: started 10 min ago, ends in 50 min (stop = end + 5 min post-roll). */
private fun onAir(id: String = "L", attempts: Int = 0, nextTry: Long = 0L) =
    schedule(id = id, mode = CaptureMode.LIVE, start = NOW - 10 * MIN, end = NOW + 50 * MIN, attempts = attempts, nextTry = nextTry)

private fun stateOf(vararg s: com.nuvio.app.features.recordings.api.RecordingSchedule) =
    RecordingsCodec.encode(RecordingsDocument(schedules = s.map { StoredRow.of(it) }))

private fun dirOf(id: String = "L") = "/rec/" + RecordingLiveLayout.dirNameFor(id)

class RecordingsLiveRepositoryTest {

    private fun rigWith(vararg s: com.nuvio.app.features.recordings.api.RecordingSchedule, capabilities: RecordingCapabilities = RecordingCapabilities.ANDROID) =
        LiveRig(capabilities).also { it.files.texts["recordings.json"] = stateOf(*s) }

    // ---- fire -> done -----------------------------------------------------------------------

    @Test
    fun `a live fire records into the schedule's own directory and finishes as a playable recording`() = runTest {
        val rig = rigWith(onAir())
        assertEquals(FireResult.Saved, rig.repo.fire("L", NOW))
        val job = rig.capturer.jobs.single()
        assertEquals(dirOf(), job.dir)
        assertEquals("rec:L", job.recordingId)
        val row = rig.repo.state.value.recordings.single()
        assertEquals(RecordingStatus.DONE, row.status)
        assertEquals(CaptureMode.LIVE, row.mode)
        assertEquals(RecordingLiveLayout.fileNameFor("L"), row.fileName)
        assertEquals(500_000_000L, row.sizeBytes)
        assertEquals(3_600_000L, row.durationMs)
        assertNull(row.problem)
        assertNull(row.retrySource)
        assertTrue(rig.repo.state.value.schedules.isEmpty())
        assertNull(rig.alarms.armed["L"])
        val uri = assertNotNull(rig.repo.playableUri(row), "the finished playlist plays through the ordinary file path")
        assertTrue(uri.startsWith("file:///rec/live-") && uri.endsWith("/index.m3u8"), uri)
    }

    @Test
    fun `a recording that has not published its playlist is not playable`() = runTest {
        val rig = rigWith(onAir())
        rig.capturer.onCapture = { job, _ -> LiveCaptureOutcome.Done(1, 1, "${job.dir}/index.m3u8") }
        rig.repo.fire("L", NOW)
        assertNull(rig.repo.playableUri(rig.repo.state.value.recordings.single()))
    }

    @Test
    fun `while it records the row is RECORDING and the bytes show in progress`() = runTest {
        val rig = rigWith(onAir())
        var status: RecordingStatus? = null
        var seen: Long? = null
        rig.capturer.onCapture = { job, progress ->
            progress(3_000_000L, 20_000L)
            status = rig.repo.state.value.recordings.single().status
            seen = rig.repo.progress.value["rec:L"]
            LiveCaptureOutcome.Done(3_000_000L, 20_000L, "${job.dir}/index.m3u8")
        }
        rig.repo.fire("L", NOW)
        assertEquals(RecordingStatus.RECORDING, status)
        assertEquals(3_000_000L, seen)
        assertNull(rig.repo.progress.value["rec:L"], "the entry goes with the capture")
    }

    @Test
    fun `fire twice is one capture`() = runTest {
        val rig = rigWith(onAir())
        val gate = CompletableDeferred<Unit>()
        rig.capturer.onCapture = { job, _ -> gate.await(); LiveCaptureOutcome.Done(10, 1000, "${job.dir}/index.m3u8") }
        val first = async(start = CoroutineStart.UNDISPATCHED) { rig.repo.fire("L", NOW) }
        assertEquals(FireResult.AlreadyHandled, rig.repo.fire("L", NOW), "a second fire while it runs")
        gate.complete(Unit)
        assertEquals(FireResult.Saved, first.await())
        assertEquals(FireResult.NotFound, rig.repo.fire("L", NOW), "after it finished the schedule is gone: a late alarm starts nothing")
        assertEquals(1, rig.capturer.jobs.size)
    }

    @Test
    fun `the capture stops at the programme's end plus the post-roll on the wall clock`() = runTest {
        val live = onAir()
        val rig = rigWith(live)
        rig.repo.fire("L", NOW)
        assertEquals(RecordingSchedulePolicy.liveStopAtMs(live), rig.capturer.jobs.single().stopAtMs)
        assertEquals(live.programmeEndMs + live.postMs, rig.capturer.jobs.single().stopAtMs)
        assertEquals(live.channel, rig.capturer.jobs.single().channel)
    }

    @Test
    fun `a live capture is not started before its time`() = runTest {
        val early = schedule(id = "L", mode = CaptureMode.LIVE, start = NOW + 2 * HOUR)
        val rig = rigWith(early)
        assertTrue(rig.repo.fire("L", NOW) is FireResult.Rearmed)
        assertTrue(rig.capturer.jobs.isEmpty())
    }

    // ---- failures --------------------------------------------------------------------------

    @Test
    fun `a stall with nothing on disk is tried again soon inside the slot`() = runTest {
        val rig = rigWith(onAir())
        rig.capturer.onCapture = { _, _ -> LiveCaptureOutcome.Failed(RecordingProblem.STALLED, offline = false, bytesKept = 0) }
        val res = rig.repo.fire("L", NOW)
        assertEquals(FireResult.Retrying(NOW + RecordingSchedulePolicy.LIVE_RETRY_MS), res)
        val s = rig.repo.state.value.schedules.single()
        assertEquals(1, s.attempts)
        assertEquals(NOW + RecordingSchedulePolicy.LIVE_RETRY_MS, rig.alarms.armed["L"])
        assertTrue(rig.repo.state.value.recordings.isEmpty())
    }

    @Test
    fun `no network does not use an attempt`() = runTest {
        val rig = rigWith(onAir())
        rig.capturer.onCapture = { _, _ -> LiveCaptureOutcome.Failed(RecordingProblem.NETWORK, offline = true, bytesKept = 0) }
        assertTrue(rig.repo.fire("L", NOW) is FireResult.Retrying)
        assertEquals(0, rig.repo.state.value.schedules.single().attempts)
    }

    @Test
    fun `a provider refusal is tried again then given up on as a failed row that can be retried`() = runTest {
        val rig = rigWith(onAir(attempts = 2))
        rig.capturer.onCapture = { _, _ -> LiveCaptureOutcome.Failed(RecordingProblem.PROVIDER_REFUSED, offline = false, bytesKept = 0) }
        assertEquals(FireResult.Failed(RecordingProblem.PROVIDER_REFUSED), rig.repo.fire("L", NOW))
        val row = rig.repo.state.value.recordings.single()
        assertEquals(RecordingStatus.FAILED, row.status)
        assertEquals(RecordingProblem.PROVIDER_REFUSED, row.problem)
        assertNotNull(row.retrySource)
        assertTrue(rig.repo.state.value.schedules.isEmpty())
    }

    @Test
    fun `a retry inside the slot continues into the SAME directory and keeps the first attempt's footage`() = runTest {
        val rig = rigWith(onAir())
        rig.files.sizes["${dirOf()}/p0/seg0.ts"] = 4_000_000L
        rig.capturer.onCapture = { _, _ -> LiveCaptureOutcome.Failed(RecordingProblem.STALLED, offline = false, bytesKept = 4_000_000L, durationKeptMs = 30_000L) }
        val first = rig.repo.fire("L", NOW)
        assertTrue(first is FireResult.Retrying, "footage kept, time left in the slot: try again, not done: $first")
        assertTrue(rig.files.deletedTrees.isEmpty(), "the retry must not delete what the first attempt recorded")
        assertEquals(4_000_000L, rig.files.treeSizeOf(dirOf()))
        rig.capturer.onCapture = { job, _ -> LiveCaptureOutcome.Done(9_000_000L, 90_000L, "${job.dir}/index.m3u8") }
        assertEquals(FireResult.Saved, rig.repo.fire("L", (first as FireResult.Retrying).atMs))
        assertEquals(2, rig.capturer.jobs.size)
        assertEquals(rig.capturer.jobs[0].dir, rig.capturer.jobs[1].dir)
        assertEquals(1, rig.repo.state.value.recordings.size)
        assertEquals(9_000_000L, rig.repo.state.value.recordings.single().sizeBytes)
        assertTrue(rig.files.deletedTrees.isEmpty())
    }

    @Test
    fun `a full disk with nothing recorded is a failed row`() = runTest {
        val rig = rigWith(onAir())
        rig.capturer.onCapture = { _, _ -> LiveCaptureOutcome.Failed(RecordingProblem.NO_SPACE, offline = false, bytesKept = 0) }
        assertEquals(FireResult.Failed(RecordingProblem.NO_SPACE), rig.repo.fire("L", NOW))
        assertEquals(RecordingStatus.FAILED, rig.repo.state.value.recordings.single().status)
        assertTrue(rig.repo.state.value.schedules.isEmpty(), "a full disk stays full: no retry")
    }

    @Test
    fun `a full disk after something was recorded ends as a finished recording that says it was cut short`() = runTest {
        val rig = rigWith(onAir())
        rig.capturer.onCapture = { _, _ -> LiveCaptureOutcome.Failed(RecordingProblem.NO_SPACE, offline = false, bytesKept = 80_000_000L, durationKeptMs = 600_000L) }
        assertEquals(FireResult.Saved, rig.repo.fire("L", NOW))
        val row = rig.repo.state.value.recordings.single()
        assertEquals(RecordingStatus.DONE, row.status)
        assertEquals(RecordingProblem.NO_SPACE, row.problem, "the note the UI shows")
        assertEquals(80_000_000L, row.sizeBytes)
        assertEquals(600_000L, row.durationMs)
        assertNull(row.retrySource)
        assertTrue(rig.repo.state.value.schedules.isEmpty())
        assertTrue(rig.files.deletedTrees.isEmpty())
    }

    @Test
    fun `giving up after a failure with footage keeps the footage as a finished recording`() = runTest {
        val rig = rigWith(onAir(attempts = 2))
        rig.capturer.onCapture = { _, _ -> LiveCaptureOutcome.Failed(RecordingProblem.STALLED, offline = false, bytesKept = 10_000_000L, durationKeptMs = 120_000L) }
        assertEquals(FireResult.Saved, rig.repo.fire("L", NOW))
        val row = rig.repo.state.value.recordings.single()
        assertEquals(RecordingStatus.DONE, row.status)
        assertEquals(RecordingProblem.STALLED, row.problem)
        assertEquals(120_000L, row.durationMs)
    }

    @Test
    fun `a failure too close to the end for another try keeps its footage`() = runTest {
        val rig = rigWith(onAir())
        val late = NOW + 54 * MIN + 45_000L   // 15 s before the stop: a 30 s retry would land after it
        rig.capturer.onCapture = { _, _ -> LiveCaptureOutcome.Failed(RecordingProblem.STALLED, offline = false, bytesKept = 5_000_000L, durationKeptMs = 3_000_000L) }
        assertEquals(FireResult.Saved, rig.repo.fire("L", late))
        assertEquals(RecordingStatus.DONE, rig.repo.state.value.recordings.single().status)
    }

    @Test
    fun `a capturer that throws is a failure not a crash`() = runTest {
        val rig = rigWith(onAir())
        rig.capturer.onCapture = { _, _ -> error("boom") }
        assertTrue(rig.repo.fire("L", NOW) is FireResult.Retrying)
    }

    // ---- cancel ----------------------------------------------------------------------------

    @Test
    fun `the user stopping a running live recording keeps the footage as a finished recording`() = runTest {
        val rig = rigWith(onAir())
        val started = CompletableDeferred<Unit>()
        rig.capturer.onCapture = { _, _ -> started.complete(Unit); awaitCancellation() }
        rig.capturer.settleResult = LiveFootage(7_000_000L, 90_000L)
        val fire = async { rig.repo.fire("L", NOW) }
        started.await()
        rig.repo.cancel("L")
        assertEquals(FireResult.Saved, fire.await())
        val row = rig.repo.state.value.recordings.single()
        assertEquals(RecordingStatus.DONE, row.status)
        assertEquals(7_000_000L, row.sizeBytes)
        assertEquals(90_000L, row.durationMs)
        assertNull(row.problem, "the user chose to stop: nothing went wrong")
        assertTrue(rig.repo.state.value.schedules.isEmpty())
        assertTrue(rig.files.deletedTrees.isEmpty())
        assertEquals(listOf(dirOf()), rig.capturer.settled)
    }

    @Test
    fun `the user stopping a live recording that has recorded nothing cancels it`() = runTest {
        val rig = rigWith(onAir())
        val started = CompletableDeferred<Unit>()
        rig.capturer.onCapture = { _, _ -> started.complete(Unit); awaitCancellation() }
        val fire = async { rig.repo.fire("L", NOW) }
        started.await()
        rig.repo.cancel("L")
        assertEquals(FireResult.Cancelled, fire.await())
        val row = rig.repo.state.value.recordings.single()
        assertEquals(RecordingStatus.CANCELLED, row.status)
        assertEquals(RecordingProblem.CANCELLED_BY_USER, row.problem)
        assertNotNull(row.retrySource)
    }

    @Test
    fun `system teardown during a live capture rethrows and is retried not a user cancel`() = runTest {
        val rig = rigWith(onAir())
        val started = CompletableDeferred<Unit>()
        rig.capturer.onCapture = { _, _ -> started.complete(Unit); awaitCancellation() }
        var thrown: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try { rig.repo.fire("L", NOW) } catch (e: CancellationException) { thrown = e; throw e }
        }
        started.await()
        job.cancel()
        job.join()
        assertNotNull(thrown, "the caller's cancellation is rethrown")
        val s = rig.repo.state.value.schedules.single()
        assertEquals(1, s.attempts)
        assertEquals(NOW + RecordingSchedulePolicy.LIVE_RETRY_MS, rig.alarms.armed["L"])
        assertTrue(rig.repo.state.value.recordings.none { it.status == RecordingStatus.CANCELLED })
        assertTrue(rig.files.deletedTrees.isEmpty(), "the footage stays for the retry")
    }

    @Test
    fun `system teardown late in the slot keeps what was recorded as a finished recording`() = runTest {
        val rig = rigWith(onAir(attempts = 2))
        val started = CompletableDeferred<Unit>()
        rig.capturer.onCapture = { _, _ -> started.complete(Unit); awaitCancellation() }
        rig.capturer.settleResult = LiveFootage(6_000_000L, 70_000L)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { rig.repo.fire("L", NOW) }
        started.await()
        job.cancel()
        job.join()
        val row = rig.repo.state.value.recordings.single()
        assertEquals(RecordingStatus.DONE, row.status)
        assertEquals(RecordingProblem.STALLED, row.problem)
    }

    // ---- delete ----------------------------------------------------------------------------

    @Test
    fun `deleting a finished live recording removes its whole directory`() = runTest {
        val rig = rigWith(onAir())
        rig.repo.fire("L", NOW)
        rig.repo.deleteRecording("rec:L")
        assertTrue(rig.repo.state.value.recordings.isEmpty())
        assertTrue(dirOf() in rig.files.deletedTrees, rig.files.deletedTrees.toString())
        assertEquals(0L, rig.files.treeSizeOf(dirOf()))
    }

    @Test
    fun `deleting a live recording that is still running stops it waits then removes the directory`() = runTest {
        val rig = rigWith(onAir())
        val started = CompletableDeferred<Unit>()
        rig.capturer.onCapture = { _, _ -> started.complete(Unit); awaitCancellation() }
        rig.capturer.settleResult = LiveFootage(1_000_000L, 10_000L)
        val fire = async { rig.repo.fire("L", NOW) }
        started.await()
        rig.repo.deleteRecording("rec:L")
        fire.await()
        assertTrue(rig.repo.state.value.recordings.isEmpty(), "the stop did not bring the row back")
        assertTrue(dirOf() in rig.files.deletedTrees)
    }

    @Test
    fun `cancelling a waiting live schedule removes the footage an earlier attempt left`() = runTest {
        val rig = rigWith(onAir(attempts = 1, nextTry = NOW + 30_000L))
        rig.files.sizes["${dirOf()}/p0/seg0.ts"] = 2_000_000L
        rig.repo.cancel("L")
        assertTrue(rig.repo.state.value.schedules.isEmpty())
        assertTrue(dirOf() in rig.files.deletedTrees)
        assertTrue(rig.repo.state.value.recordings.isEmpty(), "nothing was recorded: no row is left behind")
    }

    /** Attempt 1 recorded 40 minutes then the stream dropped: the row is gone, the footage stays, a retry is armed. */
    private suspend fun dropAfterFortyMinutes(rig: LiveRig) {
        rig.files.sizes["${dirOf()}/p0/seg0.ts"] = 40_000_000L
        rig.capturer.settleResult = LiveFootage(40_000_000L, 40 * MIN)
        rig.capturer.onCapture = { _, _ -> LiveCaptureOutcome.Failed(RecordingProblem.STALLED, offline = false, bytesKept = 40_000_000L, durationKeptMs = 40 * MIN) }
        assertTrue(rig.repo.fire("L", NOW) is FireResult.Retrying)
        assertTrue(rig.repo.state.value.recordings.isEmpty(), "while a retry waits the row is gone")
    }

    @Test
    fun `cancelling a retrying live schedule keeps the footage the dropped attempt recorded as a finished recording`() = runTest {
        val rig = rigWith(onAir())
        dropAfterFortyMinutes(rig)
        rig.repo.cancel("L")
        assertTrue(rig.files.deletedTrees.isEmpty(), "40 minutes of footage must survive Cancel")
        assertEquals(40_000_000L, rig.files.treeSizeOf(dirOf()))
        assertTrue(rig.repo.state.value.schedules.isEmpty())
        assertNull(rig.alarms.armed["L"])
        val row = rig.repo.state.value.recordings.single()
        assertEquals(RecordingStatus.DONE, row.status)
        assertEquals(40_000_000L, row.sizeBytes)
        assertEquals(40 * MIN, row.durationMs)
        assertEquals(RecordingLiveLayout.fileNameFor("L"), row.fileName)
        assertNull(row.retrySource)
    }

    @Test
    fun `removing a series rule keeps the footage of a retrying live airing`() = runTest {
        val rig = LiveRig()
        rig.files.texts["recordings.json"] = RecordingsCodec.encode(
            RecordingsDocument(
                schedules = listOf(StoredRow.of(onAir().copy(seriesRuleId = "r1"))),
                series = listOf(StoredRow.of(rule("r1"))),
            ),
        )
        val fresh = rig
        dropAfterFortyMinutes(fresh)
        fresh.repo.removeSeriesRule("r1")
        assertTrue(fresh.files.deletedTrees.isEmpty())
        assertTrue(fresh.repo.state.value.series.isEmpty())
        assertTrue(fresh.repo.state.value.schedules.isEmpty())
        val row = fresh.repo.state.value.recordings.single()
        assertEquals(RecordingStatus.DONE, row.status)
        assertEquals(40_000_000L, row.sizeBytes)
    }

    @Test
    fun `removing a series rule still deletes the empty directory of a retrying airing with no footage`() = runTest {
        val rig = LiveRig()
        rig.files.texts["recordings.json"] = RecordingsCodec.encode(
            RecordingsDocument(
                schedules = listOf(StoredRow.of(onAir(attempts = 1, nextTry = NOW + 30_000L).copy(seriesRuleId = "r1"))),
                series = listOf(StoredRow.of(rule("r1"))),
            ),
        )
        rig.repo.removeSeriesRule("r1")
        assertTrue(dirOf() in rig.files.deletedTrees)
        assertTrue(rig.repo.state.value.recordings.isEmpty())
    }

    @Test
    fun `retrying a failed live recording starts from an empty directory`() = runTest {
        val rig = rigWith(onAir(attempts = 2))
        rig.capturer.onCapture = { _, _ -> LiveCaptureOutcome.Failed(RecordingProblem.PROVIDER_REFUSED, offline = false, bytesKept = 0) }
        rig.repo.fire("L", NOW)
        rig.repo.retry("rec:L", NOW + MIN)
        assertTrue(dirOf() in rig.files.deletedTrees)
        assertEquals(1, rig.repo.state.value.schedules.size)
    }

    // ---- start conditions ------------------------------------------------------------------

    @Test
    fun `without a capturer a live schedule fails as pending and records nothing`() = runTest {
        val rig = LiveRig(withCapturer = false).also { it.files.texts["recordings.json"] = stateOf(onAir()) }
        assertEquals(FireResult.Failed(RecordingProblem.LIVE_CAPTURE_PENDING), rig.repo.fire("L", NOW))
    }

    @Test
    fun `an estimate larger than the free space never blocks a live start when the floor is clear`() = runTest {
        val live = onAir()
        val rig = rigWith(live)
        val need = RecordingSpacePolicy.estimateBytes(RecordingSchedulePolicy.liveStopAtMs(live) - NOW)
        rig.files.free = RecordingSpacePolicy.MIN_FREE_BYTES + 10L * 1024 * 1024
        assertTrue(rig.files.free - RecordingSpacePolicy.MIN_FREE_BYTES < need, "the estimate does not fit")
        assertEquals(FireResult.Saved, rig.repo.fire("L", NOW))
        assertEquals(1, rig.capturer.jobs.size, "it started")
    }

    @Test
    fun `at or below the floor a live start is refused before any byte`() = runTest {
        for (free in listOf(RecordingSpacePolicy.MIN_FREE_BYTES, RecordingSpacePolicy.MIN_FREE_BYTES - 1)) {
            val rig = rigWith(onAir())
            rig.files.free = free
            assertEquals(FireResult.Failed(RecordingProblem.NO_SPACE), rig.repo.fire("L", NOW), "free=$free")
            assertTrue(rig.capturer.jobs.isEmpty())
            val row = rig.repo.state.value.recordings.single()
            assertEquals(RecordingStatus.FAILED, row.status)
            assertEquals(RecordingProblem.NO_SPACE, row.problem)
        }
    }

    @Test
    fun `a live recording on a tight disk schedules with the may-stop-early notice and starts now`() = runTest {
        val rig = LiveRig()
        rig.files.free = RecordingSpacePolicy.MIN_FREE_BYTES + 10L * 1024 * 1024
        val res = rig.repo.scheduleProgramme(channel(), "Show", null, null, NOW - 10 * MIN, NOW + 50 * MIN, NOW)
        val scheduled = assertNotNull(res as? ScheduleResult.Scheduled)
        assertTrue(scheduled.spaceTight)
        assertTrue(scheduled.startsNow)
    }

    @Test
    fun `a live programme in the future does not start now`() = runTest {
        val rig = LiveRig()
        val res = rig.repo.scheduleProgramme(channel(), "Show", null, null, NOW + HOUR, NOW + 2 * HOUR, NOW)
        val scheduled = assertNotNull(res as? ScheduleResult.Scheduled)
        assertEquals(CaptureMode.LIVE, scheduled.schedule.mode)
        assertEquals(false, scheduled.startsNow)
        assertEquals(false, scheduled.spaceTight)
    }

    @Test
    fun `a user stop of a live capture leaves a row the page calls stopped by the user`() = runTest {
        val rig = rigWith(onAir())
        val started = CompletableDeferred<Unit>()
        rig.capturer.onCapture = { _, progress -> progress(5_000_000L, 1L); started.complete(Unit); awaitCancellation() }
        rig.capturer.settleResult = LiveFootage(9_000_000L, 120_000L)
        val fire = async { rig.repo.fire("L", NOW) }
        started.await()
        rig.wall = NOW + 20 * MIN
        rig.repo.cancel("L")
        assertEquals(FireResult.Saved, fire.await())
        val row = rig.repo.state.value.recordings.single()
        assertEquals(RecordingStatus.DONE, row.status)
        assertNull(row.problem)
        val page = com.nuvio.app.features.recordings.api.RecordingsPagePolicy.build(rig.repo.state.value, emptyMap())
        assertTrue(page.rows(com.nuvio.app.features.recordings.api.RecordingSection.DONE).single().stoppedByUser)
    }

    @Test
    fun `a slot that is over with footage on disk finishes the footage instead of failing as missed`() = runTest {
        val rig = rigWith(onAir(attempts = 1, nextTry = NOW + 30_000L))
        rig.capturer.settleResult = LiveFootage(3_000_000L, 40_000L)
        assertEquals(FireResult.Saved, rig.repo.fire("L", NOW + 56 * MIN))
        assertEquals(RecordingStatus.DONE, rig.repo.state.value.recordings.single().status)
        assertTrue(rig.capturer.jobs.isEmpty(), "no new capture for an over slot")
    }

    @Test
    fun `a slot that is over with nothing on disk is missed`() = runTest {
        val rig = rigWith(onAir())
        assertEquals(FireResult.Failed(RecordingProblem.MISSED), rig.repo.fire("L", NOW + 56 * MIN))
    }

    // ---- restart ---------------------------------------------------------------------------

    @Test
    fun `a live capture a dead process left running is finished from its footage when the slot is over`() = runTest {
        val files = TreeFiles()
        val live = onAir(attempts = 2)
        val row = recording("rec:L", status = RecordingStatus.RECORDING, size = 0).copy(scheduleId = "L", fileName = RecordingLiveLayout.fileNameFor("L"), mode = CaptureMode.LIVE)
        files.texts["recordings.json"] = RecordingsCodec.encode(
            RecordingsDocument(schedules = listOf(StoredRow.of(live)), recordings = listOf(StoredRow.of(row)))
                .withLedger(FireLedgerState(listOf(FireEntry("L", FireState.RUNNING, "L#1", 5L)), 2)),
        )
        val rig = LiveRig(files = files)
        rig.capturer.settleResult = LiveFootage(12_000_000L, 200_000L)
        rig.repo.start(NOW)
        val done = rig.repo.state.value.recordings.single()
        assertEquals(RecordingStatus.DONE, done.status, "the last allowed attempt: keep what is there")
        assertEquals(12_000_000L, done.sizeBytes)
        assertEquals(listOf(dirOf()), rig.capturer.settled)
    }

    @Test
    fun `a live capture a dead process left running is retried when attempts remain`() = runTest {
        val files = TreeFiles()
        val row = recording("rec:L", status = RecordingStatus.RECORDING, size = 0).copy(scheduleId = "L", fileName = RecordingLiveLayout.fileNameFor("L"), mode = CaptureMode.LIVE)
        files.texts["recordings.json"] = RecordingsCodec.encode(
            RecordingsDocument(schedules = listOf(StoredRow.of(onAir())), recordings = listOf(StoredRow.of(row)))
                .withLedger(FireLedgerState(listOf(FireEntry("L", FireState.RUNNING, "L#1", 5L)), 2)),
        )
        val rig = LiveRig(files = files)
        rig.capturer.settleResult = LiveFootage(1_000L, 1_000L)
        rig.repo.start(NOW)
        assertTrue(rig.repo.state.value.recordings.isEmpty())
        assertEquals(NOW + RecordingSchedulePolicy.LIVE_RETRY_MS, rig.alarms.armed["L"])
        assertTrue(files.deletedTrees.isEmpty())
    }

    // ---- scheduling ------------------------------------------------------------------------

    @Test
    fun `a programme on a channel without an archive becomes a LIVE schedule on Android`() = runTest {
        val rig = LiveRig()
        val res = rig.repo.scheduleProgramme(channel(), "Show", null, null, NOW + HOUR, NOW + 2 * HOUR, NOW)
        assertEquals(CaptureMode.LIVE, assertNotNull(res as? ScheduleResult.Scheduled).schedule.mode)
    }

    @Test
    fun `a programme that is on air now can be recorded live from now`() = runTest {
        val rig = LiveRig()
        val res = rig.repo.scheduleProgramme(channel(), "Show", null, null, NOW - 10 * MIN, NOW + 50 * MIN, NOW)
        val s = assertNotNull(res as? ScheduleResult.Scheduled).schedule
        assertEquals(CaptureMode.LIVE, s.mode)
        assertEquals(FireResult.Saved, rig.repo.fire(s.id, NOW + 6_000L))
    }

    @Test
    fun `a programme with an archive still saves from catch-up not live`() = runTest {
        val rig = LiveRig()
        val res = rig.repo.scheduleProgramme(channel(catchUp = xtream(3)), "Show", null, null, NOW + HOUR, NOW + 2 * HOUR, NOW)
        assertEquals(CaptureMode.CATCH_UP, assertNotNull(res as? ScheduleResult.Scheduled).schedule.mode)
    }

    @Test
    fun `where live cannot be recorded a channel without an archive is refused`() = runTest {
        val rig = LiveRig(capabilities = RecordingCapabilities.IPHONE)
        assertEquals(ScheduleResult.CannotRecord, rig.repo.scheduleProgramme(channel(), "Show", null, null, NOW + HOUR, NOW + 2 * HOUR, NOW))
    }

    @Test
    fun `overlapping live recordings on one playlist are warned about and both kept`() = runTest {
        val rig = LiveRig()
        rig.repo.scheduleProgramme(channel(key = "a"), "One", null, null, NOW + HOUR, NOW + 2 * HOUR, NOW)
        val second = rig.repo.scheduleProgramme(channel(key = "b"), "Two", null, null, NOW + HOUR + 30 * MIN, NOW + 3 * HOUR, NOW)
        val scheduled = assertNotNull(second as? ScheduleResult.Scheduled)
        assertEquals(listOf("One"), scheduled.clashes.map { it.title })
        assertEquals(2, rig.repo.state.value.schedules.size)
    }

    @Test
    fun `a series on a channel without an archive schedules its future airings live`() = runTest {
        val rig = LiveRig()
        val listings = listOf(
            ListingInfo("The Show", null, NOW + HOUR, NOW + 2 * HOUR),
            ListingInfo("The Show", null, NOW + DAY + HOUR, NOW + DAY + 2 * HOUR),
            ListingInfo("Other", null, NOW + 3 * HOUR, NOW + 4 * HOUR),
        )
        val created = rig.repo.addSeriesRule(channel(), "The Show", 0, listings, NOW)
        assertEquals(2, created.size)
        assertTrue(created.all { it.mode == CaptureMode.LIVE })
    }

    // ---- the capturer's per-recording state (the single-use link budget) ---------------------

    @Test
    fun `the link budget is dropped when a recording is over never while a retry is still coming`() = runTest {
        val rig = rigWith(onAir())
        rig.capturer.onCapture = { _, _ -> LiveCaptureOutcome.Failed(RecordingProblem.STALLED, offline = false, bytesKept = 0) }
        rig.repo.fire("L", NOW)
        assertTrue(rig.capturer.forgotten.isEmpty(), "a retry is coming: it must keep the budget it already spent")
        rig.capturer.onCapture = { job, _ -> LiveCaptureOutcome.Done(10, 10, "${job.dir}/index.m3u8") }
        rig.repo.fire("L", NOW + RecordingSchedulePolicy.LIVE_RETRY_MS)
        assertEquals(listOf("rec:L"), rig.capturer.forgotten)
    }

    @Test
    fun `a give-up a user retry and a delete each start the next run with a fresh budget`() = runTest {
        val rig = rigWith(onAir(attempts = 2))
        rig.capturer.onCapture = { _, _ -> LiveCaptureOutcome.Failed(RecordingProblem.PROVIDER_REFUSED, offline = false, bytesKept = 0) }
        rig.repo.fire("L", NOW)
        assertEquals(1, rig.capturer.forgotten.count { it == "rec:L" }, "given up")
        rig.repo.retry("rec:L", NOW + MIN)
        assertEquals(2, rig.capturer.forgotten.count { it == "rec:L" }, "retried by the user")
        rig.capturer.onCapture = { job, _ -> LiveCaptureOutcome.Done(10, 10, "${job.dir}/index.m3u8") }
        rig.repo.fire("L", NOW + 2 * MIN)
        rig.repo.deleteRecording("rec:L")
        assertEquals(4, rig.capturer.forgotten.count { it == "rec:L" }, "done, then deleted")
    }
}
