package com.nuvio.app.features.party

import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.Presence
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject

internal class PartyChannel(
    private val partyId: String,
    private val memberId: String,
) {
    private var channel: RealtimeChannel? = null
    private var jobs: List<Job> = emptyList()

    val isConnected: Boolean
        get() = channel?.status?.value == RealtimeChannel.Status.SUBSCRIBED

    suspend fun open(
        scope: CoroutineScope,
        onMessage: (String, JsonObject) -> Unit,
        onPresence: (joins: Map<String, Presence>, leaves: Map<String, Presence>) -> Unit,
        onConnected: (Boolean) -> Unit,
    ) {
        val created = attach(SupabaseProvider.client, scope, onMessage, onPresence, onConnected)
        created.subscribe(blockUntilSubscribed = false)
    }

    /**
     * Builds the channel and starts every listener, WITHOUT joining yet. Order matters: supabase-kt 3.6.0
     * asks the server for presence on join only if a presence callback is already registered
     * (`RealtimeChannelImpl.shouldEnablePresence`; `presence { enabled }` is not settable), and the backend's
     * Realtime delivers presence only to channels that asked on join. The collectors start UNDISPATCHED and
     * [yield] lets their producers register before [open] subscribes. Pinned by PartyChannelPresenceTest.
     */
    internal suspend fun attach(
        client: SupabaseClient,
        scope: CoroutineScope,
        onMessage: (String, JsonObject) -> Unit,
        onPresence: (joins: Map<String, Presence>, leaves: Map<String, Presence>) -> Unit,
        onConnected: (Boolean) -> Unit,
    ): RealtimeChannel {
        val created = client.channel("watch-party:$partyId") {
            isPrivate = true
            broadcast {
                receiveOwnBroadcasts = false
                acknowledgeBroadcasts = false
            }
            presence {
                key = memberId
            }
        }
        channel = created
        jobs = EVENTS.map { event ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                created.broadcastFlow<JsonObject>(event).collect { payload -> onMessage(event, payload) }
            }
        } + scope.launch(start = CoroutineStart.UNDISPATCHED) {
            created.presenceChangeFlow().collect { action -> onPresence(action.joins, action.leaves) }
        } + scope.launch(start = CoroutineStart.UNDISPATCHED) {
            created.status.collect { status -> onConnected(status == RealtimeChannel.Status.SUBSCRIBED) }
        }
        yield()
        return created
    }

    suspend fun send(event: String, payload: JsonObject) {
        val current = channel ?: return
        if (current.status.value != RealtimeChannel.Status.SUBSCRIBED) return
        current.broadcast(event, payload)
    }

    suspend fun track(payload: JsonObject) {
        val current = channel ?: return
        if (current.status.value != RealtimeChannel.Status.SUBSCRIBED) return
        current.track(payload)
    }

    suspend fun close() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        val current = channel ?: return
        channel = null
        runCatching { SupabaseProvider.client.realtime.removeChannel(current) }
    }

    private companion object {
        val EVENTS = listOf("state", "state_request", "status", "control", "member_joined", "member_left", "ended", "media", "stream")
    }
}
