package com.nuvio.app.features.iptv

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.hostedAccessTokenOrNull
import com.nuvio.app.core.build.AppBuildConfig
import com.nuvio.app.core.network.SupabaseConfig
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.network.SyncBackendDefaults
import com.nuvio.app.core.network.SyncBackendRepository
import com.nuvio.app.core.network.hasSameConnectionIdentity
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One playlist a redeem added or updated. */
data class RedeemedPlaylist(val playlistKey: String, val name: String, val action: String)

/** What `redeem_setup` did. [alreadyRedeemed]: this account used the code before (idempotent, not an error). */
data class RedeemSummary(
    val profileIndex: Int?,
    val added: Int,
    val updated: Int,
    val unchanged: Int,
    val playlists: List<RedeemedPlaylist>,
    val addedAddons: Int,
    val skippedAddons: Int,
    val alreadyRedeemed: Boolean = false,
    /** Services the setup could not install: `missing_login` (the provider has not filled the login yet) or `invalid_url`. */
    val skippedReasons: List<String> = emptyList(),
) {
    val skipped: Int get() = skippedReasons.size
}

sealed interface RedeemResult {
    data class Redeemed(val summary: RedeemSummary) : RedeemResult
    /** Anything but success, already mapped to the shared outcome ([SetupCodeOutcome.message]). */
    data class Refused(val outcome: SetupCodeOutcome) : RedeemResult
}

/**
 * The client side of provider setup codes, behind one interface so the UI and its tests use fakes.
 * No method logs, persists or reports the code.
 */
interface ProviderSetupApi {
    /** Preview what [code] (already [SetupCode.parse]d) would add. Never throws: every failure is an outcome. */
    suspend fun preview(code: String): SetupCodeOutcome

    /**
     * Redeem [code] into [profileIndex]. Never throws. The caller then forces ONE playlist pull.
     * [skipAddons]: store builds hide add-ons, so the server is told not to install them (`p_skip_addons`).
     */
    suspend fun redeem(code: String, profileIndex: Int, skipAddons: Boolean = false): RedeemResult

    /** `get_managed_playlists` for the profile. Throws on failure (callers keep their cache). */
    suspend fun managedPlaylists(profileId: Int): List<ManagedInfo>

    /** `detach_managed_playlist`. True when it was detached, false when it was not managed (already detached). */
    suspend fun detach(profileId: Int, playlistKey: String): Boolean
}

/** An RPC refusal carrying the server's stable error code (the raised P0001 message), or null when unknown. */
internal class ProviderRpcException(
    val errorCode: String?,
    cause: Throwable? = null,
    /** The server never gave an answer about the code (gateway failure, timeout): retry, never a verdict. */
    val transient: Boolean = false,
) : RuntimeException(errorCode, cause)

/** The two seams the HTTP/RPC implementation runs on; tests replace them. */
internal interface ProviderPreviewTransport {
    suspend fun get(url: String, headers: Map<String, String>): RawHttpResponse
}

internal interface ProviderRpcTransport {
    /** Calls [function]; throws [ProviderRpcException] for a server refusal, anything else for transport failure. */
    suspend fun call(function: String, params: JsonObject): JsonElement
}

/** Single constants for where the preview route lives. Release builds always use tuvora.co. */
internal object ProviderSetupConfig {
    const val PRODUCTION_WEB_BASE = "https://tuvora.co"
    const val PREVIEW_TIMEOUT_MS = 15_000L

    /** The route returns a few hundred bytes; anything bigger is not a preview (security L2). */
    const val PREVIEW_MAX_BODY_CHARS = 64 * 1024

    /** The setup-code web host; a debug build may be pointed at a local web server (`PROVIDER_WEB_URL`). */
    val webBaseUrl: String
        get() = SupabaseConfig.PROVIDER_WEB_URL.trim().trimEnd('/')
            .takeIf { AppBuildConfig.IS_DEBUG_BUILD && it.isNotEmpty() }
            ?: PRODUCTION_WEB_BASE

    fun previewUrl(base: String, code: String): String = "$base/api/s/preview?code=${SetupCode.format(code)}"
}

internal object PlatformProviderPreviewTransport : ProviderPreviewTransport {
    override suspend fun get(url: String, headers: Map<String, String>): RawHttpResponse =
        // Never follow a redirect: the route does not redirect, and the bearer token must not travel on. The
        // Darwin engine ignores this flag, so the final URL is checked against the request as well.
        httpRequestRaw(
            method = "GET", url = url, headers = headers, body = "",
            followRedirects = false, maxResponseBodyBytes = ProviderSetupConfig.PREVIEW_MAX_BODY_CHARS + 1024,
        )
}

internal object SupabaseProviderRpcTransport : ProviderRpcTransport {
    override suspend fun call(function: String, params: JsonObject): JsonElement = try {
        SupabaseProvider.client.postgrest.rpc(function, params).decodeAs<JsonElement>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RestException) {
        throw ProviderRpcErrors.fromRest(e.statusCode, e.message, e)
    }
}

/**
 * How a PostgREST refusal becomes a [ProviderRpcException]. Pure (status + message in), so the real message
 * shape is pinned by a golden test rather than assumed.
 */
internal object ProviderRpcErrors {
    /**
     * The real `RestException.message` shape (supabase-kt 3.6.0 against PostgREST, captured live on the local stack
     * 2026-10-02): `<PostgREST message>\nCode: <SQLSTATE or PGRST code>\nHint: ..\nDetails: ..\nURL: ..\nHeaders: ..`.
     *  - a raised refusal (`P0001`, HTTP 400): first line is exactly the code (`anonymous_not_allowed`), `Code: P0001`;
     *  - no usable session: HTTP 401 `42501` "permission denied for function redeem_setup" (anon key) or `PGRST301`
     *    "No suitable key or wrong key type" (bad/expired JWT) — never a code;
     *  - a function the backend does not have yet: HTTP 404 `PGRST202` (a store build sending `p_skip_addons` to an
     *    older backend) — a deployment gap, so retry later, never a verdict on the code;
     *  - a gateway/proxy failure: 5xx/408, first line "Unknown error", `Code: null`.
     * The code is read WHOLE (first line, letters and underscores only, and only when the server's own code is
     * `P0001` or absent), never by substring: "refused" contains "used" and "JWT expired" contains "expired", and
     * neither is that code.
     */
    fun fromRest(statusCode: Int, message: String?, cause: Throwable? = null): ProviderRpcException {
        val lines = message.orEmpty().trim().lines()
        val first = lines.firstOrNull().orEmpty().trim().lowercase()
        val serverCode = lines.firstOrNull { it.startsWith("Code:") }?.substringAfter("Code:")?.trim()
        val raised = first.takeIf { it.isNotEmpty() && it.all { c -> c.isLetter() || c == '_' } && (serverCode == null || serverCode == "P0001") }
        return when {
            raised != null -> ProviderRpcException(raised, cause)
            statusCode == 401 || statusCode == 403 -> ProviderRpcException("not_authenticated", cause)
            statusCode == 429 -> ProviderRpcException("rate_limited", cause)
            statusCode >= 500 || statusCode == 408 || statusCode == 404 -> ProviderRpcException(null, cause, transient = true)
            else -> ProviderRpcException(null, cause)
        }
    }
}

/**
 * The production [ProviderSetupApi]: the preview is an HTTPS GET on the web route (`preview_setup` itself
 * is service-role only), redeem / managed / detach are the Supabase RPCs. [bearerToken] is the signed-in
 * access token for the per-account rate-limit bucket (null when signed out, or when the sync backend is
 * not the hosted one, so a token for another backend is never sent to tuvora.co).
 */
internal class HttpProviderSetupApi(
    private val preview: ProviderPreviewTransport = PlatformProviderPreviewTransport,
    private val rpc: ProviderRpcTransport = SupabaseProviderRpcTransport,
    private val webBaseUrl: () -> String = { ProviderSetupConfig.webBaseUrl },
    private val bearerToken: suspend () -> String? = ::hostedAccessTokenOrNull,
) : ProviderSetupApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun preview(code: String): SetupCodeOutcome {
        // A malformed code never reaches the network (and never costs a rate-limit strike).
        when (val parsed = SetupCode.normalize(code)) {
            is SetupCodeParse.Invalid -> return SetupCodeOutcome.fromProblem(parsed.problem)
            is SetupCodeParse.Valid -> Unit
        }
        return try {
            val headers = buildMap {
                put("Accept", "application/json")
                bearerToken()?.let { put("Authorization", "Bearer $it") }
            }
            val url = ProviderSetupConfig.previewUrl(webBaseUrl(), code)
            val response = withTimeout(ProviderSetupConfig.PREVIEW_TIMEOUT_MS) { preview.get(url, headers) }
            // An engine that followed a redirect anyway ends on another host: not our route's answer.
            if (response.url.isNotBlank() && !sameHost(url, response.url)) return SetupCodeOutcome.Unusable
            interpretPreview(response)
        } catch (e: TimeoutCancellationException) {
            SetupCodeOutcome.Network
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The error class only: a message can carry the request URL, and the URL carries the code.
            co.touchlab.kermit.Logger.withTag("ProviderSetupApi").w { "preview failed: ${e::class.simpleName}" }
            SetupCodeOutcome.Network
        }
    }

    private fun sameHost(a: String, b: String): Boolean {
        fun host(u: String) = u.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }.lowercase()
        return host(a) == host(b)
    }

    internal fun interpretPreview(response: RawHttpResponse): SetupCodeOutcome {
        if (response.body.length > ProviderSetupConfig.PREVIEW_MAX_BODY_CHARS) return SetupCodeOutcome.Unusable
        if (response.status in 200..299) {
            val parsed = ProviderSetupCodec.preview(response.body) ?: return SetupCodeOutcome.Unusable
            return SetupCodeOutcome.Ready(parsed)
        }
        val body = runCatching { json.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
        val errorCode = (body?.get("code") as? JsonPrimitive)?.contentOrNull
        val retryAfter = response.headers.entries
            .firstOrNull { it.key.equals("retry-after", ignoreCase = true) }?.value?.trim()?.toIntOrNull()
        return SetupCodeOutcome.fromPreviewHttp(response.status, errorCode, retryAfter)
    }

    override suspend fun redeem(code: String, profileIndex: Int, skipAddons: Boolean): RedeemResult {
        val normalized = when (val parsed = SetupCode.normalize(code)) {
            is SetupCodeParse.Invalid -> return RedeemResult.Refused(SetupCodeOutcome.fromProblem(parsed.problem))
            is SetupCodeParse.Valid -> parsed.code
        }
        return try {
            // Same normalized form the web claim page sends: the 12 characters, no prefix or dashes.
            val result = rpc.call("redeem_setup", buildJsonObject {
                put("p_code", normalized)
                put("p_profile_index", profileIndex)
                // Only sent when asked: a backend without the parameter keeps working for full builds.
                if (skipAddons) put("p_skip_addons", true)
            })
            interpretRedeem(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderRpcException) {
            // A gateway failure or a missing function says nothing about the code: retry, never "can't be used".
            RedeemResult.Refused(if (e.transient) SetupCodeOutcome.Network else SetupCodeOutcome.fromErrorCode(e.errorCode))
        } catch (e: Throwable) {
            RedeemResult.Refused(SetupCodeOutcome.Network)
        }
    }

    internal fun interpretRedeem(result: JsonElement): RedeemResult {
        val o = result as? JsonObject ?: return RedeemResult.Refused(SetupCodeOutcome.Unusable)
        if ((o["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            return RedeemResult.Refused(SetupCodeOutcome.fromErrorCode((o["error"] as? JsonPrimitive)?.contentOrNull))
        }
        fun int(key: String) = (o[key] as? JsonPrimitive)?.intOrNull ?: 0
        val rows = (o["playlists"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val skippedReasons = rows
            .filter { (it["action"] as? JsonPrimitive)?.contentOrNull == "skipped" }
            .map { (it["reason"] as? JsonPrimitive)?.contentOrNull ?: "missing_login" }
        val playlists = (o["playlists"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            val key = (p["playlist_key"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            RedeemedPlaylist(
                playlistKey = key,
                name = (p["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                action = (p["action"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        }
        return RedeemResult.Redeemed(
            RedeemSummary(
                profileIndex = (o["profile_index"] as? JsonPrimitive)?.intOrNull,
                added = int("added"), updated = int("updated"), unchanged = int("unchanged"),
                playlists = playlists,
                addedAddons = int("added_addons"), skippedAddons = int("skipped_addons"),
                alreadyRedeemed = (o["status"] as? JsonPrimitive)?.contentOrNull == "already_redeemed",
                skippedReasons = skippedReasons,
            ),
        )
    }

    override suspend fun managedPlaylists(profileId: Int): List<ManagedInfo> =
        ProviderSetupCodec.managedPlaylists(rpc.call("get_managed_playlists", buildJsonObject { put("p_profile_id", profileId) }))

    override suspend fun detach(profileId: Int, playlistKey: String): Boolean {
        val result = rpc.call("detach_managed_playlist", buildJsonObject {
            put("p_profile_id", profileId)
            put("p_playlist_key", playlistKey)
        })
        return ((result as? JsonObject)?.get("detached") as? JsonPrimitive)?.booleanOrNull == true
    }
}
