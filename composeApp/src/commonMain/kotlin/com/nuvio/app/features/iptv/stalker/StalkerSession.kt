package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.iptv.XtreamAccount
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 * A stateful Stalker-portal (MAG/Ministra) session for ONE playlist. Owns endpoint probing (the user
 * enters just a base portal URL; we try [StalkerProtocol.ENDPOINT_CANDIDATES] in order and remember
 * the first that handshakes), the auth token from `handshake` + the device identity from `get_profile`,
 * a single-flight (re-)authenticate, and [request] — an authenticated GET that transparently
 * re-handshakes + retries once on an expired token / empty `js` / HTTP failure.
 *
 * Ported from NuvioTV's StalkerSession; the OkHttp call is swapped for [httpGetTextWithHeaders] (which
 * throws on any non-2xx / blank body — that throw IS the stale-token signal the retry path catches) and
 * Gson for kotlinx.serialization. Portal API calls use the system resolver; the per-playlist DoH only
 * applies to PLAYBACK (the create_link'd URL rides the DNS-aware player path), matching TV.
 */
internal class StalkerSession(
    private val account: XtreamAccount,
    // Injectable HTTP seam so the auth/retry logic is unit-testable with a fake portal; production
    // uses the real platform GET (throws on non-2xx / blank body — that throw IS the stale signal).
    private val httpGet: suspend (url: String, headers: Map<String, String>) -> String = ::httpGetTextWithHeaders,
) {
    private var token: String? = null
    private var resolvedEndpoint: String? = null   // e.g. "/portal.php"

    private val authMutex = Mutex()

    // Hard ceiling on concurrent requests to this portal. A real MAG box opens a couple of
    // connections; magplex (the reference client) caps this at 3 explicitly "to prevent rate
    // limiting". Ours is per-session so a busy hub can't fan out into a ban.
    private val gate = Semaphore(MAX_CONCURRENT_REQUESTS)

    private val baseUrl: String = StalkerProtocol.normalizePortalBase(account.baseUrl)
    private val identity: StalkerProtocol.DeviceIdentity =
        StalkerProtocol.deriveDeviceIdentity(
            mac = account.macAddress,
            serialOverride = account.serialNumber,
            deviceIdOverride = account.deviceId
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
        }
        if (first != null) return first
        // Stale token (empty `js` / "Authorization failed.") -> one fresh handshake, then retry once.
        reauthenticate(staleToken)
        return rawRequest(params).jsOrNull()
            ?: error("Stalker portal returned no data for ${params["action"]}")
    }

    /** Force re-auth on the next call (used when a create_link/browse hits a hard failure). */
    fun invalidate() { token = null }

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

    /** Probe endpoints (if not resolved), handshake for a token, then get_profile to activate. */
    private suspend fun doHandshakeAndProfile() {
        val endpoint = resolvedEndpoint ?: probeEndpoint().also { resolvedEndpoint = it }
        val handshakeJs = rawRequestAt(
            endpoint,
            mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0"),
            tokenOverride = ""
        ).jsOrNull() ?: error("Stalker handshake failed for ${account.name}")
        val newToken = (handshakeJs as? JsonObject)?.str("token")
            ?: error("Stalker handshake returned no token for ${account.name}")
        token = newToken

        // get_profile activates the session. Non-fatal if it errors (some portals authorise on
        // handshake alone); we keep the token either way.
        runCatching {
            val profileParams = buildMap {
                put("type", "stb"); put("action", "get_profile"); put("hd", "1")
                put("ver", STB_VER)
                put("num_banks", "2"); put("stb_type", "MAG250"); put("client_type", "STB")
                put("image_version", "218"); put("video_out", "hdmi")
                put("hw_version", "1.7-BD-00"); put("not_valid_token", "0")
                put("device_id", identity.deviceId); put("device_id2", identity.deviceId2)
                if (account.sendDeviceId) put("signature", identity.signature)
                put("sn", identity.serialNumber)
                put("auth_second_step", "0"); put("prehash", "0")
                account.stalkerUsername?.takeIf { it.isNotBlank() }?.let { put("login", it) }
                account.stalkerPassword?.takeIf { it.isNotBlank() }?.let { put("password", it) }
            }
            rawRequestAt(endpoint, profileParams)
        }
    }

    /** Try each candidate endpoint until one handshakes with a token. Throws if none do. */
    private suspend fun probeEndpoint(): String {
        var lastError: Throwable? = null
        for (candidate in StalkerProtocol.ENDPOINT_CANDIDATES) {
            val ok = runCatching {
                (rawRequestAt(
                    candidate,
                    mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0"),
                    tokenOverride = ""
                ).jsOrNull() as? JsonObject)?.str("token").isNullOrBlank().not()
            }.onFailure { lastError = it }.getOrDefault(false)
            if (ok) return candidate
        }
        throw (lastError ?: IllegalStateException("No Stalker endpoint responded for ${account.name}"))
    }

    // --- HTTP -----------------------------------------------------------------

    private suspend fun rawRequest(params: Map<String, String>): JsonElement =
        rawRequestAt(resolvedEndpoint ?: StalkerProtocol.ENDPOINT_CANDIDATES.first(), params)

    /** One raw GET to [endpointPath] with full MAG headers. [tokenOverride] "" = the handshake call
     *  (no bearer yet); null = use the current session token. */
    private suspend fun rawRequestAt(
        endpointPath: String,
        params: Map<String, String>,
        tokenOverride: String? = null
    ): JsonElement {
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
            put("User-Agent", USER_AGENT)
            put("X-User-Agent", X_USER_AGENT)
            put("Referer", referer)
            put("Cookie", cookie)
            if (!bearer.isNullOrEmpty()) put("Authorization", "Bearer $bearer")
        }

        // httpGet throws on non-2xx / blank body — the caller treats that as a stale token and
        // re-auths. A parseable-but-non-JSON body degrades to an empty object (same signal).
        // The gate is the backstop against UI fan-out: the hub fires one get_short_epg per channel
        // tile as it composes (11k channels = 11k potential requests), and a portal behind Cloudflare
        // bans a client that opens that many at once. Nothing reaches the portal outside this gate.
        val body = gate.withPermit { httpGet(url, headers) }
        // A portal that rejects the STB identity replies HTTP 200 with the plain text "Authorization
        // failed." (not JSON) — a stale token recovers via re-auth, but a persistent rejection would
        // otherwise surface as a vague "no data". Throw an actionable error instead; it only becomes
        // terminal when re-auth can't fix it (i.e. the MAC/Serial/Device ID is genuinely wrong).
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
        // ponytail: fixed ceiling, no adaptive backoff. Raise only with evidence a portal tolerates
        // more; add backoff only if we start seeing 429s at this level.
        private const val MAX_CONCURRENT_REQUESTS = 4
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private const val STB_VER =
            "ImageDescription: 0.2.18-r14-pub-250; ImageDate: Wed Aug 29 10:49:52 EEST 2018; PORTAL version: 5.6.1; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c"
        private const val USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        private const val X_USER_AGENT = "Model: MAG250; Link: WiFi"
    }
}
