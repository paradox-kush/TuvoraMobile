package com.nuvio.app.features.iptv

/**
 * UX179: "Refresh now" on a playlist. The decisions are pure so they test without the network: what a manual
 * refresh DOES for each source type, how long before another may start, and whether a catalog rebuild really
 * landed. (Delta/cost rule: user-initiated, never a loop, and a cooldown so a held OK cannot hammer a provider
 * or its firewall.)
 */
internal object IptvManualRefreshPolicy {
    /** One manual refresh per playlist per minute. */
    const val COOLDOWN_MS = 60_000L

    enum class Mode {
        /** Xtream / M3U URL: rebuild the catalog now (and the guide). */
        REFRESH_CATALOG,

        /** An M3U FILE playlist with no copy on this device: there is nothing to re-read here. */
        NEEDS_LOCAL_FILE,

        /**
         * Stalker: a handshake can evict another device on the same MAC, so it is never refreshed in the background.
         * The hub's own browse reloads the lineup when the playlist is opened.
         */
        RELOAD_ON_OPEN,
    }

    fun modeFor(sourceType: String, hasLocalCopy: Boolean): Mode = when {
        sourceType == SOURCE_TYPE_STALKER -> Mode.RELOAD_ON_OPEN
        sourceType == SOURCE_TYPE_M3U_FILE && !hasLocalCopy -> Mode.NEEDS_LOCAL_FILE
        else -> Mode.REFRESH_CATALOG
    }

    /**
     * Milliseconds until another manual refresh may start; 0 when it may start now. [sinceLastStartMs] is the time
     * elapsed on a MONOTONIC clock since the previous start (null = none yet): wall-clock time can jump backwards, which
     * would lock the button out for as long as the jump.
     */
    fun cooldownRemainingMs(sinceLastStartMs: Long?): Long {
        val elapsed = sinceLastStartMs ?: return 0L
        return (COOLDOWN_MS - elapsed.coerceAtLeast(0L)).coerceIn(0L, COOLDOWN_MS)
    }

    enum class AgeUnit { JUST_NOW, MINUTES, HOURS, DAYS }

    /** "Updated N ago" as a unit + count the UI turns into a localized string; null when the time is unknown. */
    data class Age(val unit: AgeUnit, val count: Long)

    fun age(lastMs: Long?, nowMs: Long): Age? {
        if (lastMs == null || lastMs <= 0L) return null
        val minutes = ((nowMs - lastMs).coerceAtLeast(0L)) / 60_000L
        return when {
            minutes < 1L -> Age(AgeUnit.JUST_NOW, 0L)
            minutes < 60L -> Age(AgeUnit.MINUTES, minutes)
            minutes < 60L * 24L -> Age(AgeUnit.HOURS, minutes / 60L)
            else -> Age(AgeUnit.DAYS, minutes / (60L * 24L))
        }
    }

    enum class Landed { ALL, SOME, NONE }

    /**
     * Whether a rebuild really landed: each enabled catalogue kind's build time must have CHANGED since before the refresh
     * (the builder writes it last, so a crash, a deferral or a failed build leaves the old value). Comparing before with
     * after, not with "now", keeps this right when the device clock moves during the refresh.
     */
    fun landed(builtAtBefore: List<Long?>, builtAtAfter: List<Long?>): Landed {
        if (builtAtAfter.isEmpty() || builtAtBefore.size != builtAtAfter.size) return Landed.NONE
        val changed = builtAtAfter.indices.count { i -> builtAtAfter[i] != null && builtAtAfter[i] != builtAtBefore[i] }
        return when (changed) {
            builtAtAfter.size -> Landed.ALL
            0 -> Landed.NONE
            else -> Landed.SOME
        }
    }
}

/** What a manual refresh ended as. [REFRESHED] is the only success. */
enum class ManualRefreshResult {
    REFRESHED,
    /** Some catalogue kinds updated, others did not. */
    PARTIAL,
    FAILED,
    /** Too soon after the last one: nothing was fetched. */
    COOLDOWN,
    RELOAD_ON_OPEN,
    NEEDS_LOCAL_FILE,
}
