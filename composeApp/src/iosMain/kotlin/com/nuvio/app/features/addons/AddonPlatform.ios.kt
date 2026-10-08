package com.nuvio.app.features.addons

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.network_empty_response_body
import nuvio.composeapp.generated.resources.network_request_failed_http
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSUserDefaults

actual object AddonStorage {
    private const val addonUrlsKey = "installed_manifest_urls"
    private const val addonEnabledStatesKey = "installed_manifest_enabled_states"
    private const val syncedAddonUrlsKey = "synced_manifest_urls"

    actual fun loadInstalledAddonUrls(profileId: Int): List<String> =
        NSUserDefaults.standardUserDefaults
            .stringForKey("${addonUrlsKey}_$profileId")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    actual fun saveInstalledAddonUrls(profileId: Int, urls: List<String>) {
        NSUserDefaults.standardUserDefaults.setObject(
            urls.joinToString(separator = "\n"),
            forKey = "${addonUrlsKey}_$profileId",
        )
    }

    actual fun loadAddonEnabledStates(profileId: Int): Map<String, Boolean> =
        NSUserDefaults.standardUserDefaults
            .stringForKey("${addonEnabledStatesKey}_$profileId")
            .orEmpty()
            .lineSequence()
            .mapNotNull(::parseEnabledStateLine)
            .toMap()

    actual fun saveAddonEnabledStates(profileId: Int, states: Map<String, Boolean>) {
        val payload = states.entries.joinToString(separator = "\n") { (url, enabled) ->
            "$url\t$enabled"
        }
        NSUserDefaults.standardUserDefaults.setObject(
            payload,
            forKey = "${addonEnabledStatesKey}_$profileId",
        )
    }

    actual fun loadSyncedAddonUrls(profileId: Int): List<String>? =
        NSUserDefaults.standardUserDefaults
            .stringForKey("${syncedAddonUrlsKey}_$profileId")
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()

    actual fun saveSyncedAddonUrls(profileId: Int, urls: List<String>) {
        NSUserDefaults.standardUserDefaults.setObject(
            urls.joinToString(separator = "\n"),
            forKey = "${syncedAddonUrlsKey}_$profileId",
        )
    }
}

private fun parseEnabledStateLine(line: String): Pair<String, Boolean>? {
    val url = line.substringBefore("\t").trim().takeIf { it.isNotEmpty() } ?: return null
    val rawEnabled = line.substringAfter("\t", "true").trim().lowercase()
    val enabled = when (rawEnabled) {
        "false" -> false
        else -> true
    }
    return url to enabled
}

private val addonHttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
    }
    expectSuccess = false
}

/**
 * Streaming client for [httpStreamLines] (M3U ingest + the Stalker bulk get_epg_info). Those bodies
 * run for minutes on a large portal, so a WHOLE-request cap is wrong — it would kill a long-but-
 * healthy download. Only the between-bytes [socketTimeoutMillis] guards a genuinely stalled stream;
 * [requestTimeoutMillis] is deliberately omitted (unlimited). Mirrors OkHttp's `m3uIngest` client on
 * Android/desktop, which uses a 10-minute read window for the same ~192 MB bodies.
 */
private val addonStreamHttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        connectTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
    }
    expectSuccess = false
}

/**
 * [bodyAsText] with the [MaxTextResponseBytes] guard in front of it.
 *
 * Ktor materializes the whole body here just as OkHttp does on Android, where a real provider's
 * ~27 MB Xtream catalog was enough to OOM the match-index build. The bulk lists stream now
 * (XtreamClient.streamArray), so what remains is a backstop — and unlike the Android twin it
 * checks only the DECLARED length rather than reading incrementally, because doing the read by
 * hand would mean re-implementing [bodyAsText]'s charset handling and risking a regression on
 * the panels that answer in something other than UTF-8. Worth noting the declared length can be
 * the compressed size on a gzipped response, so this bounds the obvious cases, not every case;
 * iOS also has far more headroom than ART's fixed heap ceiling, which is where the crash lived.
 */
private suspend fun HttpResponse.bodyAsBoundedText(): String {
    val declaredLength = contentLength() ?: -1L
    if (declaredLength > MaxTextResponseBytes) {
        throw ResponseTooLargeException(
            "Body too large to read as text ($declaredLength bytes, " +
                "limit ${MaxTextResponseBytes / (1024 * 1024)}MB)"
        )
    }
    return bodyAsText()
}

// [dnsProvider] (P3) is Android-only: Ktor Darwin / URLSession has no per-app DNS hook, so a
// per-playlist DoH resolver can't be installed on iOS. It's ignored here (the settings form already
// tells the user "Android only — iOS ignores this setting").
actual suspend fun httpGetText(url: String, dnsProvider: String?): String =
    getTextAttempt(url) {
        accept(ContentType.Application.Json)
    }

/**
 * GET [url] as text, split into the two phases a failover race needs (Step 0.3b): the STATUS is checked
 * and `signalHttpHeaders()` called before the body is read, so a racing attempt that lost never downloads
 * (or even starts reading) a body. Cancelling the calling coroutine cancels the NSURLSessionTask (Ktor
 * Darwin: `callContext.job.invokeOnCompletion { task.cancel() }`). Outside a race both calls are no-ops
 * and this behaves exactly like the plain `get`.
 *
 * Note for the race: Ktor Darwin completes its response when the FIRST BODY CHUNK (or the end of the
 * response) arrives, not at the status line, and has no connect-only timeout (URLSession's timeout is an
 * idle timer) — so on iOS "headers" means first byte and `connectTimeoutMs` is not applied.
 */
private suspend fun getTextAttempt(url: String, extra: io.ktor.client.request.HttpRequestBuilder.() -> Unit): String =
    addonHttpClient.prepareGet(url) { extra() }.execute { response ->
        if (!response.status.isSuccess()) {
            throw HttpStatusException(response.status.value, runBlocking { getString(Res.string.network_request_failed_http, response.status.value) })
        }
        signalHttpHeaders()
        val payload = response.bodyAsBoundedText()
        if (payload.isBlank()) {
            throw EmptyResponseBodyException(runBlocking { getString(Res.string.network_empty_response_body) })
        }
        payload
    }

actual suspend fun httpPostJson(url: String, body: String): String =
    addonHttpClient
        .post(url) {
            accept(ContentType.Application.Json)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }
        .let { response ->
            val payload = response.bodyAsBoundedText()
            if (!response.status.isSuccess()) {
                throw HttpStatusException(response.status.value, runBlocking { getString(Res.string.network_request_failed_http, response.status.value) })
            }
            if (payload.isBlank()) {
                throw EmptyResponseBodyException(runBlocking { getString(Res.string.network_empty_response_body) })
            }
            payload
        }

// [dnsProvider] is ignored here for the same reason as httpGetText above — no per-app DNS hook.
actual suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
    dnsProvider: String?,
): String =
    getTextAttempt(url) {
        accept(ContentType.Application.Json)
        headers.forEach { (key, value) ->
            header(key, value)
        }
    }

actual suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String =
    addonHttpClient
        .post(url) {
            accept(ContentType.Application.Json)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            headers.forEach { (key, value) ->
                header(key, value)
            }
            setBody(body)
        }
        .let { response ->
            val payload = response.bodyAsBoundedText()
            if (!response.status.isSuccess()) {
                throw HttpStatusException(response.status.value, runBlocking { getString(Res.string.network_request_failed_http, response.status.value) })
            }
            if (payload.isBlank()) {
                throw EmptyResponseBodyException(runBlocking { getString(Res.string.network_empty_response_body) })
            }
            payload
        }

/** Darwin decodes HTTP gzip; the line reader also handles raw gzip downloads by magic bytes. */
actual suspend fun httpStreamLines(
    url: String,
    userAgent: String?,
    dnsProvider: String?,   // Android-only (no-op on iOS — see httpGetText).
    headers: Map<String, String>,
    maxBytes: Long,
    onLine: (String) -> Unit,
) {
    addonStreamHttpClient.prepareGet(url) {
        if (!userAgent.isNullOrBlank()) header(HttpHeaders.UserAgent, userAgent)
        for ((k, v) in headers) header(k, v)
    }.execute { response ->
        if (!response.status.isSuccess()) {
            throw HttpStatusException(response.status.value, runBlocking { getString(Res.string.network_request_failed_http, response.status.value) })
        }
        // Step 0.3b: a racing failover attempt reports "answered" here, before any body byte is consumed.
        signalHttpHeaders()
        streamBoundedLines(response.bodyAsChannel(), onLine, maxBytes)
    }
}

/** See the Android twin: cap on how much is handed to [onLine] when no newline is in reach. */
private const val MAX_LINE_BYTES = 1 * 1024 * 1024

/**
 * Line reader that cannot exhaust memory on a newline-free document.
 *
 * `readUTF8Line()` buffers until it finds a '\n'. A MINIFIED XMLTV guide (whole document on one
 * line — real providers serve these) therefore buffers the entire feed, which is a ~100MB string.
 * On Android that killed the process outright; iOS is no safer under memory pressure.
 *
 * Reads fixed byte chunks, emits whole lines when found, and once the leftover exceeds the cap
 * emits it as its own chunk. Partial multi-byte characters are carried to the next chunk so a
 * glyph is never split. Safe for both consumers: M3U lines sit far below the cap, and the XMLTV
 * tokenizer accepts chunk boundaries falling anywhere.
 */
private suspend fun streamBoundedLines(channel: ByteReadChannel, onLine: (String) -> Unit, maxBytes: Long = Long.MAX_VALUE) {
    var carry = ByteArray(0)
    streamMaybeGzipChunks(channel, maxBytes) { readBuf, read ->
        val data = if (carry.isEmpty()) readBuf.copyOf(read) else carry + readBuf.copyOf(read)
        var start = 0
        while (true) {
            val newline = data.indexOfByteFrom('\n'.code.toByte(), start)
            if (newline < 0) break
            onLine(data.decodeToString(start, newline).removeSuffix("\r"))
            start = newline + 1
        }
        var rest = data.copyOfRange(start, data.size)
        while (rest.size >= MAX_LINE_BYTES) {
            val cut = utf8SafeCut(rest, MAX_LINE_BYTES)
            if (cut <= 0) break
            onLine(rest.decodeToString(0, cut))
            rest = rest.copyOfRange(cut, rest.size)
        }
        carry = rest
    }
    if (carry.isNotEmpty()) onLine(carry.decodeToString().removeSuffix("\r"))
}

private fun ByteArray.indexOfByteFrom(target: Byte, from: Int): Int {
    for (i in from until size) if (this[i] == target) return i
    return -1
}

/** Largest count <= [max] that doesn't land inside a multi-byte UTF-8 sequence. */
private fun utf8SafeCut(bytes: ByteArray, max: Int): Int {
    var n = minOf(max, bytes.size)
    if (n >= bytes.size) return bytes.size
    var walked = 0
    while (n > 0 && walked < 4 && (bytes[n].toInt() and 0xC0) == 0x80) {
        n--
        walked++
    }
    return if (n > 0) n else minOf(max, bytes.size)
}

actual suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean,
    maxResponseBodyBytes: Int,
    bodyBytes: ByteArray?,
): RawHttpResponse =
    addonHttpClient
        .request {
            url(url)
            this.method = HttpMethod.parse(method.uppercase())
            headers.forEach { (key, value) ->
                header(key, value)
            }
            if (this.method == HttpMethod.Post || this.method == HttpMethod.Put || this.method == HttpMethod.Patch) {
                setBody(bodyBytes ?: body)
            }
        }
        .let { response ->
            val receivedBytes = response.body<ByteArray>()
            val limitedBytes = receivedBytes.copyOf(minOf(receivedBytes.size, maxResponseBodyBytes.coerceAtLeast(0)))
            val decoded = limitedBytes.decodeToString()
            RawHttpResponse(
                status = response.status.value,
                statusText = response.status.description,
                url = response.call.request.url.toString(),
                body = if (receivedBytes.size > limitedBytes.size) "$decoded\n...[truncated]" else decoded,
                bodyBytes = limitedBytes,
                headers = response.headers.entries().associate { (name, values) ->
                    name.lowercase() to values.joinToString(",")
                },
            )
        }
