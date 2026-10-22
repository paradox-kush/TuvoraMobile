package com.nuvio.app.features.servers

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.iptv.hostedAccessTokenOrNull
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant

/** Managed setup is a separate authenticated exchange. Neither passwords nor tokens enter server sync. */
internal object ManagedAddonBridge {
    private const val ORIGIN = "https://addon.thekush.dev"
    private val json = Json { ignoreUnknownKeys = true }

    private val mutex = kotlinx.coroutines.sync.Mutex()
    suspend fun refresh(profileId: Int, force: Boolean = false) = mutex.withLock { refreshLocked(profileId, force) }

    private suspend fun refreshLocked(profileId: Int, force: Boolean) {
        // Native-capable builds keep their native route; never request a duplicate bridge group.
        if (AppFeaturePolicy.addonStreamSourcesEnabled) return
        val bearer = hostedAccessTokenOrNull() ?: return
        try {
            val raw = SupabaseProvider.client.postgrest.rpc("addon_bridge_status", buildJsonObject { put("p_profile_id", profileId) })
                .decodeAs<kotlinx.serialization.json.JsonElement>()
            if (ProfileRepository.activeProfileId != profileId || hostedAccessTokenOrNull() != bearer) return
            val status = raw as? JsonObject
            if (status == null || raw == JsonNull || status["state"]?.jsonPrimitive?.content != "ready") {
                ServerRepository.forgetManaged()
                return
            }
            val id = status["id"]?.jsonPrimitive?.content ?: return
            if (!ManagedBridgePolicy.validId(id)) return
            val details = status["result"] as? JsonObject ?: return
            val sourceRef = "jellyfin|${details["server_id"]?.jsonPrimitive?.content}|${details["user_id"]?.jsonPrimitive?.content}"
            if (!com.nuvio.app.core.sources.DeviceSources.allows("media_server", sourceRef)) return
            ServerRepository.forgetManaged(exceptId = id)
            val existing = ServerRepository.uiState.value.connections.firstOrNull { it.managedBridgeId == id }
            if (existing != null && !existing.enabled) return
            if (!force && status["needs_check"]?.jsonPrimitive?.content != "true" && existing != null && existing.managedRevision == status["revision"]?.jsonPrimitive?.content?.toLongOrNull() && ServerRepository.session(existing.id) != null &&
                existing.managedExpiresAtMs > Clock.System.now().toEpochMilliseconds() + 30 * 60_000) return
            val response = httpRequestRaw(
                "POST", "$ORIGIN/api/bridge?profile=$profileId", mapOf("Authorization" to "Bearer $bearer", "Content-Type" to "application/json"),
                buildJsonObject { put("action", "exchange"); put("device_id", SyncClientIdentity.currentClientId()); put("force", force) }.toString(),
                followRedirects = false, maxResponseBodyBytes = 32768,
            )
            if (response.status != 200 || response.url.isNotBlank() && !response.url.startsWith("$ORIGIN/")) return
            if (ProfileRepository.activeProfileId != profileId || hostedAccessTokenOrNull() != bearer) return
            val body = json.parseToJsonElement(response.body) as? JsonObject ?: return
            fun field(name: String) = body[name]?.jsonPrimitive?.content.orEmpty()
            if (field("id") != id || !ManagedBridgePolicy.validAddress(id, field("address"))) return
            val expires = Instant.parse(field("expires_at")).toEpochMilliseconds()
            ServerRepository.connectManaged(ServerSignIn(field("address"), "Connected addons", field("server_id"), field("user_id"), field("user_name"), field("access_token")), id, expires, status["revision"]!!.jsonPrimitive.content.toLong())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Old backends/temporarily unavailable pilot: ordinary servers still sync. No URL/token logs.
            co.touchlab.kermit.Logger.withTag("ManagedAddonBridge").w { "Managed connection refresh: ${e::class.simpleName}" }
        }
    }
}

internal object ManagedBridgePolicy {
    fun validId(value: String): Boolean = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)
    fun validAddress(id: String, address: String): Boolean = validId(id) && address == "https://addon.thekush.dev/jellyfin/$id"
}
