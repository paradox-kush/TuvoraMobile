package com.nuvio.app.features.watchprogress

import io.ktor.utils.io.errors.IOException

/** A local progress write must not tear down playback. Other failures retain their semantics. */
internal inline fun persistPlaybackProgressOrContinue(onFailure: (IOException) -> Unit, write: () -> Unit) {
    try {
        write()
    } catch (error: IOException) {
        onFailure(error)
    }
}
