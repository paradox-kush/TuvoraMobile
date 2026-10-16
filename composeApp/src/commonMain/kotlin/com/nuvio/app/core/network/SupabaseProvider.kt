package com.nuvio.app.core.network

import com.nuvio.app.core.auth.shouldRetryAuthRefreshResponse
import com.nuvio.app.core.build.AppVersionConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.websocket.KtorRealtimeWebsocketFactory
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.supabaseJson
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpHeaders
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Duration.Companion.seconds

object SupabaseProvider {
    private data class ClientHolder(
        val backend: SyncBackendConfig,
        val client: SupabaseClient,
    )

    private val clientLock = SynchronizedObject()
    private var holder: ClientHolder? = null

    val selectedBackend: SyncBackendConfig
        get() = SyncBackendRepository.selectedBackend

    @OptIn(SupabaseInternal::class)
    val client: SupabaseClient
        get() = clientFor(selectedBackend)

    fun rebuildClient() {
        synchronized(clientLock) { holder = null }
    }

    // Client construction is single-flight: two coroutines racing this getter at cold start must
    // NOT build two clients — each client's Auth plugin runs its own token auto-refresh against
    // the SAME persisted session, and two refreshers eventually desync past the server's
    // refresh-token reuse window, which revokes the session and signs the user out.
    @OptIn(SupabaseInternal::class)
    private fun clientFor(config: SyncBackendConfig): SupabaseClient = synchronized(clientLock) {
        holder
            ?.takeIf { it.backend.hasSameConnectionIdentity(config) }
            ?.let { return it.client }

        val userAgent = "Tuvora/${AppVersionConfig.VERSION_NAME.ifBlank { "dev" }}"
        val rateLimitCoordinator = BackendRateLimitCoordinator()
        lateinit var nextClient: SupabaseClient
        nextClient = createSupabaseClient(
            supabaseUrl = config.normalizedSupabaseUrl,
            supabaseKey = config.anonKey,
        ) {
            httpConfig {
                install(BackendRateLimitPlugin) { coordinator = rateLimitCoordinator }
                install(HttpRequestRetry) {
                    // Two unrelated reasons to retry share one plugin instance, because installing
                    // HttpRequestRetry twice would just overwrite the first configuration:
                    //
                    // 1. Upstream's primary->fallback endpoint hop; inert unless a fallback URL is
                    //    configured (the fork's self-hosted backend usually has none).
                    // 2. A transient refusal of the token-refresh call. supabase-kt retries only
                    //    its NETWORK_ERROR_CODES (5xx) and unreachable-host failures; every other
                    //    failing status falls through to clearSession(), which deletes the stored
                    //    session and signs the user out permanently. Absorbing 408/429/edge-403
                    //    here means the library never sees them.
                    retryOnExceptionIf(maxRetries = 2) { request, cause ->
                        SupabaseEndpointConfig.shouldRetryWithFallback(
                            requestUrl = request.url.buildString(),
                            cause = cause,
                        )
                    }
                    retryIf(maxRetries = 2) { request, response ->
                        SupabaseEndpointConfig.shouldRetryWithFallback(
                            requestUrl = request.url.toString(),
                            statusCode = response.status.value,
                        ) || shouldRetryAuthRefreshResponse(
                            statusCode = response.status.value,
                            path = request.url.encodedPath,
                            grantType = request.url.parameters["grant_type"],
                            server = response.headers[HttpHeaders.Server],
                            cloudflareRay = response.headers["cf-ray"],
                        )
                    }
                    modifyRequest { request ->
                        SupabaseEndpointConfig.fallbackUrlFor(request.url.buildString())?.let { fallbackUrl ->
                            request.url.takeFrom(fallbackUrl)
                        }
                    }
                    // Deliberately short and few: a refresh retry has to land inside GoTrue's
                    // refresh-token reuse interval, and re-presenting the token after that window
                    // trips reuse detection and revokes the whole family.
                    constantDelay(millis = 100)
                }
                defaultRequest {
                    headers.append(HttpHeaders.UserAgent, userAgent)
                }
                install(WebSockets) {
                    contentConverter = KotlinxWebsocketSerializationConverter(supabaseJson)
                }
            }
            install(Auth) {
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
                autoSaveToStorage = true
            }
            install(Postgrest)
            install(Functions)
            // Storage backs user-uploaded profile avatars (the 'user-avatars' bucket).
            install(Storage)
            // Realtime backs the fork's sync-invalidation service (upstream dropped realtime).
            install(Realtime) {
                // Never use the default accessToken provider. Its resolveAccessToken() force-
                // refreshes an expired session on channel (re)join and throws TokenExpiredException
                // on Realtime's own internal scope (no CoroutineExceptionHandler) -> uncaught ->
                // process death on a reconnect. It is also a SECOND refresher racing GoTrue's
                // refresh-token reuse window, which revokes the family -> spurious sign-out
                // (auth-js#213; see AuthManager single-refresher discipline). Return the CURRENT
                // token only: alwaysAutoRefresh keeps it fresh and propagates via setAuth, and a
                // null (session-less) token is simply omitted so the socket authenticates by apikey.
                accessToken = { auth.currentAccessTokenOrNull() }
                customUrl = SupabaseEndpointConfig.realtimeUrl(config.normalizedSupabaseUrl)
                reconnectDelay = 3.seconds
                websocketFactory = RealtimeSocketFactory { url ->
                    KtorRealtimeWebsocketFactory(nextClient.httpClient.httpClient).create(url)
                }
            }
        }
        holder = ClientHolder(backend = config, client = nextClient)
        nextClient
    }
}
