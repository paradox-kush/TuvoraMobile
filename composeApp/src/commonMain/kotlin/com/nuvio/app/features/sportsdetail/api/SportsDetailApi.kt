package com.nuvio.app.features.sportsdetail.api

/**
 * What the UI knows about the game it opened (from the Sports list / radar fixture). [leagueId] is the
 * TheSportsDB league id; [tsdbEventId] is null for an ESPN-only game ("espn:<id>"), in which case
 * [espnEventId] is set.
 */
data class GameRef(
    val gameId: String,
    val leagueId: String,
    val tsdbEventId: String?,
    val espnEventId: String?,
    val homeName: String,
    /** Null for single-entity events (a race weekend, a fight card): [homeName] then holds the event name. */
    val awayName: String?,
    val eventName: String?,
    val startsAtMs: Long,
)

/** BoundedLoad semantics: a load ends Loaded / Empty / Failed, and a failure is never rendered as empty. */
sealed interface DetailLoad {
    data class Loaded(val detail: GameDetail) : DetailLoad
    /** The sources answered and there is no such game / nothing to show. */
    data object Empty : DetailLoad
    /** A source failed (or hit its deadline). Show Retry; do not show "nothing here". */
    data class Failed(val timedOut: Boolean) : DetailLoad
}

/** One open game screen. Call [tick] on the cadence [GameDetailRefreshPolicy] says; the caller owns the lifecycle. */
interface GameDetailSource {
    /**
     * [fetchEvent]: refresh the score/status payloads. [fetchSummary]: refresh the heavier per-game payloads
     * (stats, timeline, lineups, broadcasts). Anything not refreshed is re-assembled from the last fetch, so a
     * live tick that skips the summary still shows the stats. The first tick always fetches what it lacks.
     */
    suspend fun tick(fetchEvent: Boolean = true, fetchSummary: Boolean = true): DetailLoad
}

/** Persists the last good remote mapping (and its ETag) across launches. */
interface MappingStorage {
    fun load(): StoredMapping?
    fun save(value: StoredMapping)

    /** ETag of the last REFUSED remote document, so the same bad push is not downloaded again after a restart. */
    fun loadRejectedEtag(): String? = null
    fun saveRejectedEtag(etag: String?) {}
}

data class StoredMapping(val json: String, val etag: String?)

/** Entry point for the UI lanes. */
interface SportsDetailService {
    fun open(ref: GameRef): GameDetailSource

    /** Polls the remote mapping (ETag). Safe to call on every screen open; failures keep the last good mapping. */
    suspend fun syncMapping()

    /** The mapping's refresh cadence, for [GameDetailRefreshPolicy.plan]. */
    fun refreshConfig(): GameDetailRefreshPolicy.Config
}
