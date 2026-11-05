package com.nuvio.app.features.livetv

import com.nuvio.app.features.iptv.XtreamProgram
import kotlinx.coroutines.CancellationException

/** A read of the locally stored guide: it either returned programmes (possibly none) or it failed. Never both. */
internal sealed interface StoredGuideRead {
    data class Ok(val programmes: List<XtreamProgram>) : StoredGuideRead
    data object Failed : StoredGuideRead
}

/** Runs [read]; a failure is [StoredGuideRead.Failed], not an empty guide. Cancellation always propagates. */
internal suspend fun readStoredGuide(read: suspend () -> List<XtreamProgram>): StoredGuideRead =
    try {
        StoredGuideRead.Ok(read())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StoredGuideRead.Failed
    }
