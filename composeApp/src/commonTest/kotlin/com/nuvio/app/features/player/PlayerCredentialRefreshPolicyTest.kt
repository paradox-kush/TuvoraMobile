package com.nuvio.app.features.player

import com.nuvio.app.features.player.PlayerCredentialRefreshPolicy.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression: a Stalker live link that re-mints a fresh short-TTL URL on every 401 must NOT loop
 * forever. The bounded policy attempts a capped number of consecutive refreshes, then surfaces the
 * error; it only re-arms on a genuinely new stream or sustained healthy playback.
 */
class PlayerCredentialRefreshPolicyTest {

    private fun decide(consecutive: Int, inFlight: Boolean = false, eligible: Boolean = true) =
        PlayerCredentialRefreshPolicy.decide(
            isEligible = eligible,
            refreshInFlight = inFlight,
            consecutiveRefreshes = consecutive,
        )

    @Test
    fun stalkerLiveRemintLoopIsBoundedNotInfinite() {
        // Simulate the exact loop: every refresh mints a NEW unique URL that 401s again ~5s later.
        // With the OLD URL-keyed guard this never terminates. The policy must stop after the cap.
        var attempts = 0
        var attemptsMade = 0
        repeat(1000) {
            when (decide(attempts)) {
                Decision.ATTEMPT -> { attempts++; attemptsMade++ }
                Decision.EXHAUSTED -> return@repeat  // loop broken — error surfaces
                else -> {}
            }
        }
        assertEquals(
            PlayerCredentialRefreshPolicy.MAX_CONSECUTIVE_REFRESHES, attemptsMade,
            "re-mint is capped at MAX_CONSECUTIVE_REFRESHES, not unbounded",
        )
        assertEquals(Decision.EXHAUSTED, decide(attempts), "once the cap is hit the error is surfaced")
    }

    @Test
    fun attemptsUntilCapThenExhausted() {
        assertEquals(Decision.ATTEMPT, decide(0))
        assertEquals(Decision.ATTEMPT, decide(1))
        assertEquals(Decision.ATTEMPT, decide(2))
        assertEquals(Decision.EXHAUSTED, decide(3))
        assertEquals(Decision.EXHAUSTED, decide(4))
    }

    @Test
    fun inFlightRefreshSwallowsErrorWithoutStartingAnother() {
        assertEquals(Decision.IN_FLIGHT, decide(consecutive = 0, inFlight = true))
        // even below the cap, an active job means "don't start a second refresh"
        assertEquals(Decision.IN_FLIGHT, decide(consecutive = 1, inFlight = true))
    }

    @Test
    fun nonIptvNonExpiringSourceIsNotIntercepted() {
        assertEquals(Decision.NOT_ELIGIBLE, decide(consecutive = 0, eligible = false))
    }

    @Test
    fun shortTtlDeathDoesNotCountAsRecoveryButSustainedPlaybackDoes() {
        val baseline = 12_000L
        // link died ~5s after minting -> must NOT re-arm the counter
        assertFalse(PlayerCredentialRefreshPolicy.hasRecovered(positionMs = 17_000L, refreshBaselineMs = baseline))
        // stream kept playing well past the threshold -> recovered, re-arm allowed
        assertTrue(PlayerCredentialRefreshPolicy.hasRecovered(positionMs = 12_000L + 30_000L, refreshBaselineMs = baseline))
        assertTrue(PlayerCredentialRefreshPolicy.hasRecovered(positionMs = 999_000L, refreshBaselineMs = baseline))
    }
}
