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

/**
 * Where a partial came from: the link, and the validators that link answered with. Stored beside it as
 * `<part>.meta` (three lines; the same layout Android writes), so a resume is only tried against the same
 * link and, when the server gave a validator, conditioned on it with If-Range.
 */
internal data class ResumePin(val url: String, val etag: String?, val lastModified: String?) {
    /** The value for If-Range: the strong ETag when there is one, else the Last-Modified date. */
    val validator: String? get() = etag?.takeIf { !it.startsWith("W/") } ?: lastModified

    fun encode(): String = listOf(url, etag.orEmpty(), lastModified.orEmpty()).joinToString("\n")

    companion object {
        /** Null for missing or unreadable text = "unknown source" = no resume. */
        fun decode(text: String?): ResumePin? {
            val lines = text?.split("\n") ?: return null
            val url = lines.getOrNull(0)?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return ResumePin(url, lines.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }, lines.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() })
        }
    }
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
