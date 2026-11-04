package com.tuvora.tvos.screens

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.iptv.AccountKind
import com.nuvio.app.features.iptv.ContinueResult
import com.nuvio.app.features.iptv.ManagedInfoRefresher
import com.nuvio.app.features.iptv.ManagedInfoRepository
import com.nuvio.app.features.iptv.SetupCodeController
import com.nuvio.app.features.iptv.SetupWaitPolicy
import com.nuvio.app.features.iptv.SetupWaitResult
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.XtreamSyncParticipant
import com.nuvio.app.features.iptv.accountKindOf
import com.nuvio.app.features.iptv.runSetupWait
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.time.TimeSource

/** How [TvProviderSetup.waitForPhone] ended: [foundKey] is the new playlist to open, else [reason] says why it stopped. */
data class TvSetupWaitOutcome(val foundKey: String?, val providerName: String?, val reason: String)

/**
 * The Apple TV "Enter setup code" screen's door to the shared setup-code flow (contract sections 1-4, 9).
 * Everything that decides anything is shared Kotlin: [SetupCodeController] (preview, redeem, the code held
 * in memory only), [TvSetupCodeEntry] (keypad), [TvSetupStateBuilder] (what each state shows) and
 * [SetupWaitPolicy] (the wait for a phone). This object only wires them for SwiftUI; the code never
 * leaves memory and is never logged.
 */
object TvProviderSetup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller get() = SetupCodeController.shared
    private val clock = TimeSource.Monotonic.markNow()
    private fun nowMs(): Long = clock.elapsedNow().inWholeMilliseconds

    /** The wait that is running right now, so typing the code on the TV can end it. */
    private var waiting: SetupWaitPolicy? = null

    private fun currentState(): TvSetupState = build(controller.state.value, AuthRepository.state.value)

    private fun build(ui: com.nuvio.app.features.iptv.SetupCodeUiState, auth: AuthState): TvSetupState {
        val profiles = ProfileRepository.state.value.profiles.map { TvSetupProfile(it.profileIndex, it.name) }
        return TvSetupStateBuilder.build(
            ui = ui, kind = accountKindOf(auth), accountLabel = (auth as? AuthState.Authenticated)?.email,
            profiles = profiles, activeProfile = ProfileRepository.activeProfileId, showAddons = AppFeaturePolicy.addonsEnabled,
        )
    }

    val state: StateFlow<TvSetupState> = combine(controller.state, AuthRepository.state, ProfileRepository.state) { ui, auth, _ ->
        build(ui, auth)
    }.stateIn(scope, SharingStarted.Eagerly, currentState())

    /** The screen opened: start clean (a code left over from earlier is forgotten). */
    fun begin() {
        waiting = null
        controller.cancel()
    }

    /** A key on the pad. Typing the code here ends the wait for a phone: there is nothing left to wait for. */
    fun typeKey(key: String) {
        waiting?.codeTypedHere()
        controller.onTyped(TvSetupCodeEntry.append(controller.state.value.typed, key))
    }

    fun backspace() {
        controller.onTyped(TvSetupCodeEntry.backspace(controller.state.value.typed))
    }

    /** Text from the system field (the iPhone keyboard) or a paste. */
    fun typeText(raw: String) {
        if (raw.isNotEmpty()) waiting?.codeTypedHere()
        controller.onTyped(TvSetupCodeEntry.fromKeyboard(raw))
    }

    /** Continue: hold the code in memory and look at what it would add. One preview request. */
    fun continueWithCode() {
        // Signed in or not (B205): a code can always be looked at, and added to this device without an account.
        when (controller.onContinue()) {
            ContinueResult.OPEN_PREVIEW -> controller.loadPreview(force = true)
            ContinueResult.REJECTED -> Unit
        }
    }

    fun selectProfile(index: Int) = controller.selectProfile(index)

    /** "Add to <profile>": one redeem, then ONE playlist pull. Without an account: "Add to this device", no pull. */
    fun confirm() = controller.confirm()

    /** Cancel / "Enter a different code" / leaving the screen: the code and everything about it is forgotten. */
    fun cancel() {
        waiting = null
        controller.cancel()
    }

    /** After the screen has navigated away from a finished redeem. */
    fun finish() {
        waiting = null
        controller.finish()
    }

    /**
     * Finishes by itself when a phone redeems (SetupWaitPolicy): reads `get_managed_playlists` for the active
     * profile on the policy's slow clock while the caller's screen is visible, and on success forces ONE
     * playlist pull. Cancelling the caller (leaving the screen, the app going to the background) ends it at
     * once. [TvSetupWaitOutcome.foundKey] is the new playlist now on this device, to open.
     */
    suspend fun waitForPhone(): TvSetupWaitOutcome {
        if (accountKindOf(AuthRepository.state.value) != AccountKind.REAL) return TvSetupWaitOutcome(null, null, "not_signed_in")
        val profile = ProfileRepository.activeProfileId
        val api = ManagedInfoRefresher.api
        // What is already here: the server's own list when it answers (one read), else the cache, plus every
        // playlist on this device (TvWaitSnapshot), so neither a stale cache nor a playlist redeemed elsewhere
        // earlier is announced as new.
        val serverKeys = try {
            api.managedPlaylists(profile).map { it.playlistKey }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        val snapshot = TvWaitSnapshot.of(serverKeys, ManagedInfoRepository.forProfile(profile).keys, XtreamRepository.uiState.value.accounts.map { it.id }.toSet())
        val policy = SetupWaitPolicy(snapshot, startedAtMs = nowMs())
        waiting = policy
        try {
            return when (val result = runSetupWait(policy, ::nowMs) { api.managedPlaylists(profile).map { it.playlistKey }.toSet() }) {
                is SetupWaitResult.Found -> {
                    XtreamSyncParticipant.pullFromServer(profile)
                    val local = XtreamRepository.uiState.value.accounts.map { it.id }.toSet()
                    val key = result.newKeys.firstOrNull { it in local }
                    val provider = ManagedInfoRepository.infoFor(profile, key ?: result.newKeys.first())?.providerName
                    TvSetupWaitOutcome(key, provider, "found")
                }
                is SetupWaitResult.Stopped -> TvSetupWaitOutcome(null, null, result.reason.name.lowercase())
            }
        } finally {
            if (waiting === policy) waiting = null
        }
    }
}
