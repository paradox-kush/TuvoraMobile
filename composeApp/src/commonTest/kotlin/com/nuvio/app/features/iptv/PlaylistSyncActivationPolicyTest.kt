package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B24 — the production activation policy. Pure decision table; the load-bearing case is that an
 * already-adopted profile is NEVER downgraded to the destructive v1 path when v2 is turned off.
 */
class PlaylistSyncActivationPolicyTest {

    private fun act(rollout: PlaylistV2Rollout, debugLocal: Boolean, adopted: Boolean) =
        PlaylistSyncActivationPolicy.activation(rollout, debugLocal, adopted)

    @Test
    fun `enabled activates v2 for any profile`() {
        assertEquals(PlaylistSyncActivation.V2_ACTIVE, act(PlaylistV2Rollout.ENABLED, debugLocal = false, adopted = false))
        assertEquals(PlaylistSyncActivation.V2_ACTIVE, act(PlaylistV2Rollout.ENABLED, debugLocal = false, adopted = true))
    }

    @Test
    fun `debug only activates v2 only under a debug local build`() {
        assertEquals(PlaylistSyncActivation.V2_ACTIVE, act(PlaylistV2Rollout.DEBUG_ONLY, debugLocal = true, adopted = false))
        assertEquals(PlaylistSyncActivation.V1_LEGACY, act(PlaylistV2Rollout.DEBUG_ONLY, debugLocal = false, adopted = false))
    }

    @Test
    fun `an un-adopted profile uses v1 when v2 is off`() {
        assertEquals(PlaylistSyncActivation.V1_LEGACY, act(PlaylistV2Rollout.DISABLED, debugLocal = false, adopted = false))
    }

    @Test
    fun `an adopted profile is paused not downgraded when v2 is disabled`() {
        // The critical safety rule: disabling v2 must NOT push an adopted profile back onto v1.
        assertEquals(PlaylistSyncActivation.V2_PAUSED, act(PlaylistV2Rollout.DISABLED, debugLocal = false, adopted = true))
    }

    @Test
    fun `an adopted profile is paused not downgraded under debug only in a release build`() {
        assertEquals(PlaylistSyncActivation.V2_PAUSED, act(PlaylistV2Rollout.DEBUG_ONLY, debugLocal = false, adopted = true))
    }

    @Test
    fun `rollout parsing maps aliases and falls back on unknown`() {
        assertEquals(PlaylistV2Rollout.ENABLED, PlaylistV2Rollout.parse("enabled"))
        assertEquals(PlaylistV2Rollout.ENABLED, PlaylistV2Rollout.parse("ON"))
        assertEquals(PlaylistV2Rollout.DISABLED, PlaylistV2Rollout.parse("disabled"))
        assertEquals(PlaylistV2Rollout.DEBUG_ONLY, PlaylistV2Rollout.parse("debug_only"))
        assertEquals(PlaylistV2Rollout.DEBUG_ONLY, PlaylistV2Rollout.parse(null))
        assertEquals(PlaylistV2Rollout.DISABLED, PlaylistV2Rollout.parse("garbage", fallback = PlaylistV2Rollout.DISABLED))
    }
}
