package com.nuvio.app.core.sync

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.collection.CollectionSyncService
import com.nuvio.app.features.home.HomeCatalogSettingsSyncService
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.trakt.effectiveLibrarySourceMode
import com.nuvio.app.features.trakt.shouldUseTraktProgress
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.tracking.effectiveLibrarySourceMode
import com.nuvio.app.features.tracking.effectiveWatchProgressSource
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val FOREGROUND_PULL_DELAY_MS = 2500L
private const val FOREGROUND_PULL_MIN_INTERVAL_MS = 30 * 60_000L
private const val PERIODIC_NUVIO_SYNC_PULL_INTERVAL_MS = 240_000L

internal enum class ProfileSyncStep {
    Addons,
    Plugins,
    ProfileSettings,
    ProviderCredentials,
    Library,
    ActiveWatchSource,
    Collections,
    HomeCatalogSettings,
    MediaServers,
}

internal data class ProfileSyncOperations(
    val pullAddons: suspend (Int) -> Unit,
    val pullPlugins: suspend (Int) -> Unit,
    val pullProfileSettings: suspend (Int) -> Unit,
    val syncProviderCredentials: suspend (Int) -> Unit,
    val pullLibrary: suspend (Int) -> Unit,
    val refreshActiveWatchSource: suspend (Int) -> Unit,
    val pullCollections: suspend (Int) -> Unit,
    val pullHomeCatalogSettings: suspend (Int) -> Unit,
    val syncMediaServers: suspend (Int) -> Unit = {},
)

internal data class ProfileSyncResult(
    val failedSteps: Set<ProfileSyncStep>,
    val authRefused: Boolean = false,
) {
    val succeeded: Boolean
        get() = failedSteps.isEmpty()
}

/**
 * How long to wait after [failures] consecutive failed cycles before another automatic attempt:
 * 5s, 10s, 20s, 40s, 80s, then held at 160s.
 */
internal fun syncRetryBackoffMs(failures: Int): Long {
    if (failures <= 0) return 0L
    val shift = (failures - 1).coerceAtMost(SYNC_RETRY_MAX_FAILURE_STEP - 1)
    return SYNC_RETRY_BASE_BACKOFF_MS shl shift
}

private const val SYNC_RETRY_BASE_BACKOFF_MS = 5_000L
internal const val SYNC_RETRY_MAX_FAILURE_STEP = 6

internal data class ProfilePullFreshness(
    val profileId: Int? = null,
    val completedAtEpochMs: Long = 0L,
    val consecutiveFailures: Int = 0,
    val retryNotBeforeEpochMs: Long = 0L,
) {
    fun isRecent(profileId: Int, nowEpochMs: Long, minIntervalMs: Long): Boolean =
        this.profileId == profileId && nowEpochMs - completedAtEpochMs < minIntervalMs

    /**
     * True while a failed cycle is still cooling off.
     *
     * Without this there is no throttle on failure at all: [record] only stamps freshness on
     * success — correct, since a failed pull refreshed nothing and must not look recent — so
     * [isRecent] stays false and every trigger (periodic tick, realtime invalidation, profile
     * switch) immediately runs another full cycle. That is what turned one lapsed session into ten
     * seconds of repeating 42501s rather than a single refusal.
     */
    fun isInRetryBackoff(profileId: Int, nowEpochMs: Long): Boolean =
        this.profileId == profileId && nowEpochMs < retryNotBeforeEpochMs

    fun record(
        profileId: Int,
        nowEpochMs: Long,
        result: ProfileSyncResult,
    ): ProfilePullFreshness {
        val sameProfile = this.profileId == profileId
        return if (result.succeeded) {
            ProfilePullFreshness(
                profileId = profileId,
                completedAtEpochMs = nowEpochMs,
            )
        } else {
            // An auth refusal goes straight to the longest cooldown: unlike a timeout or a 5xx, it
            // cannot resolve because we asked again. It clears when the session returns, and
            // SyncSession.canSync() already refuses to start a cycle before then.
            val failures = if (result.authRefused) {
                SYNC_RETRY_MAX_FAILURE_STEP
            } else {
                (if (sameProfile) consecutiveFailures else 0) + 1
            }
            ProfilePullFreshness(
                profileId = profileId,
                // Freshness is deliberately NOT advanced: a failed cycle refreshed nothing, so it
                // must not make the data look recent. Only the retry cooldown moves.
                completedAtEpochMs = if (sameProfile) completedAtEpochMs else 0L,
                consecutiveFailures = failures,
                retryNotBeforeEpochMs = nowEpochMs + syncRetryBackoffMs(failures),
            )
        }
    }
}

internal suspend fun runOrderedProfileSync(
    profileId: Int,
    pluginsEnabled: Boolean,
    operations: ProfileSyncOperations,
    onFailure: (ProfileSyncStep, Throwable) -> Unit = { _, _ -> },
): ProfileSyncResult {
    val failureLock = SynchronizedObject()
    val failedSteps = mutableSetOf<ProfileSyncStep>()
    // Set once the session is proven gone, so the remaining steps are abandoned rather than each
    // sending its own doomed RPC. See Throwable.isSyncAuthRefusal.
    var authRefused = false

    suspend fun runStep(
        step: ProfileSyncStep,
        operation: suspend (Int) -> Unit,
    ) {
        if (synchronized(failureLock) { authRefused }) {
            synchronized(failureLock) { failedSteps += step }
            return
        }
        try {
            operation(profileId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            synchronized(failureLock) {
                failedSteps += step
                if (error.isSyncAuthRefusal()) authRefused = true
            }
            onFailure(step, error)
        }
    }

    runStep(ProfileSyncStep.Addons, operations.pullAddons)
    if (pluginsEnabled) {
        runStep(ProfileSyncStep.Plugins, operations.pullPlugins)
    }

    runStep(ProfileSyncStep.ProfileSettings, operations.pullProfileSettings)
    runStep(ProfileSyncStep.ProviderCredentials, operations.syncProviderCredentials)

    coroutineScope {
        launch {
            runStep(ProfileSyncStep.Library, operations.pullLibrary)
        }
        launch {
            runStep(ProfileSyncStep.ActiveWatchSource, operations.refreshActiveWatchSource)
        }
        launch {
            runStep(ProfileSyncStep.Collections, operations.pullCollections)
        }
        launch {
            runStep(ProfileSyncStep.HomeCatalogSettings, operations.pullHomeCatalogSettings)
        }
        launch {
            runStep(ProfileSyncStep.MediaServers, operations.syncMediaServers)
        }
    }
    return ProfileSyncResult(
        failedSteps = synchronized(failureLock) { failedSteps.toSet() },
        authRefused = synchronized(failureLock) { authRefused },
    )
}

internal enum class ProfileSyncRequestResult {
    Started,
    Coalesced,
    Replaced,
}

internal class ProfileSyncRequestGate {
    private val lock = SynchronizedObject()
    private var activeProfileId: Int? = null
    private var activeJob: Job? = null

    fun launch(
        scope: CoroutineScope,
        profileId: Int,
        block: suspend () -> Unit,
    ): ProfileSyncRequestResult {
        lateinit var newJob: Job
        var previousJob: Job? = null
        val result = synchronized(lock) {
            val active = activeJob?.takeUnless(Job::isCompleted)
            if (active != null && activeProfileId == profileId) {
                return ProfileSyncRequestResult.Coalesced
            }

            previousJob = active
            val requestResult = if (active == null) {
                ProfileSyncRequestResult.Started
            } else {
                ProfileSyncRequestResult.Replaced
            }

            newJob = scope.launch(start = CoroutineStart.LAZY) {
                block()
            }
            activeProfileId = profileId
            activeJob = newJob
            newJob.invokeOnCompletion {
                synchronized(lock) {
                    if (activeJob === newJob) {
                        activeJob = null
                        activeProfileId = null
                    }
                }
            }
            requestResult
        }

        previousJob?.cancel()
        newJob.start()
        return result
    }

    fun cancel() {
        val job = synchronized(lock) {
            activeJob.also {
                activeJob = null
                activeProfileId = null
            }
        }
        job?.cancel()
    }
}

object SyncManager {
    private val log = Logger.withTag("SyncManager")
    private val fullSyncRequestGate = ProfileSyncRequestGate()
    private val accountScopeLock = SynchronizedObject()
    private var accountScopeJob: Job = SupervisorJob()
    private var accountScope = CoroutineScope(accountScopeJob + Dispatchers.Default)
    private val pullStateLock = SynchronizedObject()
    private var foregroundPullJob: Job? = null
    private var foregroundPullProfileId: Int? = null
    private var periodicNuvioSyncPullJob: Job? = null
    private var periodicNuvioSyncProfileId: Int? = null
    private var pullFreshness = ProfilePullFreshness()

    private val profileSyncOperations = ProfileSyncOperations(
        pullAddons = { profileId -> AddonRepository.pullFromServer(profileId) },
        pullPlugins = { profileId -> PluginRepository.pullFromServer(profileId) },
        pullProfileSettings = { profileId -> ProfileSettingsSync.pull(profileId) },
        syncProviderCredentials = { profileId -> ProviderCredentialSync.syncFromRemote(profileId) },
        pullLibrary = { profileId -> LibraryRepository.pullFromServer(profileId) },
        refreshActiveWatchSource = { profileId ->
            val result = WatchProgressSourceCoordinator.refreshActiveSource(profileId = profileId, force = true)
            check(result.succeeded) {
                "Active watch source refresh was incomplete: " +
                    "progress=${result.progressRefreshed} watched=${result.watchedHistoryRefreshed}"
            }
        },
        pullCollections = { profileId -> CollectionSyncService.pullFromServer(profileId) },
        pullHomeCatalogSettings = { profileId -> HomeCatalogSettingsSyncService.pullFromServer(profileId) },
        syncMediaServers = { profileId -> MediaServerSync.syncFromRemote(profileId) },
    )

    fun pullAllForProfile(profileId: Int) {
        startFullProfilePull(profileId = profileId, reason = "requested")
    }

    internal fun cancelAccountSync() {
        SyncDeviceReporter.clearAccountState()
        fullSyncRequestGate.cancel()
        val previousAccountJob = synchronized(accountScopeLock) {
            accountScopeJob.also {
                accountScopeJob = SupervisorJob()
                accountScope = CoroutineScope(accountScopeJob + Dispatchers.Default)
            }
        }
        previousAccountJob.cancel()
        val foregroundJob = synchronized(pullStateLock) {
            foregroundPullJob.also {
                foregroundPullJob = null
                foregroundPullProfileId = null
                pullFreshness = ProfilePullFreshness()
            }
        }
        foregroundJob?.cancel()
        stopPeriodicNuvioSyncPull()
    }

    private fun accountScopeSnapshot(): CoroutineScope = synchronized(accountScopeLock) {
        accountScope
    }

    fun requestForegroundPull(profileId: Int, force: Boolean = false) {
        if (!SyncSession.canSync()) return

        if (!force && hasRecentFullPull(profileId)) {
            return
        }
        if (isInRetryBackoff(profileId)) return
        lateinit var requestJob: Job
        var previousJob: Job? = null
        synchronized(pullStateLock) {
            if (
                !force &&
                foregroundPullJob?.isCompleted == false &&
                foregroundPullProfileId == profileId
            ) {
                return
            }

            previousJob = foregroundPullJob
            requestJob = accountScopeSnapshot().launch(start = CoroutineStart.LAZY) {
                try {
                    if (!force) {
                        delay(FOREGROUND_PULL_DELAY_MS)
                    }
                    if (!force && hasRecentFullPull(profileId)) return@launch
                    if (isInRetryBackoff(profileId)) return@launch
                    if (ProfileRepository.activeProfileId != profileId) return@launch
                    pullForegroundForProfile(profileId)
                } finally {
                    synchronized(pullStateLock) {
                        if (foregroundPullJob === requestJob) {
                            foregroundPullJob = null
                            foregroundPullProfileId = null
                        }
                    }
                }
            }
            foregroundPullProfileId = profileId
            foregroundPullJob = requestJob
        }
        previousJob?.cancel()
        requestJob.start()
    }

    private fun hasRecentFullPull(profileId: Int): Boolean =
        synchronized(pullStateLock) {
            pullFreshness.isRecent(
                profileId = profileId,
                nowEpochMs = EpisodeReleaseDatePlatform.nowEpochMs(),
                minIntervalMs = FOREGROUND_PULL_MIN_INTERVAL_MS,
            )
        }

    /**
     * Whether a cycle that just failed is still cooling off.
     *
     * Applies to `force` too. Forcing exists to bypass the 30-minute freshness window, not to
     * hammer a backend that is currently refusing us — and foreground events force on every resume,
     * so exempting them would leave the storm intact for anyone switching apps.
     */
    private fun isInRetryBackoff(profileId: Int): Boolean =
        synchronized(pullStateLock) {
            pullFreshness.isInRetryBackoff(
                profileId = profileId,
                nowEpochMs = EpisodeReleaseDatePlatform.nowEpochMs(),
            )
        }

    private suspend fun pullForegroundForProfile(profileId: Int) {
        log.i { "Foreground sync started profile=$profileId" }

        runCatching { ProfileRepository.pullProfiles() }
            .onFailure { log.e(it) { "Foreground profiles pull failed" } }
        val syncResult = runOrderedProfileSync(
            profileId = profileId,
            pluginsEnabled = AppFeaturePolicy.pluginsEnabled,
            operations = profileSyncOperations,
            onFailure = { step, error ->
                log.e(error) { "Foreground profile sync step failed profile=$profileId step=$step" }
            },
        )
        synchronized(pullStateLock) {
            pullFreshness = pullFreshness.record(
                profileId = profileId,
                nowEpochMs = EpisodeReleaseDatePlatform.nowEpochMs(),
                result = syncResult,
            )
        }
        if (!syncResult.succeeded) {
            log.w {
                "Foreground profile sync incomplete profile=$profileId failedSteps=${syncResult.failedSteps}"
            }
        }

        log.i { "Foreground sync completed profile=$profileId" }
    }

    private fun startFullProfilePull(
        profileId: Int,
        reason: String,
    ) {
        if (!SyncSession.canSync()) return
        if (ProfileRepository.activeProfileId != profileId) return

        // Piggy-backs on the sync we were going to do anyway; it is a no-op after the first call.
        SyncDeviceReporter.reportOnce()

        val result = fullSyncRequestGate.launch(
            scope = accountScopeSnapshot(),
            profileId = profileId,
        ) {
            // Re-checked inside the gate: the session can lapse between request and launch.
            if (!SyncSession.canSync()) return@launch
            if (ProfileRepository.activeProfileId != profileId) return@launch

            log.i { "Full profile sync started profile=$profileId reason=$reason" }
            // B03: read the version vector BEFORE pulling, so an edit landing mid-sync stays unseen.
            val versionsBefore = SurfaceCatchUp.fetch(profileId)
            WatchProgressSourceCoordinator.pauseAutomaticTransitions()
            val syncResult = try {
                runOrderedProfileSync(
                    profileId = profileId,
                    pluginsEnabled = AppFeaturePolicy.pluginsEnabled,
                    operations = profileSyncOperations,
                    onFailure = { step, error ->
                        log.e(error) { "Full profile sync step failed profile=$profileId step=$step" }
                    },
                )
            } finally {
                WatchProgressSourceCoordinator.resumeAutomaticTransitions()
            }
            synchronized(pullStateLock) {
                pullFreshness = pullFreshness.record(
                    profileId = profileId,
                    nowEpochMs = EpisodeReleaseDatePlatform.nowEpochMs(),
                    result = syncResult,
                )
            }
            if (!syncResult.succeeded) {
                log.w {
                    "Full profile sync incomplete profile=$profileId reason=$reason " +
                        "failedSteps=${syncResult.failedSteps}"
                }
            } else if (versionsBefore != null) {
                // Everything the ordered pipeline pulls is now current (fork participants are marked
                // by the catch-up that pulls them).
                SurfaceCatchUp.markPulled(versionsBefore.filter { it.surface in namedSurfaces })
            }
            // Now that the pull half is done, flush anything written while signed out. Order
            // matters: the merge that just ran preserves locally-dirty keys, so nothing sent here
            // can be clobbered by what was pulled, and whatever the server already had has been
            // acknowledged out of the dirty sets. Before this existed a scrobble that failed to
            // push stayed dirty forever with nothing to retry it.
            WatchProgressRepository.pushPendingToServer(profileId)
            WatchedRepository.pushPendingToServer(profileId)

            // Fork surfaces (IPTV accounts + Radar follows) ride alongside the ordered
            // pipeline — upstream's ProfileSyncOperations doesn't know about them.
            // Fork surfaces register themselves as SyncParticipants (upstream's
            // ProfileSyncOperations doesn't know about them).
            com.nuvio.app.core.contracts.SyncParticipantRegistry.all.forEach { participant ->
                accountScopeSnapshot().launch {
                    runCatching { participant.pullFromServer(profileId) }
                        .onFailure { log.e(it) { "fork sync pull failed: ${participant.name}" } }
                }
            }
            log.i { "Full profile sync completed profile=$profileId reason=$reason" }
        }

        when (result) {
            ProfileSyncRequestResult.Started -> Unit
            ProfileSyncRequestResult.Coalesced -> {
                log.d { "Full profile sync coalesced profile=$profileId reason=$reason" }
            }
            ProfileSyncRequestResult.Replaced -> {
                log.d { "Full profile sync replaced stale profile request with profile=$profileId reason=$reason" }
            }
        }
    }

    fun startPeriodicNuvioSyncPull(profileId: Int) {
        // Lifecycle gate, not a session gate: see SyncSession.hasAccount.
        if (!SyncSession.hasAccount()) {
            stopPeriodicNuvioSyncPull()
            return
        }
        if (periodicNuvioSyncPullJob?.isActive == true && periodicNuvioSyncProfileId == profileId) return

        stopPeriodicNuvioSyncPull()
        periodicNuvioSyncProfileId = profileId
        periodicNuvioSyncPullJob = accountScopeSnapshot().launch {
            while (isActive) {
                delay(PERIODIC_NUVIO_SYNC_PULL_INTERVAL_MS)

                // Per-tick session gate: skip this round if the token is momentarily gone, and pick
                // straight back up on the next one once supabase-kt has refreshed.
                if (!SyncSession.canSync()) {
                    continue
                }
                if (ProfileRepository.activeProfileId != profileId) {
                    continue
                }

                TrackingProviderRegistry.ensureLoaded()
                TrackingSettingsRepository.ensureLoaded()

                val settings = TrackingSettingsRepository.uiState.value
                val shouldPullLibrary = effectiveLibrarySourceMode(
                    requestedSource = settings.librarySourceMode,
                    isProviderAuthenticated = TrackingProviderRegistry::isAuthenticated,
                ) == LibrarySourceMode.LOCAL
                val shouldPullWatchProgress = effectiveWatchProgressSource(
                    requestedSource = settings.watchProgressSource,
                    isProviderAuthenticated = TrackingProviderRegistry::isAuthenticated,
                ) == WatchProgressSource.NUVIO_SYNC

                if (!shouldPullLibrary && !shouldPullWatchProgress) {
                    continue
                }

                log.i {
                    "Periodic Nuvio sync pull profile=$profileId " +
                        "library=$shouldPullLibrary watchProgress=$shouldPullWatchProgress"
                }
                if (shouldPullLibrary) {
                    runCatching { LibraryRepository.pullFromServer(profileId) }
                        .onFailure { log.e(it) { "Periodic Nuvio library pull failed" } }
                }
                if (shouldPullWatchProgress) {
                    runCatching {
                        WatchProgressSourceCoordinator.refreshActiveSource(profileId = profileId, force = false)
                    }.onFailure { log.e(it) { "Periodic Nuvio watch source pull failed" } }
                }
            }
        }
    }

    fun stopPeriodicNuvioSyncPull() {
        periodicNuvioSyncPullJob?.cancel()
        periodicNuvioSyncPullJob = null
        periodicNuvioSyncProfileId = null
    }

    fun requestRealtimeSurfacePull(profileId: Int, surface: String) {
        if (!SyncSession.canSync()) return

        accountScopeSnapshot().launch {
            log.i { "requestRealtimeSurfacePull($profileId, $surface)" }
            pullSurface(profileId, surface)
        }
    }

    /** Surfaces [pullSurface] knows by name (fork surfaces come from their SyncParticipants). */
    internal val namedSurfaces: Set<String> = setOf(
        "addons", "plugins", "library", "watch_progress", "watched_items", "profile_settings",
        "collections", "home_catalog_settings", "profiles", "provider_credentials", "media_servers", "device_preferences", "device_sources",
    )

    /** Every surface this client can pull (B03: what a version-vector catch-up may plan). */
    internal fun pullableSurfaces(): Set<String> =
        namedSurfaces + com.nuvio.app.core.contracts.SyncParticipantRegistry.all.flatMap { it.realtimeSurfaces }

    /**
     * Pulls one sync surface for [profileId] — the shared dispatcher of a Realtime invalidation and of
     * the B03 version-vector catch-up. True when the pull ran without an error (an unknown surface
     * counts as done: nothing to pull).
     */
    internal suspend fun pullSurface(profileId: Int, surface: String): Boolean {
        suspend fun attempt(what: String, block: suspend () -> Unit): Boolean =
            runCatching { block() }
                .onFailure { log.e(it) { "Realtime $what pull failed" } }
                .isSuccess
        return when (surface) {
            "device_preferences" -> attempt("device preferences") { com.nuvio.app.core.sources.DeviceSources.engine().refresh(profileId,false) }
            "device_sources" -> attempt("device sources") { com.nuvio.app.core.sources.DeviceSources.engine().refresh(profileId,true) }
            "addons" -> attempt("addons") { AddonRepository.pullFromServer(profileId) }
            "plugins" -> if (AppFeaturePolicy.pluginsEnabled) attempt("plugins") { PluginRepository.pullFromServer(profileId) } else true
            "library" -> attempt("library") { LibraryRepository.pullFromServer(profileId) }
            "watch_progress", "watched_items" -> attempt("active watch source") {
                WatchProgressSourceCoordinator.refreshActiveSource(profileId = profileId, force = false)
            }
            "profile_settings" -> attempt("profile settings") { ProfileSettingsSync.pull(profileId) }
            // B03: the website's Integrations tab writes credentials and emits this surface; it used
            // to be ignored here (only a full foreground sync picked it up).
            "provider_credentials" -> attempt("provider credentials") { ProviderCredentialSync.syncFromRemote(profileId) }
            "collections" -> attempt("collections") { CollectionSyncService.pullFromServer(profileId) }
            "home_catalog_settings" -> attempt("home catalog settings") { HomeCatalogSettingsSyncService.pullFromServer(profileId) }
            "profiles" -> attempt("profiles") { ProfileRepository.pullProfiles() }
            // Backend emits this when sync_push_media_servers lands from another device.
            "media_servers" -> attempt("media servers") { MediaServerSync.syncFromRemote(profileId) }
            else -> {
                // Fork surfaces (the IPTV overlay, IPTV playlists, Radar) are routed to the
                // SyncParticipant that declares them — this file never names a fork feature.
                val participants = com.nuvio.app.core.contracts.SyncParticipantRegistry
                    .participantsForRealtimeSurface(surface)
                if (participants.isEmpty()) log.d { "Ignoring unknown realtime surface=$surface" }
                participants.map { participant -> attempt(participant.name) { participant.pullFromServer(profileId) } }
                    .all { it }
            }
        }
    }
}
