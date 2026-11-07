package com.nuvio.app.core.telemetry.internal

/**
 * A short code a user can read off the screen and tell us, so we can find their device's telemetry.
 * Random per install, never derived from the analytics distinct id. Pure: the random source is injected.
 */
internal object SupportCodeGenerator {
    /** No 0/O and 1/I/L look-alikes. */
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val LENGTH = 8

    /** [randomBelow] returns a uniformly random int in `0 until bound` (platform secure source in production). */
    fun generate(randomBelow: (Int) -> Int): String =
        buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[randomBelow(ALPHABET.length)]) } }

    fun isValid(code: String?): Boolean = code != null && code.length == LENGTH && code.all { it in ALPHABET }

    /** `K7Q29MPX` -> `K7Q2-9MPX`. */
    fun display(code: String): String =
        if (code.length == LENGTH) code.substring(0, 4) + "-" + code.substring(4) else code

    /** Lookup is forgiving: lowercase, a missing dash, stray spaces. Returns null when it cannot be a code. */
    fun normalize(input: String): String? {
        val cleaned = input.uppercase().filter { it.isLetterOrDigit() }
        return cleaned.takeIf { isValid(it) }
    }
}
