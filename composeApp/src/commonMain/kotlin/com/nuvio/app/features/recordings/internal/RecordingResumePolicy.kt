package com.nuvio.app.features.recordings.internal

/**
 * Pure decisions about partial files. A `.part` may only be extended by the link that wrote it: any
 * other link (another candidate, a re-minted URL) would splice a different byte stream into it.
 */
internal object RecordingResumePolicy {
    /** [pinnedUrl] is the link recorded beside the partial (null = unknown source = no resume). */
    fun canResume(pinnedUrl: String?, url: String, resumeFromBytes: Long): Boolean =
        resumeFromBytes > 0 && pinnedUrl != null && pinnedUrl == url
}

/** Judges a stream that ended cleanly without saying how long it was (chunked, no Content-Length). */
internal object RecordingCompletenessPolicy {
    private const val MIN_FRACTION_DENOMINATOR = 10L

    /**
     * True when the end cannot be believed: nothing declared the length, an estimate exists, and far
     * less than a tenth of it arrived. The estimate is a bitrate guess, so only a gross shortfall counts.
     */
    fun endedTooEarly(writtenBytes: Long, declaredLength: Long?, estimatedBytes: Long): Boolean =
        declaredLength == null && estimatedBytes > 0 && writtenBytes < estimatedBytes / MIN_FRACTION_DENOMINATOR
}
