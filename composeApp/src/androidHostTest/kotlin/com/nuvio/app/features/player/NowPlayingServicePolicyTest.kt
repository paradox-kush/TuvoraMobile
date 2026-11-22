package com.nuvio.app.features.player

import com.nuvio.app.features.player.NowPlayingServicePolicy.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NowPlayingServicePolicyTest {
    private val policy = NowPlayingServicePolicy()

    @Test
    fun hideWhileStartPendingDefersTheStop() {
        val id = assertNotNull(policy.onPublish())
        assertFalse(policy.onHide(), "a start that onStartCommand has not answered must never be stopped")
        assertEquals(Phase.StartPending(id, stopDeferred = true), policy.phase)
    }

    @Test
    fun promotionAfterADeferredHideStopsTheService() {
        val id = assertNotNull(policy.onPublish())
        policy.onHide()
        assertTrue(policy.onPromoted(id, stillWanted = false))
        assertEquals(Phase.Idle, policy.phase)
    }

    @Test
    fun promotionWhileStillWantedRunsAndALaterHideStops() {
        val id = assertNotNull(policy.onPublish())
        assertFalse(policy.onPromoted(id, stillWanted = true))
        assertEquals(Phase.Running(id), policy.phase)
        assertTrue(policy.onHide())
        assertEquals(Phase.Idle, policy.phase)
    }

    @Test
    fun publishAfterADeferredHideCancelsTheDeferral() {
        val id = assertNotNull(policy.onPublish())
        policy.onHide()
        assertNull(policy.onPublish(), "only one start may be in flight")
        assertFalse(policy.onPromoted(id, stillWanted = true))
        assertEquals(Phase.Running(id), policy.phase)
    }

    @Test
    fun publishWhileStartPendingOrRunningSendsNoSecondStart() {
        val id = assertNotNull(policy.onPublish())
        assertNull(policy.onPublish())
        policy.onPromoted(id, stillWanted = true)
        assertNull(policy.onPublish())
    }

    @Test
    fun promoteFailureReturnsToIdleWithoutRetryAndTheNextPublishStartsAgain() {
        val id = assertNotNull(policy.onPublish())
        policy.onPromoteFailed(id)
        assertEquals(Phase.Idle, policy.phase)
        assertFalse(policy.onServiceDestroyed(id, stillWanted = true), "no automatic restart loop after a failed promotion")
        assertNotNull(policy.onPublish())
    }

    @Test
    fun startThatCouldNotBeSentReturnsToIdle() {
        val id = assertNotNull(policy.onPublish())
        policy.onStartFailed(id)
        assertEquals(Phase.Idle, policy.phase)
        assertTrue(policy.onHide(), "nothing is pending, so stopping is harmless")
    }

    @Test
    fun stalePromotionFromAnEarlierRequestDoesNotTouchAPendingStart() {
        val first = assertNotNull(policy.onPublish())
        policy.onPromoted(first, stillWanted = true)
        policy.onHide()
        val second = assertNotNull(policy.onPublish())
        assertFalse(policy.onPromoted(first, stillWanted = false), "stopping now would cut off the newer start")
        assertEquals(Phase.StartPending(second), policy.phase)
    }

    @Test
    fun untrackedPromotionWhileIdleIsStopped() {
        assertTrue(policy.onPromoted(requestId = 42, stillWanted = false))
        assertEquals(Phase.Idle, policy.phase)
    }

    @Test
    fun staleDestroyIsIgnoredAndAMatchingDestroyRepublishesWhenStillWanted() {
        val first = assertNotNull(policy.onPublish())
        policy.onPromoted(first, stillWanted = true)
        policy.onHide()
        val second = assertNotNull(policy.onPublish())
        assertFalse(policy.onServiceDestroyed(first, stillWanted = true))
        assertEquals(Phase.StartPending(second), policy.phase)

        policy.onPromoted(second, stillWanted = true)
        assertTrue(policy.onServiceDestroyed(second, stillWanted = true))
        assertEquals(Phase.Idle, policy.phase)
    }
}
