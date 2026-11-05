package com.nuvio.app.features.livetv

import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.recordings.NOW
import com.nuvio.app.features.recordings.HOUR
import com.nuvio.app.features.recordings.channel
import com.nuvio.app.features.recordings.xtream
import com.nuvio.app.features.recordings.api.ListingInfo
import com.nuvio.app.features.recordings.api.Recording
import com.nuvio.app.features.recordings.api.RecordingCapabilities
import com.nuvio.app.features.recordings.api.RecordingChannel
import com.nuvio.app.features.recordings.api.RecordingSchedule
import com.nuvio.app.features.recordings.api.RecordingsPort
import com.nuvio.app.features.recordings.api.RecordingsState
import com.nuvio.app.features.recordings.api.ScheduleResult
import com.nuvio.app.features.recordings.api.FireResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

private class SeriesPort : RecordingsPort {
    var addSeriesRuleCalls = 0
    var lastListings: List<ListingInfo> = emptyList()
    override val state: StateFlow<RecordingsState> = MutableStateFlow(RecordingsState())
    override val capabilities = RecordingCapabilities.ANDROID
    override fun playableUri(recording: Recording): String? = null
    override val progress: StateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    override suspend fun start(nowMs: Long) {}
    override suspend fun scheduleProgramme(channel: RecordingChannel, title: String, episode: String?, description: String?, startMs: Long, endMs: Long, nowMs: Long): ScheduleResult = ScheduleResult.CannotRecord
    override suspend fun addSeriesRule(channel: RecordingChannel, title: String, keepLast: Int, listings: List<ListingInfo>, nowMs: Long): List<RecordingSchedule> {
        addSeriesRuleCalls++; lastListings = listings; return emptyList()
    }
    override suspend fun expandSeries(ruleId: String, listings: List<ListingInfo>, nowMs: Long): List<RecordingSchedule> = emptyList()
    override suspend fun removeSeriesRule(ruleId: String, cancelUpcoming: Boolean) {}
    override suspend fun cancel(scheduleId: String) {}
    override suspend fun deleteRecording(id: String) {}
    override suspend fun retry(recordingId: String, nowMs: Long) {}
    override suspend fun rearmAll(nowMs: Long) {}
    override suspend fun overdueIds(nowMs: Long): List<String> = emptyList()
    override suspend fun sweepOverdue(nowMs: Long) {}
    override suspend fun fire(scheduleId: String, nowMs: Long): FireResult = FireResult.Saved
}

class ProgrammeRecordingControllerTest {
    private val programme = XtreamProgram(title = "Show", description = "", startMs = NOW + HOUR, endMs = NOW + 2 * HOUR, nowPlaying = false)
    private val ch = channel(catchUp = xtream())

    @Test
    fun an_unreadable_stored_guide_is_reported_and_no_series_rule_is_saved() = runTest {
        val port = SeriesPort()
        val ctl = ProgrammeRecordingController(port, this, { NOW }, historyProgrammes = { _, _, _ -> throw IllegalStateException("db locked") })
        ctl.recordSeries(ch, programme, emptyList())
        advanceUntilIdle()
        assertEquals(RecordWork.GuideUnreadable, ctl.work.value, "a failed read must not look like 'fewer airings'")
        assertEquals(0, port.addSeriesRuleCalls, "nothing may be saved from a guide that could not be read")
    }

    @Test
    fun a_readable_guide_saves_the_rule_with_every_airing() = runTest {
        val port = SeriesPort()
        val earlier = XtreamProgram(title = "Show", description = "", startMs = NOW - HOUR, endMs = NOW, nowPlaying = false)
        val ctl = ProgrammeRecordingController(port, this, { NOW }, historyProgrammes = { _, _, _ -> listOf(earlier) })
        ctl.recordSeries(ch, programme, emptyList())
        advanceUntilIdle()
        assertEquals(1, port.addSeriesRuleCalls)
        assertEquals(listOf(NOW - HOUR, NOW + HOUR), port.lastListings.map { it.startMs }.sorted())
        assertIs<RecordWork.Confirmed>(ctl.work.value)
    }
}
