package com.nuvio.app.features.servers.emby

import com.nuvio.app.features.servers.FixedServerHttp
import com.nuvio.app.features.servers.ServerHttp
import com.nuvio.app.features.servers.TrustedServerHttp
import com.nuvio.app.features.servers.mediabrowser.Endpoint
import com.nuvio.app.features.servers.mediabrowser.MediaBrowserProvider
import com.nuvio.app.features.servers.mediabrowser.PublicInfo
import com.nuvio.app.features.servers.mediabrowser.pathSegment
import com.nuvio.app.features.servers.ServerTitleQuery
import io.ktor.client.HttpClient

internal class EmbyProvider(
    http: ServerHttp = TrustedServerHttp.shared,
) : MediaBrowserProvider(authorizationHeader = "X-Emby-Authorization", apiPath = "/emby", http = http) {
    constructor(http: HttpClient) : this(FixedServerHttp(http))

    override val id: String = "emby"
    override val displayName: String = "Emby"
    override val minimumVersion: String = "4.7"

    override fun isSupported(info: PublicInfo): Boolean =
        info.productName?.contains("Jellyfin", ignoreCase = true) != true && isSupportedVersion(info.version)

    override fun viewsEndpoint(userId: String) = Endpoint("/Users/${pathSegment(userId)}/Views")

    override fun itemsEndpoint(userId: String) = Endpoint("/Users/${pathSegment(userId)}/Items")

    override fun itemEndpoint(userId: String, itemId: String) =
        Endpoint("/Users/${pathSegment(userId)}/Items/${pathSegment(itemId)}")

    override fun resumeEndpoint(userId: String) = Endpoint("/Users/${pathSegment(userId)}/Items/Resume")

    override fun playedEndpoint(userId: String, itemId: String) =
        Endpoint("/Users/${pathSegment(userId)}/PlayedItems/${pathSegment(itemId)}")
    override fun titleLookupQuery(query: ServerTitleQuery): Map<String, String>? {
        val ids = listOfNotNull(
            query.ids.imdb?.let { "imdb.$it" },
            query.ids.tmdb?.let { "tmdb.$it" },
            query.ids.tvdb?.let { "tvdb.$it" }
        )
        return if (ids.isEmpty()) null else mapOf("anyProviderIdEquals" to ids.joinToString(","))
    }
}
