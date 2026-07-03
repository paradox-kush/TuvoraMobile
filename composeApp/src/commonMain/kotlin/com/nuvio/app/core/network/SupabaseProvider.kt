package com.nuvio.app.core.network

import com.nuvio.app.core.build.AppVersionConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

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
        val nextClient = createSupabaseClient(
            supabaseUrl = config.normalizedSupabaseUrl,
            supabaseKey = config.anonKey,
        ) {
            httpConfig {
                defaultRequest {
                    headers.append(HttpHeaders.UserAgent, userAgent)
                }
            }
            install(Auth)
            install(Postgrest)
            install(Functions)
        }
        holder = ClientHolder(backend = config, client = nextClient)
        nextClient
    }
}
