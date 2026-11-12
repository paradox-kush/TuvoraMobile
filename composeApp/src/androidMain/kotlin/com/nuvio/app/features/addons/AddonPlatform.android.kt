package com.nuvio.app.features.addons

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.diagnostics.SentryNetworkBreadcrumbInterceptor
import com.nuvio.app.core.network.IPv4FirstDns
import com.nuvio.app.core.network.NetworkErrorInterceptor
import com.nuvio.app.core.network.PlaylistDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.io.IOException
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.network_empty_response_body
import nuvio.composeapp.generated.resources.network_request_failed_http
import org.jetbrains.compose.resources.getString
import okhttp3.Cache
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.text.Charsets
import java.util.concurrent.TimeUnit
import okio.GzipSource
import okio.buffer

actual object AddonStorage {
    private const val preferencesName = "nuvio_addons"
    private const val addonUrlsKey = "installed_manifest_urls"
    private const val addonEnabledStatesKey = "installed_manifest_enabled_states"
    private const val syncedAddonUrlsKey = "synced_manifest_urls"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadInstalledAddonUrls(profileId: Int): List<String> =
        preferences
            ?.getString("${addonUrlsKey}_$profileId", null)
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    actual fun saveInstalledAddonUrls(profileId: Int, urls: List<String>) {
        preferences
            ?.edit()
            ?.putString("${addonUrlsKey}_$profileId", urls.joinToString(separator = "\n"))
            ?.apply()
    }

    actual fun loadAddonEnabledStates(profileId: Int): Map<String, Boolean> =
        preferences
            ?.getString("${addonEnabledStatesKey}_$profileId", null)
            .orEmpty()
            .lineSequence()
            .mapNotNull(::parseEnabledStateLine)
            .toMap()

    actual fun saveAddonEnabledStates(profileId: Int, states: Map<String, Boolean>) {
        val payload = states.entries.joinToString(separator = "\n") { (url, enabled) ->
            "$url\t$enabled"
        }
        preferences
            ?.edit()
            ?.putString("${addonEnabledStatesKey}_$profileId", payload)
            ?.apply()
    }

    actual fun loadSyncedAddonUrls(profileId: Int): List<String>? =
        preferences
            ?.getString("${syncedAddonUrlsKey}_$profileId", null)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()

    actual fun saveSyncedAddonUrls(profileId: Int, urls: List<String>) {
        preferences
            ?.edit()
            ?.putString("${syncedAddonUrlsKey}_$profileId", urls.joinToString(separator = "\n"))
            ?.apply()
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

internal object AddonHttpClientProvider {
    private const val cacheSizeBytes = 50L * 1024L * 1024L
    private var client = buildAddonHttpClient()

    fun initialize(context: Context) {
        if (client.cache != null) return
        client = buildAddonHttpClient(
            cache = Cache(
                directory = File(context.cacheDir, "addon_http"),
                maxSize = cacheSizeBytes,
            ),
        )
    }

    fun get(): OkHttpClient = client
}

private fun buildAddonHttpClient(cache: Cache? = null): OkHttpClient =
    OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(NetworkErrorInterceptor())
        .addInterceptor(SentryNetworkBreadcrumbInterceptor())
        .proxy(Proxy.NO_PROXY)
        .apply {
            if (cache != null) {
                cache(cache)
            }
        }
        .build()

private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

/**
 * Whole-call limit for a text request (DNS + connect + headers + the whole body), matching iOS's
 * `requestTimeoutMillis`. The client's read timeout only fires after 60 s of SILENCE, so a provider that
 * trickles a byte now and then held a request — and the screen waiting on it — forever. Set per call, never
 * on the client: streamed downloads (a 190 MB M3U, an XMLTV guide) legitimately run longer.
 */
internal var textRequestCallTimeoutMs = 60_000L   // a test shortens it

private data class LimitedReadResult(
    val bytes: ByteArray,
    val truncated: Boolean,
)

private fun requestAllowsBody(method: String): Boolean =
    when (method.uppercase()) {
        "POST", "PUT", "PATCH", "DELETE" -> true
        else -> false
    }

private fun Map<String, String>.withoutAcceptEncoding(): Map<String, String> =
    entries
        .filterNot { (key, _) -> key.equals("Accept-Encoding", ignoreCase = true) }
        .associate { (key, value) -> key to value }

private fun Map<String, String>.getHeaderIgnoreCase(name: String): String? =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

private fun readAtMostBytes(stream: InputStream, maxBytes: Int): LimitedReadResult {
    val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var remaining = maxBytes
    var truncated = false

    while (remaining > 0) {
        val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }

    if (remaining == 0) {
        truncated = stream.read() != -1
    }

    return LimitedReadResult(out.toByteArray(), truncated)
}

private fun readResponseBodyLimited(body: ResponseBody?, maxBytes: Int): String {
    if (body == null) return ""
    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    val readResult = body.byteStream().use { stream ->
        readAtMostBytes(stream, maxBytes.coerceAtLeast(0))
    }

    val decoded = try {
        String(readResult.bytes, charset)
    } catch (_: Exception) {
        String(readResult.bytes, Charsets.UTF_8)
    }

    return if (readResult.truncated) "$decoded\n...[truncated]" else decoded
}

/**
 * Whole-body read, bounded by [MaxTextResponseBytes].
 *
 * `body.bytes()` asks for the entire response as ONE array, which is how a large Xtream catalog
 * produced `Failed to allocate a 26891064 byte allocation` here. Bulk lists stream now, so this
 * cap is a backstop against anything else on this path growing without warning.
 *
 * A declared Content-Length over the cap is refused before a single byte is read — the cheapest
 * possible outcome. Within the cap it still goes through `bytes()`, whose one exact-sized
 * allocation beats a growing buffer that doubles and copies. Only a length-less (chunked)
 * response needs the incremental read, and those are small in practice.
 */
private fun readResponseBody(body: ResponseBody?): String {
    if (body == null) return ""
    val declaredLength = body.contentLength()
    if (declaredLength > MaxTextResponseBytes) throw responseTooLarge(declaredLength)

    val bytes = if (declaredLength >= 0) {
        body.bytes()
    } else {
        val readResult = body.byteStream().use { stream ->
            readAtMostBytes(stream, MaxTextResponseBytes)
        }
        // readAtMostBytes peeks one byte past the cap, so an overrun is caught without
        // buffering the excess.
        if (readResult.truncated) throw responseTooLarge(-1)
        readResult.bytes
    }

    return runCatching {
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        String(bytes, charset)
    }.getOrElse {
        String(bytes, Charsets.UTF_8)
    }
}

private fun responseTooLarge(declaredLength: Long): ResponseTooLargeException {
    val limitMb = MaxTextResponseBytes / (1024 * 1024)
    val size = if (declaredLength >= 0) "$declaredLength bytes" else "response"
    return ResponseTooLargeException("Body too large to read as text ($size, limit ${limitMb}MB)")
}

/**
 * The client for a request: the per-playlist DoH client when [dnsProvider] names a real resolver
 * (P3, IPTV only), else the shared addon client. Any failure building the DoH client falls back to
 * [addonHttpClient] — DNS must never break a fetch.
 */
private fun clientForDns(dnsProvider: String?): OkHttpClient =
    runCatching { PlaylistDns.clientFor(dnsProvider, AddonHttpClientProvider.get()) }.getOrNull() ?: AddonHttpClientProvider.get()

/**
 * [clientForDns] with the failover attempt's CONNECT timeout (Step 0.3b) when this request runs inside
 * a backup-server race: only the connect phase is shortened (read/overall timeouts keep their values),
 * and `newBuilder()` shares the pool/dispatcher/DNS/cache, so it costs one small allocation.
 */
private suspend fun clientForAttempt(dnsProvider: String?): OkHttpClient {
    val base = clientForDns(dnsProvider)
    val connectMs = failoverConnectTimeoutMs() ?: return base
    return base.newBuilder().connectTimeout(connectMs, TimeUnit.MILLISECONDS).build()
}

/**
 * Wires coroutine cancellation to [call]: `Call.cancel()` closes the socket, which is what actually
 * unblocks a synchronous `execute()`/read (a blocking okio call never observes coroutine
 * cancellation). `onCancelling = true` fires as soon as the job is cancelled — BEFORE the blocking call
 * returns; a default `invokeOnCompletion` would only fire after it, i.e. never.
 */
@OptIn(InternalCoroutinesApi::class)
private suspend fun cancelCallWithJob(call: okhttp3.Call) =
    coroutineContext.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) call.cancel()
    }

private suspend fun executeTextRequest(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: String = "",
    dnsProvider: String? = null,
): String = withContext(Dispatchers.IO) {
    val normalizedMethod = method.uppercase()
    val sanitizedHeaders = headers.withoutAcceptEncoding()
    val builder = Request.Builder().url(url)
    sanitizedHeaders.forEach { (key, value) ->
        builder.header(key, value)
    }

    val request = if (requestAllowsBody(normalizedMethod)) {
        val contentType = sanitizedHeaders.getHeaderIgnoreCase("Content-Type")
            ?: if (normalizedMethod == "POST") "application/x-www-form-urlencoded" else "application/json"
        // Preserve exact media type and avoid implicit charset rewriting used in signed APIs like MovieBox.
        val requestBody = body.toByteArray(Charsets.UTF_8).toRequestBody(contentType.toMediaType())
        builder.method(normalizedMethod, requestBody)
    } else {
        builder.method(normalizedMethod, null)
    }.build()

    val call = clientForAttempt(dnsProvider).newCall(request)
    call.timeout().timeout(textRequestCallTimeoutMs, TimeUnit.MILLISECONDS)
    // Step 0.3b: a racing failover attempt that lost must stop NOW — cancel the socket, not just the coroutine.
    val cancelHook = cancelCallWithJob(call)
    try {
        call.execute().use { response ->
            if (!response.isSuccessful) {
                // The status is known from the headers alone: no body is read for a failure.
                throw HttpStatusException(response.code, runBlocking { getString(Res.string.network_request_failed_http, response.code) })
            }
            // 2xx headers are in: a racing attempt reports "answered" here (a loser is parked until it
            // is cancelled), before any body byte is read.
            signalHttpHeaders()
            val payload = readResponseBody(response.body)
            if (payload.isBlank()) {
                throw EmptyResponseBodyException(runBlocking { getString(Res.string.network_empty_response_body) })
            }
            payload
        }
    } catch (t: Throwable) {
        coroutineContext.ensureActive() // a cancel-induced IOException becomes CancellationException
        throw t
    } finally {
        cancelHook.dispose()
    }
}

actual suspend fun httpGetText(url: String, dnsProvider: String?): String =
    executeTextRequest(
        method = "GET",
        url = url,
        headers = mapOf("Accept" to "application/json"),
        dnsProvider = dnsProvider,
    )

actual suspend fun httpPostJson(url: String, body: String): String =
    executeTextRequest(
        method = "POST",
        url = url,
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        ),
        body = body,
    )

actual suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
    dnsProvider: String?,
): String =
    executeTextRequest(
        method = "GET",
        url = url,
        headers = mapOf("Accept" to "application/json") + headers,
        dnsProvider = dnsProvider,
    )

actual suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String =
    executeTextRequest(
        method = "POST",
        url = url,
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        ) + headers,
        body = body,
    )

actual suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean,
    maxResponseBodyBytes: Int,
    bodyBytes: ByteArray?,
): RawHttpResponse =
    withContext(Dispatchers.IO) {
        val normalizedMethod = method.uppercase()
        val sanitizedHeaders = headers.withoutAcceptEncoding()
        val builder = Request.Builder().url(url)
        sanitizedHeaders.forEach { (key, value) ->
            builder.header(key, value)
        }

        val request = if (requestAllowsBody(normalizedMethod)) {
            val contentType = sanitizedHeaders.getHeaderIgnoreCase("Content-Type")
                ?: if (normalizedMethod == "POST") "application/x-www-form-urlencoded" else "application/json"
            val requestBody = (bodyBytes ?: body.toByteArray(Charsets.UTF_8))
                .toRequestBody(contentType.toMediaType())
            builder.method(normalizedMethod, requestBody)
        } else {
            builder.method(normalizedMethod, null)
        }.build()

        val client = if (followRedirects) {
            AddonHttpClientProvider.get()
        } else {
            AddonHttpClientProvider.get().newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        val call = client.newCall(request)
        call.timeout().timeout(textRequestCallTimeoutMs, TimeUnit.MILLISECONDS)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                val contentType = response.body?.contentType()
                val readResult = response.body?.byteStream()?.use { stream ->
                    readAtMostBytes(stream, maxResponseBodyBytes.coerceAtLeast(0))
                } ?: LimitedReadResult(ByteArray(0), truncated = false)
                val charset = contentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val decoded = runCatching { String(readResult.bytes, charset) }
                    .getOrElse { String(readResult.bytes, Charsets.UTF_8) }
                RawHttpResponse(
                    status = response.code,
                    statusText = response.message,
                    url = response.request.url.toString(),
                    body = if (readResult.truncated) "$decoded\n...[truncated]" else decoded,
                    bodyBytes = readResult.bytes,
                    headers = response.headers.toMultimap().mapValues { (_, values) ->
                        values.joinToString(",")
                    }.mapKeys { (name, _) ->
                        name.lowercase()
                    },
                )
            }
        } catch (error: IOException) {
            if (call.isCanceled()) throw CancellationException("Cancelled HTTP request", error)
            throw error
        } finally {
            cancelHandle?.dispose()
        }
    }

actual suspend fun httpStreamLines(
    url: String,
    userAgent: String?,
    dnsProvider: String?,
    headers: Map<String, String>,
    maxBytes: Long,
    onLine: (String) -> Unit,
): Unit = withContext(Dispatchers.IO) {
    val builder = Request.Builder().url(url).get()
    if (!userAgent.isNullOrBlank()) builder.header("User-Agent", userAgent)
    for ((k, v) in headers) builder.header(k, v)
    // OkHttp transparently gunzips a `Content-Encoding: gzip` response when we don't set
    // Accept-Encoding ourselves — so leave it unset. For a URL that returns a raw .gz body
    // (no Content-Encoding header), we sniff the gzip magic bytes and wrap manually.
    val request = builder.build()
    // Cancellation must stop the actual blocking read, not merely abandon the coroutine: a synchronous
    // okio read does not observe coroutine cancellation, so wire it to OkHttp's Call.cancel(), which
    // closes the socket and unblocks the read. ensureActive() then surfaces it as CancellationException
    // (rather than the resulting SocketException) so the caller treats it as a cancel, not a failure.
    val call = clientForAttempt(dnsProvider).newCall(request)
    // onCancelling=true fires as soon as the job is cancelled — BEFORE the blocking read returns —
    // which is what unblocks it (a default invokeOnCompletion only fires once the block finishes,
    // deadlocking against the very read we need to interrupt).
    val cancelHook = cancelCallWithJob(call)
    try {
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, runBlocking { getString(Res.string.network_request_failed_http, response.code) })
            }
            // 2xx headers are in (Step 0.3b): a racing attempt reports "answered" before any body byte is read.
            signalHttpHeaders()
            val body = response.body ?: return@use
            val rawSource = body.source()
            val encoding = response.header("Content-Encoding")?.lowercase()
            // Peek the first two bytes for the gzip magic (0x1f 0x8b) — only when OkHttp didn't
            // already decode (encoding is null because the server sent a bare .gz file).
            val looksGzipped = encoding == null && runCatching {
                rawSource.request(2)
                rawSource.buffer.size >= 2 &&
                    rawSource.buffer[0] == 0x1f.toByte() && rawSource.buffer[1] == 0x8b.toByte()
            }.getOrDefault(false)
            val source: okio.BufferedSource = if (looksGzipped) {
                GzipSource(rawSource).buffer()
            } else {
                rawSource
            }
            streamBoundedLines(source, onLine, maxBytes)
        }
    } catch (t: Throwable) {
        coroutineContext.ensureActive() // a cancel-induced read failure becomes CancellationException
        throw t
    } finally {
        cancelHook.dispose()
    }
}

/**
 * Largest slice handed to [onLine] when the document has no newline in reach. Big enough that a
 * normal line-delimited playlist/guide is unaffected, small enough that a newline-free document
 * can't blow the heap.
 */
private const val MAX_LINE_BYTES = 1L * 1024 * 1024

/**
 * Line reader that cannot OOM on a newline-free document.
 *
 * `readUtf8Line()` buffers until it finds a '\n' — on a MINIFIED XMLTV guide (the whole document on
 * one line, which real providers do serve) that means buffering the entire feed: a 108MB allocation
 * against a ~192MB heap, which killed the app on launch. Here the search for the newline is capped,
 * and when none is found within the cap the buffered slice is emitted as its own chunk.
 *
 * Safe for both consumers: M3U lines are far below the cap so they still arrive whole, and the
 * XMLTV tokenizer explicitly accepts chunk boundaries falling anywhere, even mid-tag.
 */
internal fun streamBoundedLines(source: okio.BufferedSource, onLine: (String) -> Unit, maxBytes: Long = Long.MAX_VALUE) {
    var consumed = 0L
    while (true) {
        // [maxBytes] (the M3U validation probe reads ~1 KB and stops): the search window never reaches past it.
        val window = minOf(MAX_LINE_BYTES, maxBytes - consumed)
        if (window <= 0L) return
        val newline = source.indexOf('\n'.code.toByte(), 0L, window)
        if (newline != -1L) {
            val line = source.readUtf8(newline)
            source.skip(1)                      // drop the '\n'
            consumed += newline + 1
            onLine(line.removeSuffix("\r"))
            continue
        }
        if (!source.request(1)) return          // EOF
        // No newline within the window: emit what we have, cut on a character boundary so a
        // multi-byte glyph is never split across two chunks.
        val cut = utf8SafeCut(source.buffer, window)
        if (cut <= 0L) return
        consumed += cut
        onLine(source.readUtf8(cut))
    }
}

/** Largest byte count <= [max] that doesn't land inside a multi-byte UTF-8 sequence. */
private fun utf8SafeCut(buffer: okio.Buffer, max: Long): Long {
    var n = minOf(max, buffer.size)
    if (n >= buffer.size) return buffer.size    // whole buffer: nothing follows to split
    var walked = 0
    while (n > 0 && walked < 4 && (buffer[n].toInt() and 0xC0) == 0x80) {
        n--
        walked++
    }
    return if (n > 0) n else minOf(max, buffer.size)
}
