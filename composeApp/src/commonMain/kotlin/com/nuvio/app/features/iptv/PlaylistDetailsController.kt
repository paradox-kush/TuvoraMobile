package com.nuvio.app.features.iptv

import com.nuvio.app.core.analytics.AnalyticsSink
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.match.MatchKind
import com.nuvio.app.features.iptv.match.XtreamMatchIndex
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class PlaylistDetailsLive(
    val info: XtreamAccountInfo? = null,
    /** The panel's account check. Entered only through [BoundedLoad], so "Checking the account…" always ends. */
    val load: LoadStatus = LoadStatus.Idle,
    val counts: DetailsCounts = DetailsCounts(),
    val hasPanel: Boolean = true,
) {
    /** Still checking: not started yet (the screen opens before load runs) or in flight. */
    val loading: Boolean get() = load is LoadStatus.Loading || load == LoadStatus.Idle && hasPanel
}

/**
 * The slow, I/O half of the details screen: the panel's account answer (cached by
 * [PlaylistAccountInfoStore]) and the local catalog counts. The screen's shape is [ManagedDetailsModel];
 * this only fetches what it is built from. One load per opening of a playlist, nothing on a timer.
 */
internal class PlaylistDetailsController(
    private val store: PlaylistAccountInfoStore = PlaylistAccountInfoStore.shared,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _live = MutableStateFlow(PlaylistDetailsLive())
    val live: StateFlow<PlaylistDetailsLive> = _live.asStateFlow()
    private var job: Job? = null
    private var loadedFor: String? = null

    fun load(account: XtreamAccount) {
        if (loadedFor == account.id && job?.isActive != true && _live.value.load !is LoadStatus.Loading) return
        loadedFor = account.id
        job?.cancel()
        val hasPanel = !account.sourceType.isM3u()
        _live.value = PlaylistDetailsLive(
            info = store.cached(account.id),
            load = if (hasPanel) BoundedLoad.begin(LoadSurface.SETTINGS) else LoadStatus.Empty,
            hasPanel = hasPanel,
        )
        job = scope.launch {
            val counts = BoundedLoad.run(LoadSurface.SETTINGS, report = mapOf("row" to "details_counts")) {
                localCatalogCounts(account)
            }.valueOrNull() ?: DetailsCounts()
            _live.update { it.copy(counts = counts) }
            if (!hasPanel) return@launch
            // Bounded: a panel that never answers ends as "Couldn't reach the provider", not "Checking…" forever.
            val outcome = BoundedLoad.run(LoadSurface.SETTINGS, isEmpty = { it == null }, report = mapOf("row" to "details_account")) {
                store.infoFor(account, force = true, inspect = true)
            }
            _live.update { it.copy(info = outcome.valueOrNull() ?: it.info, load = outcome.status) }
        }
    }

    /** Re-match: stale "not on this provider" verdicts are reset so titles the panel added since are found. */
    fun rematch(account: XtreamAccount) {
        scope.launch { XtreamMatchIndex.distrustNegativeMappings(account.id) }
    }

    private suspend fun localCatalogCounts(account: XtreamAccount): DetailsCounts = when {
        account.sourceType.isM3u() ->
            IptvContentDb.ingestMeta(account.id)?.let { DetailsCounts(it.liveCount, it.vodCount, it.seriesCount) } ?: DetailsCounts()
        // Stalker: the mirrored lineup gives the live count; VOD/series are write-through partials, so skip them.
        account.sourceType == SOURCE_TYPE_STALKER ->
            DetailsCounts(channels = IptvContentDb.ingestMeta(account.id)?.liveCount?.takeIf { it > 0 })
        // Xtream: the match index already holds the full movie/series catalogs (live isn't indexed).
        else -> DetailsCounts(
            movies = XtreamMatchIndex.indexedCount(account.id, MatchKind.MOVIE),
            series = XtreamMatchIndex.indexedCount(account.id, MatchKind.SERIES),
        )
    }
}

/**
 * Detach, and what must follow it: the managed map refreshed and ONE playlist pull. The server logs the
 * detach for the provider; here nothing is deleted — the playlist simply stops being managed.
 */
internal class ManagedPlaylistActions(
    private val api: () -> ProviderSetupApi = { ManagedInfoRefresher.api },
    private val activeProfile: () -> Int = { ProfileRepository.activeProfileId },
    private val pull: suspend (Int) -> Unit = { XtreamSyncParticipant.pullFromServer(it) },
    private val refresh: suspend (Int) -> Boolean = { ManagedInfoRefresher.refresh(it) },
    private val telemetry: ProviderSetupTelemetry = ProviderSetupTelemetry,
    /** App-lifetime: a detach started here outlives the page that started it. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** [detach] on this object's own scope; [onDone] gets whether it was detached. Leaving the page cannot cancel it. */
    fun detachInBackground(playlistKey: String, onDone: (Boolean) -> Unit) {
        scope.launch { onDone(detach(playlistKey)) }
    }

    /** True when the server confirmed the playlist is no longer managed (including "already detached"). */
    suspend fun detach(playlistKey: String): Boolean {
        val profile = activeProfile()
        try {
            api().detach(profile, playlistKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return false
        }
        telemetry.detached()
        refresh(profile)
        pull(profile)
        return true
    }

    companion object {
        val shared = ManagedPlaylistActions()
    }
}
