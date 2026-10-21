package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.HttpAttemptSignal
import com.nuvio.app.features.addons.HttpStatusException
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Step 0.3 port — where a playlist's [ServerFailoverState] lives, per (profile, playlist key).
 * Device-local, never synced. Production: [PrefsServerFailoverStateStore]; tests use an in-memory one.
 */
interface ServerFailoverStateStore {
    fun read(profileId: Int, playlistKey: String): ServerFailoverState
    fun write(profileId: Int, playlistKey: String, state: ServerFailoverState)
    fun clear(profileId: Int, playlistKey: String)
    /** Every non-default state of [profileId], keyed by playlist key (drives the "Using backup" row). */
    fun all(profileId: Int): Map<String, ServerFailoverState>
}

/** In-memory [ServerFailoverStateStore] — the test double, and the shape the prefs store caches. */
class InMemoryServerFailoverStateStore : ServerFailoverStateStore {
    private val states = MutableStateFlow<Map<Int, Map<String, ServerFailoverState>>>(emptyMap())
    override fun read(profileId: Int, playlistKey: String) =
        states.value[profileId]?.get(playlistKey) ?: ServerFailoverState()
    override fun write(profileId: Int, playlistKey: String, state: ServerFailoverState) = states.update { all ->
        val profile = all[profileId].orEmpty()
        all + (profileId to if (state == ServerFailoverState()) profile - playlistKey else profile + (playlistKey to state))
    }
    override fun clear(profileId: Int, playlistKey: String) = write(profileId, playlistKey, ServerFailoverState())
    override fun all(profileId: Int): Map<String, ServerFailoverState> = states.value[profileId].orEmpty()
}

/**
 * The production store: one JSON map per profile in the IPTV prefs bag
 * ([XtreamAccountStorage.loadServerFailoverJson]), cached in memory so building a stream URL never
 * touches disk. Only non-default states are kept, so a playlist on its main server costs nothing.
 */
internal object PrefsServerFailoverStateStore : ServerFailoverStateStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), ServerFailoverState.serializer())
    private val cache = MutableStateFlow<Map<Int, Map<String, ServerFailoverState>>>(emptyMap())

    private fun profile(profileId: Int): Map<String, ServerFailoverState> {
        cache.value[profileId]?.let { return it }
        val loaded = runCatching {
            XtreamAccountStorage.loadServerFailoverJson(profileId)?.let { json.decodeFromString(serializer, it) }
        }.getOrNull().orEmpty()
        cache.update { it + (profileId to loaded) }
        return loaded
    }

    override fun read(profileId: Int, playlistKey: String) = profile(profileId)[playlistKey] ?: ServerFailoverState()

    override fun write(profileId: Int, playlistKey: String, state: ServerFailoverState) {
        val current = profile(profileId)
        val next = if (state == ServerFailoverState()) current - playlistKey else current + (playlistKey to state)
        if (next == current) return
        cache.update { it + (profileId to next) }
        runCatching { XtreamAccountStorage.saveServerFailoverJson(profileId, json.encodeToString(serializer, next)) }
    }

    override fun clear(profileId: Int, playlistKey: String) = write(profileId, playlistKey, ServerFailoverState())

    override fun all(profileId: Int): Map<String, ServerFailoverState> = profile(profileId)
}

/**
 * Step 0.3 — THE seam every fail-over-able IPTV request goes through, and the one place that knows a
 * playlist's ACTIVE server.
 *
 * FAILS OVER (via [run]): the Xtream login/account call and catalog calls (categories, streams,
 * series, series/vod info, short EPG, the EPG table, derived xmltv.php), the M3U-link playlist
 * download, and Stalker handshake/profile/browse calls. NEVER fails over: stream/playback URLs,
 * Stalker create_link, catch-up/timeshift URLs — those are built on [activeAccount]'s server (or
 * moved there by [rebaseStreamUrl]) and their failures never touch this state.
 *
 * Decisions are [ServerFailoverPolicy] (order/state), [FailoverFailureClassifier] (which error moves
 * on), [FailoverRaceScheduler] (when the next server is tried) and [FailoverProbePolicy] (what a valid
 * probe looks like); this object only executes them. A playlist with no backups takes the original
 * single-request path untouched — no state is read or written.
 *
 * Step 0.3b — STAGGERED PARALLEL FAILOVER. The real request goes to the first server of the policy's
 * order, alone: a healthy playlist makes exactly ONE request, no probe. Only when that request fails
 * (fail-over-able) or has produced no response headers within the server's stagger does the walk start
 * tiny per-type validation PROBES against the next servers; the first VALID probe wins, every other
 * in-flight attempt (the original included, while it still has no headers) is cancelled, and the real
 * request is issued ONCE, to the winner. A winner's real request that then fails continues the race over
 * the remaining servers. Cancelled losers never touch the breaker, stats, `mainRetryAfter` or any error.
 */
object PlaylistServerFailover {

    internal var store: ServerFailoverStateStore = PrefsServerFailoverStateStore
    internal var clock: () -> Long = { TraktPlatformClock.nowEpochMs() }
    internal var profileId: () -> Int = { ProfileRepository.activeProfileId }

    private val raceEpoch = TimeSource.Monotonic.markNow()
    /** Monotonic ms the race scheduler runs on. Tests swap in virtual time. */
    internal var raceClock: () -> Long = { raceEpoch.elapsedNow().inWholeMilliseconds }

    /** playlist key -> URL of the server whose error the last failed walk surfaced (in memory; cleared by the next success). */
    private val lastFailedServers = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * The server whose failure ended [acc]'s most recent walk — the one an error card should NAME. With
     * every server down that is the main server (its error is the one surfaced); when a backup refused
     * outright (a 401 behind a hung main) it is that backup. Null = the last walk succeeded, or none ran.
     */
    fun lastFailedServerUrl(acc: XtreamAccount): String? = lastFailedServers.value[acc.id]

    private val _version = MutableStateFlow(0L)
    /** Bumps on every state change, so state holders can re-read [activeIndexes]. */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** Main first, then the backups in priority order. An M3U file has no servers to walk. */
    fun servers(acc: XtreamAccount): List<String> =
        if (BackupServerValidation.supportsBackups(acc.sourceType)) listOf(acc.baseUrl) + acc.backupUrls
        else listOf(acc.baseUrl)

    /** 0 = main; i = backup i. Always a valid index for [acc]'s current list. */
    fun activeIndex(acc: XtreamAccount): Int {
        val servers = servers(acc)
        if (servers.size <= 1) return 0
        return ServerFailoverPolicy.clamp(store.read(profileId(), acc.id), servers.size).activeIndex
    }

    /** [acc] as seen on its active server — what stream/catch-up/create_link URLs are built from. */
    fun activeAccount(acc: XtreamAccount): XtreamAccount {
        com.nuvio.app.core.sources.DeviceSources.guard("iptv",acc.id)
        val index = activeIndex(acc)
        return if (index == 0) acc else acc.copy(baseUrl = servers(acc)[index])
    }

    /** Active index per playlist key for [accounts], only for those NOT on their main server. */
    fun activeIndexes(accounts: List<XtreamAccount>): Map<String, Int> =
        accounts.mapNotNull { acc -> activeIndex(acc).takeIf { it > 0 }?.let { acc.id to it } }.toMap()

    /**
     * Moves an Xtream stream URL built on any of [acc]'s servers onto the active one (a catalog fetched
     * earlier may have embedded the server that answered then). Anything else is returned unchanged:
     * M3U stream URLs are whatever the playlist lines say, and Stalker resolves create_link fresh.
     */
    fun rebaseStreamUrl(acc: XtreamAccount, url: String): String {
        if (acc.sourceType != SOURCE_TYPE_XTREAM || acc.backupUrls.isEmpty()) return url
        val servers = servers(acc).map { it.trimEnd('/') }
        val active = servers[activeIndex(acc)]
        val from = servers.firstOrNull { url.startsWith("$it/") } ?: return url
        return if (from == active) url else active + url.substring(from.length)
    }

    /**
     * Runs one fail-over-able request: [attempt] is called with [acc] re-pointed at a server of the
     * playlist. A failure the classifier says a backup would repeat (401/403, 456, auth rejected, …) is
     * thrown at once. When every server fails, the MAIN server's error is thrown (the user's primary)
     * and the state is kept.
     *
     * [probe] is the tiny per-type validation request (Xtream login JSON, M3U `Range: 0-1023`, Stalker
     * handshake) the race runs against the backups; it returns normally for a VALID server and throws
     * [FailoverInvalidResponseException] / [FailoverAuthRejectedException] / a transport failure
     * otherwise. With no [probe] there is nothing safe to race (a real request is not something to
     * duplicate), so the servers are walked one at a time.
     *
     * [canRetry] is asked before any move after a request failed: a streamed body that already
     * delivered rows to its sink must not be replayed from another server (it would duplicate or
     * splice the catalog).
     *
     * A racing [attempt] reports "I have a 2xx" through the platform helper's header signal
     * ([com.nuvio.app.features.addons.signalHttpHeaders]); an attempt that never signals counts as
     * answering when it completes.
     */
    suspend fun <T> run(
        acc: XtreamAccount,
        canRetry: () -> Boolean = { true },
        probe: (suspend (XtreamAccount) -> Unit)? = null,
        attempt: suspend (XtreamAccount) -> T,
    ): T = com.nuvio.app.core.sources.DeviceSources.guarded("iptv",acc.id) {
        val servers = servers(acc)
        if (servers.size <= 1) return@guarded attempt(acc)
        val pid = profileId()
        val wallMs = clock()
        val state = ServerFailoverPolicy.clamp(store.read(pid, acc.id), servers.size)
        val order = ServerFailoverPolicy.order(state, servers.size, wallMs)
        val walk = Walk(acc, servers, pid, state, wallMs, canRetry, probe, attempt)
        if (probe == null) walk.sequential(order) else walk.race(order)
    }

    /** One call of [run]: its inputs, and what the walk has learned so far. */
    private class Walk<T>(
        val acc: XtreamAccount,
        val servers: List<String>,
        val pid: Int,
        val state: ServerFailoverState,
        val wallMs: Long,
        val canRetry: () -> Boolean,
        val probe: (suspend (XtreamAccount) -> Unit)?,
        val attempt: suspend (XtreamAccount) -> T,
    ) {
        /** Fail-over-able failures by server index (never a cancelled loser). */
        val failures = LinkedHashMap<Int, Throwable>()

        fun accFor(index: Int): XtreamAccount = if (index == 0) acc else acc.copy(baseUrl = servers[index])

        /** The real request on [index] alone, with the failover connect timeout and no racing. */
        suspend fun real(index: Int): T =
            withContext(HttpAttemptSignal(FailoverRace.CONNECT_TIMEOUT_MS)) { attempt(accFor(index)) }

        /** Throws [error] as the walk's result, remembering which server it came from for the error card. */
        fun fail(index: Int, error: Throwable): Nothing {
            finish(null, 0L)
            lastFailedServers.update { it + (acc.id to servers[index]) }
            throw error
        }

        /** Every server failed: surface the main server's error (else the last one's). */
        fun giveUp(surface: Int): Nothing {
            val index = when {
                surface in failures -> surface
                0 in failures -> 0
                else -> failures.keys.lastOrNull() ?: 0
            }
            fail(index, failures[index] ?: IllegalStateException("No server answered for ${acc.name}"))
        }

        /** The walk's end: record what it learned (state + stats), unless the caller cancelled. */
        fun finish(successServer: Int?, sampleMs: Long) = finishWalk(this, successServer, sampleMs)

        /** [error] ended [index]'s turn: fail over from it (true) or surface it (false). */
        fun failsOver(error: Throwable): Boolean =
            FailoverFailureClassifier.shouldFailOver(classifyFailoverThrowable(error)) && canRetry()

        // --- no probe: the original one-at-a-time walk (no race, no time budget) -----------------

        suspend fun sequential(order: List<Int>): T {
            val started = raceClock()
            for (index in order) {
                val result = try {
                    real(index)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (!failsOver(t)) fail(index, t)
                    failures[index] = t
                    continue
                }
                finish(index, raceClock() - started)
                return result
            }
            giveUp(0)
        }

        // --- probe: staggered race -------------------------------------------------------------

        suspend fun race(order: List<Int>): T {
            var candidates = order
            var realFirst = true
            while (true) {
                when (val round = raceRound(candidates, realFirst)) {
                    is Round.RealWon -> { finish(round.server, round.timeMs); return round.value }
                    is Round.Surface -> fail(round.server, round.error)
                    is Round.GaveUp -> giveUp(round.surface)
                    is Round.ProbeWon -> {
                        val value = try {
                            real(round.server)
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            if (!failsOver(t)) fail(round.server, t)
                            failures[round.server] = t
                            candidates = candidates.filter { it !in failures.keys }
                            if (candidates.isEmpty()) giveUp(0)
                            realFirst = false
                            continue
                        }
                        finish(round.server, round.timeMs)
                        return value
                    }
                    is Round.WonThenFailed -> {
                        if (!failsOver(round.error)) fail(round.server, round.error)
                        failures[round.server] = round.error
                        candidates = candidates.filter { it !in failures.keys }
                        if (candidates.isEmpty()) giveUp(0)
                        realFirst = false
                    }
                }
            }
        }

        private sealed interface Event<out V> {
            data object Tick : Event<Nothing>
            data class Headers(val server: Int) : Event<Nothing>
            data class LocalWait(val server: Int, val waiting: Boolean) : Event<Nothing>
            data class Done<V>(val server: Int, val isReal: Boolean, val value: V?, val error: Throwable?) : Event<V>
        }

        sealed interface Round<out V> {
            class RealWon<V>(val server: Int, val value: V, val timeMs: Long) : Round<V>
            class ProbeWon(val server: Int, val timeMs: Long) : Round<Nothing>
            class Surface(val server: Int, val error: Throwable) : Round<Nothing>
            class GaveUp(val surface: Int) : Round<Nothing>
            class WonThenFailed(val server: Int, val error: Throwable) : Round<Nothing>
        }

        /**
         * One staggered race over [candidates]. With [realFirst] the first candidate runs the REAL
         * request (racing to its response headers); every other attempt is a [probe]. Returns once the
         * race is decided and every attempt of it has finished (losers cancelled, never read).
         */
        private suspend fun raceRound(candidates: List<Int>, realFirst: Boolean): Round<T> = coroutineScope {
            val events = Channel<Event<T>>(Channel.UNLIMITED)
            val scheduler = FailoverRaceScheduler(
                order = candidates,
                hostOf = { FailoverHostKey.of(servers[it]) },
                staggerOf = { FailoverStagger.compute(state.stats[it], wallMs) },
            )
            val jobs = HashMap<Int, Job>()
            val startedAt = HashMap<Int, Long>()
            val gates = HashMap<Int, CompletableDeferred<Unit>>()
            val headersMs = HashMap<Int, Long>()
            var winner: Int? = null
            var outcome: Round<T>? = null
            val firstServer = candidates.first()

            fun launchAttempt(server: Int) {
                val isReal = realFirst && server == firstServer
                val gate = CompletableDeferred<Unit>().also { gates[server] = it }
                startedAt[server] = raceClock()
                jobs[server] = launch {
                    val signal = HttpAttemptSignal(
                        connectTimeoutMs = FailoverRace.CONNECT_TIMEOUT_MS,
                        onLocalWait = { waiting -> events.trySend(Event.LocalWait(server, waiting)) },
                        onHeaders = if (isReal) ({ events.trySend(Event.Headers(server)); gate.await() }) else null,
                    )
                    val done = try {
                        withContext(signal) {
                            if (isReal) Event.Done(server, true, attempt(accFor(server)), null)
                            else { probe!!(accFor(server)); Event.Done(server, false, null, null) }
                        }
                    } catch (t: Throwable) {
                        Event.Done<T>(server, isReal, null, t)
                    }
                    events.trySend(done)
                }
            }

            fun handle(decisions: List<RaceDecision>) {
                for (d in decisions) when (d) {
                    is RaceDecision.StartAttempt -> launchAttempt(d.server)
                    is RaceDecision.CancelAttempts -> d.servers.forEach { jobs[it]?.cancel() }
                    is RaceDecision.Winner -> winner = d.server
                    is RaceDecision.GiveUp -> outcome = Round.GaveUp(d.surfaceServer)
                }
            }

            handle(scheduler.onTick(raceClock()))
            var timer: Job? = null
            while (outcome == null) {
                timer?.cancel()
                val wake = if (winner == null) scheduler.nextWakeMs(raceClock()) else null
                timer = wake?.let { at ->
                    launch { delay((at - raceClock()).coerceAtLeast(0L)); events.trySend(Event.Tick) }
                }
                val event = events.receive()
                val now = raceClock()
                when (event) {
                    is Event.Tick -> if (winner == null) handle(scheduler.onTick(now))
                    is Event.LocalWait -> if (winner == null) handle(scheduler.onLocalWait(now, event.server, event.waiting))
                    is Event.Headers -> if (winner == null) {
                        headersMs[event.server] = now - (startedAt[event.server] ?: now)
                        handle(scheduler.onHeaders(now, event.server))
                        if (winner == event.server) gates[event.server]?.complete(Unit)
                    }
                    is Event.Done -> {
                        val server = event.server
                        val error = event.error
                        when {
                            error is CancellationException -> if (winner == null) handle(scheduler.onCancelled(now, server))
                            error == null && winner == null -> {
                                // A valid probe, or a real request that finished without ever signalling.
                                val ms = now - (startedAt[server] ?: now)
                                handle(scheduler.onHeaders(now, server))
                                @Suppress("UNCHECKED_CAST")
                                outcome = if (event.isReal) Round.RealWon(server, event.value as T, ms) else Round.ProbeWon(server, ms)
                            }
                            error == null && winner == server && event.isReal -> {
                                @Suppress("UNCHECKED_CAST")
                                outcome = Round.RealWon(server, event.value as T, headersMs[server] ?: (now - (startedAt[server] ?: now)))
                            }
                            error == null -> Unit   // a cancelled loser that finished anyway: ignored, never read
                            winner == server -> outcome = Round.WonThenFailed(server, error)
                            winner != null -> Unit  // a loser's late failure caused by its own cancellation
                            failsOver(error) -> {
                                failures[server] = error
                                handle(scheduler.onFailed(now, server, true))
                            }
                            else -> {
                                handle(scheduler.onFailed(now, server, false))
                                outcome = Round.Surface(server, error)
                            }
                        }
                    }
                }
            }
            timer?.cancel()
            jobs.values.forEach { it.cancel() }
            outcome!!
        }
    }

    private fun finishWalk(w: Walk<*>, successServer: Int?, sampleMs: Long) {
        if (successServer != null && w.acc.id in lastFailedServers.value) lastFailedServers.update { it - w.acc.id }
        val now = clock()
        val n = w.servers.size
        record(w.pid, w.acc.id, n) { cur ->
            var next = if (successServer != null) ServerFailoverPolicy.onSuccess(cur, successServer, now, n)
            else ServerFailoverPolicy.onAllFailed(cur)
            var stats = next.stats
            for (failed in w.failures.keys) stats = stats + (failed to FailoverLatencyStats.onFailure(stats[failed], now))
            if (successServer != null) {
                val updated = FailoverLatencyStats.onWin(stats[successServer], sampleMs, now)
                stats = if (updated == null) stats - successServer else stats + (successServer to updated)
            }
            stats = stats.mapNotNull { (k, v) -> FailoverLatencyStats.prune(v, now)?.let { k to it } }.toMap()
            next.copy(stats = stats)
        }
    }

    /** The user edited [playlistKey]'s server list: back to the main server, no window. */
    fun reset(playlistKey: String) {
        lastFailedServers.update { it - playlistKey }
        record(profileId(), playlistKey, Int.MAX_VALUE) { ServerFailoverState() }
    }

    /** PlaylistRemovalCleanup's ServerFailover target: drop [playlistKey]'s state for [profileId]. */
    fun forget(profileId: Int, playlistKey: String) {
        lastFailedServers.update { it - playlistKey }
        if (store.read(profileId, playlistKey) == ServerFailoverState()) return
        store.clear(profileId, playlistKey)
        _version.update { it + 1 }
    }

    /** Walks of one playlist finish concurrently (a hub fans out many catalog calls): read-modify-write must not interleave. */
    private val recordLock = SynchronizedObject()

    private fun record(pid: Int, key: String, serverCount: Int, next: (ServerFailoverState) -> ServerFailoverState) {
        val visibleChange = synchronized(recordLock) {
            val current = store.read(pid, key)
            val updated = next(ServerFailoverPolicy.clamp(current, serverCount))
            if (updated == current) return
            store.write(pid, key, updated)
            // Stats-only changes are invisible to the UI: no state holder needs to re-read the active index.
            updated.activeIndex != current.activeIndex || updated.mainRetryAfterMs != current.mainRetryAfterMs
        }
        if (visibleChange) _version.update { it + 1 }
    }

    /** Test seam: a fresh in-memory store + clock + profile. Never called from production code. */
    internal fun installForTest(
        store: ServerFailoverStateStore = InMemoryServerFailoverStateStore(),
        clock: () -> Long = { 0L },
        profileId: () -> Int = { 1 },
        raceClock: (() -> Long)? = null,
    ) {
        this.store = store
        this.clock = clock
        this.profileId = profileId
        this.raceClock = raceClock ?: { raceEpoch.elapsedNow().inWholeMilliseconds }
    }

    /** Test seam: back to the production wiring. */
    internal fun resetForTest() {
        store = PrefsServerFailoverStateStore
        clock = { TraktPlatformClock.nowEpochMs() }
        profileId = { ProfileRepository.activeProfileId }
        raceClock = { raceEpoch.elapsedNow().inWholeMilliseconds }
    }
}

/**
 * One throwable from a fail-over-able request, in [FailoverFailureKind] terms. The platform-free
 * cases are decided here; network exceptions are mapped by each platform ([platformFailoverFailureKind]),
 * walking the cause chain because stacks wrap them.
 */
internal fun classifyFailoverThrowable(t: Throwable): FailoverFailure {
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 6) {
        when (cur) {
            is CancellationException -> return FailoverFailure(FailoverFailureKind.CANCELLED)
            is HttpStatusException -> return FailoverFailure(FailoverFailureKind.HTTP_STATUS, cur.status)
            is FailoverInvalidResponseException -> return FailoverFailure(FailoverFailureKind.INVALID_RESPONSE)
            is FailoverAuthRejectedException -> return FailoverFailure(FailoverFailureKind.AUTH_REJECTED)
            is PanelHostFastFailException -> return FailoverFailure(FailoverFailureKind.HOST_UNAVAILABLE)
        }
        platformFailoverFailureKind(cur)?.let { return FailoverFailure(it) }
        cur = cur.cause?.takeIf { it !== cur }
        depth++
    }
    return FailoverFailure(FailoverFailureKind.OTHER)
}

/**
 * The platform's network exception -> [FailoverFailureKind], or null when [t] is not one this platform
 * recognises (the caller then walks the cause chain, ending at OTHER).
 */
internal expect fun platformFailoverFailureKind(t: Throwable): FailoverFailureKind?
