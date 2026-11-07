package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException

/**
 * Decides when the managed map is refreshed — the network rule for the one extra call this feature
 * adds to the playlist pull: delta-shaped (no request when nothing could have changed), never a
 * timer, and never for a profile with no playlists.
 */
internal object ManagedInfoRefreshPolicy {
    /**
     * Refresh the map after a playlist sync that left [playlistCount] playlists on the profile when the
     * server's playlist [revision] moved since the last refresh, or no refresh has run yet this process
     * (a provider can change its contacts without bumping the customer's revision, so the first sync of
     * a launch always refreshes). A profile with zero playlists has nothing managed: no call at all.
     */
    fun shouldRefresh(playlistCount: Int, revision: Long, lastRefreshedRevision: Long?): Boolean =
        playlistCount >= 1 && lastRefreshedRevision != revision
}

/**
 * Keeps [ManagedInfoRepository] in step with the server: refreshed ONLY after a playlist pull that left
 * at least one playlist on the profile and moved the playlist revision (or the first pull of a launch),
 * and explicitly after a redeem or a detach — never on a timer. See [ManagedInfoRefreshPolicy] for the
 * network rule this implements. A failed refresh keeps the cache: the map only ever changes on success.
 */
internal object ManagedInfoRefresher {
    private val log = Logger.withTag("ManagedInfoRefresher")

    internal var api: ProviderSetupApi = HttpProviderSetupApi()
    internal var isSignedIn: () -> Boolean = {
        val s = AuthRepository.state.value
        s is AuthState.Authenticated && !s.isAnonymous
    }
    /** The playlists this device now holds for [profileId]; null when that is not the profile in memory. */
    internal var accountsFor: (Int) -> List<XtreamAccount>? = { profileId ->
        XtreamRepository.uiState.value.accounts.takeIf { ProfileRepository.activeProfileId == profileId }
    }
    /** Loads the playlist repository for the active profile if nothing has yet (a sync can run before any IPTV screen). */
    internal var loadRepository: () -> Unit = { XtreamRepository.ensureLoaded() }
    /** True when the in-memory playlist state is a faithful, complete view of the profile (loaded, not damaged). */
    internal var repositoryReady: () -> Boolean = { XtreamRepository.canPushFullReplace() }
    internal var revisionFor: (Int) -> Long = { profileId ->
        decodePlaylistSyncState(XtreamAccountStorage.loadPlaylistSyncStateJson(profileId)).revision
    }

    private val lastRefreshedRevision = mutableMapOf<Int, Long>()

    /** Called after a playlist pull for [profileId] finished (success or not; a failed pull changes nothing). */
    suspend fun afterPlaylistPull(profileId: Int) {
        if (!isSignedIn()) return
        // A sync can run before any IPTV screen loaded the playlist repository, and an unloaded repository reads
        // as "no playlists": that must never wipe the offline-cold-start cache (code review M1).
        loadRepository()
        if (!repositoryReady()) return
        val accounts = accountsFor(profileId) ?: return
        val revision = revisionFor(profileId)
        if (accounts.isEmpty()) {
            // Nothing can be managed with no playlists, and nothing to ask the server.
            ManagedInfoRepository.replace(profileId, emptyMap())
            lastRefreshedRevision.remove(profileId)
            return
        }
        if (!ManagedInfoRefreshPolicy.shouldRefresh(accounts.size, revision, lastRefreshedRevision[profileId])) return
        if (refresh(profileId)) lastRefreshedRevision[profileId] = revision
    }

    /** Refresh now (after a redeem or a detach). Returns whether the server answered. */
    suspend fun refresh(profileId: Int): Boolean = try {
        val user = ManagedInfoRepository.userId()
        val list = api.managedPlaylists(profileId)
        // The session may have ended (or another user signed in) while the call was out: the old account's
        // providers must not be written back after the wipe (security L8).
        if (!isSignedIn() || ManagedInfoRepository.userId() != user) {
            false
        } else {
            ManagedInfoRepository.replace(profileId, list.associateBy { it.playlistKey })
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Keep the cache; the next pull tries again. The error class only, never a message.
        log.w { "managed refresh failed: ${e::class.simpleName}" }
        false
    }

    fun clearLocalState() {
        lastRefreshedRevision.clear()
    }

    internal fun resetForTest() {
        lastRefreshedRevision.clear()
        api = HttpProviderSetupApi()
    }
}

/** Provider-setup analytics: an outcome only. Never the code, a URL, a provider name or a playlist name. */
internal object ProviderSetupTelemetry {
    internal var capture: (String, Map<String, Any>) -> Unit = com.nuvio.app.core.telemetry.api.TelemetryAccess::emit

    fun previewed(outcome: SetupCodeOutcome) =
        capture("setup_code_preview", mapOf("outcome" to outcome.analyticsName))

    fun redeemed(added: Int, updated: Int, outcome: String) =
        capture("setup_code_redeemed", mapOf("added" to added, "updated" to updated, "outcome" to outcome))

    fun detached() = capture("playlist_detached", emptyMap())
}
