package com.nuvio.app.features.watched

import com.nuvio.app.core.sync.SyncNotAuthenticatedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B216 — signed out means no Nuvio Sync push is attempted (and so nothing is logged as a failure). */
class WatchedNuvioPushPolicyTest {

    @Test
    fun signedOutDoesNotPush() {
        assertFalse(
            WatchedNuvioPushPolicy.shouldPushToNuvioSync(sourceUsesNuvioSync = true, canPush = false),
            "a signed-out device must not call the adapter",
        )
    }

    @Test
    fun signedInPushesWhenNuvioOwnsHistory() {
        assertTrue(WatchedNuvioPushPolicy.shouldPushToNuvioSync(sourceUsesNuvioSync = true, canPush = true))
    }

    @Test
    fun aTrackerOwnedSourceNeverPushesToNuvioSync() {
        assertFalse(WatchedNuvioPushPolicy.shouldPushToNuvioSync(sourceUsesNuvioSync = false, canPush = true))
    }

    @Test
    fun theSignedOutRefusalIsExpectedAndOtherFailuresAreNot() {
        assertTrue(WatchedNuvioPushPolicy.isExpectedSignedOutRefusal(SyncNotAuthenticatedException()))
        assertEquals(
            false,
            WatchedNuvioPushPolicy.isExpectedSignedOutRefusal(IllegalStateException("boom")),
            "a real failure stays an error",
        )
    }
}
