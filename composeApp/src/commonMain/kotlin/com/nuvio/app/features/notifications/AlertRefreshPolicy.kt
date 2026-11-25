package com.nuvio.app.features.notifications

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Why alerts are being planned again. Only the cause matters to [AlertRefreshPolicy]; the repository maps its triggers onto these. */
internal enum class AlertRefreshReason {
    /** The Library or watch progress published a new state. During playback that is once a minute (progress is saved every 60 s). */
    ProgressTick,

    /** A choice changed: switch, quiet hours, cap, timing, pause, private names, permission. Nothing about the shows changed. */
    SettingsChange,

    /** The app was opened (or its alert state first loaded). */
    AppOpened,

    /** A show's alert switch was flipped. */
    ShowsChanged,
}

/** What to do about episode metadata before planning. */
internal sealed interface AlertRefreshDecision {
    /** Ask the add-ons about every show that has alerts on. */
    data object FetchAll : AlertRefreshDecision

    /** Ask only about these shows; every other show keeps the candidates it already has. */
    data class FetchOnly(val keys: Set<String>) : AlertRefreshDecision {
        init { require(keys.isNotEmpty()) { "FetchOnly needs at least one show" } }
    }

    /** Plan from the candidates already held. No request is made. */
    data object ReplanFromCache : AlertRefreshDecision
}

/**
 * Decides when planning alerts may spend a metadata request. Pure: the caller supplies the clock and what it already
 * fetched. Written because the planner used to re-fetch every alerted show on every Library or watch-progress
 * emission, and the player saves progress every minute: N shows meant N requests per minute of playback.
 *
 * Rules, in order:
 * 1. No show has alerts on: nothing to fetch.
 * 2. Nothing fetched yet (first run of the process, or after a profile switch): fetch every show.
 * 3. The last full fetch is [FullRefetchIntervalMs] (6 h) old or older: fetch every show again, unless this is a
 *    progress tick. A tick is the cadence that must stay free, so an app left open past 6 h picks the refresh up at
 *    the next open or settings change instead.
 * 4. A show with alerts on that was not part of any earlier fetch (newly saved, or switched on): fetch just that show,
 *    whatever the reason.
 * 5. Anything else, including every settings change and an app open within 6 h: re-plan from the cache.
 *
 * A show that was asked about and failed counts as asked: it is not asked again until the next full refetch, so an
 * offline phone does not retry on every tick. The caller keeps that show's previous candidates when a fetch fails;
 * a failed fetch is never read as "no episodes".
 */
internal object AlertRefreshPolicy {
    const val FullRefetchIntervalMs: Long = 6L * 60 * 60 * 1000

    fun decide(
        trackedOnKeys: Set<String>,
        lastFetchedKeys: Set<String>,
        lastFetchAtMs: Long?,
        nowMs: Long,
        reason: AlertRefreshReason,
    ): AlertRefreshDecision {
        if (trackedOnKeys.isEmpty()) return AlertRefreshDecision.ReplanFromCache
        if (lastFetchAtMs == null) return AlertRefreshDecision.FetchAll
        val stale = nowMs - lastFetchAtMs >= FullRefetchIntervalMs
        if (stale && reason != AlertRefreshReason.ProgressTick) return AlertRefreshDecision.FetchAll
        val added = trackedOnKeys - lastFetchedKeys
        if (added.isNotEmpty()) return AlertRefreshDecision.FetchOnly(added)
        return AlertRefreshDecision.ReplanFromCache
    }
}

/**
 * The episode candidates found for each show with alerts on, and the bookkeeping [AlertRefreshPolicy] needs. The
 * metadata request is a port ([fetch]) so the cost rule is testable without the network: it returns the show's
 * candidates, or null when the request failed (then the previous candidates stay).
 */
internal class AlertCandidateCache(
    private val fetch: suspend (TrackedFollowedShow) -> List<AlertCandidate>?,
    private val concurrency: Int = 4,
) {
    private val byShow = LinkedHashMap<String, List<AlertCandidate>>()
    private var askedKeys: Set<String> = emptySet()
    private var lastFullFetchAtMs: Long? = null

    /** What [resolve] returns: every candidate of the shows with alerts on, and how many metadata requests it made. */
    class Resolved(val candidates: List<AlertCandidate>, val fetches: Int)

    /** True when [resolve] for these inputs would make a request, so the caller can wait for the add-ons only then. */
    fun wouldFetch(shows: List<TrackedFollowedShow>, nowMs: Long, reason: AlertRefreshReason): Boolean =
        decisionFor(shows, nowMs, reason) !is AlertRefreshDecision.ReplanFromCache

    suspend fun resolve(shows: List<TrackedFollowedShow>, nowMs: Long, reason: AlertRefreshReason): Resolved {
        val byKey = shows.associateBy { buildTrackedShowKey(it.contentType, it.contentId) }
        val toFetch: List<String> = when (val decision = decisionFor(shows, nowMs, reason)) {
            AlertRefreshDecision.FetchAll -> byKey.keys.toList()
            is AlertRefreshDecision.FetchOnly -> decision.keys.filter { it in byKey }
            AlertRefreshDecision.ReplanFromCache -> emptyList()
        }
        val fetchedAll = toFetch.size == byKey.size && byKey.isNotEmpty()

        if (toFetch.isNotEmpty()) {
            val gate = Semaphore(concurrency)
            val results = coroutineScope {
                toFetch.map { key ->
                    async { key to gate.withPermit { fetch(byKey.getValue(key)) } }
                }.awaitAll()
            }
            for ((key, found) in results) {
                // A failure keeps what this show had: the planner must not read it as "no episodes".
                if (found != null) byShow[key] = found
            }
            askedKeys = askedKeys + toFetch
            if (fetchedAll) lastFullFetchAtMs = nowMs
        }

        // Shows that lost their alerts (or were unfollowed) stop contributing and stop being remembered.
        byShow.keys.retainAll(byKey.keys)
        askedKeys = askedKeys intersect byKey.keys
        return Resolved(byKey.keys.flatMap { byShow[it].orEmpty() }, toFetch.size)
    }

    /** A profile switch or sign-out: the candidates belong to the previous profile. */
    fun clear() {
        byShow.clear()
        askedKeys = emptySet()
        lastFullFetchAtMs = null
    }

    private fun decisionFor(shows: List<TrackedFollowedShow>, nowMs: Long, reason: AlertRefreshReason) =
        AlertRefreshPolicy.decide(
            trackedOnKeys = shows.map { buildTrackedShowKey(it.contentType, it.contentId) }.toSet(),
            lastFetchedKeys = askedKeys,
            lastFetchAtMs = lastFullFetchAtMs,
            nowMs = nowMs,
            reason = reason,
        )
}
