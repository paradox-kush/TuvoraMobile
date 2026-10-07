package com.nuvio.app.core.sync

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.userId
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * B03 (D1/D2) — the "pull as truth" half of Realtime: reads the server's surface version vector
 * (`sync_get_surface_versions`, one < 1 KB call), pulls only the surfaces that advanced past what this
 * device last pulled ([SurfaceCatchUpPolicy]) through the same per-surface dispatcher a Realtime event
 * uses ([SyncManager.pullSurface]), and records the new versions only for surfaces whose pull
 * succeeded. Runs on every Realtime (re)subscribe — the moment events may have been missed.
 *
 * A failed catch-up leaves its versions unseen so the subscription can retry it.
 */
internal object SurfaceCatchUp {

    private val log = Logger.withTag("SurfaceCatchUp")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val seenCodec = MapSerializer(String.serializer(), Long.serializer())

    @Serializable
    private data class Row(
        @SerialName("profile_id") val profileId: Int? = null,
        val surface: String,
        val version: Long,
    )

    private fun storageKey(userId: String) = "surface_versions_$userId"

    private fun loadSeen(userId: String): Map<String, Long> =
        SyncClientIdentityStorage.loadValue(storageKey(userId))
            ?.let { runCatching { json.decodeFromString(seenCodec, it) }.getOrNull() }
            .orEmpty()

    private fun saveSeen(userId: String, seen: Map<String, Long>) {
        SyncClientIdentityStorage.saveValue(storageKey(userId), json.encodeToString(seenCodec, seen))
    }

    /** The server's vector for [profileId], or null when it cannot be read (no RPC / no session). */
    suspend fun fetch(profileId: Int): List<SurfaceVersion>? {
        if (!SyncSession.canSync()) return null
        return runCatching {
            SupabaseProvider.client.postgrest.rpc(
                "sync_get_surface_versions",
                buildJsonObject { put("p_profile_id", profileId) },
            ).decodeList<Row>().map { SurfaceVersion(it.profileId, it.surface, it.version) }
        }.onFailure {
            if (it is CancellationException) throw it
            log.d { "surface versions unavailable: ${it.message}" }
        }.getOrNull()
    }

    /** Pull whatever advanced since this device last pulled it. Returns the surfaces pulled. */
    suspend fun run(profileId: Int, reason: String): List<String> = mutex.withLock {
        val userId = AuthRepository.state.value.userId ?: return@withLock emptyList()
        val server = fetch(profileId) ?: error("Surface versions unavailable; catch-up was not completed")
        val seen = loadSeen(userId)
        val plan = SurfaceCatchUpPolicy.plan(seen, server, SyncManager.pullableSurfaces())
        if (plan.isEmpty()) return@withLock emptyList()
        log.i { "catch-up ($reason) profile=$profileId surfaces=${plan.map { it.surface }}" }
        val pulled = plan.filter { SyncManager.pullSurface(profileId, it.surface) }
        saveSeen(userId, SurfaceCatchUpPolicy.advance(seen, pulled))
        check(pulled.size == plan.size) { "Some sync surfaces failed; their versions remain unseen" }
        pulled.map { it.surface }
    }

    /**
     * A full profile sync just pulled every surface: the [versionsBefore] it (read BEFORE the sync
     * started, so an edit landing mid-sync is not marked seen) are now current.
     */
    suspend fun markPulled(versionsBefore: List<SurfaceVersion>) = mutex.withLock {
        val userId = AuthRepository.state.value.userId ?: return@withLock
        saveSeen(userId, SurfaceCatchUpPolicy.advance(loadSeen(userId), versionsBefore))
    }
}
