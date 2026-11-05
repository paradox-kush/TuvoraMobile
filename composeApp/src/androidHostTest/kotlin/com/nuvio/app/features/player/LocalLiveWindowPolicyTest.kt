package com.nuvio.app.features.player

import androidx.media3.common.PlaybackException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalLiveWindowPolicyTest {
    @Test
    fun `a local window source starts at the start of its window`() {
        assertEquals(0L, LocalLiveWindowPolicy.startPositionMs(true, null))
        assertEquals(0L, LocalLiveWindowPolicy.startPositionMs(true, 90_000L))
    }

    @Test
    fun `any other source keeps the caller's start`() {
        assertNull(LocalLiveWindowPolicy.startPositionMs(false, null))
        assertEquals(90_000L, LocalLiveWindowPolicy.startPositionMs(false, 90_000L))
    }

    @Test
    fun `behind the live window on a local window source restarts at the window start`() {
        assertTrue(LocalLiveWindowPolicy.restartsAtWindowStart(true, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
    }

    @Test
    fun `behind the live window on a normal live source keeps the live edge recovery`() {
        assertFalse(LocalLiveWindowPolicy.restartsAtWindowStart(false, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
    }

    @Test
    fun `other errors on a local window source are not recovered here`() {
        assertFalse(LocalLiveWindowPolicy.restartsAtWindowStart(true, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
    }

    @Test
    fun `a local window source prepares paused`() {
        assertFalse(LocalLiveWindowPolicy.playWhenReady(true, true))
        assertTrue(LocalLiveWindowPolicy.playWhenReady(false, true))
        assertFalse(LocalLiveWindowPolicy.playWhenReady(false, false))
    }
}
