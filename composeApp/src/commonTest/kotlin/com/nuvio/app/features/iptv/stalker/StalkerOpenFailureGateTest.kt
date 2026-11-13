package com.nuvio.app.features.iptv.stalker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * commonTest (kotlin.test) - `assertEquals(expected, actual, message)`.
 *
 * The docked Live screen's seam for one attempt's failed open. The iOS poll loop reports the ended snapshot BEFORE
 * the engine's error text (same tick), and Android libmpv reports no text for a live open at all, so an ended
 * snapshot waits a short grace for the text before it counts as "no named cause".
 */
class StalkerOpenFailureGateTest {
    private fun gate(): Pair<StalkerOpenFailureGate, StalkerStreamFallback> {
        val fallback = StalkerStreamFallback("p1", StalkerStreamProfileMemory())
        fallback.onOpened(profileFallbackEligible = true)
        return StalkerOpenFailureGate(fallback) to fallback
    }

    @Test
    fun `an ended snapshot then a named 404 does not switch`() {
        val (gate, fallback) = gate()
        val first = gate.onEndedAtOpen()
        assertTrue(first is StalkerOpenFailureGate.Step.Wait, "a switch is still possible: wait for the text")
        assertEquals(StalkerOpenFailureGate.GRACE_MS, (first as StalkerOpenFailureGate.Step.Wait).scheduleMs, "first wait schedules the grace")
        assertEquals(StalkerOpenFailureGate.Step.Wait(null), gate.onEndedAtOpen(), "later ended ticks keep waiting, no second timer")
        assertEquals(StalkerOpenFailureGate.Step.Proceed, gate.onErrorText("[ffmpeg] http: HTTP error 404 Not Found"), "404: today's failure path")
        assertEquals(StalkerOpenFailureGate.Step.None, gate.onGraceElapsed(), "the text already decided")
        assertEquals(StalkerStreamProfile.NEUTRAL, fallback.profile, "no switch")
    }

    @Test
    fun `an ended snapshot then a named 401 switches`() {
        val (gate, fallback) = gate()
        gate.onEndedAtOpen()
        assertEquals(StalkerOpenFailureGate.Step.Switch, gate.onErrorText("[ffmpeg] http: HTTP error 401 Unauthorized"))
        assertEquals(StalkerStreamProfile.IDENTITY, fallback.profile)
        assertEquals(StalkerOpenFailureGate.Step.None, gate.onGraceElapsed())
    }

    @Test
    fun `an ended snapshot with no text switches when the grace runs out`() {
        val (gate, fallback) = gate()
        gate.onEndedAtOpen()
        assertEquals(StalkerOpenFailureGate.Step.Switch, gate.onGraceElapsed(), "Android libmpv never sends text")
        assertEquals(StalkerStreamProfile.IDENTITY, fallback.profile)
    }

    @Test
    fun `an error text first decides at once`() {
        val (gate, _) = gate()
        assertEquals(StalkerOpenFailureGate.Step.Switch, gate.onErrorText("Source error (HTTP 401)"), "Media3")
        assertEquals(StalkerOpenFailureGate.Step.Proceed, gate.onEndedAtOpen(), "a later ended tick is today's path")
    }

    @Test
    fun `nothing to switch means no wait`() {
        val fallback = StalkerStreamFallback("p1", StalkerStreamProfileMemory())
        fallback.onOpened(profileFallbackEligible = false)
        val gate = StalkerOpenFailureGate(fallback)
        assertEquals(StalkerOpenFailureGate.Step.Proceed, gate.onEndedAtOpen(), "no grace for a stream that cannot switch")
        assertEquals(StalkerOpenFailureGate.Step.Proceed, gate.onErrorText("Source error (HTTP 401)"))
    }

    @Test
    fun `a stale frame from the previous channel does not block the switch`() {
        val fallback = StalkerStreamFallback("p1", StalkerStreamProfileMemory())
        fallback.onFirstFrame() // old surface tick after the zap, before this tune opened its source
        fallback.onOpened(profileFallbackEligible = true)
        assertEquals(StalkerOpenFailureGate.Step.Switch, StalkerOpenFailureGate(fallback).onErrorText("Source error (HTTP 401)"))
    }
}
