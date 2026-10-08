package com.nuvio.app.features.livetv

/** A failed live tune must not keep showing a retained frame as if it belonged to the failure. */
internal object LiveTvErrorFramePolicy {
    fun coverVideo(resolveError: Boolean, playbackError: Boolean): Boolean = resolveError || playbackError
}
