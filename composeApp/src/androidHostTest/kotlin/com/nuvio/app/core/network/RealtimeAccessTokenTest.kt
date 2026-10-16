package com.nuvio.app.core.network

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * E2E 2026-10-16 asked whether Realtime is handed the publishable key instead of the user's JWT. It is not:
 * the join token is the session JWT (proven live on the local stack: member JWT -> phx_reply ok on the private
 * watch-party channel). This pins it, with the app's own provider and a new-style non-JWT API key.
 */
// Robolectric: the Auth plugin touches Dispatchers.Main, which a bare JVM test does not have (its failure leaked
// into the next suite as "uncaught exceptions before the test started").
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealtimeAccessTokenTest {
    private val apiKey = "sb_publishable_testkey"

    private fun client() = createSupabaseClient("https://example.test", apiKey) {
        install(Auth) {
            sessionManager = MemorySessionManager()
            codeVerifierCache = MemoryCodeVerifierCache()
            alwaysAutoRefresh = false
            autoLoadFromStorage = false
        }
        install(Realtime) { accessToken = realtimeAccessToken }
    }

    @Test
    fun `a signed-in user's channels authorise with the session JWT, not the API key`() = runBlocking {
        val client = client()
        // A real exp claim: Realtime parses it when the session lands (setAuth), and a missing one throws on its scope.
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEiLCJleHAiOjQxMDI0NDQ4MDB9.sig"
        client.auth.importSession(UserSession(accessToken = jwt, refreshToken = "r", expiresIn = 3600, tokenType = "bearer", user = null), autoRefresh = false)
        assertEquals(jwt, client.realtime.config.accessToken(client))
        client.close()
    }

    @Test
    fun `signed out the provider gives nothing and the library falls back to the API key as anon`() = runBlocking {
        val client = client()
        assertNull(client.realtime.config.accessToken(client))
        client.close()
    }
}
