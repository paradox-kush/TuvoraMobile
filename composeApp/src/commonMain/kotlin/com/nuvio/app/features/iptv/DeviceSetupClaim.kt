package com.nuvio.app.features.iptv

import com.nuvio.app.features.profiles.ProfilePinCrypto
import com.nuvio.app.features.servers.secureRandomHex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * B205: a setup code adds playlists with NO Tuvora account.
 *
 * The device redeems the code on its own (POST /api/s/redeem) and keeps the playlists locally. A random
 * claim token, whose SHA-256 the server stored, lets the person move those playlists into a real account
 * later (`claim_device_setup`). Design and server contract: research/setup-code-signed-out-design.md.
 */

/**
 * One playlist as the device redeem answers it (and as a pending claim remembers it). The same columns an
 * `iptv_playlists` row carries, so [DevicePlaylistMapper] can turn it into a local account exactly like a pull.
 * Nullable lists/strings because the wire carries null for fields a source type does not use.
 */
@Serializable
data class DevicePlaylist(
    @SerialName("playlist_key") val playlistKey: String? = null,
    val name: String? = null,
    @SerialName("source_type") val sourceType: String = SOURCE_TYPE_XTREAM,
    /** `added`, or `skipped` (the provider has not filled the login yet / the address is invalid). */
    val action: String = "added",
    val reason: String? = null,
    @SerialName("base_url") val baseUrl: String? = null,
    val url: String? = null,
    @SerialName("portal_url") val portalUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    @SerialName("mac_address") val macAddress: String? = null,
    @SerialName("stalker_username") val stalkerUsername: String? = null,
    @SerialName("stalker_password") val stalkerPassword: String? = null,
    @SerialName("backup_urls") val backupUrls: List<String>? = null,
    @SerialName("epg_url") val epgUrl: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    @SerialName("content_types") val contentTypes: List<String>? = null,
) {
    val added: Boolean get() = action == "added"

    /** Never prints the login: a value that reaches a log line must not carry credentials. */
    override fun toString(): String = "DevicePlaylist(sourceType=$sourceType, action=$action)"
}

/** What `/api/s/redeem` did. [alreadyRedeemed]: the same claim token redeemed this code before (a retry). */
data class DeviceRedeemSummary(
    val providerName: String,
    val alreadyRedeemed: Boolean,
    val playlists: List<DevicePlaylist>,
    val skippedAddons: Int = 0,
) {
    val added: List<DevicePlaylist> get() = playlists.filter { it.added }
    val skippedReasons: List<String> get() = playlists.filter { !it.added }.map { it.reason ?: "missing_login" }
}

sealed interface DeviceRedeemResult {
    data class Redeemed(val summary: DeviceRedeemSummary) : DeviceRedeemResult
    /** Anything but success, already mapped to the shared outcome. A [SetupCodeOutcome.Network] is retried with the SAME token. */
    data class Refused(val outcome: SetupCodeOutcome) : DeviceRedeemResult
}

/** What `claim_device_setup` answered. [Failed.code] is the server's error, or null for network / gateway failures. */
sealed interface DeviceClaimResult {
    data class Claimed(val alreadyClaimed: Boolean) : DeviceClaimResult
    data class Failed(val code: String?) : DeviceClaimResult
}

/** What to do with a pending claim once the server has answered. */
enum class ClaimDecision {
    /** Claimed: forget the claim and pull once, which brings the managed rows. */
    DROP_AND_PULL,
    /** The setup can never be claimed: make sure the playlists exist locally (the normal push uploads them), then forget the claim. */
    RESTORE_LOCAL_AND_DROP,
    /** Not decided: keep the claim, retry at the next sign-in or app start (never on a timer). */
    KEEP_FOR_RETRY,
}

/** The pure decision per claim outcome (contract section 5). */
object DeviceClaimPolicy {
    private val unclaimable = setOf("not_found", "already_used", "revoked", "suspended", "unavailable")

    fun decide(result: DeviceClaimResult): ClaimDecision = when (result) {
        is DeviceClaimResult.Claimed -> ClaimDecision.DROP_AND_PULL
        is DeviceClaimResult.Failed ->
            if (result.code?.trim()?.lowercase() in unclaimable) ClaimDecision.RESTORE_LOCAL_AND_DROP else ClaimDecision.KEEP_FOR_RETRY
    }
}

/** A device-redeemed setup waiting for a real account to claim it. Never synced, logged or sent to analytics. */
@Serializable
data class PendingDeviceClaim(
    val token: String,
    val providerName: String,
    val createdAtMs: Long,
    val playlists: List<DevicePlaylist>,
) {
    override fun toString(): String = "PendingDeviceClaim(playlists=${playlists.size})"
}

/** Device playlist -> local account, through the SAME mapping a pulled `iptv_playlists` row uses. */
internal object DevicePlaylistMapper {
    /** An ordinary new playlist refreshes daily; a pulled row would carry the server's own value. */
    private const val DEFAULT_AUTO_REFRESH_HOURS = 24

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    fun toAccount(p: DevicePlaylist): XtreamAccount? {
        val key = p.playlistKey.clean() ?: return null
        val row = PlaylistRow(
            playlistKey = key,
            backupUrls = p.backupUrls.orEmpty().mapNotNull { it.clean() },
            sourceType = p.sourceType,
            name = p.name.clean(),
            baseUrl = p.baseUrl.clean(),
            username = p.username.clean(),
            password = p.password,
            epgUrl = p.epgUrl.clean(),
            autoRefreshHours = DEFAULT_AUTO_REFRESH_HOURS,
            contentTypes = p.contentTypes.orEmpty().ifEmpty { ALL_CONTENT_TYPES.toList() },
            url = p.url.clean(),
            userAgent = p.userAgent.clean(),
            portalUrl = p.portalUrl.clean(),
            macAddress = p.macAddress.clean(),
            stalkerUsername = p.stalkerUsername.clean(),
            stalkerPassword = p.stalkerPassword.clean(),
        )
        return row.toAccount()
    }

    /** The accounts a redeem adds: only `action == added` entries that map to a usable playlist. */
    fun accountsToAdd(playlists: List<DevicePlaylist>): List<XtreamAccount> =
        playlists.filter { it.added }.mapNotNull(::toAccount)

    /** The claim's playlists that this device no longer holds, matched by playlist key (never a duplicate). */
    fun missing(playlists: List<DevicePlaylist>, localIds: Set<String>): List<XtreamAccount> =
        accountsToAdd(playlists).filter { it.id !in localIds }.distinctBy { it.id }
}

/** The claim token: 32 bytes of the platform's secure random as lowercase hex, and its SHA-256 for the server. */
internal object DeviceClaimToken {
    fun newToken(): String = secureRandomHex(32)

    /** SHA-256 of the token's text (UTF-8), lowercase hex, 64 chars: what `/api/s/redeem` receives. */
    fun hash(token: String): String = ProfilePinCrypto.sha256Hex(token)
}

/**
 * Where pending claims live: a store the account wipe does NOT clear (own preferences file / key, not in the
 * platform cleaners' lists). The wipe runs when a guest signs in, and that is exactly when the claim is needed.
 */
internal class PendingDeviceClaimStore(
    private val load: () -> String?,
    private val save: (String?) -> Unit,
) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun list(): List<PendingDeviceClaim> {
        val raw = load()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { json.decodeFromString<List<PendingDeviceClaim>>(raw) }.getOrDefault(emptyList())
    }

    /** Adds [claim], replacing one with the same token (a retried redeem). */
    fun put(claim: PendingDeviceClaim) {
        save(json.encodeToString(list().filterNot { it.token == claim.token } + claim))
    }

    fun remove(token: String) {
        val rest = list().filterNot { it.token == token }
        save(if (rest.isEmpty()) null else json.encodeToString(rest))
    }

    companion object {
        val shared = PendingDeviceClaimStore(PendingDeviceClaimStorage::load, PendingDeviceClaimStorage::save)
    }
}

/** Platform persistence of the pending-claim list (one JSON string). Not matched by any local-data cleaner. */
internal expect object PendingDeviceClaimStorage {
    fun load(): String?
    /** null removes it. Durable before returning: the claim is the only way back to a redeemed code. */
    fun save(json: String?)
}
