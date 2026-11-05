package com.nuvio.app.features.recordings.api

/**
 * A live recording's capture, as the repository sees it (the `api` side of the firewall: no platform and no
 * livetv types). [dir] is the recording's own directory; the capturer owns its layout and writes `index.m3u8`
 * in it. [stopAtMs] is WALL-clock (the programme's end plus the post-roll); the capturer turns the time left
 * into a monotonic delay, so a timezone or NTP step cannot stretch or cut the slot.
 */
data class LiveCaptureJob(
    val recordingId: String,
    val dir: String,
    val stopAtMs: Long,
    /** The snapshot the schedule kept: live line, headers, and whether the provider's links are single-use. */
    val channel: RecordingChannel,
    val bitrateBps: Long? = null,
)

/** What is on disk for a live recording: the sum of its media pieces and their playing time. */
data class LiveFootage(val bytes: Long, val durationMs: Long)

sealed interface LiveCaptureOutcome {
    /** Reached [LiveCaptureJob.stopAtMs]: the playlist is finished (ENDLIST) and every piece is kept. */
    data class Done(val bytes: Long, val durationMs: Long, val playlistFile: String) : LiveCaptureOutcome

    /**
     * The capture ended early. [bytesKept]/[durationKeptMs] are what is on disk across ALL attempts of this
     * recording (0 when nothing was kept). [offline]: the device had no network (does not use up an attempt).
     */
    data class Failed(
        val problem: RecordingProblem,
        val offline: Boolean,
        val bytesKept: Long,
        val durationKeptMs: Long = 0L,
    ) : LiveCaptureOutcome
}

/**
 * Records a channel as it airs. Must rethrow cancellation, but only AFTER it has stopped the capture and
 * published what was recorded (finished playlist, files kept): the repository decides whether that footage is
 * a DONE recording (the user stopped it) or a retry (the system tore it down). Never deletes footage.
 */
interface RecordingLiveCapturer {
    suspend fun capture(req: LiveCaptureJob, progress: (bytes: Long, durationMs: Long) -> Unit): LiveCaptureOutcome

    /**
     * Publishes whatever [dir] holds as one finished, playable `index.m3u8` (rebuilding it from the pieces on
     * disk) and reports it; null when there is nothing to play. Idempotent. Used after a crash, a cancel or a
     * give-up, when no capture is running.
     */
    suspend fun settle(dir: String): LiveFootage?

    /** The recording is over for good (finished, given up on, deleted, retried by the user): drop any per-recording state. */
    fun forget(recordingId: String) {}
}

/** A playable live link (post DoH / User-Agent policy). */
data class RecordingLiveLink(val url: String, val headers: Map<String, String> = emptyMap())

/**
 * Resolves a channel's playable link through the same live-resolve path the guide uses (registry, DoH, the
 * playlist's User-Agent). [mint] = a fresh single-use link is required (Stalker create_link): never a cached one.
 * Returns null when the playlist is gone from this device. Throws when the provider refuses.
 */
fun interface RecordingLinkResolver {
    suspend fun resolve(channel: RecordingChannel, mint: Boolean): RecordingLiveLink?
}
