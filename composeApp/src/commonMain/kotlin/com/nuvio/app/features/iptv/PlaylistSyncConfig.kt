package com.nuvio.app.features.iptv

import com.nuvio.app.core.build.AppBuildConfig
import com.nuvio.app.core.network.PlaylistSyncRolloutSignal
import com.nuvio.app.core.network.SupabaseConfig

/**
 * B24 — resolves WHETHER and HOW the v2 revision-contract sync path activates.
 *
 * Rollout is layered:
 *   1. [buildDefaultRollout] — the value a build ships with. A release ships `DEBUG_ONLY` (v2 only in
 *      a debug build against a local/dev endpoint — the historical behaviour), so an un-migrated
 *      production build keeps v1 until rollout begins.
 *   2. [manifestRolloutOverride] — a server-driven override applied from the sync-backend manifest, so
 *      the rollout can be flipped to `ENABLED` (or `DISABLED` as a kill switch) WITHOUT a client
 *      release. This is the production rollout lever.
 *
 * The per-profile activation decision (including the "never downgrade an adopted profile to v1" rule)
 * lives in the pure [PlaylistSyncActivationPolicy]; this object only supplies the resolved inputs.
 */
internal object PlaylistSyncConfig {
    /** Ship value. Flip to ENABLED here for a build-baked rollout, or drive it from the manifest. */
    // B24 production activation (2026-09-12): v2 is ENABLED by default in released builds. The backend
    // guard (deployed to prod) enforces safety regardless of client, and adopted profiles are never
    // downgraded to the destructive v1 path (they pause). There is no fork-owned switch manifest host,
    // so activation is build-baked here rather than manifest-driven; a future kill switch would require
    // either a fork manifest host or another release.
    private val buildDefaultRollout: PlaylistV2Rollout = PlaylistV2Rollout.ENABLED

    /** Effective rollout: the server-driven manifest override (via the core signal) wins, else the
     *  build default. */
    val effectiveRollout: PlaylistV2Rollout
        get() = PlaylistSyncRolloutSignal.raw
            ?.let { PlaylistV2Rollout.parse(it, fallback = buildDefaultRollout) }
            ?: buildDefaultRollout

    /** A debug build pointing at a local/dev backend (keeps the developer inner loop on v2). */
    val isDebugLocalDev: Boolean by lazy {
        AppBuildConfig.IS_DEBUG_BUILD && isLocalOrDevEndpoint(SupabaseConfig.URL)
    }

    /** The sync path a profile should take, given whether it has already adopted v2 locally. */
    fun activationFor(profileHasAdoptedV2: Boolean): PlaylistSyncActivation =
        PlaylistSyncActivationPolicy.activation(effectiveRollout, isDebugLocalDev, profileHasAdoptedV2)

    /** True when this profile records durable pending ops (v2 active OR paused-after-adoption — a
     *  paused profile keeps accumulating intent so it syncs cleanly once v2 is re-enabled). */
    fun recordsPending(profileHasAdoptedV2: Boolean): Boolean =
        activationFor(profileHasAdoptedV2) != PlaylistSyncActivation.V1_LEGACY

    /** Back-compat convenience: would a FRESH (never-adopted) profile use v2 right now? Prefer
     *  [activationFor] wherever a profile's adoption state is available. */
    val v2Enabled: Boolean
        get() = activationFor(profileHasAdoptedV2 = false) == PlaylistSyncActivation.V2_ACTIVE

    private fun isLocalOrDevEndpoint(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.contains("10.0.2.2") ||       // Android emulator host loopback
            u.contains("127.0.0.1") ||
            u.contains("://localhost") ||
            u.contains("://192.168.") ||        // LAN dev host
            u.contains("://10.")                // private range dev host
    }
}
