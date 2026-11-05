package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.api.LiveCaptureJob
import com.nuvio.app.features.recordings.api.LiveCaptureOutcome
import com.nuvio.app.features.recordings.api.LiveFootage
import com.nuvio.app.features.recordings.api.RecordingLiveCapturer

/** A scripted live capturer: records every job it is given and answers with [onCapture]. */
internal class FakeLiveCapturer(private val onPublished: (String) -> Unit = {}) : RecordingLiveCapturer {
    val jobs = ArrayList<LiveCaptureJob>()
    val settled = ArrayList<String>()
    val forgotten = ArrayList<String>()

    /** What [settle] reports (the footage a stopped or torn-down capture left on disk). */
    var settleResult: LiveFootage? = null

    var onCapture: suspend (LiveCaptureJob, (Long, Long) -> Unit) -> LiveCaptureOutcome = { job, progress ->
        progress(5_000_000L, 60_000L)
        onPublished(job.dir)
        LiveCaptureOutcome.Done(bytes = 500_000_000L, durationMs = 3_600_000L, playlistFile = "${job.dir}/index.m3u8")
    }

    override suspend fun capture(req: LiveCaptureJob, progress: (bytes: Long, durationMs: Long) -> Unit): LiveCaptureOutcome {
        jobs += req
        return onCapture(req, progress)
    }

    override suspend fun settle(dir: String): LiveFootage? {
        settled += dir
        return settleResult
    }

    override fun forget(recordingId: String) {
        forgotten += recordingId
    }
}
