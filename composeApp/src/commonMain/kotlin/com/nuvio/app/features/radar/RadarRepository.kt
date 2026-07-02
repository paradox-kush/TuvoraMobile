package com.nuvio.app.features.radar

import com.nuvio.app.features.iptv.XtreamAccountStorage
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

data class RadarUiState(
    val catalog: RadarCatalog = RadarCatalog(),
    val follows: List<RadarFollow> = emptyList(),
    val prefs: RadarPrefs = RadarPrefs(),
    /** leagueId -> fixtures (upcoming + recent past), from the edge function. */
    val fixturesByLeague: Map<String, List<RadarFixture>> = emptyMap(),
    /** Event ids confirmed live by the livescore feed (5 covered sports). */
    val liveEventIds: Set<String> = emptySet(),
    val loadingFixtures: Boolean = false,
) {
    val followedLeagueIds: Set<String> get() = follows.map { it.leagueId }.toSet()

    fun leagueById(id: String): RadarLeague? =
        catalog.categories.asSequence().flatMap { it.leagues }.firstOrNull { it.id == id }

    fun activeFeatured(nowMs: Long): List<RadarFeaturedEvent> =
        catalog.featured.filter { it.isActive(nowMs) }

    fun isLive(fixture: RadarFixture, nowMs: Long): Boolean =
        fixture.id?.let { it in liveEventIds } ?: false || fixture.inferredLive(nowMs)

    /** Fixtures of the given leagues that are live or upcoming, soonest first. */
    fun upcoming(leagueIds: Collection<String>, nowMs: Long, cap: Int = 20): List<RadarFixture> =
        leagueIds.asSequence()
            .flatMap { fixturesByLeague[it].orEmpty() }
            .distinctBy { it.id ?: "${it.leagueId}/${it.event}/${it.ts}" }
            .filter { fx ->
                val start = fx.startEpochMs ?: return@filter false
                start >= nowMs - 4 * 60 * 60 * 1000L || isLive(fx, nowMs)
            }
            .sortedBy { it.startEpochMs }
            .take(cap)
            .toList()
}

/**
 * Sports Centre state: curated catalog, followed leagues + featured-event prefs (persisted
 * per profile + synced), and the fixtures cache (persisted per profile for offline, refreshed
 * throttled through the radar-fixtures edge function). Object-singleton StateFlow like
 * XtreamRepository / XtreamHubRepository.
 */
object RadarRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _uiState = MutableStateFlow(RadarUiState())
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    private var loaded = false
    private var currentProfileId = 1
    private var lastFetchMark: TimeMark? = null
    private val FETCH_TTL = 15.minutes

    fun ensureLoaded() {
        if (loaded) {
            refreshFixtures()
            return
        }
        loaded = true
        currentProfileId = ProfileRepository.activeProfileId
        val catalog = runCatching { json.decodeFromString<RadarCatalog>(RadarCatalogData.JSON) }
            .getOrDefault(RadarCatalog())
        val local = parseLocal(XtreamAccountStorage.loadRadarJson(currentProfileId))
        val cachedFixtures = parseFixtures(XtreamAccountStorage.loadRadarFixturesJson(currentProfileId))
        _uiState.update {
            it.copy(
                catalog = catalog,
                follows = local.follows,
                prefs = local.prefs,
                fixturesByLeague = cachedFixtures?.fixtures ?: emptyMap(),
                liveEventIds = cachedFixtures?.let { r -> liveIds(r) } ?: emptySet(),
            )
        }
        refreshFixtures()
    }

    fun onProfileChanged(profileId: Int) {
        loaded = false
        currentProfileId = profileId
        lastFetchMark = null
        _uiState.value = RadarUiState()
        ensureLoaded()
    }

    /** Leagues worth fetching: follows plus every in-window featured event's league. */
    private fun leaguesToFetch(nowMs: Long): Set<String> {
        val state = _uiState.value
        return state.followedLeagueIds + state.activeFeatured(nowMs).map { it.leagueId }
    }

    fun refreshFixtures(force: Boolean = false) {
        val mark = lastFetchMark
        if (!force && mark != null && mark.elapsedNow() < FETCH_TTL) return
        val nowMs = RadarTime.nowMs()
        val leagues = leaguesToFetch(nowMs)
        if (leagues.isEmpty()) return
        lastFetchMark = TimeSource.Monotonic.markNow()
        val profileAtStart = currentProfileId
        // Livescore only for covered sports actually on screen — 2min server TTL, cheap.
        val sports = leagues.mapNotNull { id -> _uiState.value.leagueById(id)?.sport?.lowercase() }
            .filter { it in RADAR_LIVESCORE_SPORTS }.toSet()
        _uiState.update { it.copy(loadingFixtures = true) }
        scope.launch {
            val response = RadarFixturesClient.fetch(leagues, sports)
            if (profileAtStart != currentProfileId) return@launch
            if (response == null) {
                _uiState.update { it.copy(loadingFixtures = false) }
                // Failed fetch: keep whatever we had (offline shows the cache), retry next TTL.
                lastFetchMark = null
                return@launch
            }
            _uiState.update {
                it.copy(
                    // Merge so leagues the server skipped (budget/partial) keep their cache.
                    fixturesByLeague = it.fixturesByLeague + response.fixtures,
                    liveEventIds = liveIds(response),
                    loadingFixtures = false,
                )
            }
            XtreamAccountStorage.saveRadarFixturesJson(profileAtStart, json.encodeToString(response))
        }
    }

    private fun liveIds(response: RadarFixturesResponse): Set<String> =
        response.livescore.values.asSequence().flatten().mapNotNull { it.eventId }.toSet()

    // --- follows -------------------------------------------------------------

    fun isFollowed(leagueId: String): Boolean = _uiState.value.followedLeagueIds.contains(leagueId)

    fun toggleFollow(league: RadarLeague) {
        _uiState.update { state ->
            val without = state.follows.filterNot { it.leagueId == league.id }
            val follows = if (without.size == state.follows.size) {
                without + RadarFollow(leagueId = league.id, sport = league.sport ?: "", sortOrder = without.size)
            } else {
                without
            }
            state.copy(follows = follows)
        }
        persist()
        refreshFixtures(force = true)
    }

    // --- featured-event prefs --------------------------------------------------

    fun setOptIn(featuredEventId: String, accepted: Boolean) {
        _uiState.update {
            it.copy(
                prefs = it.prefs.copy(
                    featuredEventId = featuredEventId,
                    optInState = if (accepted) RadarOptIn.ACCEPTED else RadarOptIn.DECLINED,
                )
            )
        }
        persist()
        if (accepted) refreshFixtures(force = true)
    }

    fun dismissPromo() {
        _uiState.update { it.copy(prefs = it.prefs.copy(promoDismissed = true)) }
        persist()
    }

    // --- sync ----------------------------------------------------------------

    /** Replace this profile's follows+prefs from a remote pull WITHOUT echoing a push back. */
    fun applyFromRemote(profileId: Int, follows: List<RadarFollow>, prefs: RadarPrefs?) {
        loaded = true
        currentProfileId = profileId
        _uiState.update { state ->
            state.copy(
                catalog = state.catalog.takeIf { it.categories.isNotEmpty() }
                    ?: runCatching { json.decodeFromString<RadarCatalog>(RadarCatalogData.JSON) }.getOrDefault(RadarCatalog()),
                follows = follows,
                prefs = prefs ?: state.prefs,
            )
        }
        XtreamAccountStorage.saveRadarJson(profileId, json.encodeToString(localState()))
        refreshFixtures(force = true)
    }

    private fun localState() = RadarLocalState(follows = _uiState.value.follows, prefs = _uiState.value.prefs)

    private fun persist() {
        XtreamAccountStorage.saveRadarJson(currentProfileId, json.encodeToString(localState()))
        RadarSyncService.triggerPush()
    }

    private fun parseLocal(stored: String?): RadarLocalState {
        if (stored.isNullOrBlank()) return RadarLocalState()
        return runCatching { json.decodeFromString<RadarLocalState>(stored) }.getOrDefault(RadarLocalState())
    }

    private fun parseFixtures(stored: String?): RadarFixturesResponse? {
        if (stored.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<RadarFixturesResponse>(stored) }.getOrNull()
    }
}
