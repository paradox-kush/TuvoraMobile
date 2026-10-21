package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.addons.EmptyResponseBodyException
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpStreamLines
import com.nuvio.app.features.iptv.IptvPanelGuard
import com.nuvio.app.features.iptv.PanelHostGuard
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.guardedPanelRequest
import io.ktor.http.encodeURLParameter
import com.nuvio.app.features.iptv.FailoverInvalidResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.nuvio.app.features.addons.awaitingLocally
import com.nuvio.app.features.addons.withoutHttpHeadersSignal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The portal rejected our identity/token — the ONLY failure that may trigger a re-handshake. */
internal class StalkerAuthException(message: String) : IllegalStateException(message)

/**
 * get_profile answered `status: 1`: the portal understood our identity and REFUSED the account
 * (disabled line, unknown MAC, …). Never retried — re-handshaking cannot fix a refusal, and a
 * status refusal must never be mistaken for an empty portal.
 */
internal open class StalkerPortalRefusedException(message: String) : IllegalStateException(message)

/**
 * The `status: 1` refusal names the DEVICE BINDING: the portal has a different device identity
 * pinned to this MAC. The one refusal with a user remedy, so it gets its own type and message.
 */
internal class StalkerDeviceConflictException(message: String) : StalkerPortalRefusedException(message)

/**
 * The portal is reachable and our identity is fine — it just will not serve THIS session now. A
 * Stalker line is single-device: another box holding the MAC evicts us, and a re-auth that failed
 * to win it back puts us in the cooldown that stops the two devices stampeding each other.
 *
 * Typed because it is the one browse failure with a user-facing explanation that is neither "the
 * portal is down" nor "your MAC is wrong" — see IptvLoadFailurePolicy.
 */
internal class StalkerSessionUnavailableException(message: String, val emptySection: Boolean = false) : IllegalStateException(message)

/**
 * A stateful Stalker-portal (MAG/Ministra) session for ONE playlist. Owns endpoint probing (the user
 * enters just a base portal URL; we try [StalkerProtocol.ENDPOINT_CANDIDATES] in order and remember
 * the first that handshakes), the auth token from `handshake` + the device identity from `get_profile`,
 * a single-flight (re-)authenticate, and [request] — an authenticated GET that transparently
 * re-handshakes + retries once on an expired token / empty `js` / HTTP failure.
 *
 * Ported from NuvioTV's StalkerSession; the OkHttp call is swapped for [httpGetTextWithHeaders] (which
 * throws on any non-2xx / blank body — that throw IS the stale-token signal the retry path catches) and
 * Gson for kotlinx.serialization. Portal API calls ride the playlist's own DoH resolver (Android),
 * exactly like the Xtream/M3U/XMLTV fetches and like TV — a device whose system resolver blocks the
 * portal host is otherwise unfixable from inside the app.
 */
internal class StalkerSession(
    private val account: XtreamAccount,
    // Injectable HTTP seam so the auth/retry logic is unit-testable with a fake portal; production
    // uses the real platform GET (throws on non-2xx / blank body — that throw IS the stale signal).
    private val httpGet: suspend (url: String, headers: Map<String, String>) -> String =
        { u, h -> httpGetTextWithHeaders(u, h, account.dnsProvider) },
    // Streaming twin of [httpGet] (bulk EPG): chunks go to the callback, nothing body-sized is
    // held. Tests inject `{ u, h, c -> c(fakePortal(u, h)) }` so the fake drives BOTH seams.
    private val httpStream: suspend (url: String, headers: Map<String, String>, onChunk: (String) -> Unit) -> Unit =
        { u, h, c -> httpStreamLines(u, userAgent = null, dnsProvider = account.dnsProvider, headers = h, onLine = c) },
    // The per-origin circuit breaker every portal request is admitted through (WP6). Injectable so
    // tests drive it with their own clock; production shares the process-wide instance.
    private val panelGuard: PanelHostGuard = IptvPanelGuard.guard,
) {
    private var token: String? = null
    private var resolvedEndpoint: String? = null   // e.g. "/portal.php"
    /**
     * The STB identity this portal accepted. Starts at the one we have always sent (or the Model / HW
     * Version the user pinned — F46), so a portal that already works is unaffected; a rejection walks
     * [StalkerMagPresets.LADDER] (never away from a pinned identity). Session-scoped: a
     * relaunch re-walks it, which costs one rejected request on the minority of portals that need it.
     */
    private var magPreset: StalkerMagPreset = StalkerMagPresets.initial(account.stbModel, account.hwVersion)
    /** When a re-auth ran and the retry STILL came back empty (another device holds the MAC). */
    private var lastFailedReauthAtMs: Long = 0L

    private val authMutex = Mutex()

    // Hard ceiling on concurrent requests to this portal. A real MAG box opens a couple of
    // connections; magplex (the reference client) caps this at 3 explicitly "to prevent rate
    // limiting". Ours is per-session so a busy hub can't fan out into a ban.
    private val gate = Semaphore(MAX_CONCURRENT_REQUESTS)

    // The get_events keep-alive (see [StalkerWatchdogPolicy]). Session-owned so its lifetime IS
    // the session's: (re)started on every successful activation, gone on [shutdown]. Same scope
    // idiom the repositories use for periodic work.
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null

    private val baseUrl: String = StalkerProtocol.normalizePortalBase(account.baseUrl)
    private val identity: StalkerProtocol.DeviceIdentity =
        StalkerProtocol.deriveDeviceIdentity(
            mac = account.macAddress,
            serialOverride = account.serialNumber,
            deviceIdOverride = account.deviceId,
            deviceId2Override = account.deviceId2,
            signatureOverride = account.signature,
        )

    private val referer: String
        get() = StalkerProtocol.refererFor(baseUrl, resolvedEndpoint ?: StalkerProtocol.ENDPOINT_CANDIDATES.first())

    /**
     * Authenticated Stalker GET. [params] are the JsHttpRequest query params (type/action/…); the
     * token header + `&JsHttpRequest=1-xml` are added here. Returns the `js` element of the
     * `{"js": …}` envelope. Re-handshakes + retries ONCE on a stale token. Throws on hard failure so
     * callers' runCatching degrades to empty.
     */
    suspend fun request(params: Map<String, String>): JsonElement {
        com.nuvio.app.core.sources.DeviceSources.guard("iptv",account.id)
        ensureAuthenticated()
        val staleToken = token
        // ONLY an auth failure earns a re-handshake. A transport/HTTP throw (429/419/5xx/timeout) must
        // NOT: re-authing on those turns a rate-limited portal into a stampede — every call becomes
        // request + handshake + retry — which is exactly how we got a live portal to block us. Those
        // throws propagate; callers' runCatching degrades to empty.
        val first = try {
            rawRequest(params).jsOrNull()
        } catch (e: StalkerAuthException) {
            null   // fall through to the single re-auth + retry below
        } catch (e: EmptyResponseBodyException) {
            // A portal that has handed our MAC's session to another device answers 200 with an
            // EMPTY BODY — no 401, no "Authorization failed.". That is the eviction signal, and it
            // used to escape this catch entirely (it isn't a StalkerAuthException), so the re-auth
            // below never ran and callers just saw "no results".
            null
        }
        if (first != null) {
            lastFailedReauthAtMs = 0L   // healthy again
            return first
        }
        // Stale token (empty body / empty `js` / "Authorization failed.") -> one handshake, retry once.
        // Cooldown: when two devices share a MAC each re-auth evicts the other, so a failed recovery
        // must not have every following request handshake again — that is the stampede that gets a
        // portal to ban the IP. Fail fast for a short window instead; the next user action retries.
        val now = com.nuvio.app.features.streams.epochMs()
        if (lastFailedReauthAtMs != 0L && now - lastFailedReauthAtMs < REAUTH_COOLDOWN_MS) {
            throw StalkerSessionUnavailableException("Stalker session for ${account.name} is held by another device — cooling down")
        }
        reauthenticate(staleToken)
        var retryBody: String? = null
        val retried = try {
            rawRequest(params, onBody = { retryBody = it }).jsOrNull()
        } catch (e: EmptyResponseBodyException) {
            null
        }
        if (retried == null) {
            // B02: name what the portal actually did (empty body / empty envelope / error page) —
            // only the empty-body eviction is "in use elsewhere", and only it earns the cooldown.
            val kind = StalkerEmptyReplyPolicy.classify(retryBody)
            if (StalkerEmptyReplyPolicy.startsCooldown(kind)) lastFailedReauthAtMs = now
            throw StalkerSessionUnavailableException(
                StalkerEmptyReplyPolicy.message(kind, account.name, params, retryBody),
                emptySection = kind == StalkerEmptyReplyPolicy.Kind.NOTHING_FOR_SECTION,
            )
        }
        lastFailedReauthAtMs = 0L
        return retried
    }

    /**
     * The failover race's validation probe (Step 0.3b): a Stalker portal is VALID when this session can
     * authenticate (probe endpoint + handshake token + get_profile) — exactly [ensureAuthenticated], so
     * the real request that follows on the winner reuses the token and never handshakes again. An
     * already-authenticated session must still prove the portal is alive: one harmless `get_events`
     * ping (a fresh handshake would rotate the MAC's token), whose `{"js": …}` envelope must be there —
     * a parked domain's HTML is not. A portal that answers but is not a Stalker portal throws
     * [FailoverInvalidResponseException] (fails over); a device rejection ([StalkerAuthException],
     * refusal exceptions) is the same on every portal and surfaces. Cancellation discards a half-built
     * session ([doHandshakeAndProfile]).
     */
    suspend fun probe() {
        val warm = token != null
        ensureAuthenticated()
        if (!warm) return
        val envelope = rawRequest(StalkerWatchdogPolicy.pingParams(init = false)) as? JsonObject
        if (envelope == null || "js" !in envelope) {
            throw FailoverInvalidResponseException("Stalker probe: ${account.name} did not answer like a portal")
        }
    }

    /** Force re-auth on the next call (used when a create_link/browse hits a hard failure). */
    fun invalidate() { token = null }

    /** Tear the session down (evicted/replaced): stops the watchdog. Do not use it afterwards. */
    fun shutdown() {
        watchdogScope.cancel()
    }

    /**
     * Authenticated GET that STREAMS the body to [onChunk] instead of materializing it — for the
     * one Stalker response that can be enormous (bulk get_epg_info: 174.5 MB from a real client
     * trace on our research mock; see research/iptv-catalog-loading.md). Same auth + single
     * re-auth retry semantics as [request]: the first bytes are sniffed for the portal's
     * "Authorization failed." sentinel / an empty body, and ONE re-handshake retry runs before
     * giving up. [onRestart] fires before the retry's first chunk so a consumer that already
     * swallowed chunks can reset (the EPG ingest re-begins its transaction).
     *
     * The sniff window is buffered (few hundred bytes) and flushed to [onChunk] as soon as the
     * body proves healthy, so memory stays O(chunk).
     */
    suspend fun requestStream(
        params: Map<String, String>,
        onRestart: () -> Unit,
        onChunk: (String) -> Unit,
    ) {
        ensureAuthenticated()
        val staleToken = token
        val first = runCatchingCancellable { rawRequestStream(params, onChunk) }
        if (first.isSuccess && first.getOrThrow()) {
            lastFailedReauthAtMs = 0L
            return
        }
        val authFailure = first.exceptionOrNull() is StalkerAuthException ||
            first.exceptionOrNull() is EmptyResponseBodyException ||
            (first.isSuccess && !first.getOrThrow())   // empty body, no throw
        if (!authFailure) throw first.exceptionOrNull() ?: error("Stalker stream failed")
        val now = com.nuvio.app.features.streams.epochMs()
        if (lastFailedReauthAtMs != 0L && now - lastFailedReauthAtMs < REAUTH_COOLDOWN_MS) {
            throw StalkerSessionUnavailableException("Stalker session for ${account.name} is held by another device — cooling down")
        }
        reauthenticate(staleToken)
        onRestart()
        val retried = runCatchingCancellable { rawRequestStream(params, onChunk) }
        if (retried.isFailure || !retried.getOrThrow()) {
            lastFailedReauthAtMs = now
            throw StalkerSessionUnavailableException("Stalker portal returned no data for ${params["action"]} — the session is in use elsewhere")
        }
        lastFailedReauthAtMs = 0L
    }

    /** One streamed GET. Returns true when any body bytes arrived; throws [StalkerAuthException]
     *  when the sniff window contains the rejection sentinel. */
    private suspend fun rawRequestStream(
        params: Map<String, String>,
        onChunk: (String) -> Unit,
    ): Boolean {
        val endpointPath = resolvedEndpoint ?: StalkerProtocol.ENDPOINT_CANDIDATES.first()
        val query = (params + ("JsHttpRequest" to "1-xml")).entries.joinToString("&") { (k, v) ->
            "${k.encodeURLParameter()}=${v.encodeURLParameter()}"
        }
        val url = "$baseUrl$endpointPath?$query"
        val cookie = buildString {
            append("mac=").append(StalkerProtocol.encodeMacForCookie(account.macAddress))
            append("; stb_lang=en; timezone=Europe/London")
            append("; sn=").append(identity.serialNumber)
            append("; PHPSESSID=null")
        }
        val headers = buildMap {
            put("User-Agent", magPreset.userAgent)
            put("X-User-Agent", magPreset.xUserAgent)
            put("Referer", referer)
            put("Cookie", cookie)
            token?.takeIf { it.isNotEmpty() }?.let { put("Authorization", "Bearer $it") }
        }
        var sniffing = true
        val sniff = StringBuilder()
        var sawBytes = false
        // Guarded like rawRequestAt (WP6): the bulk-EPG stream is a panel request too. A sniffed
        // rejection sentinel throws from inside the block, which classifies as HTTP_RESPONSE —
        // body bytes arrived, so the host is alive and the record clears.
        panelGuard.guardedPanelRequest(url) {
            gate.withPermitAsLocalWait {
                httpStream(url, headers) { line ->
                    sawBytes = sawBytes || line.isNotEmpty()
                    if (sniffing) {
                        sniff.append(line)
                        if (sniff.contains(AUTH_FAILED_MARKER, ignoreCase = true))
                            throw StalkerAuthException("Stalker portal rejected this device for ${account.name} — check the MAC address (and Serial / Device ID if the portal requires them)")
                        if (sniff.length > SNIFF_WINDOW) {
                            sniffing = false
                            onChunk(sniff.toString())
                            sniff.clear()
                        }
                    } else {
                        onChunk(line)
                    }
                }
            }
        }
        // Body smaller than the sniff window (tiny/empty responses) — flush what we held.
        if (sniffing && sniff.isNotEmpty()) onChunk(sniff.toString())
        return sawBytes
    }

    // --- Auth -----------------------------------------------------------------

    private suspend fun ensureAuthenticated() {
        if (token != null) return
        authMutex.withLock {
            if (token != null) return
            doHandshakeAndProfile()
        }
    }

    /**
     * Re-handshake ONCE for a stale [staleToken]. Single-flight like [ensureAuthenticated]: if another
     * coroutine already refreshed the token while we waited on the lock, reuse theirs instead of
     * handshaking again. Critical because a Stalker handshake OVERWRITES the MAC's token server-side —
     * N concurrent browse calls all re-authing would rotate the token N times and invalidate each
     * other's retry ("portal error" on the return-to-app path).
     */
    private suspend fun reauthenticate(staleToken: String?) {
        authMutex.withLock {
            if (token != staleToken) return   // someone already refreshed — reuse it
            token = null
            doHandshakeAndProfile()
        }
    }

    /**
     * Probe endpoints (if not resolved), handshake for a token, then get_profile to activate.
     *
     * Step 0.3b: this runs inside a failover race attempt that may be CANCELLED half-way (it lost to a
     * faster server). A session left with a token but no completed profile would read as "stale" on its
     * next use and trigger a re-handshake — a re-mint that rotates the MAC's token on a portal nobody
     * chose. So a cancellation anywhere in here discards the half-built session (token back to null: the
     * next use does one ordinary first handshake) and is never swallowed by a `runCatching`. The
     * handshake traffic is also not "the answer" of a racing attempt — only the browse request after it is.
     */
    private suspend fun doHandshakeAndProfile() {
        try {
            withoutHttpHeadersSignal { handshakeAndProfile() }
        } catch (c: CancellationException) {
            token = null
            throw c
        }
    }

    private suspend fun handshakeAndProfile() {
        val endpoint = resolvedEndpoint ?: probeEndpoint().also { resolvedEndpoint = it }
        val handshakeJs = rawRequestAt(
            endpoint,
            mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0"),
            tokenOverride = ""
        ).jsOrNull() ?: throw FailoverInvalidResponseException("Stalker handshake failed for ${account.name}")
        val newToken = (handshakeJs as? JsonObject)?.str("token")
            ?: throw FailoverInvalidResponseException("Stalker handshake returned no token for ${account.name}")
        token = newToken

        // get_profile activates the session. Non-fatal if it errors (some portals authorise on
        // handshake alone); we keep the token either way.
        // The ONE failure we must not shrug off is an identity rejection: a portal provisioned for a
        // different box answers the plain text "Authorization failed." here, and every later content
        // call then returns nothing. Left swallowed, that reads as an empty portal.
        val profileOutcome = runCatchingCancellable {
            rawRequestAt(endpoint, profileParams(authSecondStep = false))
        }

        val rejection = profileOutcome.exceptionOrNull() as? StalkerAuthException
        if (rejection == null) {
            val profileJs = profileOutcome.getOrNull()?.jsOrNull() as? JsonObject
            // status=1 is a REFUSAL (the portal understood us and said no) — never "empty portal".
            throwIfRefused(profileJs)
            val activatedJs = runFollowUpBootstrap(endpoint, profileJs)
            startWatchdog(activatedJs ?: profileJs)
            return
        }
        val nextPreset = StalkerMagPresets.next(magPreset) ?: throw rejection
        magPreset = nextPreset
        token = null
        handshakeAndProfile()
    }

    /** The full MAG profile params. [authSecondStep] is set ONLY by the post-do_auth retry —
     *  the portal's own client sends get_user_profile(false) at boot and (true) after do_auth. */
    private fun profileParams(authSecondStep: Boolean): Map<String, String> = buildMap {
        put("type", "stb"); put("action", "get_profile"); put("hd", "1")
        put("ver", magPreset.stbVer)
        put("num_banks", "2"); put("stb_type", magPreset.stbType); put("client_type", "STB")
        put("image_version", magPreset.imageVersion); put("video_out", "hdmi")
        put("hw_version", magPreset.hwVersion); put("not_valid_token", "0")
        put("device_id", identity.deviceId); put("device_id2", identity.deviceId2)
        if (account.sendDeviceId) put("signature", identity.signature)
        put("sn", identity.serialNumber)
        put("auth_second_step", if (authSecondStep) "1" else "0"); put("prehash", "0")
        account.stalkerUsername?.takeIf { it.isNotBlank() }?.let { put("login", it) }
        account.stalkerPassword?.takeIf { it.isNotBlank() }?.let { put("password", it) }
    }

    /** Throws the typed refusal for a `status: 1` profile — see [StalkerBootstrapPolicy.refusalAfterProfile]. */
    private fun throwIfRefused(profileJs: JsonObject?) {
        val refusal = StalkerBootstrapPolicy.refusalAfterProfile(
            status = profileJs?.get("status")?.looseInt(),
            msg = profileJs?.str("msg"),
            blockMsg = profileJs?.str("block_msg"),
        ) ?: return
        val portalSaid = refusal.portalText?.let { " Portal says: $it" }.orEmpty()
        if (refusal.deviceConflict) {
            throw StalkerDeviceConflictException(
                "Another device is using this MAC on ${account.name} — the portal has a different " +
                    "Device ID pinned to it. Stop the other device or ask the provider to reset the MAC.$portalSaid"
            )
        }
        throw StalkerPortalRefusedException(
            "Stalker portal refused ${account.name}." +
                portalSaid.ifEmpty { " The account may be disabled or the MAC not provisioned." }
        )
    }

    /**
     * The extra calls some portals require before they will serve anything — see
     * [StalkerBootstrapPolicy]. Best-effort: a portal that did not want these answers them with
     * junk, and failing the whole session over an optional step would be worse than not asking.
     *
     * Returns the auth_second_step profile's js when that retry ran (its fields are the freshest —
     * the watchdog cadence should come from it), else null.
     */
    private suspend fun runFollowUpBootstrap(endpoint: String, js: JsonObject?): JsonObject? {
        if (js == null) return null
        val steps = StalkerBootstrapPolicy.stepsAfterProfile(
            authAccess = js["auth_access"]?.looseBoolean(),
            status = js["status"]?.looseInt(),
            hasCredentials = !account.stalkerUsername.isNullOrBlank() &&
                !account.stalkerPassword.isNullOrBlank(),
        )
        var secondStepJs: JsonObject? = null
        for (step in steps) {
            when (step) {
                StalkerBootstrapPolicy.Step.DO_AUTH -> {
                    val authed = runCatchingCancellable {
                        rawRequestAt(
                            endpoint,
                            mapOf(
                                "type" to "stb",
                                "action" to "do_auth",
                                "login" to account.stalkerUsername.orEmpty(),
                                "password" to account.stalkerPassword.orEmpty(),
                            )
                        ).jsOrNull()
                    }.getOrNull() != null
                    // The portal's own client re-fetches the profile with auth_second_step=1 after
                    // a successful do_auth (c/xpcom.common.js) — ONLY that retry sets the flag.
                    if (authed) {
                        secondStepJs = runCatchingCancellable {
                            rawRequestAt(endpoint, profileParams(authSecondStep = true))
                        }.getOrNull()?.jsOrNull() as? JsonObject
                        // A refusal on the retry is as final as one on the first profile.
                        throwIfRefused(secondStepJs)
                    }
                }
                StalkerBootstrapPolicy.Step.GET_MODULES -> {
                    runCatchingCancellable { rawRequestAt(endpoint, mapOf("type" to "stb", "action" to "get_modules")) }
                }
            }
        }
        return secondStepJs
    }

    /**
     * (Re)start the get_events keep-alive with the cadence [profileJs] advertises. The init ping
     * rides activation INLINE (a real box pings before it browses; strict portals read it as part
     * of the bootstrap) — but its failure never fails auth, and the periodic loop pings only while
     * a token exists: the keep-alive must NEVER re-handshake on its own, because a handshake
     * evicts the other device on a shared MAC. Ping failures are log-only by contract — a missed
     * ping only affects the portal's "online" reporting.
     */
    private suspend fun startWatchdog(profileJs: JsonObject?) {
        val timing = StalkerWatchdogPolicy.timingFrom(
            watchdogTimeoutSeconds = profileJs?.str("watchdog_timeout")?.trim()?.toDoubleOrNull()?.toLong(),
            timeslotSeconds = profileJs?.str("timeslot")?.trim()?.toDoubleOrNull(),
        )
        runCatchingCancellable { rawRequest(StalkerWatchdogPolicy.pingParams(init = true)) }
        watchdogJob?.cancel()
        watchdogJob = watchdogScope.launch {
            delay(StalkerWatchdogPolicy.initialPeriodicDelayMs(timing))
            while (isActive) {
                if (token != null) {
                    runCatching { rawRequest(StalkerWatchdogPolicy.pingParams(init = false)) }
                }
                delay(StalkerWatchdogPolicy.periodMs(timing))
            }
        }
    }

    /**
     * Portals are loose about types — these arrive as booleans, numbers or quoted strings depending
     * on the panel. Null means absent or unreadable, which the policy treats as "nothing further".
     */
    private fun JsonElement.looseBoolean(): Boolean? {
        val prim = this as? JsonPrimitive ?: return null
        prim.booleanOrNull?.let { return it }
        prim.content.trim().toIntOrNull()?.let { return it != 0 }
        return when (prim.content.trim().lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
    }

    private fun JsonElement.looseInt(): Int? =
        (this as? JsonPrimitive)?.content?.trim()?.toIntOrNull()

    /** Try each candidate endpoint until one handshakes with a token. Throws if none do.
     *  The probes carry the guard's discovery flag (WP6): they run even while the breaker is open
     *  and their failures are never counted — but a probe that reaches the host clears its record. */
    private suspend fun probeEndpoint(): String {
        var lastError: Throwable? = null
        for (candidate in StalkerProtocol.ENDPOINT_CANDIDATES) {
            val ok = runCatchingCancellable {
                (rawRequestAt(
                    candidate,
                    mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0"),
                    tokenOverride = "",
                    discovery = true
                ).jsOrNull() as? JsonObject)?.str("token").isNullOrBlank().not()
            }.onFailure { lastError = it }.getOrDefault(false)
            if (ok) return candidate
        }
        // Every candidate answered with something that is not a Stalker handshake (a parked domain, a CDN page).
        throw (lastError ?: FailoverInvalidResponseException("No Stalker endpoint responded for ${account.name}"))
    }

    // --- HTTP -----------------------------------------------------------------

    private suspend fun rawRequest(params: Map<String, String>, onBody: ((String) -> Unit)? = null): JsonElement =
        rawRequestAt(resolvedEndpoint ?: StalkerProtocol.ENDPOINT_CANDIDATES.first(), params, onBody = onBody)

    /** One raw GET to [endpointPath] with full MAG headers. [tokenOverride] "" = the handshake call
     *  (no bearer yet); null = use the current session token. */
    /**
     * Hold browse traffic back while a stream from this portal is playing — most Stalker accounts
     * allow barely any concurrent connections, and a guide pulling categories can cost the viewer
     * the picture. Bootstrap and link creation are exempt: playback depends on them.
     */
    private suspend fun awaitPlaybackTraffic(action: String) {
        val isExempt = action in PLAYBACK_CRITICAL_ACTIONS
        var waited = 0L
        while (
            StalkerPlaybackTraffic.shouldDefer(
                playbackActive = StalkerPlaybackTraffic.isPlaybackActive,
                waitedMs = waited,
                isBootstrap = isExempt
            )
        ) {
            delay(StalkerPlaybackTraffic.DEFER_SLICE_MS)
            waited += StalkerPlaybackTraffic.DEFER_SLICE_MS
        }
    }

    private suspend fun rawRequestAt(
        endpointPath: String,
        params: Map<String, String>,
        tokenOverride: String? = null,
        // Endpoint-discovery probe (WP6): admitted even while the breaker is open, its failures
        // never counted — discovery expects most candidates to fail. Successes still clear.
        discovery: Boolean = false,
        // Sees the raw body (diagnostics only — see StalkerEmptyReplyPolicy).
        onBody: ((String) -> Unit)? = null,
    ): JsonElement {
        val action = params["action"].orEmpty()
        // Captured at ENQUEUE: if the user switches providers while this call waits for a gate
        // permit, it is answering a screen nobody is on — drop it instead of spending the
        // throttled host's budget on it (see StalkerPlaybackTraffic.browseEpoch).
        val enqueueEpoch = StalkerPlaybackTraffic.browseEpoch
        val tEnq = com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs()
        com.nuvio.app.core.diag.HubTrace.log("stalker", "enqueue") { "action=$action" }
        awaitingLocally { awaitPlaybackTraffic(action) }   // a local wait: not the portal being slow (failover race clock)
        val query = (params + ("JsHttpRequest" to "1-xml")).entries.joinToString("&") { (k, v) ->
            "${k.encodeURLParameter()}=${v.encodeURLParameter()}"
        }
        val url = "$baseUrl$endpointPath?$query"

        val bearer = tokenOverride ?: token
        val cookie = buildString {
            append("mac=").append(StalkerProtocol.encodeMacForCookie(account.macAddress))
            append("; stb_lang=en; timezone=Europe/London")
            append("; sn=").append(identity.serialNumber)
            append("; PHPSESSID=null")
        }
        val headers = buildMap {
            put("User-Agent", magPreset.userAgent)
            put("X-User-Agent", magPreset.xUserAgent)
            put("Referer", referer)
            put("Cookie", cookie)
            if (!bearer.isNullOrEmpty()) put("Authorization", "Bearer $bearer")
        }

        // httpGet throws on non-2xx / blank body — the caller treats that as a stale token and
        // re-auths. A parseable-but-non-JSON body degrades to an empty object (same signal).
        // The gate is the backstop against UI fan-out: the hub fires one get_short_epg per channel
        // tile as it composes (11k channels = 11k potential requests), and a portal behind Cloudflare
        // bans a client that opens that many at once. Nothing reaches the portal outside this gate.
        // The panel guard sits OUTSIDE the gate (WP6): a fast-fail must not queue behind requests
        // that are busy timing out, and its refusal (PanelHostFastFailException — never an auth
        // type, and worded to read as a connection-level failure) propagates without re-auth.
        val body = panelGuard.guardedPanelRequest(url, discovery) {
            gate.withPermitAsLocalWait {
                com.nuvio.app.core.diag.HubTrace.log("stalker", "gotPermit") { "action=$action waited=${com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs() - tEnq}ms" }
                // Checked with the permit in hand — the whole wait is the window a switch can
                // land in. Thrown INSIDE the guard so the breaker ignores it (an abandoned call
                // is not a panel failure); callers treat it like any transport failure.
                if (StalkerPlaybackTraffic.isAbandoned(
                        requestEpoch = enqueueEpoch,
                        currentEpoch = StalkerPlaybackTraffic.browseEpoch,
                        isCritical = action in PLAYBACK_CRITICAL_ACTIONS,
                    )
                ) {
                    throw StalkerBrowseAbandonedException()
                }
                val body0 = httpGet(url, headers)
                com.nuvio.app.core.diag.HubTrace.log("stalker", "done") { "action=$action total=${com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs() - tEnq}ms bytes=${body0.length}" }
                body0
            }
        }
        // A portal that rejects the STB identity replies HTTP 200 with the plain text "Authorization
        // failed." (not JSON) — a stale token recovers via re-auth, but a persistent rejection would
        // otherwise surface as a vague "no data". Throw an actionable error instead; it only becomes
        // terminal when re-auth can't fix it (i.e. the MAC/Serial/Device ID is genuinely wrong).
        onBody?.invoke(body)
        if (body.contains(AUTH_FAILED_MARKER, ignoreCase = true))
            throw StalkerAuthException("Stalker portal rejected this device for ${account.name} — check the MAC address (and Serial / Device ID if the portal requires them)")
        return runCatching { JSON.parseToJsonElement(body) }.getOrDefault(JsonObject(emptyMap()))
    }

    // --- JSON helpers ---------------------------------------------------------

    /** The `js` element of a `{"js": …}` envelope, or null if absent/empty/false. */
    private fun JsonElement.jsOrNull(): JsonElement? {
        val js = (this as? JsonObject)?.get("js") ?: return null
        return when {
            js is JsonNull -> null
            js is JsonPrimitive && js.booleanOrNull == false -> null
            js is JsonObject && js.isEmpty() -> null
            js is JsonArray && js.isEmpty() -> js   // an empty list IS valid data (no channels)
            else -> js
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        // The reference server's rejection sentinel: `echo 'Authorization failed.'; exit;`
        private const val AUTH_FAILED_MARKER = "Authorization failed"
        /**
         * How long to stop re-handshaking after a re-auth failed to recover. Two devices sharing a
         * MAC evict each other on every handshake, so without this each request would handshake
         * again and the pair would spin — a self-inflicted request storm, and portals ban for that.
         */
        private const val REAUTH_COOLDOWN_MS = 30_000L
        /** Bytes buffered at stream start to sniff "Authorization failed." before forwarding. */
        private const val SNIFF_WINDOW = 512
        // ponytail: fixed ceiling, no adaptive backoff. Raise only with evidence a portal tolerates
        // more; add backoff only if we start seeing 429s at this level.
        // Was 4; lowered after tracing TiviMate 5.3.3 against a controlled portal: the category
        // leader runs STRICTLY serial against Stalker portals (peak concurrency 1 across its whole
        // session, even the initial load), and a real portal's Cloudflare has banned this app
        // before over request volume. 2 keeps browse+EPG overlap without looking like a scraper.
        // (research/iptv-catalog-loading.md)
        private const val MAX_CONCURRENT_REQUESTS = 2

        /** Never held back by [StalkerPlaybackTraffic] — playback itself depends on these.
         *  get_events is here because the init ping rides the auth path (deferring it would add
         *  its wait to every mid-playback zap) and the keep-alive is a few bytes every ~120s. */
        private val PLAYBACK_CRITICAL_ACTIONS = setOf(
            "handshake", "get_profile", "create_link", "do_auth", "get_modules", "get_main_info",
            "get_events"
        )
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/** [runCatching] that never swallows a cancellation (a lost failover race must unwind, not read as a failure). */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }

/**
 * [kotlinx.coroutines.sync.withPermit], with the queueing for the permit reported as a LOCAL wait
 * (Step 0.3b): a request stuck behind its own siblings is not the portal being slow, and must not make
 * a failover race hand it to a backup portal.
 */
private suspend inline fun <T> Semaphore.withPermitAsLocalWait(action: () -> T): T {
    awaitingLocally { acquire() }
    try {
        return action()
    } finally {
        release()
    }
}
