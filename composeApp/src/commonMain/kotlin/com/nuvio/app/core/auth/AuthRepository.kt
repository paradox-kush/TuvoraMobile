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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val refreshMutex = Mutex()

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
        if (!couldBeInvalidSessionError(error.restStatusCode(), error.authErrorText())) return false
        val auth = SupabaseProvider.client.auth
        val staleToken = auth.currentAccessTokenOrNull() ?: return false

        // A 401/jwt-expired usually just means the access token lapsed. The refresh
        // endpoint is the authority: only a rejected refresh proves the session is dead.
        val refreshError = refreshMutex.withLock {
            if (auth.currentAccessTokenOrNull() != staleToken) {
                null // another caller already refreshed while we waited
            } else {
                runCatching { auth.refreshCurrentSession() }.exceptionOrNull()
            }
        }
        if (refreshError == null) {
            log.i { "$source hit an auth error but the session refreshed; keeping auth state" }
            return false
        }
        if (!isInvalidRefreshError(refreshError.restStatusCode(), refreshError.authErrorText())) {
            log.w(refreshError) { "$source hit an auth error and the session refresh failed transiently; keeping auth state" }
            return false
        }

        log.w(error) { "$source failed because the current Supabase account/session is no longer valid; clearing local auth" }
        clearLocalSessionAfterRemoteInvalidation()
        return true
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

    internal fun isInvalidRefreshError(statusCode: Int?, text: String): Boolean =
        statusCode == 400 || statusCode == 401 || statusCode == 403 ||
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
}
