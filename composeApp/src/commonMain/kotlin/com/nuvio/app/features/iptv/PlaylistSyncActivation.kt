package com.nuvio.app.features.iptv

/**
 * B24 — production activation policy for the v2 revision-contract sync path.
 *
 * This replaces the old "debug build AND local endpoint" hard restriction with an explicit,
 * server-controllable rollout that is a PURE, unit-tested decision (no I/O, no Android). The gate
 * that keeps v2 out of an un-migrated production build still exists — it is now the [PlaylistV2Rollout]
 * mode, which a release ships with `DEBUG_ONLY` and a rollout flips to `ENABLED` (or `DISABLED` as a
 * kill switch) via the sync-backend manifest, WITHOUT a client release.
 *
 * The load-bearing safety property is per-profile stickiness: once a profile has committed a v2 write
 * (its local revision advanced), it must NEVER be silently downgraded to the destructive v1
 * full-replace, even if the rollout is later turned off. Turning v2 off for such a profile PAUSES its
 * sync (pending work is retained, nothing is pushed) instead of reverting it to v1.
 */
enum class PlaylistV2Rollout {
    /** v2 active everywhere the backend is reachable (production rollout on). */
    ENABLED,

    /** v2 active only under a debug build pointing at a local/dev backend (pre-rollout default). */
    DEBUG_ONLY,

    /** Kill switch: v2 off. Un-adopted profiles use v1; already-adopted profiles PAUSE (never v1). */
    DISABLED,
    ;

    companion object {
        /** Parse a manifest / config string; unknown or blank falls back to [fallback]. */
        fun parse(raw: String?, fallback: PlaylistV2Rollout = DEBUG_ONLY): PlaylistV2Rollout =
            when (raw?.trim()?.lowercase()) {
                "enabled", "on", "true", "1" -> ENABLED
                "disabled", "off", "false", "0" -> DISABLED
                "debug_only", "debug", "auto" -> DEBUG_ONLY
                else -> fallback
            }
    }
}

/** What sync path a given profile should take right now. */
enum class PlaylistSyncActivation {
    /** Use the v2 revision-contract path (pull-reconcile-push under a revision). */
    V2_ACTIVE,

    /** Use the legacy v1 path (this profile never adopted v2 and the rollout is off for it). */
    V1_LEGACY,

    /**
     * v2 is off but this profile already adopted it — do NOT downgrade to destructive v1. Retain
     * pending ops and push nothing until v2 is re-enabled (or the backend is reachable again).
     */
    V2_PAUSED,
}

object PlaylistSyncActivationPolicy {
    /**
     * @param rollout the effective rollout mode (build default, possibly overridden by the manifest).
     * @param isDebugLocalDev true when this is a debug build pointing at a local/dev endpoint.
     * @param profileHasAdoptedV2 true when this profile has already committed a v2 write locally
     *        (its stored revision advanced, or it holds pending v2 ops).
     */
    fun activation(
        rollout: PlaylistV2Rollout,
        isDebugLocalDev: Boolean,
        profileHasAdoptedV2: Boolean,
    ): PlaylistSyncActivation {
        val globallyOn = when (rollout) {
            PlaylistV2Rollout.ENABLED -> true
            PlaylistV2Rollout.DISABLED -> false
            PlaylistV2Rollout.DEBUG_ONLY -> isDebugLocalDev
        }
        return when {
            globallyOn -> PlaylistSyncActivation.V2_ACTIVE
            // Off, but a profile that already adopted v2 must never fall back to the destructive v1
            // path (that is exactly the data-loss hazard). Pause instead — pending is preserved.
            profileHasAdoptedV2 -> PlaylistSyncActivation.V2_PAUSED
            else -> PlaylistSyncActivation.V1_LEGACY
        }
    }
}
