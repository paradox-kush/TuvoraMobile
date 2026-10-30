package com.nuvio.app.features.servers

import com.nuvio.app.features.servers.emby.EmbyProvider
import com.nuvio.app.features.servers.jellyfin.JellyfinProvider
import com.nuvio.app.features.tracking.TrackingExternalIds

enum class ServerCapability {
    SEARCH,
    EXTERNAL_ID_LOOKUP,
    USER_STATE_READ,
    USER_STATE_WRITE,
    TRANSCODING,
    QUICK_CONNECT,
}

interface ServerProvider {
    val id: String
    val displayName: String
    val capabilities: Set<ServerCapability>
    val minimumVersion: String

    suspend fun signIn(address: String, username: String, password: String): ServerSignIn = unsupported()

    suspend fun libraries(session: ServerSession): List<ServerLibrary>

    suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int,
    ): ServerPage<ServerTitle>

    suspend fun details(session: ServerSession, itemId: String): ServerItemDetails

    suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate>

    suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession

    suspend fun report(session: ServerSession, playback: ServerPlaybackSession, event: ServerPlaybackEvent) = Unit

    suspend fun signOut(session: ServerSession) = Unit

    /**
     * Reaches the server at [address] without signing in: the address as it will be stored, the server's own name and version.
     * Throws [ServerException] (NOT_FOUND = not an address, UNSUPPORTED = not this product / too old, UNREACHABLE) or a
     * [ServerCertificateException] when the certificate must be trusted first. Lets a screen with a slow keyboard (Apple TV)
     * confirm the server and its certificate before asking for credentials.
     */
    suspend fun discover(address: String): ServerDiscovery = unsupported()

    /** Whether the server at [address] currently accepts Quick Connect (false when off, unreachable or unsupported). */
    suspend fun quickConnectAvailable(address: String): Boolean = false

    /** Asks the server for a Quick Connect code to show the user. Only for providers with [ServerCapability.QUICK_CONNECT]. */
    suspend fun quickConnectStart(address: String): QuickConnectChallenge = unsupported()

    suspend fun quickConnectPoll(challenge: QuickConnectChallenge): QuickConnectPoll = unsupported()

    /** Exchanges an approved request for this device's own token. */
    suspend fun quickConnectComplete(challenge: QuickConnectChallenge): ServerSignIn = unsupported()

    suspend fun collectionPage(
        session: ServerSession,
        collectionId: String,
        start: Int,
        limit: Int,
    ): ServerPage<ServerTitle> = unsupported()

    suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int,
    ): List<ServerTitle> = unsupported()

    suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerTitle> = unsupported()

    suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean): Unit = unsupported()

    suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int,
    ): ServerPage<ServerIndexEntry> = unsupported()

    suspend fun lookupTitle(
        session: ServerSession,
        library: ServerLibrary,
        query: ServerTitleQuery,
    ): List<ServerIndexEntry>? = null

    suspend fun findEpisode(
        session: ServerSession,
        seriesItemId: String,
        season: Int,
        episode: Int,
    ): ServerEpisode? = unsupported()
}

data class ServerDiscovery(
    val address: String,
    val serverName: String,
    val version: String?,
)

data class ServerIndexEntry(
    val itemId: String,
    val ids: TrackingExternalIds,
    val year: Int? = null,
)

data class ServerTitleQuery(
    val ids: TrackingExternalIds,
    val name: String?,
    val year: Int?,
    val originalName: String? = null,
)

data class ServerEpisode(
    val itemId: String,
    val premiereDate: String?,
)

fun ServerProvider.supports(capability: ServerCapability): Boolean = capability in capabilities

private fun unsupported(): Nothing = throw ServerException(ServerFailure.UNSUPPORTED)

internal object ServerProviders {
    internal val registered: MutableList<ServerProvider> = mutableListOf(JellyfinProvider(), EmbyProvider())

    fun forId(id: String?): ServerProvider? = registered.firstOrNull { it.id == id }
}

internal fun verifiedTitleMatches(entries: List<ServerIndexEntry>, query: ServerTitleQuery): List<String> {
    val byItem = entries.associateBy { it.itemId }
    return LibraryIndex(entries).lookup(query.ids).filter { itemId ->
        val entry = byItem.getValue(itemId)
        (entry.ids.imdb != null && query.ids.imdb != null) ||
            query.year == null || entry.year == null || kotlin.math.abs(query.year - entry.year) <= 1
    }
}
