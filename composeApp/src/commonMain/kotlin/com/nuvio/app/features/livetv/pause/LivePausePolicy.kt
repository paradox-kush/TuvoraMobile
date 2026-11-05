package com.nuvio.app.features.livetv.pause

import kotlin.math.roundToLong

enum class LivePauseMechanism { PROVIDER_CATCHUP, DISK_BUFFER, ENGINE_BACK_BUFFER, UNAVAILABLE }

data class LivePauseChannel(val isLive: Boolean, val isCatchUpPlayback: Boolean, val hasProviderArchive: Boolean)

data class LivePauseCapabilities(val diskBuffer: Boolean, val engineBackBuffer: Boolean)

/**
 * Every decision of live pause / rewind that does not need the network, the disk or a player, so it tests
 * without any of them (house pattern: `LivePlaybackFreezePolicy`). Platform code feeds it facts (free bytes,
 * bitrate, memory budget) and acts on the verdict; it never decides on its own.
 */
object LivePausePolicy {
    const val DEFAULT_KEEP_MS = 10 * 60_000L

    /** Below this a buffer is not worth starting. */
    const val MIN_KEEP_MS = 60_000L

    /** Never eat the last 300 MB of free space. */
    const val FREE_SPACE_RESERVE_BYTES = 300L * 1024 * 1024

    /** Unknown bitrate: assume HD, conservative. */
    const val FALLBACK_BITRATE_BPS = 8_000_000L

    /** TS/HLS overhead over the elementary bitrate. */
    const val CONTAINER_OVERHEAD = 1.10

    /** First playable piece or Failed. */
    const val BUFFER_START_DEADLINE_MS = 20_000L

    private const val LOW_MEMORY_CAPTURE_CAP_BPS = 6_000_000L
    private const val NORMAL_CAPTURE_CAP_BPS = 15_000_000L

    fun mechanism(channel: LivePauseChannel, caps: LivePauseCapabilities): LivePauseMechanism = when {
        !channel.isLive || channel.isCatchUpPlayback -> LivePauseMechanism.UNAVAILABLE
        channel.hasProviderArchive -> LivePauseMechanism.PROVIDER_CATCHUP
        caps.diskBuffer -> LivePauseMechanism.DISK_BUFFER
        caps.engineBackBuffer -> LivePauseMechanism.ENGINE_BACK_BUFFER
        else -> LivePauseMechanism.UNAVAILABLE
    }

    private fun effectiveBitrate(bitrateBps: Long?): Long =
        if (bitrateBps == null || bitrateBps <= 0L) FALLBACK_BITRATE_BPS else bitrateBps

    fun bytesFor(durationMs: Long, bitrateBps: Long?): Long {
        if (durationMs <= 0L) return 0L
        val bps = effectiveBitrate(bitrateBps)
        return (bps.toDouble() * durationMs / 8_000.0 * CONTAINER_OVERHEAD).roundToLong().coerceAtLeast(0L)
    }

    fun durationFor(bytes: Long, bitrateBps: Long?): Long {
        if (bytes <= 0L) return 0L
        val bps = effectiveBitrate(bitrateBps)
        return (bytes.toDouble() * 8_000.0 / (bps.toDouble() * CONTAINER_OVERHEAD)).roundToLong().coerceAtLeast(0L)
    }

    sealed interface StartDecision {
        data class Start(val keepMs: Long, val budgetBytes: Long) : StartDecision
        data class Refuse(val reason: RefuseReason) : StartDecision
    }

    enum class RefuseReason { NOT_LIVE, UNSUPPORTED, NO_SPACE }

    /** DISK_BUFFER start: shrink keepMs to what fits in (freeBytes - reserve); under [MIN_KEEP_MS] it is refused. */
    fun diskStart(freeBytes: Long, bitrateBps: Long?, requestedKeepMs: Long = DEFAULT_KEEP_MS): StartDecision {
        val usable = freeBytes - FREE_SPACE_RESERVE_BYTES
        if (usable <= 0L) return StartDecision.Refuse(RefuseReason.NO_SPACE)
        val keepMs = minOf(requestedKeepMs, durationFor(usable, bitrateBps))
        if (keepMs < MIN_KEEP_MS) return StartDecision.Refuse(RefuseReason.NO_SPACE)
        return StartDecision.Start(keepMs, minOf(bytesFor(keepMs, bitrateBps), usable))
    }

    enum class SpaceVerdict { WRITE, TRIM_OLDEST, STOP_NO_SPACE }

    /** Checked before EVERY piece/segment write, on every path. */
    fun beforeWrite(freeBytes: Long, nextPieceBytes: Long, bufferedBytes: Long, budgetBytes: Long): SpaceVerdict = when {
        freeBytes - nextPieceBytes < FREE_SPACE_RESERVE_BYTES ->
            if (bufferedBytes > 0L) SpaceVerdict.TRIM_OLDEST else SpaceVerdict.STOP_NO_SPACE
        // Nothing buffered means nothing to trim: a lone oversize piece is written rather than looping.
        bufferedBytes > 0L && bufferedBytes + nextPieceBytes > budgetBytes -> SpaceVerdict.TRIM_OLDEST
        else -> SpaceVerdict.WRITE
    }

    /** How many pieces to drop from the front so the rest is at most [keepMs]; never leaves fewer than [minPieces]. */
    fun piecesToDrop(pieceDurationsMs: List<Long>, keepMs: Long, minPieces: Int = 3): Int {
        var remaining = pieceDurationsMs.sum()
        var count = pieceDurationsMs.size
        var dropped = 0
        while (count > minPieces && remaining > keepMs) {
            remaining -= pieceDurationsMs[dropped]
            dropped++
            count--
        }
        return dropped
    }

    data class EngineCachePlan(val maxBytes: Long, val maxBackBytes: Long, val cacheSecs: Long)

    private fun backBytes(memoryBudgetBytes: Long, bitrateBps: Long?, keepMs: Long): Long =
        minOf(memoryBudgetBytes / 2, bytesFor(keepMs, bitrateBps)).coerceAtLeast(0L)

    /** While watching live (rewind possible): the normal forward cache plus a back buffer. */
    fun engineLivePlan(
        memoryBudgetBytes: Long,
        normal: EngineCachePlan,
        bitrateBps: Long?,
        keepMs: Long = DEFAULT_KEEP_MS,
    ): EngineCachePlan = normal.copy(maxBackBytes = backBytes(memoryBudgetBytes, bitrateBps, keepMs))

    /** While paused: the rest of the budget goes to the forward cache so the demuxer keeps reading ahead. */
    fun enginePausedPlan(
        memoryBudgetBytes: Long,
        normal: EngineCachePlan,
        bitrateBps: Long?,
        keepMs: Long = DEFAULT_KEEP_MS,
    ): EngineCachePlan {
        val back = backBytes(memoryBudgetBytes, bitrateBps, keepMs)
        val fwd = maxOf(memoryBudgetBytes - back, normal.maxBytes)
        return EngineCachePlan(
            maxBytes = fwd,
            maxBackBytes = back,
            cacheSecs = maxOf(durationFor(fwd, bitrateBps) / 1_000L, normal.cacheSecs),
        )
    }

    enum class GoLiveAction { SEEK_TO_EDGE, RETUNE }

    fun goLive(mechanism: LivePauseMechanism, captureHealthy: Boolean): GoLiveAction = when (mechanism) {
        LivePauseMechanism.ENGINE_BACK_BUFFER -> if (captureHealthy) GoLiveAction.SEEK_TO_EDGE else GoLiveAction.RETUNE
        LivePauseMechanism.DISK_BUFFER, LivePauseMechanism.PROVIDER_CATCHUP, LivePauseMechanism.UNAVAILABLE -> GoLiveAction.RETUNE
    }

    data class HlsVariant(val uri: String, val bandwidthBps: Long, val height: Int?)

    fun maxCaptureBitrateBps(lowMemoryTier: Boolean): Long =
        if (lowMemoryTier) LOW_MEMORY_CAPTURE_CAP_BPS else NORMAL_CAPTURE_CAP_BPS

    /** The highest bandwidth at or under the cap; none fits → the lowest; empty → null. */
    fun pickVariant(variants: List<HlsVariant>, maxBitrateBps: Long): HlsVariant? {
        if (variants.isEmpty()) return null
        return variants.filter { it.bandwidthBps <= maxBitrateBps }.maxByOrNull { it.bandwidthBps }
            ?: variants.minByOrNull { it.bandwidthBps }
    }
}
