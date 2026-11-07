package com.tuvora.tvos.telemetry

/**
 * Support code policy (contract: 8 chars from an unambiguous alphabet, shown XXXX-XXXX). Pure, so it tests
 * without the device; the Mobile lane's SupportCodeGenerator has the same behaviour.
 */
object TvSupportCode {
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val LENGTH = 8

    /** [randomInt] returns a uniform integer in [0, bound) from a secure source. */
    fun generate(randomInt: (bound: Int) -> Int): String =
        buildString { repeat(LENGTH) { append(ALPHABET[randomInt(ALPHABET.length)]) } }

    /** `K7Q29MPX` -> `K7Q2-9MPX`. */
    fun display(code: String): String = if (code.length == LENGTH) code.substring(0, 4) + "-" + code.substring(4) else code

    /**
     * Lookup form: accepts lowercase, spaces and a missing dash. Null when it cannot be a code. The alphabet
     * has no 0/O or 1/I/L at all, so those look-alikes can never be part of a real code and are rejected,
     * not guessed at.
     */
    fun normalize(input: String): String? =
        input.uppercase().filter { it != '-' && !it.isWhitespace() }.takeIf { isValid(it) }

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }
}

/** One report per window (contract: 120 s). Pure; the caller supplies the clock. */
class TvReportRateLimit(private val windowMs: Long = 120_000L) {
    private var lastSentAtMs: Long? = null

    /** 0 when a report may go now, else seconds to wait. */
    fun retryInSec(nowMs: Long): Int {
        val last = lastSentAtMs ?: return 0
        val remaining = last + windowMs - nowMs
        return if (remaining <= 0) 0 else ((remaining + 999) / 1000).toInt()
    }

    fun markSent(nowMs: Long) { lastSentAtMs = nowMs }
}
