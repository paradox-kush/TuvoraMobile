package com.nuvio.app.features.livetv.pause

import com.nuvio.app.core.contracts.MemoryTier

/**
 * Per-instance memory facts for the pause controller, from the shared device tier (no fake `isLowRamDevice` on iOS:
 * each platform reports its own tier honestly through the memory port). Pure.
 *
 * The normal plan mirrors what the Android mpv engine ships per tier (`demuxerBytesFor`: LOW 48+16 MiB, MID/HIGH
 * 64+32 MiB). [LivePauseConfig.engineMemoryBudgetBytes] is what mpv MAY hold for ONE player; LOW gets none, so
 * a low-RAM phone keeps the plain pause and whatever back buffer it already has (the policy never raises below
 * the normal plan).
 */
object LivePauseDeviceBudget {
    private const val MIB = 1024L * 1024L
    /** mpv's own default when no buffer length is chosen: bounded by bytes, not seconds. */
    const val UNBOUNDED_CACHE_SECS = 1_000_000_000L

    fun configFor(tier: MemoryTier, liveBufferSeconds: Int?): LivePauseConfig {
        val normalSecs = (liveBufferSeconds ?: 0).takeIf { it > 0 }?.toLong() ?: UNBOUNDED_CACHE_SECS
        return when (tier) {
            MemoryTier.LOW -> LivePauseConfig(
                lowMemoryTier = true,
                engineMemoryBudgetBytes = 0L,
                engineNormalPlan = LivePausePolicy.EngineCachePlan(48 * MIB, 16 * MIB, normalSecs),
            )
            MemoryTier.MID -> LivePauseConfig(
                engineMemoryBudgetBytes = 160 * MIB,
                engineNormalPlan = LivePausePolicy.EngineCachePlan(64 * MIB, 32 * MIB, normalSecs),
            )
            MemoryTier.HIGH -> LivePauseConfig(
                engineMemoryBudgetBytes = 256 * MIB,
                engineNormalPlan = LivePausePolicy.EngineCachePlan(64 * MIB, 32 * MIB, normalSecs),
            )
        }
    }
}

/** Hand-formatted time text for the pause UI (no String.format: Kotlin/Native). */
object LivePauseUiText {
    /** 65_000 -> "1:05", 3_725_000 -> "1:02:05". */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L)) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val ss = s.toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
    }

    /** "−1:05"; the minus is U+2212. */
    fun behind(ms: Long): String = "−" + clock(ms)

    /** Closer than this to the end counts as live: the chip stays a plain LIVE. */
    const val AT_LIVE_MS = 2_000L
}
