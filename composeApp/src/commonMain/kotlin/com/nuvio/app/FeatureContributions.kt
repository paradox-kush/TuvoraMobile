package com.nuvio.app

import com.nuvio.app.core.diag.installLogRedaction
import com.nuvio.app.core.telemetry.api.TelemetryStartup
import com.nuvio.app.core.contracts.IptvCatalogAccess
import com.nuvio.app.core.contracts.IptvSubtitleIdAccess
import com.nuvio.app.core.contracts.LivePlaybackAccess
import com.nuvio.app.core.contracts.LiveRecentsAccess
import com.nuvio.app.core.contracts.LocalStateCleanerRegistry
import com.nuvio.app.core.contracts.MemoryPortAccess
import com.nuvio.app.core.contracts.PlaybackGateAccess
import com.nuvio.app.core.contracts.ProfileChangeParticipants
import com.nuvio.app.core.contracts.RecTrackingAccess
import com.nuvio.app.core.contracts.SyncParticipantRegistry
import com.nuvio.app.core.memory.MemoryPortImpl
import com.nuvio.app.core.rec.RecLocalStateCleaner
import com.nuvio.app.core.rec.RecPlaybackReporterImpl
import com.nuvio.app.core.rec.RecSettingsImpl
import com.nuvio.app.features.iptv.IptvPlaybackGateAdapter
import com.nuvio.app.features.iptv.DeviceSetupClaimCoordinator
import com.nuvio.app.features.iptv.IptvProfileChange
import com.nuvio.app.features.iptv.XtreamAccountsCleaner
import com.nuvio.app.features.iptv.XtreamLivePlaybackProvider
import com.nuvio.app.features.iptv.XtreamLiveRecentsProvider
import com.nuvio.app.features.iptv.XtreamRecentsCleaner
import com.nuvio.app.features.iptv.IptvSourceRegistrations
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.XtreamSubtitleIdResolver
import com.nuvio.app.features.iptv.XtreamSyncParticipant
import com.nuvio.app.features.iptv.overlay.IptvOverlaySyncParticipant
import com.nuvio.app.features.radar.RadarProfileChange
import com.nuvio.app.features.radar.RadarSyncParticipant
import com.nuvio.app.features.calendar.api.CalendarAccounts
import com.nuvio.app.features.calendar.api.CalendarFeature
import com.nuvio.app.features.calendar.api.CalendarFollowItem
import com.nuvio.app.features.calendar.api.CalendarFollowOrigin
import com.nuvio.app.features.calendar.api.CalendarFollowType
import com.nuvio.app.features.calendar.api.CalendarFollowedRead
import com.nuvio.app.features.calendar.api.CalendarFollowedSources
import com.nuvio.app.features.calendar.api.CalendarWatchedEpisode
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktConfig
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Process-init registration of the fork's LOGIC ports: data, sync, playback and memory — nothing that
 * renders. Split out of [registerFeatureContributions] (FeatureWiring.kt) so the Apple TV build
 * (NuvioMobile :tvosCore), which cannot compile the Compose UI slots, registers exactly the same list
 * from its own composition root (TvAppGraph). Part of the composition root: the second file the
 * architecture test allows to name fork implementations. Add a new logic port HERE, not in
 * FeatureWiring.kt, or Apple TV will run without it.
 */
fun registerLogicFeatureContributions() {
    // B116: first, so every later log line (all Kermit loggers share this config) is redacted.
    installLogRedaction()
    // Telemetry v2: publish the service the platform started first (crash handler up before DI) as the app's port.
    TelemetryStartup.registerPort()
    // S10: app-wide memory port (AppMemory + BudgetRegistry) — image loaders, player buffer
    // sizing, and the platform startup probes size their budgets through this.
    MemoryPortAccess.register(MemoryPortImpl)
    // S3a: register the IptvCatalog read port for non-Compose consumers.
    IptvCatalogAccess.register(XtreamRepository)
    // Plural source ports (media-servers design 5.1): IPTV registers as ONE entry in each - content
    // classifier, stream source, meta source, search provider - plus its own-source id predicates.
    IptvSourceRegistrations.register()
    // Jellyfin/Emby is upstream's features/servers, wired directly in the shared spine (sync via
    // sync_pull/push_media_servers) - nothing to register here.
    SyncParticipantRegistry.register(XtreamSyncParticipant)
    SyncParticipantRegistry.register(RadarSyncParticipant)
    SyncParticipantRegistry.register(IptvOverlaySyncParticipant)
    LocalStateCleanerRegistry.register(XtreamRecentsCleaner)
    LocalStateCleanerRegistry.register(RecLocalStateCleaner)
    LocalStateCleanerRegistry.register(XtreamAccountsCleaner)
    RecTrackingAccess.register(RecPlaybackReporterImpl)
    RecTrackingAccess.registerSettings(RecSettingsImpl)
    ProfileChangeParticipants.register(IptvProfileChange)
    ProfileChangeParticipants.register(RadarProfileChange)
    LiveRecentsAccess.register(XtreamLiveRecentsProvider)
    PlaybackGateAccess.register(IptvPlaybackGateAdapter)
    LivePlaybackAccess.register(XtreamLivePlaybackProvider)
    // F17: public subtitle ids for IPTV movies/episodes (OpenSubtitles in the IPTV section).
    IptvSubtitleIdAccess.register(XtreamSubtitleIdResolver)
    // B205: playlists redeemed from a setup code with no account move into the account once one signs in.
    DeviceSetupClaimCoordinator.shared.start()
    // Calendar + reminders: the followed-title and account ports (this file is the one place that may name the
    // library / progress / watched / Trakt / TMDB / profile objects). Lazy: nothing is read until a surface asks.
    CalendarFeature.register(CalendarSourcesAdapter, CalendarAccountsAdapter, CalendarSportsAdapter)
}

/** Local reads only — no network. Maps the app's own models to the Calendar's neutral types. */
private object CalendarSourcesAdapter : CalendarFollowedSources {
    override suspend fun read(): CalendarFollowedRead {
        LibraryRepository.ensureLoaded()
        WatchProgressRepository.ensureLoaded()
        WatchedRepository.ensureLoaded()
        val library = LibraryRepository.uiState.value
        val progress = WatchProgressRepository.uiState.value
        val watched = WatchedRepository.uiState.value
        val items = buildList {
            library.items.forEach { item ->
                val type = calendarTypeOf(item.type) ?: return@forEach
                add(
                    CalendarFollowItem(
                        contentId = item.id, type = type, origin = CalendarFollowOrigin.LIBRARY, name = item.name,
                        poster = item.poster, lastActivityMs = item.savedAtEpochMs, imdbId = item.imdbId,
                        tmdbId = item.tmdbId?.toLong(), traktId = item.traktId?.toLong(),
                    ),
                )
            }
            progress.entries.forEach { entry ->
                val type = calendarTypeOf(entry.parentMetaType) ?: return@forEach
                add(
                    CalendarFollowItem(
                        contentId = entry.parentMetaId, type = type, origin = CalendarFollowOrigin.PROGRESS,
                        name = entry.title, poster = entry.poster, lastActivityMs = entry.lastUpdatedEpochMs,
                    ),
                )
            }
            watched.items.forEach { item ->
                val type = calendarTypeOf(item.type) ?: return@forEach
                add(
                    CalendarFollowItem(
                        contentId = item.id, type = type, origin = CalendarFollowOrigin.WATCHED, name = item.name,
                        poster = item.poster, lastActivityMs = item.markedAtEpochMs,
                    ),
                )
            }
        }
        val watchedEpisodes = watched.items.mapNotNull { item ->
            CalendarWatchedEpisode(item.id, item.season ?: return@mapNotNull null, item.episode ?: return@mapNotNull null)
        } + progress.entries.filter { it.isCompleted }.mapNotNull { entry ->
            CalendarWatchedEpisode(entry.parentMetaId, entry.seasonNumber ?: return@mapNotNull null, entry.episodeNumber ?: return@mapNotNull null)
        }
        val watchedMovies = watched.items.filter { calendarTypeOf(it.type) == CalendarFollowType.MOVIE }.map { it.id } +
            progress.entries.filter { it.isCompleted && calendarTypeOf(it.parentMetaType) == CalendarFollowType.MOVIE }.map { it.parentMetaId }
        return CalendarFollowedRead(
            items = items,
            watchedEpisodes = watchedEpisodes,
            watchedMovies = watchedMovies,
            removedFromContinueWatching = progress.hiddenContentIds,
            unreadOrigins = buildSet {
                if (!library.isLoaded) add(CalendarFollowOrigin.LIBRARY)
                if (!watched.isLoaded) add(CalendarFollowOrigin.WATCHED)
            },
        )
    }

    private fun calendarTypeOf(type: String?): CalendarFollowType? = when (type?.lowercase()) {
        "series", "tv", "show", "anime" -> CalendarFollowType.SERIES
        "movie" -> CalendarFollowType.MOVIE
        else -> null
    }
}

/**
 * Games for the Calendar, read from what the Sports feature already holds (followed leagues' and teams' fixtures plus
 * live scores). NO ensureLoaded(), no refresh, no poll: those would start network work the Calendar must not cause.
 */
private object CalendarSportsAdapter : com.nuvio.app.features.calendar.api.CalendarSportsSource {
    override fun games(nowMs: Long) = com.nuvio.app.features.radar.RadarCalendarGames.games(
        com.nuvio.app.features.radar.RadarRepository.uiState.value, nowMs,
    )

    override val changes: kotlinx.coroutines.flow.Flow<Unit> =
        com.nuvio.app.features.radar.RadarRepository.uiState
            .distinctUntilChanged { a, b ->
                a.fixturesByLeague === b.fixturesByLeague && a.fixturesByTeam === b.fixturesByTeam &&
                    a.liveScores === b.liveScores && a.follows == b.follows && a.teamFollows == b.teamFollows
            }
            .map { }
}

private object CalendarAccountsAdapter : CalendarAccounts {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val activeProfileId: StateFlow<Int> by lazy {
        ProfileRepository.state.map { ProfileRepository.activeProfileId }
            .stateIn(scope, SharingStarted.Eagerly, ProfileRepository.activeProfileId)
    }

    override fun tmdbApiKey(): String? = TmdbSettingsRepository.effectiveApiKey().takeIf { it.isNotBlank() }

    override fun traktSignedIn(): Boolean {
        TraktAuthRepository.ensureLoaded()
        return TraktAuthRepository.isAuthenticated.value
    }

    override suspend fun traktAuthorizedHeaders(): Map<String, String>? = TraktAuthRepository.authorizedHeaders()

    override fun traktPublicHeaders(): Map<String, String> =
        mapOf("trakt-api-version" to "2", "trakt-api-key" to TraktConfig.CLIENT_ID)

    override fun region(): String =
        com.nuvio.app.features.calendar.api.calendarRegionFromLanguageTags(com.nuvio.app.features.player.DeviceLanguagePreferences.preferredLanguageCodes())
}
