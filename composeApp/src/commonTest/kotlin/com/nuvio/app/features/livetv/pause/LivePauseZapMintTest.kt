package com.nuvio.app.features.livetv.pause

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A channel change must never mint the new channel's link into the old buffer, nor hand the old channel's link to
 * anything after the zap. The controller's minter resolves "the channel on screen", so a mint that straddles a
 * zap is wrong whichever channel it answers for: the capture's request carries a session-bound minter.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LivePauseZapMintTest {
    private val disk = LivePauseCapabilities(diskBuffer = true, engineBackBuffer = false)

    /** A minter the test completes by hand, standing in for the screen's resolve path. */
    private class HandMinter : LiveLinkMinter {
        var asked = 0
        val pending = mutableListOf<CompletableDeferred<LiveLink>>()
        override suspend fun mint(): LiveLink {
            asked++
            return CompletableDeferred<LiveLink>().also { pending += it }.await()
        }
    }

    @Test
    fun `a mint in flight when the viewer zaps is discarded not delivered to the old capture`() = runTest {
        val minter = HandMinter()
        val r = LivePauseTestRig(backgroundScope, minter = minter)
        r.open("A", disk, singleUse = true)
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        val request = r.capture.requests.single()

        // The capture's reconnect loop asks for a fresh link and waits on the provider.
        var delivered: LiveLink? = null
        var cancelled = false
        backgroundScope.launch {
            try {
                delivered = request.minter.mint()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        runCurrent()
        assertEquals(1, minter.asked)

        r.open("B", disk, singleUse = true)   // zap
        minter.pending.single().complete(LiveLink("http://p/live/B-fresh.ts"))   // the resolve path answered for B
        runCurrent()

        assertEquals(null, delivered, "the new channel's link must not reach the old buffer")
        assertTrue(cancelled, "the old capture's mint ends as cancelled")
    }

    @Test
    fun `a capture of a session that already ended cannot start a mint`() = runTest {
        val minter = HandMinter()
        val r = LivePauseTestRig(backgroundScope, minter = minter)
        r.open("A", disk, singleUse = true)
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        val request = r.capture.requests.single()
        r.open("B", disk, singleUse = true)   // zap while the old capture is still reconnecting
        var cancelled = false
        backgroundScope.launch {
            try {
                request.minter.mint()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        runCurrent()
        assertEquals(0, minter.asked, "no provider call is made for a session that is over")
        assertTrue(cancelled)
    }

    @Test
    fun `the current session still mints through the screen's resolver`() = runTest {
        val minter = HandMinter()
        val r = LivePauseTestRig(backgroundScope, minter = minter)
        r.open("A", disk, singleUse = true)
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        var delivered: LiveLink? = null
        backgroundScope.launch { delivered = r.capture.requests.single().minter.mint() }
        runCurrent()
        minter.pending.single().complete(LiveLink("http://p/live/A-fresh.ts"))
        runCurrent()
        assertEquals("http://p/live/A-fresh.ts", delivered?.url)
    }

    @Test
    fun `a zap while a retry start is pending never opens a capture for the old channel`() = runTest {
        val r = LivePauseTestRig(backgroundScope)
        r.open("A", disk, singleUse = true)
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        r.capture.handles.single().emit(LiveCaptureEvent.Failed(LivePauseFailure.STALLED)); runCurrent()
        assertIs<LivePauseState.Failed>(r.controller.state.value)
        r.controller.retry()   // restarts the capture directly: a start is now pending behind its delay
        assertIs<LivePauseState.Preparing>(r.controller.state.value)
        r.open("B", disk, singleUse = true)   // zap
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, r.capture.requests.size, "no second capture was opened for A")
        assertEquals(LivePauseState.Off, r.controller.state.value)
        assertFalse(r.host.retunes > 0)
    }

    @Test
    fun `a retry that is waiting to re pause does not outlive a zap`() = runTest {
        val r = LivePauseTestRig(backgroundScope)
        r.open("A", disk)
        r.controller.onPause()
        advanceTimeBy(1_500); runCurrent()
        r.capture.handles.single().emit(LiveCaptureEvent.Failed(LivePauseFailure.PROVIDER_REFUSED)); runCurrent()
        r.controller.retry()
        // Retry on a released surface restarts the capture itself; zap to B before it opens.
        r.open("B", disk)
        r.controller.onSnapshot(1_000, isPlaying = true)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(LivePauseState.Off, r.controller.state.value, "B plays live, it is not re-paused for A's Retry")
    }
}
