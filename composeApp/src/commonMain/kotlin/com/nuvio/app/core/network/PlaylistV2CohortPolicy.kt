package com.nuvio.app.core.network

/**
 * B24 — the client-side cohort resolver for the IPTV-playlist v2 rollout.
 *
 * v2 activation is NOT a backend authorization decision: the v2 RPCs are available to every
 * authenticated user, and the backend's revision contract + legacy-write guard enforce safety
 * regardless of any client flag. The cohort therefore only decides whether THIS client chooses to use
 * v2 — a pure feature-flag decision, computed here from the manifest and the client's own stable
 * attributes. A client cannot grant itself access by lying: at worst it uses or skips v2 for its own
 * data under the same server-enforced contract.
 *
 * Cohorting is by a stable hash of the account id, so a given account is deterministically in or out,
 * and RAISING [percent] only ADDS accounts (never removes one already enrolled) — the monotonic
 * property a canary needs. Optional platform allow-list narrows further.
 *
 * Returns a resolved mode STRING ("enabled" | "disabled" | "debug_only") so this stays in core with no
 * dependency on the IPTV feature; PlaylistSyncConfig parses it exactly as it parses the raw manifest
 * value.
 */
object PlaylistV2CohortPolicy {

    /** Stable 0..99 bucket for an account id (FNV-1a/32, deterministic across platforms and runs). */
    fun bucketOf(userId: String?): Int {
        if (userId.isNullOrBlank()) return -1 // no account yet -> never in cohort
        var hash = 0x811c9dc5.toInt()
        for (c in userId) {
            hash = hash xor c.code
            hash *= 0x01000193
        }
        val mod = hash % 100
        return if (mod < 0) mod + 100 else mod
    }

    /**
     * Resolve the effective rollout mode for this client.
     *
     * @param rawMode manifest `iptvPlaylistV2` ("enabled" | "disabled" | "debug_only" | null).
     * @param percent manifest `iptvPlaylistV2Percent` (0..100; null ⇒ 100 when enabled).
     * @param platforms manifest `iptvPlaylistV2Platforms` (allow-list; null/empty ⇒ all platforms).
     * @param userId the signed-in account id (null before sign-in).
     * @param platform this client's platform token (e.g. "android", "ios", "androidtv").
     */
    fun resolveMode(
        rawMode: String?,
        percent: Int?,
        platforms: List<String>?,
        userId: String?,
        platform: String,
    ): String {
        val mode = rawMode?.trim()?.lowercase()
        // Only "enabled" is subject to cohorting; disabled/debug_only/absent pass straight through.
        if (mode != "enabled" && mode != "on" && mode != "true" && mode != "1") {
            return rawMode ?: "debug_only"
        }
        // Platform gate.
        val allowed = platforms?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
        if (!allowed.isNullOrEmpty() && platform.trim().lowercase() !in allowed) return "disabled"
        // Percentage gate (default 100 = fully enabled once mode=enabled with no percent).
        val pct = (percent ?: 100).coerceIn(0, 100)
        if (pct <= 0) return "disabled"
        if (pct >= 100) return "enabled"
        val bucket = bucketOf(userId)
        return if (bucket in 0 until pct) "enabled" else "disabled"
    }
}
