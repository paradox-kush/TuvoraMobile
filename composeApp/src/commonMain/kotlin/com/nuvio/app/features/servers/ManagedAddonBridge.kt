package com.nuvio.app.features.servers

import com.nuvio.app.core.auth.hostedAccessTokenOrNull
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.features.addons.httpRequestRaw
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
    private val _notice = kotlinx.coroutines.flow.MutableStateFlow<ManagedBridgeNotice?>(null)
    /** Why the managed connection is not usable right now; null when fine or when there is none. */
    val notice: kotlinx.coroutines.flow.StateFlow<ManagedBridgeNotice?> = _notice
    private fun setNotice(value: ManagedBridgeNotice?) { _notice.value = value }
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
            when (val decision = ManagedBridgePolicy.decide(status.takeIf { raw != JsonNull })) {
                ManagedBridgeDecision.Forget -> { setNotice(null); ServerRepository.forgetManaged(); return }
                // The bridge is rebuilding or gave up. Keep the connection the user already has: deleting it
                // here is what turned every bridge hiccup into a silent "no sources".
                ManagedBridgeDecision.Preparing -> { setNotice(ManagedBridgeNotice.Preparing); return }
                is ManagedBridgeDecision.Failed -> { setNotice(ManagedBridgeNotice.Failed(decision.code)); return }
                ManagedBridgeDecision.Ready -> Unit
            }
            if (status == null) return
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
            if (response.url.isNotBlank() && !response.url.startsWith("$ORIGIN/")) return
            if (response.status != 200) {
                setNotice(ManagedBridgePolicy.exchangeFailure(response.status, response.body))
                return
            }
            if (ProfileRepository.activeProfileId != profileId || hostedAccessTokenOrNull() != bearer) return
            val body = json.parseToJsonElement(response.body) as? JsonObject ?: return
            fun field(name: String) = body[name]?.jsonPrimitive?.content.orEmpty()
            if (field("id") != id || !ManagedBridgePolicy.validAddress(id, field("address"))) return
            val expires = Instant.parse(field("expires_at")).toEpochMilliseconds()
            setNotice(null)
            ServerRepository.connectManaged(ServerSignIn(field("address"), "Connected addons", field("server_id"), field("user_id"), field("user_name"), field("access_token")), id, expires, status["revision"]!!.jsonPrimitive.content.toLong())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Old backends/temporarily unavailable pilot: ordinary servers still sync. No URL/token logs.
            co.touchlab.kermit.Logger.withTag("ManagedAddonBridge").w { "Managed connection refresh: ${e::class.simpleName}" }
            setNotice(ManagedBridgeNotice.Failed("unreachable"))
        }
    }
}

internal sealed interface ManagedBridgeDecision {
    data object Ready : ManagedBridgeDecision
    data object Preparing : ManagedBridgeDecision
    data object Forget : ManagedBridgeDecision
    data class Failed(val code: String?) : ManagedBridgeDecision
}

internal sealed interface ManagedBridgeNotice {
    data object Preparing : ManagedBridgeNotice
    data class Failed(val code: String?) : ManagedBridgeNotice
}

internal object ManagedBridgePolicy {
    /** Only a missing or disconnected connection is forgotten; a pending, working or failed one is kept and explained. */
    fun decide(status: JsonObject?): ManagedBridgeDecision = when (status?.get("state")?.jsonPrimitive?.content) {
        "ready" -> ManagedBridgeDecision.Ready
        "pending", "working" -> ManagedBridgeDecision.Preparing
        "failed" -> ManagedBridgeDecision.Failed((status["result"] as? JsonObject)?.get("error")?.jsonPrimitive?.content)
        else -> ManagedBridgeDecision.Forget
    }

    fun exchangeFailure(status: Int, body: String): ManagedBridgeNotice {
        val code = runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonPrimitive?.content }.getOrNull()
        return when {
            code == "connection_reprovisioning" || code == "connection_not_ready" || code == "connection_changed" || status == 410 -> ManagedBridgeNotice.Preparing
            else -> ManagedBridgeNotice.Failed(code ?: "http_$status")
        }
    }

    fun validId(value: String): Boolean = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)
    fun validAddress(id: String, address: String): Boolean = validId(id) && address == "https://addon.thekush.dev/jellyfin/$id"
}
