package com.nuvio.app.features.radar.alerts

import co.touchlab.kermit.Logger
import com.nuvio.app.core.sync.RealtimeRetryPolicy
import com.nuvio.app.features.common.lifecycle.Disposable
import com.nuvio.app.features.common.lifecycle.EffectScope
import io.github.jan.supabase.SupabaseClient
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonObject

/**
 * Keeps this device joined to exactly the alert topics [desiredTopics] asks for. The caller gates that flow on the
 * app being in the FOREGROUND (it is empty in the background) and re-derives it on every follows / settings / auth /
 * profile change, so leaving the foreground leaves every topic. Each joined topic is a [Disposable] adopted by the
 * caller's [EffectScope]; disposing the scope leaves them all (LIFO). Joins retry with [RealtimeRetryPolicy].
 * No timers beyond the retry back-off, no polls, no HTTP.
 */
internal class SportsAlertSubscription(
    private val client: () -> SupabaseClient,
    private val desiredTopics: Flow<Set<String>>,
    private val onAlert: (JsonObject) -> Unit,
    private val scope: CoroutineScope,
) {
    private val log = Logger.withTag("SportsAlerts")

    /** This session's joined topics. One session per instance: a restart builds a new subscription. */
    private val handles = HashMap<String, Disposable>()

    fun start(effects: EffectScope): Disposable {
        val watcher = scope.launch {
            desiredTopics.distinctUntilChanged().collect { desired -> reconcile(desired, effects) }
        }
        // Registered first, so it is reverted LAST: the topics leave before the watcher stops.
        return effects.onRevert { watcher.cancel() }
    }

    /** Runs only in the watcher coroutine (one collector), so [handles] needs no lock. */
    private fun reconcile(desired: Set<String>, effects: EffectScope) {
        (handles.keys - desired).toList().forEach { topic -> handles.remove(topic)?.dispose() }
        (desired - handles.keys).forEach { topic -> handles[topic] = effects.adopt(join(topic)) }
    }

    private fun join(topic: String): Disposable {
        // LAZY: started only once the previous holder of this topic has fully left (see below).
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var attempt = 1
            while (isActive) {
                val channel = SportsAlertChannel(client(), topic)
                try {
                    joinLeave.withLock { channel.open(this, onAlert) }
                    log.i { "joined $topic" }
                    val joinedAt = TimeSource.Monotonic.markNow()
                    channel.awaitUnhealthy()
                    if (joinedAt.elapsedNow().inWholeMilliseconds >= RealtimeRetryPolicy.STABLE_CONNECTION_MS) attempt = 1
                    log.w { "$topic left SUBSCRIBED; rejoining" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.w(e) { "join $topic failed (attempt $attempt)" }
                } finally {
                    // close() is NonCancellable inside; the lock keeps a leave from overlapping a join of any topic.
                    withContext(NonCancellable) { joinLeave.withLock { channel.close() } }
                }
                delay(RealtimeRetryPolicy.delayMs(attempt, Random.nextDouble()))
                attempt += 1
            }
        }
        val previous = synchronized(jobsLock) { jobs.put(topic, job) }
        // Chain on the previous holder of this topic (this or an earlier session): it must have LEFT before we join.
        if (previous != null) {
            scope.launch { previous.join(); job.start() }
        } else {
            job.start()
        }
        return Disposable { job.cancel() }
    }

    private companion object {
        /**
         * Process-wide, like the Realtime client: every join and every leave, across all topics and sessions, goes
         * through this one lock, so a foreground/background flap can never re-get a channel that is still leaving.
         */
        val joinLeave = Mutex()
        val jobsLock = SynchronizedObject()
        /** The newest job per topic, across sessions. */
        val jobs = HashMap<String, Job>()
    }
}
