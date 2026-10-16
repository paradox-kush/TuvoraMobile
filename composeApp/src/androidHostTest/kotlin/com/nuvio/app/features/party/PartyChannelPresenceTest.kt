package com.nuvio.app.features.party

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * nuvio-backend's Realtime delivers presence on a private channel only when the client asked for it on join.
 * supabase-kt 3.6.0 asks (`presence.enabled = true` in the join payload) only if a presence callback is
 * registered when `subscribe` runs. This pins that PartyChannel registers it first, without a network.
 * Reflection on a private library method on purpose: a supabase-kt bump that renames it fails here, loudly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PartyChannelPresenceTest {
    private val client = createSupabaseClient("https://example.test", "anon") { install(Realtime) }

    private fun RealtimeChannel.asksForPresence(): Boolean =
        javaClass.getDeclaredMethod("shouldEnablePresence").apply { isAccessible = true }.invoke(this) as Boolean

    @Test
    fun `the party channel asks for presence when it joins`() = runBlocking {
        val dispatcher = Dispatchers.Default.limitedParallelism(1)   // PartyRepository's own dispatcher shape
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val channel = withContext(dispatcher) {
            PartyChannel("party-1", "member-1").attach(client, scope, { _, _ -> }, { _, _ -> }, { })
        }
        assertTrue(channel.asksForPresence(), "presence must be requested on join, or members never see each other")
        scope.cancel()
    }

    @Test
    fun `a channel without a presence listener would not ask - the trap this guards`() {
        val bare = client.channel("watch-party:party-2") { isPrivate = true; presence { key = "m" } }
        assertFalse(bare.asksForPresence())
    }
}
