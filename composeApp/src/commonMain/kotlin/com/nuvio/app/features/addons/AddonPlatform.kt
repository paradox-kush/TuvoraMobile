package com.nuvio.app.features.addons

internal expect object AddonStorage {
    fun loadInstalledAddonUrls(profileId: Int): List<String>
    fun saveInstalledAddonUrls(profileId: Int, urls: List<String>)
    fun loadAddonEnabledStates(profileId: Int): Map<String, Boolean>
    fun saveAddonEnabledStates(profileId: Int, states: Map<String, Boolean>)

    /** The list this device and the server last agreed on, or null if it never synced. See [AddonSyncMerge]. */
    fun loadSyncedAddonUrls(profileId: Int): List<String>?
    fun saveSyncedAddonUrls(profileId: Int, urls: List<String>)

    /** Tracking add-ons the user consented to ON THIS DEVICE (never synced): transport urls. Default: none. */
    fun loadTrackingConsentUrls(profileId: Int): List<String>
    fun saveTrackingConsentUrls(profileId: Int, urls: List<String>)
}

data class RawHttpResponse(
    val status: Int,
    val statusText: String,
    val url: String,
    val body: String,
    val headers: Map<String, String>,
    /** The response body before charset decoding. */
    val bodyBytes: ByteArray = body.encodeToByteArray(),
)

/** Default safety limit for generic and plugin-provided HTTP responses. */
internal const val DefaultRawHttpResponseMaxBytes = 1024 * 1024

/**
 * Ceiling on a body [httpGetText] and friends will materialize as a String.
 *
 * Those functions can only work by holding the whole response at once, so a big enough body is
 * an OOM no matter how carefully it is read — a real provider's Xtream catalog is ~27 MB, and
 * allocating that in one piece is what silently killed TMDB matching on a phone already using
 * ~80 MB of heap. Bulk lists now stream instead (see XtreamClient.streamArray), and nothing
 * left on this path legitimately approaches this size, so exceeding it means something has gone
 * wrong. Failing with [ResponseTooLargeException] keeps that diagnosable and, unlike an
 * OutOfMemoryError, doesn't drag the rest of the app through blocking-GC stalls on the way down.
 */
internal const val MaxTextResponseBytes = 16 * 1024 * 1024

/**
 * A response was too large to be read as a String — see [MaxTextResponseBytes].
 *
 * [IllegalStateException] so existing `runCatching`/catch sites treat it exactly like any other
 * fetch failure.
 */
class ResponseTooLargeException(message: String) : IllegalStateException(message)

/**
 * The server answered 2xx with an empty body.
 *
 * Distinct from the generic failure so callers can tell "the server said nothing" apart from a
 * transport error or a 4xx/5xx. A Stalker portal uses exactly this to say "your session was taken
 * over" — it replies 200 with no body rather than 401 — and that must trigger a re-handshake,
 * whereas a 429/5xx must NOT (re-authing on those is how a portal gets hammered into banning us).
 *
 * Stays an [IllegalStateException] so existing `runCatching`/catch sites behave exactly as before.
 */
class EmptyResponseBodyException(message: String) : IllegalStateException(message)

/**
 * The server answered with a non-2xx status.
 *
 * Carries the [status] so callers can tell the failure classes apart WITHOUT parsing a localized
 * message. That distinction matters most for IPTV panels behind a WAF: a 403/429 means the
 * provider's edge refused us and the portal itself is perfectly healthy, which is the opposite of
 * the "portal is down" story a bare failure tells. See IptvLoadFailurePolicy.
 *
 * Stays an [IllegalStateException] with the SAME message the platform helpers always threw, so
 * every existing `runCatching`/catch site behaves exactly as before.
 */
class HttpStatusException(val status: Int, message: String) : IllegalStateException(message)

/**
 * GETs [url] as text. [dnsProvider] (P3) selects a per-playlist DNS-over-HTTPS resolver on Android
 * (values: system|cloudflare|google|mullvad|quad9|dnssb; null/"system" = the platform resolver).
 * iOS ignores it — there is no per-app DNS hook on URLSession/Ktor Darwin, so it's a no-op there.
 * Every non-IPTV caller omits it and keeps the exact previous behaviour.
 */
expect suspend fun httpGetText(url: String, dnsProvider: String? = null): String

expect suspend fun httpPostJson(url: String, body: String): String

/**
 * As [httpGetText] but with caller-supplied request headers. [dnsProvider] carries the same
 * per-playlist DoH selection (Android-only; iOS/desktop ignore it) — the Stalker portal session
 * passes its account's choice so portal calls resolve through the playlist's own resolver, like
 * every other IPTV fetch already does.
 */
expect suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
    dnsProvider: String? = null,
): String

expect suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String

expect suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean = true,
    maxResponseBodyBytes: Int = DefaultRawHttpResponseMaxBytes,
    /** When present, sends these bytes verbatim instead of UTF-8 encoding [body]. */
    bodyBytes: ByteArray? = null,
): RawHttpResponse

/**
 * Streams a text resource line-by-line to [onLine], NEVER materializing the whole body as a String.
 * Required for M3U ingestion: a provider playlist can be 190+ MB — [httpGetText] would OOM. The
 * response is gzip-decoded transparently when the server sends `Content-Encoding: gzip`. [onLine]
 * runs on a background thread; keep it cheap (buffer + flush) and do not block it. Throws on a
 * non-2xx status. Memory stays O(one line + the caller's buffer).
 *
 * Also carries the Xtream bulk lists (XtreamClient.streamArray), whose JSON is typically minified
 * onto a single line — the chunking cap in each implementation is what keeps that bounded, and the
 * catalog splitter accepts chunk boundaries falling anywhere. Note that a line arrives WITHOUT its
 * newline: harmless for JSON, where a newline is only whitespace between tokens.
 *
 * [dnsProvider] (P3) selects a per-playlist DNS-over-HTTPS resolver on Android for the M3U/XMLTV
 * fetch (same values as [httpGetText]); iOS ignores it (no per-app DNS hook).
 */
expect suspend fun httpStreamLines(
    url: String,
    userAgent: String?,
    dnsProvider: String? = null,
    // Extra request headers (P5, Stalker bulk-EPG streaming: Cookie/Referer/Authorization —
    // a MAG portal rejects bare requests). Additive default keeps every existing caller as-is.
    headers: Map<String, String> = emptyMap(),
    // Stop (and close the connection) after handing out this many bytes. The M3U failover probe reads
    // ~1 KB of a playlist that may be 190+ MB; Long.MAX_VALUE = read it all, as every other caller does.
    maxBytes: Long = Long.MAX_VALUE,
    onLine: (String) -> Unit,
)
