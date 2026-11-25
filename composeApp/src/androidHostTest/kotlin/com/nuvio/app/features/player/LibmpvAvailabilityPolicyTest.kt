package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B287: a device whose libmpv will not load must get ExoPlayer, not a crash in mpv's constructor. */
class LibmpvAvailabilityPolicyTest {

    @Test
    fun `libmpv wanted but its libraries do not load falls back`() {
        assertFalse(LibmpvAvailabilityPolicy.usable(wanted = true) { false }, "a failed native load is not usable")
    }

    @Test
    fun `libmpv wanted and loadable is used`() {
        assertTrue(LibmpvAvailabilityPolicy.usable(wanted = true) { true })
    }

    @Test
    fun `the probe is not run when libmpv is not wanted`() {
        var probed = 0
        assertFalse(LibmpvAvailabilityPolicy.usable(wanted = false) { probed++; true })
        assertEquals(0, probed, "ExoPlayer playback never loads mpv's native code")
    }
}
