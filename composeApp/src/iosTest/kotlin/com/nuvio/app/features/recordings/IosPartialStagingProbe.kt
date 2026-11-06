package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.internal.IosPartialStaging

/** Reads the link pinned beside a partial the way the saver does. */
internal object IosPartialStagingProbe {
    fun pinUrl(part: String): String? = IosPartialStaging().readPin(part)
}
