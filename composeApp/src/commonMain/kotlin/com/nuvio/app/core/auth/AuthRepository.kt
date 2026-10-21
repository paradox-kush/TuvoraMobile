package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.network.SyncBackendRepository
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

/**
 * The Terms of Use version recorded in sign-up metadata. Bump it (and its pinning test) whenever
 * https://tuvora.co/terms changes, so the recorded acceptance matches the text the user agreed to.
 */
internal const val TUVORA_TERMS_VERSION = "2026-09-27"

object AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AuthRepository")

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _signInRequests = MutableStateFlow(0)
    val signInRequests: StateFlow<Int> = _signInRequests.asStateFlow()

    private val _sessionLostNotice = MutableStateFlow<LocalDataOwner?>(null)

    /**
     * Non-null while the "you were signed out; everything on this device was kept — sign in again"
     * notice should show. Raised only for a lost session (never a deliberate sign-out), see D1.
     */
    val sessionLostNotice: StateFlow<LocalDataOwner?> = _sessionLostNotice.asStateFlow()

    private val _accountSwitchPrompt = MutableStateFlow<AccountSwitchPrompt?>(null)

    /**
     * Non-null while a DIFFERENT account has signed in over a lost session's kept data. That account
     * is held back (auth stays signed out, so nothing syncs) until the viewer confirms or cancels.
     */
    val accountSwitchPrompt: StateFlow<AccountSwitchPrompt?> = _accountSwitchPrompt.asStateFlow()

    private var initialized = false
    private var validatedRemoteUserId: String? = null

    // How long startup may sit in Loading before the watchdog surfaces the persisted/anonymous
    // session (or signed-out). Matches NuvioTV AuthManager's 3s stall timeout.
    private const val AUTH_INIT_STALL_TIMEOUT_MS = 3_000L

    /** Asks the app shell to show the sign-in screen (used from Settings while signed out). */
    fun requestSignIn() {
        _signInRequests.value += 1
    }

    fun initialize() {
        if (initialized) return
        initialized = true
        com.nuvio.app.core.sources.DeviceSources.activate()

        scope.launch {
            // Load the persisted sync-backend selection up front so SyncBackendState.isLoaded flips
            // regardless of whether any other subsystem happens to touch SupabaseProvider first.
            // Without this, an anonymous / zero-profile viewer whose avatar catalog cache is warm
            // never triggers a Supabase client build (the only accidental toucher), isLoaded stays
            // false, and this collector parks on the guard below forever -> auth is stuck in Loading
            // -> infinite startup spinner on every launch after the first. Mirrors NuvioTV's
            // AuthManager, which has always called ensureLoaded() here. (Fixes the startup deadlock.)
            SyncBackendRepository.ensureLoaded()
            SyncBackendRepository.state.collectLatest { backendState ->
                if (!backendState.isLoaded) return@collectLatest
                validatedRemoteUserId = null

                AuthStorage.loadAnonymousUserId()?.let { savedAnonId ->
                    _state.value = AuthState.Authenticated(
                        userId = savedAnonId,
                        email = null,
                        isAnonymous = true,
                    )
                } ?: run {
                    _state.value = AuthState.Loading
                }

                SupabaseProvider.client.auth.sessionStatus.collect { status ->
                    if (AuthStorage.loadAnonymousUserId() != null) return@collect
                    when (status) {
                        is SessionStatus.Authenticated -> {
                            val user = status.session.user
                            val userId = user?.id.orEmpty()
                            if (!validateRemoteSession(userId)) return@collect
                            publishAuthState(
                                AuthState.Authenticated(
                                    userId = userId,
                                    email = user?.email,
                                    isAnonymous = false,
                                )
                            )
                        }
                        is SessionStatus.NotAuthenticated -> {
                            // A process restart or a transient SDK refresh failure can drop the
                            // in-memory session before the persisted rotating refresh token has
                            // been re-imported. Only show sign-in when storage has no recoverable
                            // full-account session. Explicit sign-out clears storage first.
                            if (!restorePersistedSession()) {
                                // No session left to switch to, so any pending switch prompt is moot.
                                _accountSwitchPrompt.value = null
                                _state.value = AuthState.Unauthenticated
                                // A session that vanished (e.g. "No entry with the key sb-…-session"
                                // on launch) keeps the data and asks the viewer to sign in again.
                                refreshSessionLostNotice()
                            }
                        }
                        is SessionStatus.Initializing -> {
                            if (AuthStorage.loadAnonymousUserId() == null) {
                                _state.value = AuthState.Loading
                            }
                        }
                        is SessionStatus.RefreshFailure -> {
                            // Offline/flaky token refresh is NOT a sign-out: keep showing the
                            // persisted session (supabase settles the real state once reachable).
                            val user = runCatching {
                                SupabaseProvider.client.auth.sessionManager.loadSession()
                            }.getOrNull()?.user
                            publishAuthState(
                                authStateAfterRefreshFailure(
                                    current = _state.value,
                                    persistedUserId = user?.id,
                                    persistedEmail = user?.email,
                                )
                            )
                        }
                    }
                }
            }
        }

        // Watchdog: if auth has not settled shortly after start, surface whatever the device
        // already knows so the UI leaves the loading gate. Two real stalls need this: an offline
        // cold start, where supabase-kt retries the boot token refresh indefinitely (~10s/attempt)
        // while sessionStatus sits in Initializing and authState stays Loading; and any regression
        // that leaves the sync backend's isLoaded unflipped. The collector above overwrites this
        // once the real state settles. Mirrors NuvioTV AuthManager.unblockUiIfAuthInitStalls().
        scope.launch {
            SyncBackendRepository.ensureLoaded()
            delay(AUTH_INIT_STALL_TIMEOUT_MS)
            if (_state.value !is AuthState.Loading) return@launch
            val anonymousUserId = runCatching { AuthStorage.loadAnonymousUserId() }.getOrNull()
            val persistedUser = runCatching {
                SupabaseProvider.client.auth.sessionManager.loadSession()
            }.getOrNull()?.user
            publishAuthState(
                authStateAfterInitStall(
                    current = _state.value,
                    anonymousUserId = anonymousUserId,
                    persistedUserId = persistedUser?.id,
                    persistedEmail = persistedUser?.email,
                )
            )
            if (_state.value is AuthState.Unauthenticated) refreshSessionLostNotice()
            log.w { "Auth init not settled after ${AUTH_INIT_STALL_TIMEOUT_MS}ms; proceeding with ${_state.value::class.simpleName}" }
        }
    }

    private suspend fun restorePersistedSession(): Boolean {
        val auth = SupabaseProvider.client.auth
        val persistedSession = runCatching { auth.sessionManager.loadSession() }
            .onFailure { error -> log.w(error) { "Unable to read persisted Supabase session" } }
            .getOrNull()
            ?: return false
        val user = persistedSession.user ?: return false
        if (persistedSession.refreshToken.isBlank()) return false

        // Keep the cached account usable offline while the SDK refreshes in the background.
        publishAuthState(
            AuthState.Authenticated(
                userId = user.id,
                email = user.email,
                isAnonymous = false,
            )
        )
        return runCatching {
            auth.importSession(persistedSession, autoRefresh = true)
            true
        }.getOrElse { error ->
            if (isInvalidRefreshError(error.restStatusCode(), error.authErrorText())) {
                log.w(error) { "Persisted Supabase session was rejected; clearing local auth" }
                clearLocalSessionAfterRemoteInvalidation()
                false
            } else {
                log.w(error) { "Persisted Supabase session could not refresh yet; keeping cached auth state" }
                true
            }
        }
    }

    private suspend fun validateRemoteSession(userId: String): Boolean {
        if (userId.isBlank() || validatedRemoteUserId == userId) return true

        return runCatching {
            SupabaseProvider.client.auth.retrieveUserForCurrentSession(false)
            validatedRemoteUserId = userId
            true
        }.getOrElse { e ->
            if (signOutIfSessionInvalid(e, "Session validation")) {
                false
            } else {
                log.w(e) { "Unable to validate stored Supabase session; keeping cached auth state" }
                true
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun signInAnonymously() {
        _error.value = null
        val userId = Uuid.random().toString()
        AuthStorage.saveAnonymousUserId(userId)
        _state.value = AuthState.Authenticated(
            userId = userId,
            email = null,
            isAnonymous = true,
        )
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> = runCatching {
        _error.value = null
        SupabaseProvider.client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
            data = buildJsonObject {
                put("adult_confirmed", true)
                put("terms_version", TUVORA_TERMS_VERSION)
            }
        }
        // Clear any lingering anonymous id so the sessionStatus collector honors the real session.
        AuthStorage.clearAnonymousUserId()
        Unit
    }.onFailure { e ->
        log.e(e) { "Email sign-up failed" }
        _error.value = e.safeAuthErrorDescription()
            ?: getString(Res.string.auth_sign_up_failed)
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> = runCatching {
        _error.value = null
        SupabaseProvider.client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        // Clear any lingering anonymous id so the sessionStatus collector honors the real session.
        AuthStorage.clearAnonymousUserId()
    }.onFailure { e ->
        log.e(e) { "Email sign-in failed" }
        _error.value = e.safeAuthErrorDescription()
            ?: getString(Res.string.auth_sign_in_failed)
    }

    suspend fun signOut(): Result<Unit> {
        _error.value = null
        // Deliberate: forget the data owner BEFORE the session goes, so the NotAuthenticated this
        // triggers is not mistaken for a lost session (no "sign in again" notice).
        forgetLocalDataOwner()
        val anonymousRead = runCatching { AuthStorage.loadAnonymousUserId() }
        val wasAnonymous = anonymousRead.getOrNull() != null
        val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }
        validatedRemoteUserId = null
        val remoteSignOut = if (wasAnonymous) {
            Result.success(Unit)
        } else {
            runCatching { SupabaseProvider.client.auth.signOut() }
        }

        val fallbackSessionClear = if (remoteSignOut.isFailure) {
            runCatching { SupabaseProvider.client.auth.clearSession() }
                .onFailure { error -> log.w(error) { "Failed to clear Supabase session after sign-out failure" } }
        } else {
            Result.success(Unit)
        }
        val localCleanup = runCatching {
            endAccountSession(SessionEndReason.USER_SIGN_OUT, clearLocalData = LocalAccountDataCleaner::wipe)
        }
        forgetLocalDataOwner()
        _state.value = AuthState.Unauthenticated

        val failure = anonymousRead.exceptionOrNull()
            ?: anonymousClear.exceptionOrNull()
            ?: remoteSignOut.exceptionOrNull()
            ?: fallbackSessionClear.exceptionOrNull()
            ?: localCleanup.exceptionOrNull()
        val cancellation = remoteSignOut.exceptionOrNull() as? CancellationException
            ?: fallbackSessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return if (failure == null) {
            Result.success(Unit)
        } else {
            log.e(failure) { "Sign-out did not complete cleanly; all local cleanup steps were attempted" }
            _error.value = failure.message ?: runCatching {
                getString(Res.string.auth_sign_out_failed)
            }.getOrDefault("Sign out failed")
            Result.failure(failure)
        }
    }

    suspend fun signOutIfSessionInvalid(error: Throwable, source: String): Boolean {
        if (!couldBeInvalidSessionError(error.restStatusCode(), error.authErrorText())) return false

        // Single refresher: do NOT fire our own refresh here — it would race supabase-kt's
        // alwaysAutoRefresh loop on the rotating refresh token and trip GoTrue reuse detection
        // (refresh_token_not_found -> forced sign-out mid-session; supabase/auth-js#213). Sign out
        // only if the error ITSELF is a genuine invalid-session / deleted-user marker; otherwise
        // keep the session and let the loop recover (recover-not-eject). A genuinely revoked or
        // deleted session is caught by the loop's NotAuthenticated -> restorePersistedSession path.
        if (!isInvalidRefreshError(error.restStatusCode(), error.authErrorText())) {
            log.w(error) { "$source hit an auth error that is not a genuine invalid session; keeping auth state" }
            return false
        }

        log.w(error) { "$source failed because the Supabase account/session is no longer valid; clearing local auth" }
        clearLocalSessionAfterRemoteInvalidation()
        return true
    }

    /**
     * The server said this session is dead. That is a LOST session, not a sign-out: the dead auth is
     * dropped, but every profile's local data — including progress that never synced — stays, and the
     * viewer is asked to sign in again (D1). Signing back in to the same account syncs what is pending;
     * a different account is held behind [accountSwitchPrompt].
     */
    private suspend fun clearLocalSessionAfterRemoteInvalidation() {
        _error.value = null
        AuthStorage.clearAnonymousUserId()
        validatedRemoteUserId = null
        runCatching {
            SupabaseProvider.client.auth.clearSession()
        }.onFailure { e ->
            log.w(e) { "Failed to clear Supabase session after remote invalidation; local data is kept" }
        }
        _state.value = AuthState.Unauthenticated
        endAccountSession(
            reason = SessionEndReason.SESSION_LOST,
            clearLocalData = LocalAccountDataCleaner::wipe,
            onSessionLost = ::refreshSessionLostNotice,
        )
    }

    /**
     * The one place a full (non-anonymous) account becomes the app's auth state. When a lost
     * session's data from a DIFFERENT account is still on the device, that account is held back —
     * auth stays signed out so no sync can push the kept data into it — and the viewer is asked.
     */
    private fun publishAuthState(next: AuthState) {
        if (next !is AuthState.Authenticated || next.isAnonymous || next.userId.isBlank()) {
            _state.value = next
            return
        }
        val owner = LocalDataOwnerStore.load()
        when (AccountDataRetentionPolicy.decideOnSignIn(owner, next.userId)) {
            SignInDataDecision.PROCEED -> {
                LocalDataOwnerStore.record(LocalDataOwner(userId = next.userId, email = next.email))
                _accountSwitchPrompt.value = null
                _sessionLostNotice.value = null
                _state.value = next
            }
            SignInDataDecision.ASK_BEFORE_REPLACING_OTHER_ACCOUNT_DATA -> {
                val previousOwner = owner ?: return
                if (_accountSwitchPrompt.value?.newUserId != next.userId) {
                    log.w { "A different account signed in over a lost session's kept data; asking before touching it" }
                }
                _accountSwitchPrompt.value = AccountSwitchPrompt(
                    previousOwner = previousOwner,
                    newUserId = next.userId,
                    newEmail = next.email,
                )
                _sessionLostNotice.value = null
                if (_state.value !is AuthState.Unauthenticated) _state.value = AuthState.Unauthenticated
            }
        }
    }

    private fun refreshSessionLostNotice() {
        val owner = LocalDataOwnerStore.load()
        _sessionLostNotice.value = owner?.takeIf {
            AccountDataRetentionPolicy.showsSessionLostNotice(
                isSignedOut = _state.value is AuthState.Unauthenticated,
                owner = it,
                acknowledged = LocalDataOwnerStore.isNoticeAcknowledged(),
            )
        }
    }

    /** The viewer has seen the lost-session notice; do not show it again until the next loss. */
    fun acknowledgeSessionLostNotice() {
        LocalDataOwnerStore.acknowledgeNotice()
        _sessionLostNotice.value = null
    }

    /**
     * The viewer confirmed continuing as the new account: the previous account's kept data leaves the
     * device (a deliberate choice, like signing that account out), then the new account signs in and
     * syncs its own data. Nothing of the previous account is pushed to the new one.
     */
    suspend fun confirmAccountSwitch() {
        val prompt = _accountSwitchPrompt.value ?: return
        forgetLocalDataOwner()
        runCatching {
            endAccountSession(SessionEndReason.SWITCHED_ACCOUNT, clearLocalData = LocalAccountDataCleaner::wipe)
        }.onFailure { error ->
            log.e(error) { "Local cleanup failed while switching accounts" }
        }
        forgetLocalDataOwner()
        val liveUser = runCatching { SupabaseProvider.client.auth.currentUserOrNull() }.getOrNull()
            ?.takeIf { it.id == prompt.newUserId }
        publishAuthState(
            AuthState.Authenticated(
                userId = prompt.newUserId,
                email = liveUser?.email ?: prompt.newEmail,
                isAnonymous = false,
            )
        )
    }

    /**
     * The viewer chose to keep the previous account's data: the new account's session is dropped
     * (local scope — its other devices are untouched) and the kept data stays, waiting for the
     * previous account to sign back in.
     */
    suspend fun cancelAccountSwitch() {
        _accountSwitchPrompt.value ?: return
        _accountSwitchPrompt.value = null
        // They just read why they are signed out; don't stack the lost-session notice on top.
        LocalDataOwnerStore.acknowledgeNotice()
        _sessionLostNotice.value = null
        validatedRemoteUserId = null
        runCatching { SupabaseProvider.client.auth.signOut() }
            .onFailure { error ->
                log.w(error) { "Sign-out of the declined account failed; clearing its session locally" }
                runCatching { SupabaseProvider.client.auth.clearSession() }
            }
        _state.value = AuthState.Unauthenticated
    }

    private fun forgetLocalDataOwner() {
        LocalDataOwnerStore.clear()
        _sessionLostNotice.value = null
        _accountSwitchPrompt.value = null
    }

    suspend fun resetForSyncBackendChange(): Result<Unit> = runCatching {
        _error.value = null
        forgetLocalDataOwner()
        val wasAnonymous = AuthStorage.loadAnonymousUserId() != null
        AuthStorage.clearAnonymousUserId()
        validatedRemoteUserId = null

        if (!wasAnonymous) {
            runCatching {
                SupabaseProvider.client.auth.signOut()
            }.onFailure { e ->
                log.w(e) { "Supabase sign-out failed during sync backend reset; continuing local reset" }
            }
        }

        _state.value = AuthState.Unauthenticated
        endAccountSession(SessionEndReason.SYNC_BACKEND_SWITCH, clearLocalData = LocalAccountDataCleaner::wipe)
        forgetLocalDataOwner()
    }.onFailure { e ->
        log.e(e) { "Sync backend auth reset failed" }
        _error.value = e.message ?: getString(Res.string.auth_sign_out_failed)
    }

    suspend fun deleteAccount(): Result<Unit> = runCatching {
        _error.value = null
        SupabaseProvider.client.functions.invoke("delete-account")
        forgetLocalDataOwner()
        SupabaseProvider.client.auth.signOut()
        validatedRemoteUserId = null
        try {
            endAccountSession(SessionEndReason.ACCOUNT_DELETED, clearLocalData = LocalAccountDataCleaner::wipe)
        } finally {
            forgetLocalDataOwner()
            _state.value = AuthState.Unauthenticated
        }
        Unit
    }.onFailure { e ->
        log.e(e) { "Account deletion failed" }
        _error.value = e.message ?: getString(Res.string.auth_account_deletion_failed)
    }

    fun clearError() {
        _error.value = null
    }

    private fun Throwable.restStatusCode(): Int? = findCause<RestException>()?.statusCode

    private fun Throwable.authErrorText(): String {
        val restError = findCause<RestException>()
        return buildString {
            append(message.orEmpty())
            if (restError != null) {
                append(' ')
                append(restError.error)
                append(' ')
                append(restError.description)
            }
        }.lowercase()
    }

    // Classifiers are pure (status + lowercased text) so tests need no ktor fixtures.
    // couldBeInvalidSessionError only gates the refresh probe; it never signs out by itself.
    internal fun couldBeInvalidSessionError(statusCode: Int?, text: String): Boolean =
        statusCode == 401 || statusCode == 403 ||
            (
                "jwt" in text &&
                    ("invalid" in text || "expired" in text || "malformed" in text)
                ) || (
                "user" in text &&
                    ("does not exist" in text || "not found" in text || "deleted" in text)
                ) || (
                "foreign key" in text &&
                    ("auth.users" in text || "user_id" in text)
                )

    /**
     * A RefreshFailure is the SDK announcing it will retry — it has not cleared storage at that
     * point, and NotAuthenticated is the only status that genuinely means "signed out". Forcing
     * Unauthenticated here signed people out over a single unreadable session read, so an unknown
     * persisted user now leaves the existing state untouched.
     */
    internal fun authStateAfterRefreshFailure(
        current: AuthState,
        persistedUserId: String?,
        persistedEmail: String?
    ): AuthState =
        if (!persistedUserId.isNullOrBlank()) {
            AuthState.Authenticated(
                userId = persistedUserId,
                email = persistedEmail,
                isAnonymous = false,
            )
        } else {
            current
        }

    /**
     * The fallback [AuthState] applied by the init-stall watchdog when startup has not settled in
     * time. Precedence mirrors [initialize]'s own: a state that already left [AuthState.Loading]
     * wins (never override a settled state); then a saved anonymous id (a "Continue Without
     * Account" viewer, whose id lives outside the Supabase session); then a persisted full-account
     * session; otherwise signed-out. Pure so it tests without Supabase, storage, or the player.
     */
    internal fun authStateAfterInitStall(
        current: AuthState,
        anonymousUserId: String?,
        persistedUserId: String?,
        persistedEmail: String?,
    ): AuthState =
        when {
            current !is AuthState.Loading -> current
            !anonymousUserId.isNullOrBlank() -> AuthState.Authenticated(
                userId = anonymousUserId,
                email = null,
                isAnonymous = true,
            )
            !persistedUserId.isNullOrBlank() -> AuthState.Authenticated(
                userId = persistedUserId,
                email = persistedEmail,
                isAnonymous = false,
            )
            else -> AuthState.Unauthenticated
        }

    internal fun isInvalidRefreshError(statusCode: Int?, text: String): Boolean =
        // recover-not-eject: a bare 400/401/403 is NOT proof the session is dead (a Cloudflare
        // edge-403, a rate-limit, a lapsed access token). Only a genuine GoTrue invalid-session or
        // deleted-user marker signs the user out; everything else keeps the session so the
        // alwaysAutoRefresh loop can recover. See supabase/auth-js#213. (statusCode retained for the
        // call/test signature; the decision is reason-based.)
        listOf(
            "invalid refresh token",
            "refresh token not found",
            "refresh_token_not_found",
            "invalid_grant",
            "session not found",
            "invalid session",
            "invalid token",
            "user not found",
            "user does not exist",
        ).any { it in text }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    private fun Throwable.safeAuthErrorDescription(): String? =
        findCause<AuthRestException>()
            ?.errorDescription
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: findCause<RestException>()
                ?.description
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
}
