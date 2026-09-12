package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B24 — the client-side canary cohort resolver. A global "enabled" is narrowed by an account-bucketed
 * percentage (+ optional platform allow-list); raising the percent only ADDS accounts.
 */
class PlaylistV2CohortPolicyTest {

    private fun resolve(mode: String?, pct: Int? = null, platforms: List<String>? = null,
                        user: String? = "user-abc", platform: String = "android") =
        PlaylistV2CohortPolicy.resolveMode(mode, pct, platforms, user, platform)

    @Test
    fun `disabled and debug_only and null pass straight through`() {
        assertEquals("disabled", resolve("disabled"))
        assertEquals("debug_only", resolve("debug_only"))
        assertEquals("debug_only", resolve(null))
    }

    @Test
    fun `enabled with no percent is full rollout`() {
        assertEquals("enabled", resolve("enabled"))
    }

    @Test
    fun `percent zero disables and percent hundred enables`() {
        assertEquals("disabled", resolve("enabled", pct = 0))
        assertEquals("enabled", resolve("enabled", pct = 100))
    }

    @Test
    fun `platform allow-list gates the cohort`() {
        assertEquals("enabled", resolve("enabled", platforms = listOf("android", "ios")))
        assertEquals("disabled", resolve("enabled", platforms = listOf("ios"), platform = "android"))
        assertEquals("enabled", resolve("enabled", platforms = listOf("ANDROID"))) // case-insensitive
    }

    @Test
    fun `a missing account buckets out of an enabled cohort`() {
        // No signed-in account yet -> not enrolled until sign-in (bucket -1), but 100% still enables.
        assertEquals("disabled", resolve("enabled", pct = 50, user = null))
        assertEquals("enabled", resolve("enabled", pct = 100, user = null))
    }

    @Test
    fun `bucketing is deterministic and stable across calls`() {
        val b1 = PlaylistV2CohortPolicy.bucketOf("account-xyz")
        val b2 = PlaylistV2CohortPolicy.bucketOf("account-xyz")
        assertEquals(b1, b2)
        assertTrue(b1 in 0..99, "bucket in range")
    }

    @Test
    fun `raising the percent only adds accounts never removes one already enrolled`() {
        // For every account, if it is enrolled at percent P it stays enrolled at any P' >= P.
        val users = (0 until 200).map { "acct-$it" }
        for (u in users) {
            var wasIn = false
            for (pct in 0..100) {
                val inNow = PlaylistV2CohortPolicy.resolveMode("enabled", pct, null, u, "android") == "enabled"
                if (wasIn) assertTrue(inNow, "account $u dropped out of the cohort when percent rose to $pct")
                if (inNow) wasIn = true
            }
        }
    }

    @Test
    fun `roughly percent of accounts are enrolled at a given percent`() {
        val n = 2000
        val enrolled = (0 until n).count {
            PlaylistV2CohortPolicy.resolveMode("enabled", 25, null, "user-$it", "android") == "enabled"
        }
        // 25% of 2000 = 500; allow a generous band for hash spread.
        assertTrue(enrolled in 350..650, "expected ~25% enrolled, got $enrolled")
    }
}
