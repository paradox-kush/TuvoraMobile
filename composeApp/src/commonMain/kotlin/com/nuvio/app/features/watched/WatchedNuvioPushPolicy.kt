package com.nuvio.app.features.watched

import com.nuvio.app.core.sync.SyncNotAuthenticatedException

/**
 * B216 — whether a watched-items write goes to Nuvio Sync right now, decided without I/O.
 *
 * Signed out, nothing can sync: the write stays in the dirty set and [WatchedRepository.pushPendingToServer]
 * sends it on the next full sync after sign-in (that full sync is the wake-up — no timer needed). Calling
 * the adapter anyway threw [SyncNotAuthenticatedException] on every mark, and the catch that swallowed it
 * logged it as an error each time ("sync push skipped and left queued").
 */
internal object WatchedNuvioPushPolicy {
    /** True when this write should be sent to Nuvio Sync: Nuvio owns history and a session can push. */
    fun shouldPushToNuvioSync(sourceUsesNuvioSync: Boolean, canPush: Boolean): Boolean =
        sourceUsesNuvioSync && canPush

    /**
     * The session lapsed between the [shouldPushToNuvioSync] check and the call. Expected, not a
     * fault: the item is still dirty, so it is logged quietly rather than as an error.
     */
    fun isExpectedSignedOutRefusal(error: Throwable): Boolean = error is SyncNotAuthenticatedException
}
