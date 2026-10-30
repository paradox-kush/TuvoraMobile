package com.nuvio.app.features.servers.jellyfin

import com.nuvio.app.features.servers.FixedServerHttp
import com.nuvio.app.features.servers.ServerHttp
import com.nuvio.app.features.servers.TrustedServerHttp
import com.nuvio.app.features.servers.mediabrowser.Endpoint
import com.nuvio.app.features.servers.mediabrowser.MediaBrowserProvider
import com.nuvio.app.features.servers.mediabrowser.pathSegment
import com.nuvio.app.features.servers.ServerTitleQuery
import io.ktor.client.HttpClient

internal class JellyfinProvider(
    http: ServerHttp = TrustedServerHttp.shared,
) : MediaBrowserProvider(authorizationHeader = "Authorization", apiPath = "", http = http) {
    constructor(http: HttpClient) : this(FixedServerHttp(http))

    override val id: String = "jellyfin"
    override val displayName: String = "Jellyfin"
    override val minimumVersion: String = "10.9"
    override val capabilities: Set<com.nuvio.app.features.servers.ServerCapability> =
        super.capabilities + com.nuvio.app.features.servers.ServerCapability.QUICK_CONNECT

    override fun viewsEndpoint(userId: String) = Endpoint("/UserViews", mapOf("userId" to userId))

    override fun itemsEndpoint(userId: String) = Endpoint("/Items", mapOf("userId" to userId))

    override fun itemEndpoint(userId: String, itemId: String) =
        Endpoint("/Items/${pathSegment(itemId)}", mapOf("userId" to userId))

    override fun resumeEndpoint(userId: String) = Endpoint("/UserItems/Resume", mapOf("userId" to userId))

    override fun playedEndpoint(userId: String, itemId: String) =
        Endpoint("/UserPlayedItems/${pathSegment(itemId)}", mapOf("userId" to userId))
    override fun titleLookupQuery(query: ServerTitleQuery): Map<String, String>? =
        query.name?.trim()?.takeIf { it.isNotEmpty() }?.let { mapOf("searchTerm" to it) }

    override fun titleRetryQuery(query: ServerTitleQuery): Map<String, String>? =
        query.originalName?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(query.name?.trim(), ignoreCase = true) }
            ?.let { mapOf("searchTerm" to it) }
}
