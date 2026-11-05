package com.nuvio.app.features.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.app.core.diagnostics.SentryNetworkBreadcrumbInterceptor
import com.nuvio.app.core.network.IPv4FirstDns
import com.nuvio.app.core.contracts.PlaybackTrustHosts
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.streams.PlaybackTlsClass
import com.nuvio.app.features.streams.PlaybackTlsPolicy
import io.ktor.http.Url
import okhttp3.Call
import okhttp3.OkHttpClient
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal object PlayerPlaybackNetworking {
    internal const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val DEFAULT_STREAM_HEADERS = mapOf(
        "User-Agent" to DEFAULT_USER_AGENT,
    )

    /** Certificate checks off. Reachable ONLY through [PanelFallbackCallFactory], for hosts [PlaybackTlsPolicy] calls PermissivePanel. */
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val permissiveHostnameVerifier = HostnameVerifier { _, _ -> true }

    private val permissiveSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    private fun baseClientBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(SentryNetworkBreadcrumbInterceptor())

    /** Normal system validation and hostname verification: what every host gets unless the policy says otherwise. */
    private val strictPlaybackHttpClient: OkHttpClient by lazy { baseClientBuilder().build() }

    private val permissivePlaybackHttpClient: OkHttpClient by lazy {
        strictPlaybackHttpClient.newBuilder()
            .sslSocketFactory(permissiveSslContext.socketFactory, trustAllManager)
            .hostnameVerifier(permissiveHostnameVerifier)
            .build()
    }

    /** The class of [host] for the live app: panel hosts of the user's IPTV providers, minus any media-server host. */
    internal fun tlsClassOf(host: String): PlaybackTlsClass = PlaybackTlsPolicy.classify(
        host = host,
        isPanelHost = PlaybackTrustHosts::isPanelHost,
        isMediaServerHost = { candidate ->
            ServerRepository.uiState.value.connections.any { connection ->
                runCatching { Url(connection.address).host }.getOrNull()?.let(PlaybackTlsPolicy::normalize) == candidate
            }
        },
    )

    private fun playbackCallFactory(configure: OkHttpClient.Builder.() -> Unit = {}): Call.Factory =
        PanelFallbackCallFactory(
            strict = strictPlaybackHttpClient.newBuilder().apply(configure).build(),
            permissive = permissivePlaybackHttpClient.newBuilder().apply(configure).build(),
            classify = ::tlsClassOf,
        )

    private fun loopbackConfigure(): OkHttpClient.Builder.() -> Unit = {
        addInterceptor { chain ->
            val request = chain.request()
            val requestChain = if (isLoopbackHost(request.url.host)) {
                chain.withReadTimeout(65, TimeUnit.SECONDS)
            } else {
                chain
            }
            requestChain.proceed(request)
        }
    }

    /**
     * The one place that decides how a playback request is sent: retry policy, the loopback long read timeout and
     * the same-host Authorization re-injection. The player and the live pause capture both go through it, so a
     * capture never behaves differently from the player it replaces.
     */
    private fun playerCallFactory(
        requestHeaders: Map<String, String>,
        loopback: Boolean,
        retryConnectionFailures: Boolean,
    ): Call.Factory {
        val authorization = requestHeaders.headerValue("Authorization")
        return playbackCallFactory {
            if (!retryConnectionFailures) retryOnConnectionFailure(false)
            if (loopback) loopbackConfigure()(this)
            authorization?.let { value ->
                addNetworkInterceptor { chain ->
                    val request = chain.request()
                    // Re-inject only on the original host. OkHttp deliberately drops Authorization on
                    // a cross-host redirect; re-adding it there would leak the credential to a foreign
                    // host (e.g. a subtitle/segment URL that 30x-es away).
                    val sameHost = request.url.host.equals(chain.call().request().url.host, ignoreCase = true)
                    if (sameHost && request.header("Authorization") == null) {
                        chain.proceed(request.newBuilder().header("Authorization", value).build())
                    } else {
                        chain.proceed(request)
                    }
                }
            }
        }
    }

    fun createHttpDataSourceFactory(
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
        /** False for a single-use link (LiveLinkReconnectPolicy): a silent OkHttp re-send would re-open a spent URL. */
        retryConnectionFailures: Boolean = true,
    ): DataSource.Factory {
        val requestHeaders = sanitizeHeaders(defaultHeaders)
        val client = playerCallFactory(requestHeaders, loopback = useLongReadTimeout, retryConnectionFailures = retryConnectionFailures)

        return OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(requestHeaders)
            if (requestHeaders.headerValue("User-Agent") == null) {
                setUserAgent(DEFAULT_USER_AGENT)
            }
        }
    }

    /**
     * The playback stack for the live pause capture (same DNS, TLS policy, timeouts and Sentry breadcrumbs as the
     * player). [retryConnectionFailures] false for a single-use link: a silent OkHttp re-send would re-open a spent URL.
     */
    fun captureCallFactory(url: String, headers: Map<String, String>, retryConnectionFailures: Boolean): Call.Factory =
        playerCallFactory(
            sanitizeHeaders(headers),
            loopback = runCatching { isLoopbackHost(java.net.URI(url).host.orEmpty().trim('[', ']')) }.getOrDefault(false),
            retryConnectionFailures = retryConnectionFailures,
        )

    /** The request headers the player would send for [headers]: sanitised, with the default User-Agent when none is set. */
    fun captureHeaders(headers: Map<String, String>): Map<String, String> = withDefaultUserAgent(headers)

    fun createDataSourceFactory(
        context: Context,
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
    ): DataSource.Factory {
        return DefaultDataSource.Factory(
            context,
            createHttpDataSourceFactory(defaultHeaders, useLongReadTimeout),
        )
    }

    fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null,
    ): HttpURLConnection {
        val mergedHeaders = withDefaultUserAgent(headers)
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", mergedHeaders.headerValue("User-Agent") ?: DEFAULT_USER_AGENT)
            mergedHeaders.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            range?.let { setRequestProperty("Range", it) }
        }
    }

    private fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapNotNull { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isBlank() || value.isBlank() || key.equals("Range", ignoreCase = true)) {
                null
            } else {
                key to value
            }
        }.toMap()

    private fun isLoopbackHost(host: String): Boolean = when (host.lowercase()) {
        "127.0.0.1", "localhost", "::1" -> true
        else -> false
    }

    private fun withDefaultUserAgent(headers: Map<String, String>): Map<String, String> {
        val sanitized = sanitizeHeaders(headers)
        if (sanitized.headerValue("User-Agent") != null) return sanitized
        return DEFAULT_STREAM_HEADERS + sanitized
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value
}
