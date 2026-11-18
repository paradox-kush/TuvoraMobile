package com.nuvio.app.features.player

/**
 * For an engine polled for its state (libmpv on iOS): whether to hold an "ended" snapshot for one poll because the
 * engine's error text has not arrived yet (pure).
 *
 * libmpv flags the end a moment before it publishes the error text. Delivered at once, the ended snapshot fails a
 * live open with NO text — the screen says "The stream did not start" and tears the engine down before the next poll
 * can report "HTTP error 458", so the connection-limit reason and hint never show. Held once (one poll, 250 ms), the
 * text arrives first; a clean end with no error is delivered on the next poll, never later.
 */
object EngineEndedReportPolicy {
    fun holdEnded(isEnded: Boolean, hasError: Boolean, alreadyHeld: Boolean): Boolean =
        isEnded && !hasError && !alreadyHeld
}
