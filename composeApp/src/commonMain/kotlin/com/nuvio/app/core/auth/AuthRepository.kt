package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.network.SyncBackendRepository
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AuthRepository")

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _signInRequests = MutableStateFlow(0)
    val signInRequests: StateFlow<Int> = _signInRequests.asStateFlow()

    private var initialized = false
    private var validatedRemoteUserId: String? = null

    /** Asks the app shell to show the sign-in screen (used from Settings while signed out). */
    fun requestSignIn() {
        _signInRequests.value += 1
    }

    fun initialize() {
        if (initialized) return
        initialized = true

        scope.launch {
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
                            _state.value = AuthState.Authenticated(
                                userId = userId,
                                email = user?.email,
                                isAnonymous = false,
                            )
                        }
                        is SessionStatus.NotAuthenticated -> {
                            _state.value = AuthState.Unauthenticated
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
                            _state.value = if (user != null) {
                                AuthState.Authenticated(
                                    userId = user.id,
                                    email = user.email,
                                    isAnonymous = false,
                                )
                            } else {
                                AuthState.Unauthenticated
                            }
                        }
                    }
                }
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
            if (isSessionDefinitelyInvalid(e)) {
                log.w(e) { "Stored Supabase session no longer belongs to an active account; clearing local auth" }
                clearLocalSessionAfterRemoteInvalidation()
                false
            } else {
                // Transient, or a stale/unverifiable ACCESS token that the refresh just settled —
                // either way the account is intact; keep the cached auth state.
                validatedRemoteUserId = userId
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
        }
        // Clear any lingering anonymous id so the sessionStatus collector honors the real session.
        AuthStorage.clearAnonymousUserId()
        Unit
    }.onFailure { e ->
        log.e(e) { "Email sign-up failed" }
        _error.value = e.message ?: getString(Res.string.auth_sign_up_failed)
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
        _error.value = e.message ?: getString(Res.string.auth_sign_in_failed)
    }

    suspend fun signOut(): Result<Unit> = runCatching {
        _error.value = null
        val wasAnonymous = AuthStorage.loadAnonymousUserId() != null
        AuthStorage.clearAnonymousUserId()
        validatedRemoteUserId = null
        if (!wasAnonymous) {
            SupabaseProvider.client.auth.signOut()
        }
        _state.value = AuthState.Unauthenticated
        LocalAccountDataCleaner.wipe()
    }.onFailure { e ->
        log.e(e) { "Sign-out failed" }
        _error.value = e.message ?: getString(Res.string.auth_sign_out_failed)
    }

    suspend fun signOutIfSessionInvalid(error: Throwable, source: String): Boolean {
        if (!isSessionDefinitelyInvalid(error)) return false

        log.w(error) { "$source failed because the current Supabase account/session is no longer valid; clearing local auth" }
        clearLocalSessionAfterRemoteInvalidation()
        return true
    }

    /**
     * Decides whether an error really means "this account/session is dead" — the ONLY verdict that
     * may wipe local auth. A 401/bad-JWT from an API call is NOT that: access tokens legitimately
     * go stale/unverifiable (expiry while the app was closed, device clock skew, a backend JWT
     * signing-key rotation), and every one of those heals with a refresh. So the REFRESH TOKEN is
     * the arbiter: on a token-shaped failure we try refreshCurrentSession() and only report
     * invalid when the refresh itself is definitively rejected (invalid_grant & co). Network
     * errors on the refresh stay "not invalid" — never sign out for being offline.
     *
     * (This replaced a blanket 401/403 => sign-out classifier that wiped the session at cold
     * start whenever the stored access token failed verification — the "signed out after every
     * app update" bug.)
     */
    private suspend fun isSessionDefinitelyInvalid(error: Throwable): Boolean {
        if (isUserGoneError(error)) return true
        if (!isAuthTokenError(error)) return false
        val refreshed = runCatching { SupabaseProvider.client.auth.refreshCurrentSession() }
        return refreshed.fold(
            onSuccess = {
                log.i { "Access token was rejected but the session refreshed fine; keeping auth" }
                false
            },
            onFailure = { refreshError -> isRefreshDefinitelyInvalid(refreshError) }
        )
    }

    private suspend fun clearLocalSessionAfterRemoteInvalidation() {
        _error.value = null
        AuthStorage.clearAnonymousUserId()
        validatedRemoteUserId = null
        runCatching {
            SupabaseProvider.client.auth.clearSession()
        }.onFailure { e ->
            log.w(e) { "Failed to clear Supabase session after remote invalidation; continuing local reset" }
        }
        _state.value = AuthState.Unauthenticated
        LocalAccountDataCleaner.wipe()
    }

    suspend fun resetForSyncBackendChange(): Result<Unit> = runCatching {
        _error.value = null
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
        LocalAccountDataCleaner.wipe()
    }.onFailure { e ->
        log.e(e) { "Sync backend auth reset failed" }
        _error.value = e.message ?: getString(Res.string.auth_sign_out_failed)
    }

    suspend fun deleteAccount(): Result<Unit> = runCatching {
        _error.value = null
        SupabaseProvider.client.functions.invoke("delete-account")
        SupabaseProvider.client.auth.signOut()
        validatedRemoteUserId = null
        _state.value = AuthState.Unauthenticated
        LocalAccountDataCleaner.wipe()
    }.onFailure { e ->
        log.e(e) { "Account deletion failed" }
        _error.value = e.message ?: getString(Res.string.auth_account_deletion_failed)
    }

    fun clearError() {
        _error.value = null
    }

    private fun fullMessage(error: Throwable): String {
        val restError = error.findCause<RestException>()
        return buildString {
            append(error.message.orEmpty())
            if (restError != null) {
                append(' ')
                append(restError.error)
                append(' ')
                append(restError.description)
            }
        }.lowercase()
    }

    /** The account itself is gone (deleted server-side) — invalid regardless of tokens. */
    internal fun isUserGoneError(error: Throwable): Boolean {
        val message = fullMessage(error)
        return (
            "user" in message &&
                ("does not exist" in message || "not found" in message || "deleted" in message)
            ) || (
            "foreign key" in message &&
                ("auth.users" in message || "user_id" in message)
            )
    }

    /** A token-shaped rejection (401/403 or a JWT parse/verify/expiry complaint) — refreshable. */
    internal fun isAuthTokenError(error: Throwable): Boolean {
        val restError = error.findCause<RestException>()
        if (restError?.statusCode == 401 || restError?.statusCode == 403) return true
        val message = fullMessage(error)
        return "bad_jwt" in message || (
            "jwt" in message &&
                ("invalid" in message || "expired" in message || "malformed" in message)
            )
    }

    /** The refresh call itself was rejected: the refresh token (= the session) is dead. Network
     *  and 5xx failures are NOT invalid — offline must never sign the user out. */
    internal fun isRefreshDefinitelyInvalid(error: Throwable): Boolean {
        val message = fullMessage(error)
        if (
            "invalid_grant" in message ||
            "invalid refresh token" in message ||
            "refresh token not found" in message ||
            "refresh_token_not_found" in message ||
            "session not found" in message ||
            "session_not_found" in message
        ) {
            return true
        }
        val restError = error.findCause<RestException>()
        return restError?.statusCode == 400 || restError?.statusCode == 401 || restError?.statusCode == 403
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }
}
