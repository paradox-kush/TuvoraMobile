package com.nuvio.app.features.livetv.pause

import com.nuvio.app.features.livetv.pause.LiveCaptureReconnect.Decision
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveCaptureReconnectTest {
    @Test
    fun `backoff doubles from two seconds and caps at eight`() {
        val r = LiveCaptureReconnect(singleUseLink = false, maxAttemptsWithoutProgress = 5)
        assertEquals(Decision.Retry(2_000L, mintFreshLink = false), r.onDrop())
        assertEquals(Decision.Retry(4_000L, mintFreshLink = false), r.onDrop())
        assertEquals(Decision.Retry(8_000L, mintFreshLink = false), r.onDrop())
        assertEquals(Decision.Retry(8_000L, mintFreshLink = false), r.onDrop())
        assertEquals(Decision.Retry(8_000L, mintFreshLink = false), r.onDrop())
    }

    @Test
    fun `it gives up after the maximum drops with no progress`() {
        val r = LiveCaptureReconnect(singleUseLink = false)
        r.onDrop(); r.onDrop(); r.onDrop()
        assertEquals(Decision.GiveUp, r.onDrop())
        assertEquals(3, r.attempts, "a give-up is not another attempt")
    }

    @Test
    fun `progress starts the count over`() {
        val r = LiveCaptureReconnect(singleUseLink = false)
        r.onDrop(); r.onDrop()
        assertEquals(2, r.attempts)
        r.onProgress()
        assertEquals(0, r.attempts)
        assertEquals(Decision.Retry(2_000L, mintFreshLink = false), r.onDrop())
        r.onDrop(); r.onDrop()
        r.onProgress()
        assertEquals(Decision.Retry(2_000L, mintFreshLink = false), r.onDrop())
    }

    @Test
    fun `a single-use link always mints a fresh link`() {
        val r = LiveCaptureReconnect(singleUseLink = true)
        assertEquals(true, r.mintOnFirstOpen)
        assertEquals(Decision.Retry(2_000L, mintFreshLink = true), r.onDrop())
        assertEquals(Decision.Retry(4_000L, mintFreshLink = true), r.onDrop())
        assertEquals(Decision.Retry(8_000L, mintFreshLink = true), r.onDrop())
    }

    @Test
    fun `a reusable link never asks for a mint`() {
        val r = LiveCaptureReconnect(singleUseLink = false)
        assertEquals(false, r.mintOnFirstOpen)
        assertEquals(false, (r.onDrop() as Decision.Retry).mintFreshLink)
    }

    @Test
    fun `a custom maximum is honoured`() {
        val r = LiveCaptureReconnect(singleUseLink = false, maxAttemptsWithoutProgress = 1)
        assertEquals(Decision.Retry(2_000L, mintFreshLink = false), r.onDrop())
        assertEquals(Decision.GiveUp, r.onDrop())
    }
}
