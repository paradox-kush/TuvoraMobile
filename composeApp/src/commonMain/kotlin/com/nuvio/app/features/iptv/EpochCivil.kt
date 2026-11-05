package com.nuvio.app.features.iptv

/**
 * Epoch milliseconds <-> civil date and time with pure arithmetic (Hinnant's civil_from_days and
 * days_from_civil). commonMain has no timezone database, so callers shift the instant by an
 * explicit offset first. Extracted from [XtreamCatchUp.formatStart] so the M3U catch-up styles
 * share one implementation.
 */
internal object EpochCivil {

    data class Civil(
        val year: Long,
        val month: Long,
        val day: Long,
        val hour: Long,
        val minute: Long,
        val second: Long,
    )

    /** The civil date and time of [epochMs] shifted by [offsetMs]. */
    fun fromEpochMs(epochMs: Long, offsetMs: Long = 0L): Civil {
        val totalSecs = (epochMs + offsetMs).floorDiv(1_000L)
        val days = totalSecs.floorDiv(86_400L)
        val secsOfDay = totalSecs.mod(86_400L)
        val z = days + 719_468L
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
        val y = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val d = doy - (153L * mp + 2L) / 5L + 1L
        val m = if (mp < 10L) mp + 3L else mp - 9L
        val year = if (m <= 2L) y + 1L else y
        return Civil(
            year = year, month = m, day = d,
            hour = secsOfDay / 3_600L,
            minute = (secsOfDay % 3_600L) / 60L,
            second = secsOfDay % 60L,
        )
    }

    /** Inverse of [fromEpochMs] with a zero offset: the epoch ms of a civil date-time read as UTC. */
    fun toEpochMs(year: Long, month: Long, day: Long, hour: Long, minute: Long, second: Long = 0L): Long {
        val y = if (month <= 2L) year - 1L else year
        val era = (if (y >= 0) y else y - 399L) / 400L
        val yoe = y - era * 400L
        val mp = if (month > 2L) month - 3L else month + 9L
        val doy = (153L * mp + 2L) / 5L + day - 1L
        val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
        val days = era * 146_097L + doe - 719_468L
        return ((days * 24L + hour) * 60L + minute) * 60_000L + second * 1_000L
    }

    fun pad(n: Long, width: Int = 2): String = n.toString().padStart(width, '0')
}
