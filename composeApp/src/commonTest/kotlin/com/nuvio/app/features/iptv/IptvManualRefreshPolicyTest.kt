package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals

/** UX179 — what "Refresh now" does per source, the per-playlist cooldown, and when a rebuild counts as landed. */
class IptvManualRefreshPolicyTest {
    @Test
    fun `xtream and m3u url refresh the catalog stalker reloads on open a file with no copy cannot`() {
        assertEquals(IptvManualRefreshPolicy.Mode.REFRESH_CATALOG, IptvManualRefreshPolicy.modeFor(SOURCE_TYPE_XTREAM, hasLocalCopy = false))
        assertEquals(IptvManualRefreshPolicy.Mode.REFRESH_CATALOG, IptvManualRefreshPolicy.modeFor(SOURCE_TYPE_M3U_URL, hasLocalCopy = false))
        assertEquals(IptvManualRefreshPolicy.Mode.RELOAD_ON_OPEN, IptvManualRefreshPolicy.modeFor(SOURCE_TYPE_STALKER, hasLocalCopy = false))
        assertEquals(IptvManualRefreshPolicy.Mode.NEEDS_LOCAL_FILE, IptvManualRefreshPolicy.modeFor(SOURCE_TYPE_M3U_FILE, hasLocalCopy = false))
        assertEquals(IptvManualRefreshPolicy.Mode.REFRESH_CATALOG, IptvManualRefreshPolicy.modeFor(SOURCE_TYPE_M3U_FILE, hasLocalCopy = true))
    }

    @Test
    fun `a second refresh inside the minute waits then may start measured on a monotonic clock`() {
        val c = IptvManualRefreshPolicy.COOLDOWN_MS
        assertEquals(0L, IptvManualRefreshPolicy.cooldownRemainingMs(null))
        assertEquals(c, IptvManualRefreshPolicy.cooldownRemainingMs(0L))
        assertEquals(c - 10_000L, IptvManualRefreshPolicy.cooldownRemainingMs(10_000L))
        assertEquals(0L, IptvManualRefreshPolicy.cooldownRemainingMs(c))
        assertEquals(0L, IptvManualRefreshPolicy.cooldownRemainingMs(c + 5_000L))
        // an impossible negative elapsed time is treated as "just now", never as a longer lock-out
        assertEquals(c, IptvManualRefreshPolicy.cooldownRemainingMs(-9_999L))
    }

    @Test
    fun `a rebuild counts only when every enabled kind's build time changed whatever the clock did`() {
        assertEquals(IptvManualRefreshPolicy.Landed.ALL, IptvManualRefreshPolicy.landed(listOf(40L, 50L), listOf(150L, 160L)))
        assertEquals(IptvManualRefreshPolicy.Landed.ALL, IptvManualRefreshPolicy.landed(listOf(null, null), listOf(150L, 160L)))
        assertEquals(IptvManualRefreshPolicy.Landed.NONE, IptvManualRefreshPolicy.landed(listOf(40L, null), listOf(40L, null)))
        assertEquals(IptvManualRefreshPolicy.Landed.SOME, IptvManualRefreshPolicy.landed(listOf(40L, 50L), listOf(150L, 50L)))
        // the device clock moved BACKWARDS during the refresh: a fresh build time that is numerically older still counts
        assertEquals(IptvManualRefreshPolicy.Landed.ALL, IptvManualRefreshPolicy.landed(listOf(900L), listOf(100L)))
        assertEquals(IptvManualRefreshPolicy.Landed.NONE, IptvManualRefreshPolicy.landed(emptyList(), emptyList()))
    }

    @Test
    fun `the updated line says just now minutes hours or days and nothing when unknown`() {
        val now = 10_000_000_000L
        fun age(agoMs: Long) = IptvManualRefreshPolicy.age(now - agoMs, now)
        assertEquals(null, IptvManualRefreshPolicy.age(null, now))
        assertEquals(null, IptvManualRefreshPolicy.age(0L, now))
        assertEquals(IptvManualRefreshPolicy.Age(IptvManualRefreshPolicy.AgeUnit.JUST_NOW, 0), age(20_000L))
        assertEquals(IptvManualRefreshPolicy.Age(IptvManualRefreshPolicy.AgeUnit.MINUTES, 5), age(5 * 60_000L + 1_000L))
        assertEquals(IptvManualRefreshPolicy.Age(IptvManualRefreshPolicy.AgeUnit.HOURS, 3), age(3 * 3_600_000L + 600_000L))
        assertEquals(IptvManualRefreshPolicy.Age(IptvManualRefreshPolicy.AgeUnit.DAYS, 2), age(2 * 86_400_000L + 3_600_000L))
        // a stored time slightly in the future (clock skew) reads as just now, never negative
        assertEquals(IptvManualRefreshPolicy.Age(IptvManualRefreshPolicy.AgeUnit.JUST_NOW, 0), IptvManualRefreshPolicy.age(now + 5_000L, now))
    }
}
