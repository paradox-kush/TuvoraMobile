package com.nuvio.app.features.radar.alerts

import com.nuvio.app.core.sync.RealtimeChannelHealthPolicy
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject

/**
 * One receive-only sports-alerts topic (`sports-alerts:v1:{sport}`) on the app's ONE Realtime client (no new socket).
 * Modelled on PartyChannel: private, broadcast only, no presence, never our own broadcasts; the `alert` listener is
 * registered BEFORE subscribe (the collector starts UNDISPATCHED, then [yield]), and the channel is torn down with
 * `realtime.removeChannel` in NonCancellable — never `unsubscribe`, which leaves it registered and rejoined on every
 * reconnect. The topic IS the channel name, so it cannot carry a sequence suffix: callers serialise [open] / [close]
 * through one Mutex and never re-open a topic before its previous close finished (the reused-channel crash).
 */
internal class SportsAlertChannel(
    private val client: SupabaseClient,
    val topic: String,
) {
    private var channel: RealtimeChannel? = null
    private var listener: Job? = null

    /** Joins and waits until SUBSCRIBED (deadline [SUBSCRIBE_TIMEOUT_MS]). On any failure the channel is removed again. */
    suspend fun open(scope: CoroutineScope, onAlert: (JsonObject) -> Unit) {
        val created = client.channel(topic) {
            isPrivate = true
            broadcast {
                receiveOwnBroadcasts = false
                acknowledgeBroadcasts = false
            }
        }
        channel = created
        listener = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            created.broadcastFlow<JsonObject>(EVENT).collect { payload -> onAlert(payload) }
        }
        yield()
        try {
            withTimeout(SUBSCRIBE_TIMEOUT_MS) { created.subscribe(blockUntilSubscribed = true) }
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    /**
     * Suspends while the channel is healthy; returns once it has left SUBSCRIBED for longer than [grace]
     * (server close, token expiry, standby) so the caller can rejoin. Reacts to status changes; no polling.
     */
    suspend fun awaitUnhealthy(grace: Long = UNHEALTHY_GRACE_MS) {
        val current = channel ?: return
        while (true) {
            current.status.first { it != RealtimeChannel.Status.SUBSCRIBED }
            withTimeoutOrNull(grace) { current.status.first { it == RealtimeChannel.Status.SUBSCRIBED } } ?: return
        }
    }

    /** Idempotent. Runs to completion even when the caller is being cancelled. */
    suspend fun close() {
        listener?.cancel()
        listener = null
        val doomed = channel ?: return
        channel = null
        withContext(NonCancellable) {
            runCatching { client.realtime.removeChannel(doomed) }
        }
    }

    companion object {
        const val EVENT = "alert"
        const val SUBSCRIBE_TIMEOUT_MS = 15_000L
        /** Same grace as the sync-invalidation channel. */
        const val UNHEALTHY_GRACE_MS = RealtimeChannelHealthPolicy.GRACE_MS
    }
}
