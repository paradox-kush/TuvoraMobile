package com.nuvio.app.features.updater

/**
 * Keeps the downloader's read loop from becoming a UI state change per read: a ~60 MB APK reports thousands of times,
 * and every report rebuilt the updater state and woke everything reading it (B274, TV parity).
 *
 * Emits when the whole percent moved and at least [minIntervalMs] passed since the last emission, plus the first
 * report and the final 100%. One instance per download; not thread-safe (the platform downloader reports from one
 * coroutine).
 */
class UpdateProgressThrottle(private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS) {
    private var lastPercent = -1
    private var lastEmitAtMs = 0L

    /** Returns the progress (0..1) to publish now, or null to skip this report. */
    fun accept(downloadedBytes: Long, totalBytes: Long?, nowMs: Long): Float? {
        if (totalBytes == null || totalBytes <= 0L) return null
        val percent = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        val publish = when {
            lastPercent < 0 -> true
            percent == lastPercent -> false
            percent == 100 -> true
            else -> nowMs - lastEmitAtMs >= minIntervalMs
        }
        if (!publish) return null
        lastPercent = percent
        lastEmitAtMs = nowMs
        return percent / 100f
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 250L
    }
}
