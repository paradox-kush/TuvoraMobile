package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.contracts.IptvCatalogPendingException
import com.nuvio.app.core.contracts.StreamSourceGroup
import kotlinx.coroutines.Job

/**
 * One IPTV match source's slot in a title's source list — the code both stream fetchers (the
 * Streams screen's [StreamsRepository] and the player's own source fetcher) run per playlist.
 */
internal object IptvMatchSourceLane {
    private val log = Logger.withTag("IptvMatchLane")

    /**
     * Resolves [source] into its finished (never loading) group. [othersSettled] completes once
     * every addon/plugin load of the same list has finished. B63: a playlist that has not answered
     * within [IptvSourceWaitPolicy]'s budget once nothing else is loading is cut — an empty,
     * error-free group — instead of holding the whole list open for its connect timeout.
     */
    suspend fun resolveGroup(
        source: StreamSourceGroup,
        othersSettled: Job,
        resolve: suspend () -> List<StreamItem>,
    ): AddonStreamGroup =
        // IptvSourceWaitPolicy ends the wait once nothing else is loading (B63), so a panel may outwait
        // add-ons and plugins. The safety deadline is for the case where "the others" never settle
        // (a plugin lane parked by a paused search): the chip must still end.
        SourceLaneGuard.run(deadlineMs = SourceLaneGuard.IPTV_SAFETY_DEADLINE_MS) { IptvSourceWaitPolicy.await(othersSettled) { resolve() } }.fold(
            onSuccess = { resolved ->
                val streams = resolved ?: emptyList<StreamItem>().also {
                    log.w { "Xtream match for ${source.addonName} cut after ${IptvSourceWaitPolicy.PLAYLIST_BUDGET_MS} ms (playlist not answering)" }
                }
                log.d { "Xtream match: ${streams.size} streams from ${source.addonName}" }
                AddonStreamGroup(
                    addonName = source.addonName,
                    addonId = source.sourceId,
                    streams = streams,
                    isLoading = false,
                    // Cut before it answered: still loading, not "nothing on this playlist".
                    iptvPending = resolved == null,
                )
            },
            onFailure = { err ->
                if (err is SourceLaneTimeoutException) {
                    // Safety deadline: same outcome as a budget cut ("still loading"), not a fetch failure.
                    log.w { "Xtream match for ${source.addonName} cut at the safety deadline" }
                    return@fold AddonStreamGroup(
                        addonName = source.addonName,
                        addonId = source.sourceId,
                        streams = emptyList(),
                        isLoading = false,
                        iptvPending = true,
                    )
                }
                if (err is IptvCatalogPendingException) {
                    log.i { "Xtream match for ${source.addonName}: catalog still loading" }
                    return@fold AddonStreamGroup(
                        addonName = source.addonName,
                        addonId = source.sourceId,
                        streams = emptyList(),
                        isLoading = false,
                        iptvPending = true,
                    )
                }
                log.w(err) { "Xtream match failed for ${source.addonName}" }
                AddonStreamGroup(
                    addonName = source.addonName,
                    addonId = source.sourceId,
                    streams = emptyList(),
                    isLoading = false,
                    error = err.laneFailureMessage(),
                )
            },
        )
}
