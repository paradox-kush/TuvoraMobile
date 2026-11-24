package com.nuvio.app.core.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppForegroundMonitorTest {

    @Test
    fun `foreground triggers foreground work`() {
        assertTrue(shouldTriggerForegroundWork(AppVisibility.Foreground))
    }

    @Test
    fun `background never triggers foreground work`() {
        // Regression (1.5.7 sync degradation): the Flow<Unit> -> Flow<AppVisibility> refactor let
        // Background emissions reach both events() consumers unfiltered. A pull kicked off as the
        // app backgrounds fails (network torn / token flap) and arms SyncManager's per-profile
        // retry-backoff, which then suppresses the NEXT, legitimate foreground pull
        // (requestForegroundPull's force flag bypasses the recency throttle but NOT isInRetryBackoff).
        // Background must never trigger foreground work.
        assertFalse(shouldTriggerForegroundWork(AppVisibility.Background))
    }

    @Test
    fun `foregroundEvents keeps only foreground emissions`() = runBlocking {
        val emitted = flowOf(
            AppVisibility.Foreground,
            AppVisibility.Background,
            AppVisibility.Foreground,
            AppVisibility.Background,
        ).foregroundEvents().toList()

        assertEquals(listOf(AppVisibility.Foreground, AppVisibility.Foreground), emitted)
    }

    /** A platform whose app becomes active while the stream is being set up (iPhone launch). */
    private class LaunchingPlatform {
        var active = false
        private val onActive = mutableListOf<() -> Unit>()
        /** Activation fires just before the stream's observers are in place, as UIKit can at launch. */
        var activateDuringObserve = true

        fun isActive(): Boolean = active

        fun observe(activeCallback: () -> Unit, @Suppress("UNUSED_PARAMETER") resignCallback: () -> Unit): () -> Unit {
            if (activateDuringObserve) {
                activateDuringObserve = false
                active = true
                onActive.forEach { it() } // nobody is listening yet: this notification is gone
            }
            onActive += activeCallback
            return { onActive -= activeCallback }
        }
    }

    @Test
    fun `an activation during setup still reads as foreground`() = runBlocking {
        val platform = LaunchingPlatform()
        val first = appVisibilityEvents(platform::isActive, platform::observe).first()
        assertEquals(AppVisibility.Foreground, first, "the launch-time activation must not be lost")
    }

    @Test
    fun `an inactive app reads as background`() = runBlocking {
        val platform = LaunchingPlatform().apply { activateDuringObserve = false }
        val first = appVisibilityEvents(platform::isActive, platform::observe).first()
        assertEquals(AppVisibility.Background, first, "not active and nothing posted")
    }
}
