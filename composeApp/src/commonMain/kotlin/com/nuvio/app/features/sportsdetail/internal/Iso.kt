package com.nuvio.app.features.sportsdetail.internal

/** Tiny UTC-only ISO-8601 helpers (no tz database, no dependency): providers send "2026-11-07T12:30Z" / "2026-11-07T03:00:00". */
internal object Iso {
    private val PATTERN = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?)?\s*(Z|[+-]\d{2}:?\d{2})?$""")

    /** Epoch ms of [text] (no offset = UTC), or null if it is not a date we understand. Never throws. */
    fun parseMs(text: String?): Long? {
        val m = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
        val g = m.groupValues
        val days = daysFromCivil(g[1].toInt(), g[2].toInt(), g[3].toInt())
        var ms = days * 86_400_000L + (g[4].ifEmpty { "0" }.toLong() * 3600 + g[5].ifEmpty { "0" }.toLong() * 60 + g[6].ifEmpty { "0" }.toLong()) * 1000
        val off = g[7]
        if (off.isNotEmpty() && off != "Z") {
            val sign = if (off[0] == '-') -1 else 1
            val digits = off.drop(1).replace(":", "")
            ms -= sign * (digits.take(2).toLong() * 60 + digits.drop(2).toLong()) * 60_000L
        }
        return ms
    }

    fun format(ms: Long): String {
        val days = ms.floorDiv(86_400_000L)
        val rem = ms.mod(86_400_000L) / 1000
        val (y, mo, d) = civilFromDays(days)
        return "${pad(y, 4)}-${pad(mo, 2)}-${pad(d, 2)}T${pad(rem / 3600, 2)}:${pad((rem % 3600) / 60, 2)}:${pad(rem % 60, 2)}Z"
    }

    /** "yyyyMMdd" in UTC, for ESPN's `dates=` query. */
    fun yyyymmdd(ms: Long): String {
        val (y, mo, d) = civilFromDays(ms.floorDiv(86_400_000L))
        return pad(y, 4) + pad(mo, 2) + pad(d, 2)
    }

    private fun pad(n: Number, width: Int): String = n.toString().padStart(width, '0')

    fun normalize(text: String?): String? = parseMs(text)?.let(::format)

    // Howard Hinnant's civil-date algorithms.
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = y.floorDiv(400)
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146_097L + doe - 719_468L
    }

    private fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719_468L
        val era = z.floorDiv(146_097L)
        val doe = (z - era * 146_097L).toInt()
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
    }
}
