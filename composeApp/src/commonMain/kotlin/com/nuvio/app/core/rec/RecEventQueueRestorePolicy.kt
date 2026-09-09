package com.nuvio.app.core.rec

/**
 * Bounds both the RESTORE (read) and PERSIST (write) of the recommendation-event queue BEFORE any
 * large allocation, so a corrupt or externally-grown queue can never turn into an OOM at startup and
 * the app itself can never write an unbounded blob back.
 *
 * The queue is a diagnostics artifact (fire-and-forget telemetry): safe to bound and drop, never
 * authoritative user data. A 500-record queue is NOT byte-bounded — a single pathological record, or
 * many just-under-cap records, defeats a record-count limit — so this bounds by record count, by
 * per-record size, and by total size, on both directions.
 *
 * Byte vs char are separate limits, deliberately NOT an ASCII equivalence: [MAX_QUEUE_BYTES] bounds
 * a file/stream read on the STORAGE side (before an in-memory string exists); [MAX_QUEUE_CHARS] and
 * [MAX_RECORD_CHARS] bound the in-memory string on the DECODE side. Under UTF-8 a stored byte count
 * is not the string's char count, so a caller must apply the byte limit to storage and the char
 * limits to strings — never assume one stands for the other.
 *
 * Pure and platform-agnostic so it is unit-tested on every runner without storage, JSON, or a clock.
 */
internal object RecEventQueueRestorePolicy {
    /** Char cap for an in-memory string (decode side). ~512 KB; a 500 * small-record queue cannot
     *  approach it. A healthy file measured ~100 KB. */
    const val MAX_QUEUE_CHARS = 512_000

    /** Byte cap for a file/stream read (storage side, before decode). 1 MiB, strictly >=
     *  [MAX_QUEUE_CHARS] so a legitimate in-bound string always fits even at 2 bytes/char, while a
     *  corrupt/huge file is rejected WHILE the stream is consumed — not only by a preceding length
     *  check (which has a time-of-check/time-of-use gap and misses length-unknown streams). */
    const val MAX_QUEUE_BYTES = 1_048_576

    /** Per-record char cap. A single impression record is a few short fields. */
    const val MAX_RECORD_CHARS = 8_192

    data class Bounded(val lines: List<String>, val oversized: Boolean)

    /**
     * Keeps the newest [maxRecords] in-bound lines whose combined size (with separators) stays within
     * [maxTotalChars], preserving on-disk order. Used on BOTH read (restore) and write (persist) so
     * neither can materialize or store an unbounded set. Blank and over-length lines are dropped.
     */
    fun boundLines(
        lines: List<String>,
        maxRecords: Int,
        maxTotalChars: Int = MAX_QUEUE_CHARS,
    ): List<String> {
        if (maxRecords <= 0) return emptyList()
        val kept = ArrayList<String>(minOf(maxRecords, 64))
        var total = 0
        var i = lines.size - 1
        while (i >= 0 && kept.size < maxRecords) {
            val line = lines[i]
            i--
            if (line.isBlank() || line.length > MAX_RECORD_CHARS) continue
            val add = line.length + 1 // + separator
            if (total + add > maxTotalChars) break
            total += add
            kept.add(line)
        }
        kept.reverse()
        return kept
    }

    /**
     * Restore side. Rejects an oversized blob wholesale ([Bounded.oversized]) — never splitting or
     * decoding it — otherwise returns the bounded newest-N lines. Callers reading from a file MUST
     * additionally bound the read itself with [MAX_QUEUE_BYTES] while consuming the stream; this
     * bounds the string that read produced.
     */
    fun select(raw: String?, separator: String, maxRecords: Int): Bounded {
        if (raw.isNullOrBlank()) return Bounded(emptyList(), oversized = false)
        if (raw.length > MAX_QUEUE_CHARS) return Bounded(emptyList(), oversized = true)
        return Bounded(boundLines(raw.split(separator), maxRecords), oversized = false)
    }
}
