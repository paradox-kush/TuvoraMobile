package com.nuvio.app.features.recordings.internal

import com.nuvio.app.features.recordings.api.RecordingChannel
import com.nuvio.app.features.recordings.api.RecordingProblem

object CatchUpSaveLinks {
    const val RESUME_OVERLAP_MS = 30_000L
    fun candidates(channel: RecordingChannel, fromMs: Long, toMs: Long, nowMs: Long): List<String> = TODO()
    fun resumeCandidates(channel: RecordingChannel, fromMs: Long, doneMs: Long, toMs: Long, nowMs: Long): List<String> = TODO()
    fun isProgressive(url: String): Boolean = TODO()
    fun problemFor(candidates: List<String>): RecordingProblem? = TODO()
}
