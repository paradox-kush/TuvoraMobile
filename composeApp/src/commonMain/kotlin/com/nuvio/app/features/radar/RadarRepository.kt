package com.nuvio.app.features.radar

import co.touchlab.kermit.Logger

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
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val RADAR_PRE_KICKOFF_GRACE_MS = 10L * 60 * 1000

data class RadarUiState(
    val catalog: RadarCatalog = RadarCatalog(),
    val follows: List<RadarFollow> = emptyList(),
    val prefs: RadarPrefs = RadarPrefs(),
    /** leagueId -> fixtures (upcoming + recent past), from the edge function. */
    val fixturesByLeague: Map<String, List<RadarFixture>> = emptyMap(),
    /** Event ids confirmed live by the livescore feed (5 covered sports). */
    val liveEventIds: Set<String> = emptySet(),
    /** eventId -> latest livescore row (in-progress score + minute) for covered sports. */
    val liveScores: Map<String, RadarLiveScore> = emptyMap(),
    /** Sports the last FRESH fetch returned livescore data for — the feed is authoritative
     *  for these (empty set after a cold-start disk load: stale feed data must not
     *  suppress the time-window inference). */
    val livescoreSports: Set<String> = emptySet(),
    val loadingFixtures: Boolean = false,
    /** Followed clubs (always user-added — there is no published team catalog). */
    val teamFollows: List<RadarTeamFollow> = emptyList(),
    /** teamId -> that club's own schedule, from the team lane of radar-fixtures. */
    val fixturesByTeam: Map<String, List<RadarFixture>> = emptyMap(),
) {
    val followedLeagueIds: Set<String> get() = follows.map { it.leagueId }.toSet()
    val followedTeamIds: Set<String> get() = teamFollows.map { it.teamId }.toSet()

    /** Followed clubs in follow order, as the picker/search shape. */
    val followedTeams: List<RadarTeam> get() = teamFollows.sortedBy { it.sortOrder }.map { it.asTeam() }

    /** Fixtures of the given clubs that are live or upcoming, soonest first. */
    fun upcomingForTeams(teamIds: Collection<String>, nowMs: Long, cap: Int = 20): List<RadarFixture> =
        teamIds.asSequence()
            .flatMap { fixturesByTeam[it].orEmpty() }
            .distinctBy { it.id ?: "${it.leagueId}/${it.event}/${it.ts}" }
            .filter { fx ->
                val start = fx.startEpochMs ?: return@filter false
                start >= nowMs - 4 * 60 * 60 * 1000L || isLive(fx, nowMs)
            }
            .sortedBy { it.startEpochMs }
            .take(cap)
            .toList()

    /** Finished/started fixtures of one club, most recent first. */
    fun recentForTeam(teamId: String, nowMs: Long, cap: Int = 15): List<RadarFixture> =
        fixturesByTeam[teamId].orEmpty()
            .distinctBy { it.id ?: "${it.leagueId}/${it.event}/${it.ts}" }
            .filter { fx ->
                val start = fx.startEpochMs ?: return@filter false
                start < nowMs && !isLive(fx, nowMs)
            }
            .sortedByDescending { it.startEpochMs }
            .take(cap)

    /**
     * Catalog first, then the user's own follows — a league someone added themselves isn't in
     * the catalog, and everything downstream resolves names and badges through here.
     */
    fun leagueById(id: String): RadarLeague? =
        catalog.categories.asSequence().flatMap { it.leagues }.firstOrNull { it.id == id }
            ?: follows.firstOrNull { it.leagueId == id }?.asLeague()

    /** Leagues the user added that aren't in the published catalog, in follow order. */
    val customLeagues: List<RadarLeague>
        get() = follows.sortedBy { it.sortOrder }.mapNotNull { it.asLeague() }

    fun activeFeatured(nowMs: Long): List<RadarFeaturedEvent> =
        catalog.featured.filter { it.isActive(nowMs) }

    fun isLive(fixture: RadarFixture, nowMs: Long): Boolean {
        // A finished / postponed / cancelled fixture is never live, however stale the feed's live
        // set is — the strStatus the badge logic used to ignore (inventory T1/BK1: a Sunday NFL
        // game reading LIVE on Monday because its id lingered in the stale-served live set).
        if (fixture.isFinishedOrOff) return false
        // Hard plausibility cap: even a feed-covered sport cannot read LIVE past a sport-typical
        // window plus grace — the backstop for a stale live-set entry whose finished status we
        // never received.
        fixture.startEpochMs?.let { start ->
            // Before kickoff a fixture is never live, even when the livescore feed already lists
            // its id — feeds pre-populate the live set for imminent games. Symmetric to the
            // max-window cap; the grace absorbs feed/clock skew. B49 (unstarted games badged LIVE).
            if (nowMs < start - RADAR_PRE_KICKOFF_GRACE_MS) return false
            if (nowMs >= start + fixture.maxLiveWindowMs()) return false
        }
        val feedConfirmed = fixture.id?.let { it in liveEventIds } == true
        val sport = fixture.sport?.lowercase()
        // Fresh feed coverage for this sport -> the feed decides; otherwise infer from kick-off.
        return if (sport != null && sport in livescoreSports) feedConfirmed
        else feedConfirmed || fixture.inferredLive(nowMs)
    }

    /** Finished/started fixtures of one league, most recent first (scores when the API has them). */
    fun recent(leagueId: String, nowMs: Long, cap: Int = 15): List<RadarFixture> =
        fixturesByLeague[leagueId].orEmpty()
            .distinctBy { it.id ?: "${it.leagueId}/${it.event}/${it.ts}" }
            .filter { fx ->
                val start = fx.startEpochMs ?: return@filter false
                start < nowMs && !isLive(fx, nowMs)
            }
            .sortedByDescending { it.startEpochMs }
            .take(cap)

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
    /** Test-injectable clock (S2). Production = RadarSystemClock. */
    internal var clock: RadarClock = RadarSystemClock

    private val log = Logger.withTag("RadarRepository")

    // Leagues change on the order of weeks; this only needs to be faster than a release.
    private const val CATALOG_TTL_MS = 6 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _uiState = MutableStateFlow(RadarUiState())
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    private var loaded = false
    private var currentProfileId = 1
    private var lastFetchMark: TimeMark? = null
    private val FETCH_TTL = 15.minutes

    fun ensureLoaded() {
        // The match sheet reads the EPG mirror; refresh it (12h TTL, no-op when fresh) so
        // the EPG tier is warm by the time a fixture is opened.
        scope.launch { com.nuvio.app.features.epg.EpgMirrorRepository.ensureFresh() }
        if (loaded) {
            refreshFixtures()
            return
        }
        loaded = true
        currentProfileId = ProfileRepository.activeProfileId
        // Bundled copy first so the tab is never empty and never waits on the network; the
        // cached publish layers on top, and a fresh fetch layers on top of that.
        val bundled = runCatching { json.decodeFromString<RadarCatalog>(RadarCatalogData.JSON) }
            .getOrDefault(RadarCatalog())
        val cachedCatalog = runCatching {
            XtreamAccountStorage.loadRadarCatalogJson(currentProfileId)
                ?.let { json.decodeFromString<RadarCachedCatalog>(it) }
        }.getOrNull()
        val catalog = cachedCatalog?.catalog?.takeIf { it.isUsable() } ?: bundled
        scope.launch { refreshCatalog(cachedCatalog?.fetchedAtMs) }
        val local = parseLocal(XtreamAccountStorage.loadRadarJson(currentProfileId))
        val cachedFixtures = parseFixtures(XtreamAccountStorage.loadRadarFixturesJson(currentProfileId))
        _uiState.update {
            it.copy(
                catalog = catalog,
                follows = local.follows,
                prefs = local.prefs,
                teamFollows = local.teams,
                fixturesByLeague = cachedFixtures?.fixtures ?: emptyMap(),
                fixturesByTeam = cachedFixtures?.teamFixtures ?: emptyMap(),
                liveEventIds = cachedFixtures?.let { r -> liveIds(r) } ?: emptySet(),
                liveScores = cachedFixtures?.let { r -> scoresById(r) } ?: emptyMap(),
            )
        }
        refreshFixtures()
    }


    /**
     * Pulls the published catalog when the cached copy is older than [CATALOG_TTL_MS].
     *
     * Deliberately quiet: a failed fetch, an unpublished catalog (payload null), or a
     * document that doesn't validate all leave whatever is already loaded in place. The only
     * way this changes what the user sees is a well-formed publish.
     */
    private suspend fun refreshCatalog(cachedAtMs: Long?) {
        val ageMs = cachedAtMs?.let { clock.nowMs() - it } ?: Long.MAX_VALUE
        if (ageMs < CATALOG_TTL_MS) return
        val envelope = RadarCatalogClient.fetch() ?: return
        val remote = envelope.payload ?: return
        if (!remote.isUsable()) {
            log.w { "published catalog v${envelope.version} failed validation; keeping current" }
            return
        }
        XtreamAccountStorage.saveRadarCatalogJson(
            currentProfileId,
            json.encodeToString(RadarCachedCatalog(envelope.version, remote, clock.nowMs())),
        )
        _uiState.update { it.copy(catalog = remote) }
        log.i { "adopted published catalog v${envelope.version}" }
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
        val nowMs = clock.nowMs()
        val leagues = leaguesToFetch(nowMs)
        val teams = _uiState.value.followedTeamIds
        if (leagues.isEmpty() && teams.isEmpty()) return
        lastFetchMark = TimeSource.Monotonic.markNow()
        val profileAtStart = currentProfileId
        // Livescore only for covered sports actually on screen — 2min server TTL, cheap.
        val sports = (
            leagues.mapNotNull { id -> _uiState.value.leagueById(id)?.sport?.lowercase() } +
                // A followed club may be the only reason a sport is on screen at all.
                _uiState.value.teamFollows.map { it.sport.lowercase() }
            ).filter { it in RADAR_LIVESCORE_SPORTS }.toSet()
        _uiState.update { it.copy(loadingFixtures = true) }
        scope.launch {
            val response = RadarFixturesClient.fetch(leagues, sports, teams)
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
                    fixturesByTeam = it.fixturesByTeam + response.teamFixtures,
                    liveEventIds = liveIds(response),
                    liveScores = scoresById(response),
                    // An empty upstream lane is not authoritative coverage. Treating it as such
                    // suppresses kickoff-time inference (notably for NFL games where the live feed
                    // can be empty while the fixture endpoint already reports Q1 and a score).
                    livescoreSports = coveredLivescoreSports(response),
                    loadingFixtures = false,
                )
            }
            // Persist the MERGED map — persisting only the raw response would drop leagues
            // this (possibly partial) response omitted from the offline cache.
            XtreamAccountStorage.saveRadarFixturesJson(
                profileAtStart,
                json.encodeToString(
                    response.copy(
                        fixtures = _uiState.value.fixturesByLeague,
                        teamFixtures = _uiState.value.fixturesByTeam,
                    ),
                ),
            )
        }
    }

    /**
     * Fast score tick for the Sports screen: refresh ONLY followed leagues/teams that have a
     * live-or-imminent fixture ([RadarLiveRefreshPolicy]), so the 2-minute poll transfers a live
     * subset — often nothing — instead of the whole followed set every time. Merges results the
     * same way [ensureLeagueLoaded] does (the livescore feed is per-sport and authoritative for the
     * sports it returns; stale live ids are already defended by [RadarUiState.isLive]). Leaves the
     * full-refresh throttle untouched: schedule discovery stays with [refreshFixtures].
     */
    fun refreshLiveFixtures() {
        val nowMs = clock.nowMs()
        val state = _uiState.value
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = leaguesToFetch(nowMs),
            candidateTeamIds = state.followedTeamIds,
            fixturesByLeague = state.fixturesByLeague,
            fixturesByTeam = state.fixturesByTeam,
            nowMs = nowMs,
        )
        if (targets.isEmpty) return // nothing live/imminent → no network call at all
        val sports = (
            targets.leagueIds.mapNotNull { id -> state.leagueById(id)?.sport?.lowercase() } +
                state.teamFollows.filter { it.teamId in targets.teamIds }.map { it.sport.lowercase() }
            ).filter { it in RADAR_LIVESCORE_SPORTS }.toSet()
        val profileAtStart = currentProfileId
        scope.launch {
            val response = RadarFixturesClient.fetch(targets.leagueIds, sports, targets.teamIds) ?: return@launch
            if (profileAtStart != currentProfileId) return@launch
            _uiState.update {
                it.copy(
                    fixturesByLeague = it.fixturesByLeague + response.fixtures,
                    fixturesByTeam = it.fixturesByTeam + response.teamFixtures,
                    liveEventIds = it.liveEventIds + liveIds(response),
                    liveScores = it.liveScores + scoresById(response),
                    livescoreSports = it.livescoreSports + coveredLivescoreSports(response),
                )
            }
            XtreamAccountStorage.saveRadarFixturesJson(
                profileAtStart,
                json.encodeToString(
                    response.copy(
                        fixtures = _uiState.value.fixturesByLeague,
                        teamFixtures = _uiState.value.fixturesByTeam,
                    ),
                ),
            )
        }
    }

    /**
     * On-demand fetch for a league the user is BROWSING (league/event page) — followed
     * leagues load via [refreshFixtures]; discovery must not depend on following.
     */
    fun ensureLeagueLoaded(leagueId: String) {
        if (_uiState.value.fixturesByLeague.containsKey(leagueId)) return
        val sport = _uiState.value.leagueById(leagueId)?.sport?.lowercase()
        val sports = if (sport != null && sport in RADAR_LIVESCORE_SPORTS) setOf(sport) else emptySet()
        val profileAtStart = currentProfileId
        scope.launch {
            val response = RadarFixturesClient.fetch(listOf(leagueId), sports) ?: return@launch
            if (profileAtStart != currentProfileId) return@launch
            _uiState.update {
                it.copy(
                    fixturesByLeague = it.fixturesByLeague + response.fixtures,
                    liveEventIds = it.liveEventIds + liveIds(response),
                    liveScores = it.liveScores + scoresById(response),
                    livescoreSports = it.livescoreSports + coveredLivescoreSports(response),
                )
            }
        }
    }

    private fun liveIds(response: RadarFixturesResponse): Set<String> =
        response.livescore.values.asSequence().flatten().mapNotNull { it.eventId }.toSet()

    private fun scoresById(response: RadarFixturesResponse): Map<String, RadarLiveScore> =
        response.livescore.values.asSequence().flatten()
            .mapNotNull { score -> score.eventId?.let { it to score } }
            .toMap()

    private fun coveredLivescoreSports(response: RadarFixturesResponse): Set<String> =
        response.livescore.asSequence()
            .filter { (_, scores) -> scores.isNotEmpty() }
            .map { (sport, _) -> sport.lowercase() }
            .toSet()

    // --- follows -------------------------------------------------------------

    fun isFollowed(leagueId: String): Boolean = _uiState.value.followedLeagueIds.contains(leagueId)

    fun toggleFollow(league: RadarLeague) {
        _uiState.update { state ->
            val without = state.follows.filterNot { it.leagueId == league.id }
            // A league that isn't in the published catalog carries its own metadata on the
            // follow — nothing else would be able to name or draw it later.
            val inCatalog = state.catalog.categories
                .any { category -> category.leagues.any { it.id == league.id } }
            val follows = if (without.size == state.follows.size) {
                without + RadarFollow(
                    leagueId = league.id,
                    sport = league.sport ?: "",
                    sortOrder = without.size,
                    name = league.name.takeUnless { inCatalog },
                    badge = league.badge.takeUnless { inCatalog },
                    banner = league.banner.takeUnless { inCatalog },
                    keywords = if (inCatalog) emptyList() else league.keywords,
                    custom = !inCatalog,
                )
            } else {
                without
            }
            state.copy(follows = follows)
        }
        persist()
        refreshFixtures(force = true)
    }

    fun isTeamFollowed(teamId: String): Boolean = _uiState.value.followedTeamIds.contains(teamId)

    /**
     * Follow/unfollow a club. Unlike a league there is no catalog to fall back on, so the
     * whole team travels onto the follow row — dropping it would leave nothing to name,
     * draw or channel-match the club with later.
     */
    fun toggleFollowTeam(team: RadarTeam) {
        _uiState.update { state ->
            val without = state.teamFollows.filterNot { it.teamId == team.id }
            val teams = if (without.size == state.teamFollows.size) {
                without + team.asFollow(sortOrder = without.size)
            } else {
                without
            }
            state.copy(teamFollows = teams)
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
    fun applyFromRemote(
        profileId: Int,
        follows: List<RadarFollow>,
        prefs: RadarPrefs?,
        teams: List<RadarTeamFollow>? = null,
    ) {
        loaded = true
        currentProfileId = profileId
        _uiState.update { state ->
            state.copy(
                catalog = state.catalog.takeIf { it.categories.isNotEmpty() }
                    ?: runCatching { json.decodeFromString<RadarCatalog>(RadarCatalogData.JSON) }.getOrDefault(RadarCatalog()),
                follows = follows,
                prefs = prefs ?: state.prefs,
                // Null means the pull said nothing about teams (older backend); keep local.
                teamFollows = teams ?: state.teamFollows,
            )
        }
        XtreamAccountStorage.saveRadarJson(profileId, json.encodeToString(localState()))
        refreshFixtures(force = true)
    }

    private fun localState() = RadarLocalState(
        follows = _uiState.value.follows,
        prefs = _uiState.value.prefs,
        teams = _uiState.value.teamFollows,
    )

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

    // Broadcaster listings barely change and the edge function caches them 12h — one
    // fetch per event per app session is plenty.
    private val tvCache = mutableMapOf<String, List<RadarTvStation>>()
    private val tvCacheLock = kotlinx.coroutines.sync.Mutex()

    /** TheSportsDB broadcaster list for a fixture (session-cached; empty when unknown). */
    suspend fun tvStations(eventId: String?): List<RadarTvStation> {
        if (eventId.isNullOrBlank()) return emptyList()
        tvCacheLock.withLock { tvCache[eventId] }?.let { return it }
        val fetched = RadarFixturesClient.fetchTv(eventId)
        if (fetched.isNotEmpty()) tvCacheLock.withLock { tvCache[eventId] = fetched }
        return fetched
    }
}
