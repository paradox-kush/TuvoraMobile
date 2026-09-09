package com.nuvio.app.core.rec

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private const val QUEUE_SEPARATOR = "\n"

/** The edge function caps a batch at 200; stay well under so one flush is always one request. */
private const val FLUSH_AT_EVENTS = 50
private const val MAX_QUEUED_EVENTS = 500
private const val FLUSH_INTERVAL_MS = 30_000L

private const val BACKOFF_START_MS = 30_000L
private const val BACKOFF_MAX_MS = 15 * 60 * 1000L

/**
 * The recommendation event queue. Twin of NuvioTV's `RecEventLogger`, same contract, same
 * failure semantics — see that file for the full rationale.
 *
 * Events accumulate in memory, the whole unsent buffer is mirrored to a file on each flush
 * attempt, and the file is cleared only once the backend accepts them. A kill loses at most one
 * flush interval, without a disk write per impression.
 *
 * Failure handling: 204 accepted · 410 kill switch (silent 24h, discard) · other 4xx means a
 * client bug the backend will never accept, so drop rather than wedge the queue · 5xx and
 * network are transient, keep and back off.
 *
 * FAIL-OPEN CONTRACT: nothing in this package may ever break the app. Every public entry point
 * swallows its own exceptions, no caller is ever blocked, and a misconfigured backend degrades
 * to "no events" rather than to anything a user can see.
 */
object RecEventLogger {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val log = Logger.withTag("RecEventLogger")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flushMutex = Mutex()
    private val queueLock = SynchronizedObject()
    private val queue = ArrayDeque<RecEventRecord>()

    // Supabase-kt wraps its Ktor client in KtorSupabaseHttpClient, which is not a plain
    // HttpClient and carries that client's auth/retry configuration — neither of which suits a
    // fire-and-forget anon POST. A bare client is cheaper to reason about; Ktor resolves the
    // engine per platform (OkHttp on Android, Darwin on iOS) from the classpath.
    private val http: HttpClient by lazy { HttpClient() }

    @Volatile
    private var started = false

    @Volatile
    private var backoffMs = BACKOFF_START_MS

    @Volatile
    private var retryNotBeforeMs = 0L

    /** Called once at app start. Restores anything a previous process left unsent. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            runCatching {
                if (RecEventSettings.isActive(recNowMillis())) restoreQueue()
                else discardPendingEvents()
            }
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush("timer")
            }
        }
    }

    /**
     * Queue one event. Safe from any thread including composition; the whole body is guarded so
     * a bad event can never surface as a crash.
     */
    fun log(event: RecEvent) {
        try {
            val now = recNowMillis()
            if (!RecEventSettings.isActive(now)) return
            val sessionId = RecEventIdentity.sessionId(now)
            val record = RecEventRecord(
                sessionId = sessionId,
                event = event.copy(
                    clientTs = recIsoTimestamp(now),
                    profileId = activeProfileId(),
                ),
            )
            val shouldFlush = synchronized(queueLock) {
                while (queue.size >= MAX_QUEUED_EVENTS) queue.removeFirst()
                queue.addLast(record)
                queue.size >= FLUSH_AT_EVENTS
            }
            if (shouldFlush) scope.launch { flush("threshold") }
        } catch (e: Throwable) {
            log.d { "Dropped event: ${e.message}" }
        }
    }

    /** The session impressions should be deduped against. Does not extend the session. */
    fun currentSessionId(): String = RecEventIdentity.peekSessionId()

    fun requestFlush(reason: String) {
        if (!started) return
        runCatching { scope.launch { flush(reason) } }
    }

    /** Synchronous privacy boundary used by opt-out and local account deletion. */
    internal fun discardPendingEvents() {
        synchronized(queueLock) { queue.clear() }
        retryNotBeforeMs = 0L
        backoffMs = BACKOFF_START_MS
        persistQueue(emptyList())
    }

    /** Remove queued behavior and unlink future anonymous events from the old install token. */
    internal fun resetLocalState() {
        discardPendingEvents()
        RecEventIdentity.resetLocalState()
    }

    /**
     * Read per event, never cached: on a shared device the profile IS the user as far as
     * training is concerned.
     */
    private fun activeProfileId(): Int =
        runCatching { ProfileRepository.activeProfileId.coerceIn(1, Short.MAX_VALUE.toInt()) }
            .getOrDefault(1)

    private suspend fun flush(reason: String) {
        val now = recNowMillis()
        if (!RecEventSettings.isActive(now) || now < retryNotBeforeMs) return
        if (!flushMutex.tryLock()) return
        try {
            val pending = synchronized(queueLock) { queue.toList() }
            if (pending.isEmpty()) return
            persistQueue(pending)
            if (!RecEventSettings.isActive(recNowMillis())) {
                discardPendingEvents()
                return
            }

            // One request per session: the envelope carries a single session_id, and a batch that
            // survived a cold start can straddle two.
            for ((sessionId, records) in pending.groupBy { it.sessionId }) {
                for (chunk in records.chunked(FLUSH_AT_EVENTS)) {
                    if (!RecEventSettings.isActive(recNowMillis())) {
                        discardPendingEvents()
                        return
                    }
                    val outcome = send(sessionId, chunk.map { it.event })
                    if (outcome == SendOutcome.RETRY) {
                        retryNotBeforeMs = recNowMillis() + backoffMs
                        backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
                        log.d { "Flush ($reason) deferred; retrying in ${backoffMs}ms" }
                        return
                    }
                    drop(chunk)
                    if (outcome == SendOutcome.DISABLED) {
                        RecEventSettings.suppressUntil(recNowMillis())
                        synchronized(queueLock) { queue.clear() }
                        persistQueue(emptyList())
                        log.i { "Ingest disabled by backend; silent for 24h" }
                        return
                    }
                }
            }
            backoffMs = BACKOFF_START_MS
            persistQueue(synchronized(queueLock) { queue.toList() })
        } catch (e: Throwable) {
            log.d { "Flush ($reason) failed: ${e.message}" }
        } finally {
            flushMutex.unlock()
        }
    }

    private fun drop(sent: List<RecEventRecord>) {
        synchronized(queueLock) {
            // Identity-based removal: events appended while the request was in flight must live.
            for (record in sent) queue.remove(record)
        }
    }

    private enum class SendOutcome { ACCEPTED, DROP, RETRY, DISABLED }

    private suspend fun send(sessionId: String, events: List<RecEvent>): SendOutcome {
        val backend = SupabaseProvider.selectedBackend
        // A backend with no URL or key is a configuration state, not a transient fault.
        if (backend.normalizedSupabaseUrl.isBlank() || backend.anonKey.isBlank()) {
            log.d { "No sync backend configured; discarding batch" }
            return SendOutcome.DROP
        }
        val batch = RecEventBatch(
            deviceId = RecEventIdentity.deviceId(),
            sessionId = sessionId,
            app = recAppIdentifier,
            appVersion = AppVersionConfig.VERSION_NAME.ifBlank { "dev" }.take(32),
            events = events,
        )
        val token = runCatching {
            SupabaseProvider.client.auth.currentAccessTokenOrNull()
        }.getOrNull()

        return try {
            val response: HttpResponse =
                http.post("${backend.normalizedSupabaseUrl}/functions/v1/rec-events") {
                    header("apikey", backend.anonKey)
                    if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(batch))
                }
            val code = response.status.value
            when {
                code in 200..299 -> SendOutcome.ACCEPTED
                code == 410 -> SendOutcome.DISABLED
                code in 400..499 -> {
                    log.w { "Batch rejected ($code)" }
                    SendOutcome.DROP
                }
                else -> SendOutcome.RETRY
            }
        } catch (e: Throwable) {
            log.d { "Send failed: ${e.message}" }
            SendOutcome.RETRY
        }
    }

    private fun persistQueue(records: List<RecEventRecord>) {
        runCatching {
            // Bound the WRITE too: never persist more than the newest MAX_QUEUED_EVENTS records, drop
            // an over-length record, and keep the blob within the cap — so the app itself can never
            // create the oversized value the restore path defends against.
            val lines = records.map { json.encodeToString(it) }
            val bounded = RecEventQueueRestorePolicy.boundLines(lines, MAX_QUEUED_EVENTS)
            RecEventStorage.saveQueue(bounded.takeIf { it.isNotEmpty() }?.joinToString(QUEUE_SEPARATOR))
        }
    }

    private fun restoreQueue() {
        // Bound the restore in TWO stages, both before a large allocation. (1) The queue is
        // file-backed on every platform — Android/iOS `RecEventStorage.loadQueue` reads it with a
        // byte cap (MAX_QUEUE_BYTES) enforced WHILE consuming the file, so an oversized/corrupt file
        // never fully lands in memory. (2) Here, the returned string is bounded by char: a blob over
        // the char cap is rejected wholesale, over-length lines dropped, only the newest
        // MAX_QUEUED_EVENTS kept. The PERSIST side is bounded too (persistQueue), so the app never
        // writes such a value itself.
        val bounded = RecEventQueueRestorePolicy.select(
            RecEventStorage.loadQueue(), QUEUE_SEPARATOR, MAX_QUEUED_EVENTS,
        )
        if (bounded.oversized) {
            log.w { "Rec queue exceeded ${RecEventQueueRestorePolicy.MAX_QUEUE_CHARS} chars; discarding corrupt queue" }
            RecEventStorage.saveQueue(null)
            return
        }
        val restored = bounded.lines.mapNotNull { line ->
            runCatching { json.decodeFromString<RecEventRecord>(line) }.getOrNull()
        }
        if (restored.isEmpty()) {
            RecEventStorage.saveQueue(null)
            return
        }
        synchronized(queueLock) {
            for (record in restored) {
                while (queue.size >= MAX_QUEUED_EVENTS) queue.removeFirst()
                queue.addLast(record)
            }
        }
        log.d { "Restored ${restored.size} unsent events" }
    }
}
