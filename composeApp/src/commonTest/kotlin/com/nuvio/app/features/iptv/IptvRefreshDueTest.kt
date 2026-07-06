package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P3-B auto-refresh due-selection ([dueForRefresh]). Pure time arithmetic, so it's a plain commonTest
 * — no DB, network, or platform. Covers: off (0h), never-refreshed, exactly-at/just-under/just-over
 * the interval, and a backwards clock self-healing.
 */
class IptvRefreshDueTest {

    private val hourMs = 60L * 60L * 1000L
    private val now = 1_000_000_000_000L   // fixed "now"

    @Test
    fun offWhenAutoRefreshHoursIsZeroOrNegative() {
        // autoRefreshHours = 0 is the "Off" setting — never due, even if ancient.
        assertFalse(dueForRefresh(lastRefreshMs = 0L, autoRefreshHours = 0, nowMs = now))
        assertFalse(dueForRefresh(lastRefreshMs = now - 100L * hourMs, autoRefreshHours = 0, nowMs = now))
        assertFalse(dueForRefresh(lastRefreshMs = 0L, autoRefreshHours = -1, nowMs = now))
    }

    @Test
    fun neverRefreshedIsAlwaysDueWhenOn() {
        // No stored timestamp (0/absent) => refresh on the first eligible pass.
        assertTrue(dueForRefresh(lastRefreshMs = 0L, autoRefreshHours = 24, nowMs = now))
        assertTrue(dueForRefresh(lastRefreshMs = -5L, autoRefreshHours = 24, nowMs = now))
    }

    @Test
    fun dueOnlyOnceTheIntervalHasElapsed() {
        val last = now - 24 * hourMs
        // Exactly at the interval boundary => due.
        assertTrue(dueForRefresh(lastRefreshMs = last, autoRefreshHours = 24, nowMs = now))
        // Just past => due.
        assertTrue(dueForRefresh(lastRefreshMs = last - 1, autoRefreshHours = 24, nowMs = now))
        // Just under => not yet.
        assertFalse(dueForRefresh(lastRefreshMs = last + 1, autoRefreshHours = 24, nowMs = now))
        // Refreshed one hour ago on a 24h cadence => nowhere near due.
        assertFalse(dueForRefresh(lastRefreshMs = now - hourMs, autoRefreshHours = 24, nowMs = now))
    }

    @Test
    fun backwardsClockIsTreatedAsDue() {
        // If the device clock jumped backwards (now < last), self-heal by refreshing rather than
        // waiting ~forever for the stale future timestamp to age out.
        assertTrue(dueForRefresh(lastRefreshMs = now + 5 * hourMs, autoRefreshHours = 24, nowMs = now))
    }

    @Test
    fun shortCadenceUsesTheGivenHours() {
        // A 1h cadence: 61 min ago is due, 59 min ago is not.
        assertTrue(dueForRefresh(lastRefreshMs = now - 61L * 60L * 1000L, autoRefreshHours = 1, nowMs = now))
        assertFalse(dueForRefresh(lastRefreshMs = now - 59L * 60L * 1000L, autoRefreshHours = 1, nowMs = now))
    }
}
