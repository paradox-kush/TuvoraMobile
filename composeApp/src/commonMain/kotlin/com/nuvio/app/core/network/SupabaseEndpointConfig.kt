package com.nuvio.app.core.network

internal object SupabaseEndpointConfig {
    private val primaryBaseUrl: String
        get() = ServerConfigurationRepository.active.value.backendUrl.normalizedBaseUrl()
    private val fallbackBaseUrl: String
        get() = ServerConfigurationRepository.active.value.fallbackBackendUrl.orEmpty().normalizedBaseUrl()

    val hasFallback: Boolean
        get() = fallbackBaseUrl.isNotBlank() && !fallbackBaseUrl.equals(primaryBaseUrl, ignoreCase = true)

    /**
     * The reachability probe's target.
     *
     * Deliberately `health_ping` and not the REST root. `GET /rest/v1/` carries no Authorization
     * header, so PostgREST answers 401 — the probe accepts any status and therefore worked, but it
     * generated ~2,400 401s a day and made the log's largest error class a non-event. `health_ping`
     * is granted to `anon`, answers 200, and unlike the REST root it actually reaches Postgres, so a
     * healthy answer means the sync path is genuinely up.
     *
     * It is a VOLATILE function, so PostgREST requires POST — see [healthProbeMethod].
     */
    fun healthProbeUrls(): List<String> =
        endpointUrls("/rest/v1/rpc/health_ping")

    const val healthProbeMethod: String = "POST"

    fun realtimeUrl(backendUrl: String): String {
        val base = backendUrl.normalizedBaseUrl()
        val socketBase = when {
            base.startsWith("https://", ignoreCase = true) -> "wss://" + base.substring("https://".length)
            base.startsWith("http://", ignoreCase = true) -> "ws://" + base.substring("http://".length)
            else -> base
        }
        return "$socketBase/realtime/v1?nuvio_realtime=1"
    }

    fun fallbackUrlFor(requestUrl: String): String? {
        if (!hasFallback || primaryBaseUrl.isBlank()) return null
        val trimmedUrl = requestUrl.trim()
        if (!trimmedUrl.startsWith(primaryBaseUrl, ignoreCase = true)) return null
        return fallbackBaseUrl + trimmedUrl.substring(primaryBaseUrl.length)
    }

    fun shouldRetryWithFallback(requestUrl: String, cause: Throwable): Boolean {
        return fallbackUrlFor(requestUrl) != null && cause.isOriginFailure()
    }

    fun shouldRetryWithFallback(requestUrl: String, statusCode: Int): Boolean {
        return fallbackUrlFor(requestUrl) != null && statusCode.isOriginFailureStatus()
    }

    private fun endpointUrls(path: String): List<String> =
        buildList {
            if (primaryBaseUrl.isNotBlank()) add(primaryBaseUrl + path)
            if (hasFallback) add(fallbackBaseUrl + path)
        }

    private fun String.normalizedBaseUrl(): String =
        trim().trimEnd('/')

    private fun Int.isOriginFailureStatus(): Boolean =
        this in 520..526 || this == 502 || this == 503 || this == 504

    private fun Throwable.isOriginFailure(): Boolean {
        val text = generateSequence(this) { it.cause }
            .joinToString(separator = " ") { error ->
                listOfNotNull(error::class.simpleName, error.message).joinToString(separator = " ")
            }
            .lowercase()

        return originFailureMarkers.any(text::contains)
    }

    private val originFailureMarkers = listOf(
        "ssl",
        "tls",
        "certificate",
        "certpath",
        "handshake",
        "timeout",
        "timed out",
        "connection reset",
        "failed to connect",
        "unable to resolve host",
        "network is unreachable",
        "closed",
    )
}
