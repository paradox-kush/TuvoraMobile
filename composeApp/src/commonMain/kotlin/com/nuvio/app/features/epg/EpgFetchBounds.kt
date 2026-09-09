package com.nuvio.app.features.epg

/** Signals that a streamed EPG response crossed the byte/char cap and must be rejected. */
internal class EpgResponseTooLargeException : RuntimeException()

/**
 * Accumulates a streamed (already-gunzipped) response up to [maxChars], returning null if it is
 * exceeded — the caller then REJECTS the whole fetch and keeps the previous valid generation, never
 * a partial replacement. The cap is checked BEFORE each append, so an oversized/decompression-bomb
 * response never grows the buffer past [maxChars] even if the underlying streamer keeps feeding after
 * the abort throw is swallowed. Pure and inline so it composes with a suspending line streamer while
 * staying unit-testable with a plain producer.
 */
internal inline fun boundedJoin(maxChars: Int, produce: (emit: (String) -> Unit) -> Unit): String? {
    val sb = StringBuilder()
    var overflowed = false
    try {
        produce { chunk ->
            if (!overflowed) {
                if (sb.length.toLong() + chunk.length > maxChars) {
                    overflowed = true
                    throw EpgResponseTooLargeException()
                }
                sb.append(chunk)
            }
        }
    } catch (_: EpgResponseTooLargeException) {
        // best-effort early abort; the `overflowed` guard bounds memory even if this is swallowed
    }
    return if (overflowed) null else sb.toString()
}
