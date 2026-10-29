package com.tuvora.tvos.app

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.contracts.IptvCatalogAccess
import com.nuvio.app.core.contracts.RecTrackingAccess
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.core.sync.ProfileSettingsSync
import com.nuvio.app.core.sync.RealtimeSyncConfig
import com.nuvio.app.core.sync.RealtimeSyncInvalidationService
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.collection.CollectionSyncService
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileSwitchController
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Apple TV's app runtime: the startup gate, then everything the phone starts from Compose effects in
 * AppGate.kt and MainAppContent.kt (upstream, excluded from :tvosCore). One owner per process, so
 * nothing runs twice. Decisions come from [TvGatePolicy]; this object only carries them out.
 *
 * Swift reads [screen] and calls [pickProfile] / [openProfilePicker] from the gate screens.
 */
object TvAppLifecycle {
    private val log = Logger.withTag("TvAppLifecycle")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _screen = MutableStateFlow(TvGateScreen.Loading)
    val screen: StateFlow<TvGateScreen> = _screen.asStateFlow()

    /** Bumps on every completed profile switch (the phone's appContentGeneration): per-profile screens reload. */
    private val _sessionGeneration = MutableStateFlow(0)
    val sessionGeneration: StateFlow<Int> = _sessionGeneration.asStateFlow()

    private var started = false
    private var runtimeStarted = false
    private var userOpenedPicker = false
    /** A profile switch completed and nothing has signed the viewer out since. */
    private var profileLive = false
    private var sessionJob: Job? = null

    /** Called once by TvAppGraph.start(), after the feature ports are registered. */
    internal fun start() {
        if (started) return
        started = true

        // AppGate.kt:103-114 and App.kt:77.
        AuthRepository.initialize()
        NetworkStatusRepository.ensureStarted()
        MemberAccessRepository.ensureStarted()
        ProfileRepository.loadCachedProfiles()
        ThemeSettingsRepository.ensureLoaded()
        scope.launch { runCatching { AvatarRepository.fetchAvatars() }.onFailure { log.w(it) { "avatars" } } }

        // The gate (AppGate.kt:323-366), re-evaluated whenever auth, network, profiles or the screen change.
        scope.launch {
            combine(AuthRepository.state, NetworkStatusRepository.uiState, ProfileRepository.state, _screen) { auth, net, profiles, current ->
                Triple(auth, net.condition == NetworkCondition.Online, profiles) to current
            }.collect { (triple, current) ->
                val (auth, online, profileState) = triple
                if (auth is AuthState.Authenticated && (current == TvGateScreen.Loading || current == TvGateScreen.SignIn)) {
                    ProfileRepository.ensureLoaded(auth.userId)
                    ProfileRepository.ensureDefaultLocalProfile()
                }
                val decision = TvGatePolicy.decide(
                    TvGateInput(
                        auth = auth,
                        profiles = ProfileRepository.state.value.profiles,
                        current = current,
                        online = online,
                        rememberLastProfileEnabled = profileState.rememberLastProfileEnabled,
                        hasEverSelectedProfile = profileState.hasEverSelectedProfile,
                        activeProfileIndex = ProfileRepository.activeProfileId,
                        userOpenedPicker = userOpenedPicker,
                    ),
                )
                apply(decision)
            }
        }

        // AppGate.kt:368-372: each new account pulls its profiles.
        scope.launch {
            AuthRepository.state.map { (it as? AuthState.Authenticated)?.userId }.distinctUntilChanged().collect { userId ->
                if (userId != null) {
                    ProfileRepository.ensureLoaded(userId)
                    runCatching { ProfileRepository.pullProfiles() }.onFailure { log.w(it) { "pullProfiles" } }
                }
            }
        }
    }

    /** The picker's Retry after its wait timed out: asks the account for its profiles again (the user's event, never a loop). */
    fun retryProfiles() {
        scope.launch { runCatching { ProfileRepository.pullProfiles() }.onFailure { log.w(it) { "retryProfiles" } } }
    }

    /** The picker's choice, after any PIN check the picker performs. */
    fun pickProfile(profileIndex: Int) {
        val profile = ProfileRepository.state.value.profiles.find { it.profileIndex == profileIndex } ?: return
        val active = ProfileRepository.state.value.activeProfile?.profileIndex
        if (TvGatePolicy.onPick(profileIndex, active, profileLive, userOpenedPicker) == TvPickAction.Close) {
            closeProfilePicker()
            return
        }
        switchTo(profile, sync = AuthRepository.state.value is AuthState.Authenticated)
    }

    /**
     * Leave a picker opened from Settings (Manage Profiles / Switch profile) back to the running
     * app. Returns false when there is nothing to go back to (the startup picker).
     */
    /** Whether Menu / Done on the picker has somewhere to go back to. */
    val canCloseProfilePicker: Boolean get() = TvGatePolicy.leavePicker(userOpenedPicker, profileLive) != null

    fun closeProfilePicker(): Boolean {
        val next = TvGatePolicy.leavePicker(userOpenedPicker, profileLive) ?: return false
        userOpenedPicker = false
        _screen.value = next
        return true
    }

    /** "Switch profile" from settings: show the picker and keep it up even with one profile. */
    fun openProfilePicker() {
        userOpenedPicker = true
        _screen.value = TvGateScreen.ProfilePicker
    }

    private fun apply(decision: TvGateDecision) {
        when (decision) {
            TvGateDecision.Stay -> Unit
            is TvGateDecision.Show -> {
                if (decision.screen == TvGateScreen.SignIn) {
                    ProfileRepository.clearInMemory()
                    profileLive = false
                }
                _screen.value = decision.screen
            }
            is TvGateDecision.SwitchTo -> switchTo(decision.profile, decision.sync)
        }
    }

    private fun switchTo(profile: NuvioProfile, sync: Boolean) {
        if (_screen.value == TvGateScreen.Switching) return
        _screen.value = TvGateScreen.Switching
        scope.launch {
            // Single-flight: warms every profile-bound repository, then pulls (ProfileSwitchController.kt:71).
            val switched = runCatching { ProfileSwitchController.switch(profile.profileIndex, sync) }
                .onFailure { log.w(it) { "profile switch failed" } }
                .getOrDefault(false)
            if (switched) {
                userOpenedPicker = false
                profileLive = true
                _sessionGeneration.value += 1
                _screen.value = TvGateScreen.Main
                startRuntimeOnce()
                restartSession()
            } else {
                _screen.value = TvGateScreen.ProfilePicker
            }
        }
    }

    /** Warm-ups the phone starts once from MainAppContent.kt (:216-356, :567-575, :723-727, :851). */
    private fun startRuntimeOnce() {
        if (runtimeStarted) return
        runtimeStarted = true
        MetaScreenSettingsRepository.ensureLoaded()
        EpisodeReleaseNotificationsRepository.ensureLoaded()
        IptvCatalogAccess.catalog.warmUpMatchIndexes(startDelayMs = 10_000)
        CollectionSyncService.startObserving()
        ProfileSettingsSync.startObserving()
        AddonRepository.initialize()
        LibraryRepository.ensureLoaded()
        PlayerSettingsRepository.ensureLoaded()
        WatchedRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.ensureLoaded()
        runCatching { RecTrackingAccess.reporter.startLogging() }
        runCatching { EpisodeReleaseNotificationsRepository.refreshAsync() }
        scope.launch { watchReconnects() }
    }

    /**
     * Per account-and-profile session (MainAppContent.kt:688-759): full pull, periodic pull only while
     * the app is in the foreground (lifecycle-bound, per the egress rule), and realtime invalidations.
     */
    private fun restartSession() {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            val auth = AuthRepository.state.value as? AuthState.Authenticated
            val profileId = ProfileRepository.state.value.activeProfile?.profileIndex
            val syncProfileId = profileId?.takeIf { auth != null && !auth.isAnonymous }
            syncProfileId?.let(SyncManager::pullAllForProfile)

            if (RealtimeSyncConfig.ENABLED && auth != null && !auth.isAnonymous && profileId != null) {
                RealtimeSyncInvalidationService.start(userId = auth.userId, profileId = profileId)
            } else {
                RealtimeSyncInvalidationService.stop()
            }

            try {
                AppForegroundMonitor.events().collectLatest { visibility ->
                    when (visibility) {
                        AppVisibility.Foreground -> {
                            NetworkStatusRepository.requestForegroundRefresh()
                            MemberAccessRepository.refreshIfStale()
                            if (syncProfileId != null) {
                                SyncManager.startPeriodicNuvioSyncPull(syncProfileId)
                                SyncManager.requestForegroundPull(syncProfileId)
                            } else {
                                SyncManager.stopPeriodicNuvioSyncPull()
                            }
                            runCatching { IptvCatalogAccess.catalog.refreshDuePlaylists() }
                                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                        }
                        AppVisibility.Background -> SyncManager.stopPeriodicNuvioSyncPull()
                    }
                }
            } finally {
                SyncManager.stopPeriodicNuvioSyncPull()
                RealtimeSyncInvalidationService.stop()
            }
        }
    }

    /** MainAppContent.kt:596-652: after a connectivity drop, re-pull once the connection is back. */
    private suspend fun watchReconnects() {
        var reconnectPending = false
        NetworkStatusRepository.uiState.map { it.condition }.distinctUntilChanged().collect { condition ->
            when (condition) {
                NetworkCondition.NoInternet, NetworkCondition.ServersUnreachable -> reconnectPending = true
                NetworkCondition.Online -> {
                    if (!reconnectPending) return@collect
                    MemberAccessRepository.refresh()
                    val profileId = ProfileRepository.state.value.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId
                    val auth = AuthRepository.state.value as? AuthState.Authenticated
                    if (auth != null && !auth.isAnonymous) {
                        SyncManager.requestForegroundPull(profileId = profileId)
                        reconnectPending = false
                    } else if (WatchProgressSourceCoordinator.refreshActiveSource(profileId = profileId, force = true).succeeded) {
                        reconnectPending = false
                    }
                }
                NetworkCondition.Unknown, NetworkCondition.Checking -> Unit
            }
        }
    }
}
