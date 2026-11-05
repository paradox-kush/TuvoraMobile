package com.nuvio.app.features.recordings.internal

import com.nuvio.app.features.recordings.api.CaptureMode
import com.nuvio.app.features.recordings.api.Recording
import com.nuvio.app.features.recordings.api.RecordingSchedule
import com.nuvio.app.features.recordings.api.SeriesRule

data class ListingInfo(val title: String, val description: String?, val startMs: Long, val endMs: Long, val episodeText: String? = null)

object SeriesExpansion {
    fun scheduleId(rule: SeriesRule, startMs: Long): String = "${rule.id}:${rule.channel.key}:$startMs"
    fun normalizeTitle(title: String): String = TODO()
    fun expand(
        rule: SeriesRule, listings: List<ListingInfo>, existingSchedules: List<RecordingSchedule>, existingRecordings: List<Recording>,
        nowMs: Long, preMs: Long, postMs: Long, modeFor: (startMs: Long, endMs: Long) -> CaptureMode?,
    ): List<RecordingSchedule> = TODO()
    fun keepLastToDelete(rule: SeriesRule, recordings: List<Recording>): List<String> = TODO()
}
