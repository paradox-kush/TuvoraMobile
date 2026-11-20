package com.nuvio.app.features.servers

import com.nuvio.app.core.telemetry.api.FirstRunVocab
import com.nuvio.app.core.telemetry.api.firstSourceAdded
import com.nuvio.app.core.telemetry.api.TelemetryAccess
import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.random.Random

data class ServersUiState(
    val connections: List<ServerConnection> = emptyList(),
    val failures: Map<String, ServerFailure> = emptyMap(),
    val revision: Int = 0,
) {
    val enabledConnections: List<ServerConnection>
        get() = connections.filter { it.availableOnDevice }
}

object ServerRepository {
    private val log = Logger.withTag("ServerRepository")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(ServersUiState())
    val uiState: StateFlow<ServersUiState> = _uiState.asStateFlow()

    private val lock = SynchronizedObject()

    @Volatile
    private var loadedProfileId: Int? = null

    @Volatile
    private var generation = 0L
    private val tokens = mutableMapOf<String, String>()
    private var syncState = ServerSyncState()
    private var localVersion = 0L
    private val renewals = mutableMapOf<String, Mutex>()
    private val _localChanges = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val localChanges: SharedFlow<Int> = _localChanges.asSharedFlow()

    fun onDeviceSourcesChanged() = synchronized(lock) {
        generation++
        _uiState.update { it.copy(revision = it.revision + 1) }
    }

    fun ensureLoaded() {
        val profileId = ProfileRepository.activeProfileId
        if (loadedProfileId != profileId) load(profileId)
    }

    fun onProfileChanged() {
        load(ProfileRepository.activeProfileId)
    }

    fun clearLocalState() {
        generation++
        synchronized(lock) { tokens.clear() }
        loadedProfileId = null
        syncState = ServerSyncState()
        runCatching { ServerStorage.clear() }.onFailure { log.w(it) { "Unable to clear server storage" } }
        runCatching { PlatformLegacyMediaServerSource.eraseAll() }
        _uiState.value = ServersUiState(revision = _uiState.value.revision + 1)
        ServerMatcher.shared.clear()
        runCatching { ServerTrust.shared.clearAll() }
    }

    fun removeProfile(profileId: Int) {
        readConnections(profileId).forEach { deleteCredential(it.credentialRef) }
        runCatching { ServerStorage.write(connectionsKey(profileId), null) }
        runCatching { ServerStorage.write(syncKey(profileId), null) }
        if (loadedProfileId == profileId) load(profileId)
    }

    fun connection(connectionId: String): ServerConnection? {
        ensureLoaded()
        return _uiState.value.connections.firstOrNull { it.id == connectionId }
    }

    fun enabledConnections(): List<ServerConnection> {
        ensureLoaded()
        return _uiState.value.enabledConnections
    }

    /** True when this device holds no usable token for [connectionId] (it arrived from another device, or was signed out here). */
    fun needsSignIn(connectionId: String): Boolean {
        val connection = connection(connectionId) ?: return false
        return ServerTokenPolicy.needsSignIn(token(connection.credentialRef))
    }

    fun provider(connection: ServerConnection): ServerProvider? = ServerProviders.forId(connection.providerId)

    fun sourceLabel(connection: ServerConnection): String =
        "${provider(connection)?.displayName ?: connection.providerId} · ${connection.name}"

    fun session(connectionId: String): ServerSession? {
        val connection = connection(connectionId)?.takeIf { it.availableOnDevice } ?: return null
        val token = synchronized(lock) {
            tokens.getOrPut(connection.credentialRef) {
                readCredential(connection.credentialRef) ?: return null
            }
        }
        return ServerSession(connection, token)
    }

    suspend fun <T> call(
        connectionId: String,
        block: suspend (ServerProvider, ServerSession) -> T,
    ): T {
        connection(connectionId)?.takeIf { it.managedBridgeId != null && it.availableOnDevice &&
            (it.managedExpiresAtMs < kotlin.time.Clock.System.now().toEpochMilliseconds() + 15 * 60_000 ||
                ServerTokenPolicy.needsSignIn(token(it.credentialRef)) || _uiState.value.failures[it.id] == ServerFailure.AUTH_REQUIRED) }?.let {
            ManagedAddonBridge.refresh(ProfileRepository.activeProfileId, force = _uiState.value.failures[it.id] == ServerFailure.AUTH_REQUIRED)
        }
        val preferenceEpoch = com.nuvio.app.core.sources.DeviceSources.epoch
        val startedGeneration = generation
        val connection = connection(connectionId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        if (!connection.availableOnDevice) throw ServerException(ServerFailure.UNREACHABLE)
        val session = session(connectionId) ?: throw ServerException(ServerFailure.AUTH_REQUIRED)
        val provider = provider(session.connection) ?: throw ServerException(ServerFailure.UNSUPPORTED)
        return try {
            val current = if (provider.renewalDue(session)) renewedAhead(provider, session) else session
            try {
                block(provider, current)
            } catch (error: ServerException) {
                if (error.failure != ServerFailure.AUTH_REQUIRED) throw error
                val renewed = renewed(provider, current, error) ?: throw error
                block(provider, renewed)
            }.also {
                if (startedGeneration != generation || preferenceEpoch != com.nuvio.app.core.sources.DeviceSources.epoch) throw CancellationException("Server scope changed")
                setFailure(connectionId, null)
            }
        } catch (error: ServerException) {
            if (startedGeneration == generation &&
                (error.failure == ServerFailure.AUTH_REQUIRED || error.failure == ServerFailure.UNREACHABLE ||
                    error.failure == ServerFailure.CERTIFICATE_CHANGED || error.failure == ServerFailure.CERTIFICATE_UNTRUSTED)
            ) {
                setFailure(connectionId, error.failure)
            }
            throw error
        }
    }

    /**
     * For a play already running: renews like [call], but carries on when the server list changes, uses [started]
     * when its connection is gone, and leaves the connection's failure state alone.
     */
    suspend fun <T> callForPlayback(
        started: ServerSession,
        block: suspend (ServerProvider, ServerSession) -> T,
    ): T {
        val session = session(started.connection.id) ?: started
        val provider = provider(session.connection) ?: throw ServerException(ServerFailure.UNSUPPORTED)
        val current = if (provider.renewalDue(session)) renewedAhead(provider, session) else session
        return try {
            block(provider, current)
        } catch (error: ServerException) {
            if (error.failure != ServerFailure.AUTH_REQUIRED) throw error
            val renewed = renewed(provider, current, error) ?: throw error
            block(provider, renewed)
        }
    }

    /** Renews ahead of time; the current token is kept when the server can't be asked right now. */
    private suspend fun renewedAhead(provider: ServerProvider, session: ServerSession): ServerSession = try {
        renewed(provider, session, failure = null) ?: session
    } catch (error: ServerException) {
        if (error.failure == ServerFailure.AUTH_REQUIRED) throw error
        session
    }

    /** One renewal at a time per connection: a call that waited gets the token the first one saved. */
    private suspend fun renewed(provider: ServerProvider, session: ServerSession, failure: ServerException?): ServerSession? {
        val connectionId = session.connection.id
        val mutex = synchronized(lock) { renewals.getOrPut(connectionId) { Mutex() } }
        return mutex.withLock {
            val latest = session(connectionId) ?: return@withLock null
            if (latest.token != session.token) return@withLock latest
            val profileId = loadedProfileId ?: return@withLock null
            val token = provider.renewToken(latest, failure) ?: return@withLock null
            if (!replaceToken(profileId, latest.connection, latest.token, token)) return@withLock null
            ServerSession(latest.connection, token)
        }
    }

    /** Saves [token] only while [profileId] is loaded and the connection still holds [previous]. */
    private fun replaceToken(profileId: Int, connection: ServerConnection, previous: String, token: String): Boolean {
        val saved = synchronized(lock) {
            val current = tokens[connection.credentialRef] ?: readCredential(connection.credentialRef)
            if (loadedProfileId != profileId || current != previous) return false
            runCatching { ServerStorage.write(credentialKey(connection.credentialRef), token) }.isSuccess
                .also { if (it) tokens[connection.credentialRef] = token }
        }
        if (!saved) return false
        // Fork: tokens never leave this device (ServerConnection.toSynced sends none), so a renewal has nothing to push.
        return true
    }

    suspend fun connect(provider: ServerProvider, address: String, username: String, password: String): ServerConnection =
        connectWith(provider) { provider.signIn(address, username, password) }

    /** Quick Connect sign-in: the token it yields is this device's own and stays on this device (never synced). */
    suspend fun connectWithQuickConnect(provider: ServerProvider, challenge: QuickConnectChallenge): ServerConnection =
        connectWith(provider) { provider.quickConnectComplete(challenge) }

    /** Upstream's Plex sign-in shape: the provider runs its own flow and hands back the sign-in. */
    suspend fun connect(provider: ServerProvider, signIn: suspend () -> ServerSignIn): ServerConnection =
        connectWith(provider) { signIn() }

    internal suspend fun connectManaged(signIn: ServerSignIn, bridgeId: String, expiresAtMs: Long, revision: Long): ServerConnection =
        connectWith(checkNotNull(ServerProviders.forId("jellyfin")), bridgeId, expiresAtMs, revision) { signIn }

    internal fun forgetManaged(exceptId: String? = null) {
        ensureLoaded()
        val removed = _uiState.value.connections.filter { it.managedBridgeId != null && it.managedBridgeId != exceptId }
        if (removed.isEmpty()) return
        removed.forEach { deleteCredential(it.credentialRef) }
        saveConnections(_uiState.value.connections - removed.toSet(), invalidate = true, local = false)
    }

    private suspend fun connectWith(provider: ServerProvider, managedBridgeId: String? = null, managedExpiresAtMs: Long = 0, managedRevision: Long = 0, signInBlock: suspend () -> ServerSignIn): ServerConnection {
        ensureLoaded()
        val startedGeneration = generation
        val profileId = loadedProfileId ?: ProfileRepository.activeProfileId
        val signIn = signInBlock()
        val existing = _uiState.value.connections.firstOrNull {
            it.providerId == provider.id &&
                it.remoteServerId == signIn.serverId &&
                it.remoteUserId == signIn.userId
        }
        val draft = ServerConnection(
            id = existing?.id ?: newId("c"),
            providerId = provider.id,
            name = signIn.serverName,
            address = signIn.address,
            remoteServerId = signIn.serverId,
            remoteUserId = signIn.userId,
            userName = signIn.userName,
            credentialRef = newId("k"),
            libraries = existing?.libraries.orEmpty(),
            enabled = if (managedBridgeId != null) existing?.enabled ?: true else true,
            managedBridgeId = managedBridgeId,
            managedExpiresAtMs = managedExpiresAtMs,
            managedRevision = managedRevision,
            useCatalogMetadata = existing?.useCatalogMetadata ?: false,
            importWatchState = existing?.importWatchState ?: false,
        )
        val libraries = provider.libraries(ServerSession(draft, signIn.token))
        if (startedGeneration != generation || profileId != loadedProfileId) {
            throw CancellationException("Server scope changed")
        }
        val connection = draft.copy(libraries = mergeLibraries(existing?.libraries.orEmpty(), libraries))
        store(connection, signIn.token, local = managedBridgeId == null)
        TelemetryAccess.current().firstSourceAdded(FirstRunVocab.KIND_MEDIA_SERVER, if (managedBridgeId == null) FirstRunVocab.VIA_MANUAL else FirstRunVocab.VIA_UNKNOWN)
        existing?.let { deleteCredential(it.credentialRef) }
        return connection
    }

    internal fun store(connection: ServerConnection, token: String, local: Boolean = true) {
        ensureLoaded()
        writeCredential(connection.credentialRef, token)
        saveConnections(
            _uiState.value.connections.filterNot { it.id == connection.id } + connection,
            invalidate = true,
            local = local,
        )
        setFailure(connection.id, null)
    }

    suspend fun refreshLibraries(connectionId: String) {
        val libraries = call(connectionId) { provider, session -> provider.libraries(session) }
        updateConnection(connectionId) { it.copy(libraries = mergeLibraries(it.libraries, libraries)) }
    }

    fun setLibrarySelected(connectionId: String, libraryId: String, selected: Boolean) {
        updateConnection(connectionId) { connection ->
            connection.copy(
                libraries = connection.libraries.map {
                    if (it.id == libraryId) it.copy(selected = selected) else it
                },
            )
        }
    }

    fun setCatalogMetadata(connectionId: String, enabled: Boolean) {
        updateConnection(connectionId) { it.copy(useCatalogMetadata = enabled) }
    }

    fun setImportWatchState(connectionId: String, enabled: Boolean) {
        updateConnection(connectionId) { it.copy(importWatchState = enabled) }
    }

    fun setEnabled(connectionId: String, enabled: Boolean) {
        updateConnection(connectionId) { it.copy(enabled = enabled) }
    }

    fun remove(connectionId: String) {
        val connection = connection(connectionId) ?: return
        val session = session(connectionId)
        saveConnections(_uiState.value.connections.filterNot { it.id == connectionId }, invalidate = true)
        deleteCredential(connection.credentialRef)
        setFailure(connectionId, null)
        if (session != null) {
            scope.launch {
                withContext(NonCancellable) {
                    withTimeoutOrNull(5_000L) {
                        runCatching { provider(connection)?.signOut(session) }
                    }
                }
            }
        }
    }

    fun syncSnapshot(profileId: Int): ServerSyncSnapshot? {
        ensureLoaded()
        if (loadedProfileId != profileId) return null
        return ServerSyncSnapshot(
            profileId = profileId,
            version = localVersion,
            servers = _uiState.value.connections.filter { it.managedBridgeId == null }.map { it.toSynced() },
            pendingPush = syncState.pendingPush,
            syncedKeys = syncState.syncedKeys?.toSet(),
        )
    }

    fun applySync(snapshot: ServerSyncSnapshot, servers: List<SyncedServer>, keys: Set<String>): Boolean {
        if (loadedProfileId != snapshot.profileId || localVersion != snapshot.version) return false
        val current = _uiState.value.connections
        val currentByKey = current.associateBy { serverKey(it.providerId, it.remoteServerId, it.remoteUserId) }
        val usedIds = mutableSetOf<String>()
        val connections = servers.map { server ->
            val local = currentByKey[server.key]
            val credentialRef = local?.credentialRef ?: newId("k")
            val id = local?.id ?: server.id.takeUnless { id -> current.any { it.id == id } || id in usedIds } ?: newId("c")
            usedIds += id
            // Tokens never sync: keep whatever this device holds, whatever the remote row says.
            server.toConnection(id, credentialRef)
        }
        val managed = current.filter { it.managedBridgeId != null }
        current.filter { it.managedBridgeId == null && it.id !in usedIds }.forEach { removed ->
            deleteCredential(removed.credentialRef)
            setFailure(removed.id, null)
        }
        syncState = ServerSyncState(pendingPush = false, syncedKeys = keys.toList())
        if (connections + managed != current) saveConnections(connections + managed, invalidate = true, local = false)
        if (current.isEmpty() && connections.isNotEmpty()) TelemetryAccess.current().firstSourceAdded(FirstRunVocab.KIND_MEDIA_SERVER, FirstRunVocab.VIA_SYNC)
        persistSyncState()
        markUnauthenticated(connections)
        return true
    }

    fun markPushed(snapshot: ServerSyncSnapshot) {
        if (loadedProfileId != snapshot.profileId) return
        syncState = ServerSyncState(
            pendingPush = syncState.pendingPush && localVersion != snapshot.version,
            syncedKeys = snapshot.servers.map { it.key },
        )
        persistSyncState()
    }

    /** The released fork stack's saved servers move into this store once, before anything reads it (see LegacyMediaServerImport). */
    private fun importLegacyOnce() {
        if (legacyImportDone || !PlatformLegacyMediaServerSource.isAvailable()) return
        legacyImportDone = true
        runCatching { LegacyMediaServerImport(PlatformLegacyMediaServerSource, PlatformServerKeyValueStore).runOnce() }
            .onFailure { log.w(it) { "legacy media-server import failed" } }
    }

    @Volatile
    private var legacyImportDone = false

    private fun load(profileId: Int) {
        importLegacyOnce()
        generation++
        synchronized(lock) { tokens.clear() }
        loadedProfileId = profileId
        syncState = readSyncState(profileId)
        ServerMatcher.shared.clear()
        val connections = readConnections(profileId)
        _uiState.value = ServersUiState(
            connections = connections,
            failures = connections
                .filter { ServerTokenPolicy.needsSignIn(token(it.credentialRef)) }
                .associate { it.id to ServerFailure.AUTH_REQUIRED },
            revision = _uiState.value.revision + 1,
        )
        // A server that holds no library list yet (imported from the released fork stack, or arrived from another device) lists its
        // libraries once in the background, so it is not an empty "No libraries selected" until the user finds Refresh.
        val discoveryGeneration = generation
        connections
            .filter { ServerLibraryBootstrapPolicy.needsRefresh(it, hasToken = !ServerTokenPolicy.needsSignIn(token(it.credentialRef))) }
            .forEach { connection -> scope.launch {
                try {
                    refreshLibraries(connection.id)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (generation == discoveryGeneration) setFailure(connection.id, error.serverFailure())
                    log.w { "Server library discovery failed: ${error.serverFailure()}" }
                }
            } }
    }

    /** A server that arrived from another device has no token here: show it as "Sign in again" up front. */
    private fun markUnauthenticated(connections: List<ServerConnection>) {
        connections
            .filter { ServerTokenPolicy.needsSignIn(token(it.credentialRef)) }
            .forEach { setFailure(it.id, ServerFailure.AUTH_REQUIRED) }
    }

    private fun updateConnection(connectionId: String, transform: (ServerConnection) -> ServerConnection) {
        ensureLoaded()
        val connections = _uiState.value.connections.map { if (it.id == connectionId) transform(it) else it }
        saveConnections(connections, invalidate = true)
    }

    private fun saveConnections(connections: List<ServerConnection>, invalidate: Boolean, local: Boolean = true) {
        val profileId = loadedProfileId ?: return
        if (local) {
            syncState = syncState.copy(pendingPush = true)
            localVersion++
            persistSyncState()
        }
        runCatching {
            ServerStorage.write(
                connectionsKey(profileId),
                json.encodeToString(ListSerializer(ServerConnection.serializer()), connections),
            )
        }.onFailure { log.w(it) { "Unable to save server connections" } }
        if (invalidate) {
            generation++
            }
        _uiState.update { it.copy(connections = connections, revision = it.revision + 1) }
        if (local) _localChanges.tryEmit(profileId)
    }

    private fun persistSyncState() {
        val profileId = loadedProfileId ?: return
        runCatching {
            ServerStorage.write(syncKey(profileId), json.encodeToString(ServerSyncState.serializer(), syncState))
        }.onFailure { log.w(it) { "Unable to save server sync state" } }
    }

    private fun readSyncState(profileId: Int): ServerSyncState =
        runCatching {
            ServerStorage.read(syncKey(profileId))?.let { json.decodeFromString(ServerSyncState.serializer(), it) }
        }.getOrNull() ?: ServerSyncState()

    private fun token(ref: String): String? = synchronized(lock) {
        tokens[ref] ?: readCredential(ref)?.also { tokens[ref] = it }
    }

    private fun setFailure(connectionId: String, failure: ServerFailure?) {
        _uiState.update { state ->
            if (state.failures[connectionId] == failure) return@update state
            val failures = if (failure == null) state.failures - connectionId else state.failures + (connectionId to failure)
            state.copy(failures = failures)
        }
    }

    private fun readConnections(profileId: Int): List<ServerConnection> =
        runCatching {
            ServerStorage.read(connectionsKey(profileId))
                ?.let { json.decodeFromString(ListSerializer(ServerConnection.serializer()), it) }
        }.getOrNull().orEmpty()

    private fun readCredential(ref: String): String? =
        runCatching { ServerStorage.read(credentialKey(ref)) }.getOrNull()

    private fun writeCredential(ref: String, token: String) {
        ServerStorage.write(credentialKey(ref), token)
        synchronized(lock) { tokens[ref] = token }
    }

    private fun deleteCredential(ref: String) {
        synchronized(lock) { tokens.remove(ref) }
        runCatching { ServerStorage.write(credentialKey(ref), null) }
    }

    private fun mergeLibraries(previous: List<ServerLibrary>, current: List<ServerLibrary>): List<ServerLibrary> {
        val selection = previous.associate { it.id to it.selected }
        return current.map { library -> library.copy(selected = selection[library.id] ?: true) }
    }

    private fun connectionsKey(profileId: Int) = "profile.$profileId.connections"

    private fun syncKey(profileId: Int) = "profile.$profileId.sync"

    private fun credentialKey(ref: String) = "credential.$ref"

    private fun newId(prefix: String): String =
        prefix + (0 until 15).joinToString("") { Random.nextInt(16).toString(16) }
}
