package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.iptv.XtreamAccount
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

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
) {
    private var token: String? = null
    private var resolvedEndpoint: String? = null   // e.g. "/portal.php"

    private val authMutex = Mutex()

    private val baseUrl: String = account.baseUrl.trimEnd('/')
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
        val first = runCatching { rawRequest(params) }.getOrNull()?.jsOrNull()
        if (first != null) return first
        // Stale token / transient failure -> force a fresh handshake, then retry exactly once.
        reauthenticate()
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

    private suspend fun reauthenticate() {
        authMutex.withLock {
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

        // httpGetTextWithHeaders throws on non-2xx / blank body — the caller treats that as a stale
        // token and re-auths. A parseable-but-non-JSON body degrades to an empty object (same signal).
        val body = httpGetTextWithHeaders(url, headers)
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
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private const val STB_VER =
            "ImageDescription: 0.2.18-r14-pub-250; ImageDate: Wed Aug 29 10:49:52 EEST 2018; PORTAL version: 5.6.1; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c"
        private const val USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        private const val X_USER_AGENT = "Model: MAG250; Link: WiFi"
    }
}
