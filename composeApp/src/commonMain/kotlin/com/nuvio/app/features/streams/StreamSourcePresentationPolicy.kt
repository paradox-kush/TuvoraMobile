package com.nuvio.app.features.streams

import com.nuvio.app.features.addons.ManagedAddon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where one installed add-on is on its way to becoming a source lane. An add-on contributes a lane only
 * once its manifest has loaded; until then it is neither "missing" nor "empty".
 */
internal enum class AddonLaneReadiness {
    /** Manifest loaded: the add-on is (or can be) a source lane. */
    Ready,

    /** Manifest still being fetched (including the short retry ladder): not a lane yet, but it will be. */
    Pending,

    /** Manifest fetch ended in an error: the add-on is a failed source, not an absent one. */
    Failed,

    /** No manifest, nothing in flight, no error: nothing to say about it. */
    Idle,
}

internal data class AddonReadinessCounts(
    val ready: Int = 0,
    val pending: Int = 0,
    val failed: Int = 0,
)

/** What the stream list does when this load found no source lane to run at all. */
internal enum class NoLaneOutcome {
    /** Some add-ons are still fetching their manifests: wait for them, bounded; never say "missing". */
    WaitForAddons,

    /** The add-ons that could have served this title failed to load (or the wait ran out): Failed, with Retry. */
    AddonsFailed,

    /** Every add-on loaded and none serves this type of title: the genuine "no compatible add-on". */
    NoCompatibleAddons,
}

/** What the stream list body shows. Loaded ([Streams]), Empty and Failed ([Empty] carries the reason) are distinct from [Loading]. */
internal enum class StreamsBody { Loading, Streams, Empty }

internal data class FailedLane(val name: String, val reason: String?)

/**
 * The decisions behind B162 ("missing plugin/add-on" message before sources load) and B186 (a source
 * search that never ends), as pure functions: lane readiness, the no-lane outcome, what the list body
 * shows, which lanes failed, and how stalled lanes end. Same rule as `LivePlaybackFreezePolicy`: the
 * decision is separate from the I/O so it tests without the network.
 *
 * Rule: still-loading is never missing, and a failure is never empty.
 */
internal object StreamSourcePresentationPolicy {
    /**
     * How long a stream list waits for add-on manifests that were still loading when it opened. Covers the
     * manifest retry ladder (2 s + 8 s waits around three fetches) with room for a slow first fetch.
     */
    const val MANIFEST_WAIT_MS = 45_000L

    /**
     * The list's own backstop, measured from the start of the load: above the longest per-lane deadline
     * (the 150 s IPTV safety net), so it only fires for a lane that ignores every lane-level bound (a plugin
     * runtime that never returns). The screen then stops waiting and shows what it has.
     */
    const val LIST_BACKSTOP_MS = 180_000L

    fun readiness(addon: ManagedAddon): AddonLaneReadiness = when {
        addon.manifest != null -> AddonLaneReadiness.Ready
        addon.isRefreshing -> AddonLaneReadiness.Pending
        !addon.errorMessage.isNullOrBlank() -> AddonLaneReadiness.Failed
        else -> AddonLaneReadiness.Idle
    }

    /** Counts the add-ons that are switched on and allowed on this device. */
    fun counts(addons: List<ManagedAddon>): AddonReadinessCounts {
        var ready = 0
        var pending = 0
        var failed = 0
        for (addon in addons) {
            if (!addon.availableOnDevice) continue
            when (readiness(addon)) {
                AddonLaneReadiness.Ready -> ready++
                AddonLaneReadiness.Pending -> pending++
                AddonLaneReadiness.Failed -> failed++
                AddonLaneReadiness.Idle -> Unit
            }
        }
        return AddonReadinessCounts(ready = ready, pending = pending, failed = failed)
    }

    /**
     * Whether Play may open the source list. An add-on that is still loading (or failed to load) is not
     * proof that nothing can play, so the user is let in and the list says what is happening. Store builds
     * never use add-ons as sources, so there pending/failed add-ons prove nothing.
     */
    fun canAttemptPlayback(
        hasUsableSource: Boolean,
        counts: AddonReadinessCounts,
        addonSourcesEnabled: Boolean,
    ): Boolean = hasUsableSource || (addonSourcesEnabled && (counts.pending > 0 || counts.failed > 0))

    /**
     * No lane could be built for this title. [manifestWaitElapsed] is true on the pass that follows the wait
     * for pending manifests: an add-on that is still pending then is treated as failed, so the wait ends.
     */
    fun noLaneOutcome(counts: AddonReadinessCounts, manifestWaitElapsed: Boolean): NoLaneOutcome = when {
        counts.pending > 0 && !manifestWaitElapsed -> NoLaneOutcome.WaitForAddons
        counts.pending > 0 || counts.failed > 0 -> NoLaneOutcome.AddonsFailed
        else -> NoLaneOutcome.NoCompatibleAddons
    }

    /**
     * What the list body shows. A list that is not for this request yet, one that is still loading and
     * one that has not decided anything are all [StreamsBody.Loading]; [StreamsBody.Empty] needs a settled
     * request with a reason.
     */
    fun body(
        hasAnyStreams: Boolean,
        anyLoading: Boolean,
        hasGroups: Boolean,
        emptyReason: StreamsEmptyStateReason?,
        isForThisRequest: Boolean,
    ): StreamsBody = when {
        hasAnyStreams -> StreamsBody.Streams
        !isForThisRequest || anyLoading -> StreamsBody.Loading
        !hasGroups && emptyReason == null -> StreamsBody.Loading
        else -> StreamsBody.Empty
    }

    /** Lanes that ended without streams and with a reason: the ones to name on screen. A pending IPTV catalog is not a failure. */
    fun failedLanes(groups: List<AddonStreamGroup>): List<FailedLane> =
        groups
            .filter { !it.isLoading && it.streams.isEmpty() && !it.iptvPending && !it.error.isNullOrBlank() }
            .map { FailedLane(name = it.addonName, reason = it.error) }

    /** Ends every lane that is still loading as Failed with [stalledMessage]; lanes holding streams just stop loading. */
    fun endStalledLanes(groups: List<AddonStreamGroup>, stalledMessage: String): List<AddonStreamGroup> =
        groups.map { group ->
            when {
                !group.isLoading -> group
                group.streams.isEmpty() -> group.copy(isLoading = false, error = group.error ?: stalledMessage)
                else -> group.copy(isLoading = false)
            }
        }

    /**
     * Waits until no enabled add-on is still fetching its manifest. True when they settled, false when
     * [deadlineMs] ran out first (the caller then treats the stragglers as failed).
     */
    suspend fun awaitAddonsSettled(addons: Flow<List<ManagedAddon>>, deadlineMs: Long = MANIFEST_WAIT_MS): Boolean =
        withTimeoutOrNull(deadlineMs) {
            addons.first { counts(it).pending == 0 }
            true
        } ?: false
}
