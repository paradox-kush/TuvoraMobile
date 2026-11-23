package com.nuvio.app.features.servers

import com.nuvio.app.core.auth.hostedAccessTokenOrNull
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.core.telemetry.api.TelemetryAccess
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamsEmptyStateReason
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
    /** An early return: say why when it leaves the user without a connection (B246), stay quiet when it is benign. */
    private fun skip(reason: ManagedBridgeSkip) { ManagedBridgePolicy.noticeFor(reason)?.let(::setNotice) }
    suspend fun refresh(profileId: Int, force: Boolean = false) = mutex.withLock { refreshLocked(profileId, force) }

    private suspend fun refreshLocked(profileId: Int, force: Boolean) {
        // Native-capable builds keep their native route; never request a duplicate bridge group.
        if (AppFeaturePolicy.addonStreamSourcesEnabled) return skip(ManagedBridgeSkip.NativeRoute)
        val bearer = hostedAccessTokenOrNull() ?: return skip(ManagedBridgeSkip.SignedOut)
        try {
            val raw = SupabaseProvider.client.postgrest.rpc("addon_bridge_status", buildJsonObject { put("p_profile_id", profileId) })
                .decodeAs<kotlinx.serialization.json.JsonElement>()
            if (ProfileRepository.activeProfileId != profileId || hostedAccessTokenOrNull() != bearer) return skip(ManagedBridgeSkip.Superseded)
            val status = raw as? JsonObject
            when (val decision = ManagedBridgePolicy.decide(status.takeIf { raw != JsonNull })) {
                ManagedBridgeDecision.Forget -> { setNotice(null); ServerRepository.forgetManaged(); return }
                // The bridge is rebuilding or gave up. Keep the connection the user already has: deleting it
                // here is what turned every bridge hiccup into a silent "no sources".
                ManagedBridgeDecision.Preparing -> { setNotice(ManagedBridgeNotice.Preparing); return }
                is ManagedBridgeDecision.Failed -> { setNotice(ManagedBridgeNotice.Failed(decision.code)); return }
                ManagedBridgeDecision.Ready -> setNotice(null) // a stale Failed/Preparing must not outlive a ready bridge
            }
            if (status == null) return skip(ManagedBridgeSkip.InvalidStatus)
            val id = status["id"]?.jsonPrimitive?.content ?: return skip(ManagedBridgeSkip.InvalidStatus)
            if (!ManagedBridgePolicy.validId(id)) return skip(ManagedBridgeSkip.InvalidStatus)
            val details = status["result"] as? JsonObject ?: return skip(ManagedBridgeSkip.InvalidStatus)
            val sourceRef = "jellyfin|${details["server_id"]?.jsonPrimitive?.content}|${details["user_id"]?.jsonPrimitive?.content}"
            if (!com.nuvio.app.core.sources.DeviceSources.allows("media_server", sourceRef)) return skip(ManagedBridgeSkip.DeviceOptedOut)
            ServerRepository.forgetManaged(exceptId = id)
            val existing = ServerRepository.uiState.value.connections.firstOrNull { it.managedBridgeId == id }
            if (existing != null && !existing.enabled) return skip(ManagedBridgeSkip.ConnectionDisabled)
            if (!force && status["needs_check"]?.jsonPrimitive?.content != "true" && existing != null && existing.managedRevision == status["revision"]?.jsonPrimitive?.content?.toLongOrNull() && ServerRepository.session(existing.id) != null &&
                existing.managedExpiresAtMs > Clock.System.now().toEpochMilliseconds() + 30 * 60_000) return skip(ManagedBridgeSkip.UpToDate)
            val response = httpRequestRaw(
                "POST", "$ORIGIN/api/bridge?profile=$profileId", mapOf("Authorization" to "Bearer $bearer", "Content-Type" to "application/json"),
                buildJsonObject { put("action", "exchange"); put("device_id", SyncClientIdentity.currentClientId()); put("force", force) }.toString(),
                followRedirects = false, maxResponseBodyBytes = 32768,
            )
            if (response.url.isNotBlank() && !response.url.startsWith("$ORIGIN/")) return skip(ManagedBridgeSkip.UnexpectedRedirect)
            if (response.status != 200) {
                setNotice(ManagedBridgePolicy.exchangeFailure(response.status, response.body))
                return
            }
            if (ProfileRepository.activeProfileId != profileId || hostedAccessTokenOrNull() != bearer) return skip(ManagedBridgeSkip.Superseded)
            val body = json.parseToJsonElement(response.body) as? JsonObject ?: return skip(ManagedBridgeSkip.InvalidResponse)
            fun field(name: String) = body[name]?.jsonPrimitive?.content.orEmpty()
            if (field("id") != id || !ManagedBridgePolicy.validAddress(id, field("address"))) return skip(ManagedBridgeSkip.InvalidResponse)
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
    /**
     * Never produced by the bridge: derived by [ManagedBridgeEmptyStatePolicy] for a store user who has synced add-ons
     * but never set up Tuvora Connect (no connection, no bridge notice). Nothing to retry; the user sets it up.
     */
    data object NotConnected : ManagedBridgeNotice
    data class Failed(val code: String?) : ManagedBridgeNotice
}

/** Every reason [ManagedAddonBridge.refresh] can return early without finishing a connection (B246). */
internal enum class ManagedBridgeSkip {
    /** Native-capable build: it keeps its own add-on route. */
    NativeRoute,
    /** No hosted account: nothing to connect. */
    SignedOut,
    /** Another profile or account took over mid-flight; the newer refresh speaks for the user. */
    Superseded,
    /** The user switched this connection off on this device. */
    DeviceOptedOut,
    /** The user disabled the connection. */
    ConnectionDisabled,
    /** The connection is already good for this revision. */
    UpToDate,
    /** The bridge status was incomplete or malformed. */
    InvalidStatus,
    /** The exchange answered from somewhere else than the bridge. */
    UnexpectedRedirect,
    /** The exchange answered with a body or address that cannot be trusted. */
    InvalidResponse,
}

internal object ManagedBridgePolicy {
    /**
     * The notice a skip owes the user. Choices of the user, a superseded call and "already fine" stay silent
     * (a notice there would be a lie); a malformed status or response is a failure with a closed, text-free code.
     */
    fun noticeFor(skip: ManagedBridgeSkip): ManagedBridgeNotice? = when (skip) {
        ManagedBridgeSkip.InvalidStatus -> ManagedBridgeNotice.Failed("invalid_status")
        ManagedBridgeSkip.UnexpectedRedirect, ManagedBridgeSkip.InvalidResponse -> ManagedBridgeNotice.Failed("invalid_response")
        ManagedBridgeSkip.NativeRoute, ManagedBridgeSkip.SignedOut, ManagedBridgeSkip.Superseded,
        ManagedBridgeSkip.DeviceOptedOut, ManagedBridgeSkip.ConnectionDisabled, ManagedBridgeSkip.UpToDate -> null
    }

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

/** What the empty Streams screen says about the managed (Tuvora Connect) bridge on store builds. */
internal object ManagedBridgeEmptyStatePolicy {
    /** The bridge problem to explain, or null when the generic empty state is the honest one. */
    fun explanation(
        streamSourcesEnabled: Boolean,
        notice: ManagedBridgeNotice?,
        reason: StreamsEmptyStateReason?,
        hasSyncedAddons: Boolean,
        hasManagedConnection: Boolean,
    ): ManagedBridgeNotice? {
        if (streamSourcesEnabled || reason !in explainedReasons) return null
        // No bridge notice: a user with synced add-ons and no connection simply has not set up Connect (the play gate
        // lets them in, so the list must say so). A ready connection with no notice keeps the generic text.
        return notice ?: ManagedBridgeNotice.NotConnected.takeIf { hasSyncedAddons && !hasManagedConnection }
    }

    /** Retry re-asks the bridge: only meaningful when the bridge had something to say. */
    fun canRetry(explanation: ManagedBridgeNotice?): Boolean =
        explanation != null && explanation != ManagedBridgeNotice.NotConnected

    /**
     * Why the list is empty, in the reasons a Connect user can meet: no add-ons, none that serve this title
     * (B246: Connect users get NoCompatibleAddons), or nothing found. A loading, failed-fetch or provider
     * reason has its own honest text and keeps it. `null` is the pre-classification empty state.
     */
    private val explainedReasons = setOf(
        StreamsEmptyStateReason.NoAddonsInstalled,
        StreamsEmptyStateReason.NoCompatibleAddons,
        StreamsEmptyStateReason.NoStreamsFound,
        null,
    )

    /** Opening an empty list is a user event: re-ask once if the bridge was last seen not ready. Never a timer. */
    fun shouldRefreshOnEmpty(streamSourcesEnabled: Boolean, notice: ManagedBridgeNotice?): Boolean =
        notice != null && notice != ManagedBridgeNotice.NotConnected && !streamSourcesEnabled
}

/**
 * B246: with no ready managed connection `ServerStreams.canServe` is false, so Play was a dead end and the sources
 * list (the only place that explains Tuvora Connect and can retry it) was unreachable. A store-posture user who
 * has synced add-ons, or whose bridge has something to say, may open the list; a store user with neither keeps
 * today's gate.
 */
internal object ManagedBridgePlayGatePolicy {
    fun allowsOpeningSources(streamSourcesEnabled: Boolean, hasSyncedAddons: Boolean, notice: ManagedBridgeNotice?): Boolean =
        !streamSourcesEnabled && (hasSyncedAddons || notice != null)
}

/** The user-visible side effects of the empty Streams screen showing a bridge explanation; shared by Compose and Apple TV. */
internal object ManagedBridgeEmptyStateEffects {
    /**
     * Once per opening of an empty list (a user event, never a timer): re-ask the bridge if it was not ready, then
     * report which state the user met (codes only; the telemetry policy dedupes per session).
     */
    suspend fun onShown() {
        val enabled = AppFeaturePolicy.addonStreamSourcesEnabled
        if (ManagedBridgeEmptyStatePolicy.shouldRefreshOnEmpty(enabled, ManagedAddonBridge.notice.value)) {
            ManagedAddonBridge.refresh(ProfileRepository.activeProfileId)
        }
        ManagedBridgeTelemetryPolicy.session.nextReport(enabled, ManagedAddonBridge.notice.value)
            ?.let { runCatching { TelemetryAccess.current().emit(ManagedBridgeTelemetryPolicy.EVENT, it) } }
    }

    /** The user pressed Retry: re-ask the bridge, forcing a fresh exchange. */
    suspend fun retry() {
        ManagedAddonBridge.refresh(ProfileRepository.activeProfileId, force = true)
    }
}

/**
 * `addon_bridge_state` telemetry: which managed-bridge state a store-build user meets on the empty Streams screen.
 * Codes only (no hosts, URLs, add-on names or message text); the server's reason is collapsed to a closed vocabulary.
 * Volume: the caller asks once per Streams opening, and each (state, reason) pair passes once per app session.
 */
internal class ManagedBridgeTelemetryPolicy {
    private val reported = HashSet<String>()

    /** The event properties to emit now, or null (full build, or this (state, reason) already went out this session). */
    fun nextReport(streamSourcesEnabled: Boolean, notice: ManagedBridgeNotice?): Map<String, Any>? {
        if (streamSourcesEnabled) return null
        val state = stateOf(notice)
        val reason = reasonOf(notice)
        if (!reported.add("$state|$reason")) return null
        return mapOf("state" to state, "reason" to reason, "surface" to SURFACE)
    }

    companion object {
        const val EVENT = "addon_bridge_state"
        const val SURFACE = "streams_empty"
        private val knownReasons = setOf(
            "provisioning_timeout", "no_supported_addons", "no_playback_addons", "addon_selection_changed",
            "connection_reprovisioning", "connection_not_ready", "connection_changed",
            "invalid_status", "invalid_response",
        )
        private val httpReason = Regex("http_[0-9]{3}")

        /** The notice is null when the bridge is ready or there is none (the two are not distinguished client-side). */
        fun stateOf(notice: ManagedBridgeNotice?): String = when (notice) {
            null -> "none"
            ManagedBridgeNotice.Preparing -> "preparing"
            // Derived, never reported (telemetry reads the bridge's own notice, which is null here): keep the vocabulary closed.
            ManagedBridgeNotice.NotConnected -> "none"
            is ManagedBridgeNotice.Failed -> when (notice.code) {
                "unreachable" -> "unreachable"
                "disconnected" -> "disconnected"
                else -> "failed"
            }
        }

        fun reasonOf(notice: ManagedBridgeNotice?): String {
            val code = (notice as? ManagedBridgeNotice.Failed)?.code ?: return ""
            return when {
                code in knownReasons || httpReason.matches(code) -> code
                code == "unreachable" || code == "disconnected" -> ""
                else -> "other"
            }
        }

        /** Process-wide session instance used by the Streams screen. */
        val session = ManagedBridgeTelemetryPolicy()
    }
}
