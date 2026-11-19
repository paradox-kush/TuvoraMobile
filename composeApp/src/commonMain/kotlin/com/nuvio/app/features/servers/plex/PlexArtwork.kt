package com.nuvio.app.features.servers.plex

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.mediabrowser.buildUrl
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodedPath

/** Signs marked Plex artwork links as they are downloaded. The disk cache keeps the unsigned link as its key. */
internal class PlexArtworkInterceptor(
    private val access: (String) -> PlexArtworkAccess? = ::plexArtworkAccess,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed()
        val connectionId = PlexArtworkLink.connectionId(url) ?: return chain.proceed()
        val server = access(connectionId) ?: return chain.proceed()
        val signed = PlexArtworkLink.signed(url, server.address, server.token) ?: return chain.proceed()
        val signedRequest = request.newBuilder()
            .data(signed)
            .diskCacheKey(request.diskCacheKey ?: url)
            .build()
        return chain.withRequest(signedRequest).proceed()
    }
}

/** The address and token a marked Plex link is fetched with. */
internal class PlexArtworkAccess(val address: String, val token: String) {
    override fun toString(): String = "PlexArtworkAccess(address=$address)"
}

private fun plexArtworkAccess(connectionId: String): PlexArtworkAccess? =
    ServerRepository.session(connectionId)
        ?.takeIf { it.connection.providerId == PlexProvider.ID }
        ?.let { PlexArtworkAccess(it.connection.address, it.token) }
