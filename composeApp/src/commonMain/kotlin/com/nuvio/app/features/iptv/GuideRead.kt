package com.nuvio.app.features.iptv

import kotlin.coroutines.cancellation.CancellationException

/**
 * A guide read the screen can live without (the guide falls back to now-and-next). It swallows a FAILURE into
 * [default] but never a cancellation: `runCatching` around suspending work turns the caller leaving into a
 * "fallback" value and lets a cancelled scope carry on. Replay paths that must tell failed from empty do not use
 * this: they call the throwing variants.
 */
internal object GuideRead {
    suspend fun <T> softly(default: T, block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            default
        }
}
