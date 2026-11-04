package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Runs the pending device claims (B205) when a real account becomes available.
 *
 * Event-driven, never a timer: [start] collects a flow that emits the real account's id when a real account is
 * signed in with an active profile (so: right after a sign-in, and once at app start when already signed in).
 * A claim that cannot be decided now (network, rate limit, missing profile) stays stored and is tried again at
 * the next such event. Single-flight: overlapping events never run two passes.
 *
 * Owned by the feature wiring ([com.nuvio.app.registerLogicFeatureContributions]); the UI never names it.
 */
internal class DeviceSetupClaimCoordinator(
    private val claims: PendingDeviceClaimStore,
    private val api: () -> ProviderSetupApi,
    /** Emits the id of the real (non-guest) account that is ready to claim into, or null while there is none. */
    private val realAccountReady: Flow<String?>,
    private val activeProfileIndex: () -> Int,
    private val skipAddons: () -> Boolean,
    private val localAccountIds: () -> Set<String>,
    private val addLocalPlaylists: (List<XtreamAccount>) -> Int,
    private val pullPlaylists: suspend (Int) -> Unit,
    private val telemetry: (outcome: String) -> Unit,
    private val scope: CoroutineScope,
) {
    private val log = Logger.withTag("DeviceSetupClaim")
    private val pass = Mutex()
    private var collector: Job? = null

    /** Idempotent: a second call does nothing. */
    fun start() {
        if (collector != null) return
        collector = scope.launch {
            realAccountReady.distinctUntilChanged().collect { id -> if (id != null) runPending() }
        }
    }

    /** One pass over every pending claim. Returns without doing anything when a pass is already running. */
    suspend fun runPending() {
        if (!pass.tryLock()) return
        try {
            val pending = claims.list()
            if (pending.isEmpty()) return
            val profile = activeProfileIndex()
            var pull = false
            for (claim in pending) {
                val result = api().claimDeviceSetup(claim.token, profile, skipAddons())
                val decision = DeviceClaimPolicy.decide(result)
                telemetry(decision.name.lowercase())
                when (decision) {
                    ClaimDecision.DROP_AND_PULL -> {
                        claims.remove(claim.token)
                        pull = true
                    }
                    ClaimDecision.RESTORE_LOCAL_AND_DROP -> {
                        // Re-add only what the sign-in wipe took away, matched by playlist key. If the write
                        // fails the claim stays, so the logins are not lost.
                        val missing = DevicePlaylistMapper.missing(claim.playlists, localAccountIds())
                        if (missing.isEmpty() || addLocalPlaylists(missing) >= 0) claims.remove(claim.token)
                    }
                    ClaimDecision.KEEP_FOR_RETRY -> Unit
                }
            }
            // ONE pull for the profile, however many claims were made.
            if (pull) {
                try {
                    pullPlaylists(profile)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.w { "playlist pull after a claim failed: ${e::class.simpleName}" }
                }
            }
        } finally {
            pass.unlock()
        }
    }

    companion object {
        /** Production wiring. The flow is the signed-in real account, once a profile is active. */
        fun production(): DeviceSetupClaimCoordinator = DeviceSetupClaimCoordinator(
            claims = PendingDeviceClaimStore.shared,
            api = { ManagedInfoRefresher.api },
            realAccountReady = combine(AuthRepository.state, ProfileRepository.state) { auth, profiles ->
                (auth as? AuthState.Authenticated)?.takeIf { !it.isAnonymous && profiles.activeProfile != null }?.userId
            },
            activeProfileIndex = { ProfileRepository.activeProfileId },
            skipAddons = { !AppFeaturePolicy.addonsEnabled },
            localAccountIds = { XtreamRepository.uiState.value.accounts.map { it.id }.toSet() },
            addLocalPlaylists = { XtreamRepository.addProvisioned(it) },
            pullPlaylists = { XtreamSyncParticipant.pullFromServer(it) },
            telemetry = { ProviderSetupTelemetry.capture("setup_code_claim", mapOf("outcome" to it)) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

        val shared: DeviceSetupClaimCoordinator by lazy { production() }
    }
}
