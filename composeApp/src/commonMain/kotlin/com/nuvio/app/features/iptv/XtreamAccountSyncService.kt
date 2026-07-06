package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Syncs IPTV playlists per profile to Supabase, mirroring CollectionSyncService.
 * Push = full-replace RPC on change (debounced); pull = direct RLS-scoped select on login. Only runs
 * for a real (non-anonymous) session — the local "Anonymous" state keeps playlists device-local.
 *
 * Reads/writes the `iptv_playlists` table for ALL source types (xtream / m3u_url / m3u_file /
 * stalker). Every push is scoped to those types (p_source_types) so this client can never delete
 * a future client's unknown rows. When the table holds no usable rows, the pull falls back to the
 * legacy `xtream_accounts` rows (written by older app versions), migrates them up (guarded by
 * p_only_if_empty against a two-device first-login race), then clears the legacy rows — a
 * one-shot migration, so stale legacy rows can't resurrect playlists deleted later.
 */
object XtreamAccountSyncService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("XtreamAccountSyncService")
    private const val PUSH_DEBOUNCE_MS = 600L

    @Volatile
    var isSyncingFromRemote: Boolean = false
    private var pushJob: Job? = null

    /** Legacy `xtream_accounts` row — read-only fallback for rows synced by older app versions. */
    @Serializable
    private data class LegacyRow(
        @SerialName("base_url") val baseUrl: String,
        val username: String,
        val password: String,
        val name: String? = null,
        val enabled: Boolean = true,
        @SerialName("sort_order") val sortOrder: Int = 0,
    )

    private fun authed(): Boolean {
        val s = AuthRepository.state.value
        return s is AuthState.Authenticated && !s.isAnonymous
    }

    /** Debounced push after a local account change (called from XtreamRepository.persist()). */
    fun triggerPush() {
        pushJob?.cancel()
        pushJob = scope.launch {
            val profileId = ProfileRepository.activeProfileId
            delay(PUSH_DEBOUNCE_MS)
            if (ProfileRepository.activeProfileId != profileId) return@launch
            if (isSyncingFromRemote || !authed()) return@launch
            pushToRemote(profileId)
        }
    }

    private suspend fun pushToRemote(profileId: Int) {
        runCatching {
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            val accounts = XtreamRepository.uiState.value.accounts
            SupabaseProvider.client.postgrest
                .rpc("sync_push_iptv_playlists", playlistPushParams(profileId, accounts))
            log.d { "pushToRemote — ${accounts.size} playlists" }
        }.onFailure { e -> log.e(e) { "pushToRemote — FAILED" } }
    }

    /**
     * One-shot legacy migration: push the just-applied legacy playlists up with p_only_if_empty
     * (two devices racing on first login — the loser no-ops instead of clobbering the winner),
     * then clear the legacy rows via the old RPC. Without the clear, deleting every playlist
     * later (empty new table + stale legacy rows) would resurrect them on the next login pull.
     * The clear only runs after a successful new-table push, so a failed push retries whole.
     */
    private suspend fun migrateLegacyUp(profileId: Int) {
        runCatching {
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            val accounts = XtreamRepository.uiState.value.accounts
            SupabaseProvider.client.postgrest
                .rpc("sync_push_iptv_playlists", playlistPushParams(profileId, accounts, onlyIfEmpty = true))
            SupabaseProvider.client.postgrest.rpc("sync_push_xtream_accounts", buildJsonObject {
                put("p_profile_id", profileId)
                put("p_accounts", JsonArray(emptyList()))
            })
            log.i { "migrateLegacyUp — ${accounts.size} playlists migrated, legacy rows cleared" }
        }.onFailure { e -> log.e(e) { "migrateLegacyUp — FAILED" } }
    }

    /**
     * Pull this profile's playlists on login. New table first; no usable xtream rows there
     * falls back to the legacy rows (then migrates them up, one-shot); both empty + non-empty
     * local => migrate local up.
     */
    suspend fun pullFromServer(profileId: Int) {
        if (!authed() || ProfileRepository.activeProfileId != profileId) return
        runCatching {
            val rows = SupabaseProvider.client.postgrest
                .from("iptv_playlists")
                .select {
                    filter { eq("profile_id", profileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<PlaylistRow>()
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            val playlists = usableRemoteAccounts(rows)
            if (playlists.isNotEmpty()) {
                apply(profileId, reconcileLocalIds(playlists, XtreamRepository.uiState.value.accounts))
                return@runCatching
            }
            // Zero usable rows (empty table, or only a newer client's unknown source types)
            // = empty remote for this client — never apply an empty list over local state.

            val legacy = SupabaseProvider.client.postgrest
                .from("xtream_accounts")
                .select {
                    filter { eq("profile_id", profileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<LegacyRow>()
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            if (legacy.isNotEmpty()) {
                log.i { "pullFromServer — migrating ${legacy.size} legacy xtream_accounts rows up" }
                apply(profileId, legacy.map { it.toAccount() })
                migrateLegacyUp(profileId)
            } else if (XtreamRepository.uiState.value.accounts.isNotEmpty()) {
                log.i { "pullFromServer — remote empty, migrating local playlists up" }
                pushToRemote(profileId)
            }
        }.onFailure { e ->
            isSyncingFromRemote = false
            log.e(e) { "pullFromServer — FAILED" }
        }
    }

    private fun apply(profileId: Int, accounts: List<XtreamAccount>) {
        isSyncingFromRemote = true
        XtreamRepository.applyFromRemote(profileId, accounts)
        isSyncingFromRemote = false
        log.i { "pullFromServer — applied ${accounts.size} playlists" }
    }

    private fun LegacyRow.toAccount(): XtreamAccount = XtreamAccount(
        id = "$baseUrl|$username",
        name = name ?: baseUrl,
        baseUrl = baseUrl,
        username = username,
        password = password,
        enabled = enabled,
    )
}

/** `iptv_playlists` row (only the columns this client uses; the rest ignore-unknown away). internal for tests. */
@Serializable
internal data class PlaylistRow(
    @SerialName("source_type") val sourceType: String = "xtream",
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("base_url") val baseUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    @SerialName("epg_url") val epgUrl: String? = null,
    @SerialName("dns_provider") val dnsProvider: String = "system",
    @SerialName("auto_refresh_hours") val autoRefreshHours: Int = 0,
    @SerialName("content_types") val contentTypes: List<String> = ALL_CONTENT_TYPES.toList(),
    // jsonb; decoded leniently by hand — a malformed shape must not sink the whole pull
    @SerialName("category_selections") val categorySelections: JsonElement? = null,
    // M3U (url/file) columns
    val url: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    // Stalker columns
    @SerialName("portal_url") val portalUrl: String? = null,
    @SerialName("mac_address") val macAddress: String? = null,
    @SerialName("stalker_username") val stalkerUsername: String? = null,
    @SerialName("stalker_password") val stalkerPassword: String? = null,
    @SerialName("serial_number") val serialNumber: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("send_device_id") val sendDeviceId: Boolean = true,
)

/**
 * Maps a sync row to a local account for every source type this client understands; null for
 * malformed rows and unknown (future) source types — those stay remote-only, and the push scope
 * (p_source_types) guarantees we never delete them. Ids are re-derived locally with the same
 * builders the Add-Playlist form uses, so a pulled playlist gets the exact id a hand-added one
 * would (stable content-DB / registry keys). "url"/"file" are accepted as aliases for the
 * canonical m3u_url/m3u_file (NuvioTV's internal spellings, tolerated defensively on the wire).
 */
internal fun PlaylistRow.toAccount(): XtreamAccount? = when (sourceType) {
    "xtream" -> {
        val base = baseUrl
        val user = username
        if (base == null || user == null) null else XtreamAccount(
            id = "$base|$user",
            name = name ?: base,
            baseUrl = base,
            username = user,
            password = password ?: "",
            enabled = enabled,
            sourceType = SOURCE_TYPE_XTREAM,
        ).withOptions(this)
    }
    SOURCE_TYPE_M3U_URL, "url" -> {
        val playlistUrl = (url ?: baseUrl)?.takeIf { it.isNotBlank() }
        if (playlistUrl == null) null else XtreamAccount(
            id = "m3u|$playlistUrl",
            name = name ?: playlistUrl,
            baseUrl = playlistUrl,
            username = "",
            password = "",
            enabled = enabled,
            sourceType = SOURCE_TYPE_M3U_URL,
            userAgent = userAgent?.takeIf { it.isNotBlank() },
        ).withOptions(this)
    }
    SOURCE_TYPE_M3U_FILE, "file" -> {
        // File BYTES are never synced — this lands as a re-import ghost (the form shows "added on
        // another device, choose the file again"). Deterministic id so repeated pulls are stable;
        // reconcileLocalIds keeps the local id when this device already has the real file copy.
        val fn = fileName?.takeIf { it.isNotBlank() } ?: name ?: "Playlist"
        XtreamAccount(
            id = "m3u_file|$fn|synced",
            name = name ?: fn.substringBeforeLast('.'),
            baseUrl = "",
            username = "",
            password = "",
            enabled = enabled,
            sourceType = SOURCE_TYPE_M3U_FILE,
            userAgent = userAgent?.takeIf { it.isNotBlank() },
            fileName = fn,
        ).withOptions(this)
    }
    SOURCE_TYPE_STALKER -> {
        val portal = (portalUrl ?: baseUrl)?.takeIf { it.isNotBlank() }
        val mac = macAddress?.takeIf { it.isNotBlank() }
        if (portal == null || mac == null) null else XtreamAccount(
            id = "stalker|$portal|$mac",
            name = name ?: portal,
            baseUrl = portal,
            username = "",
            password = "",
            enabled = enabled,
            sourceType = SOURCE_TYPE_STALKER,
            macAddress = mac,
            stalkerUsername = stalkerUsername?.takeIf { it.isNotBlank() },
            stalkerPassword = stalkerPassword?.takeIf { it.isNotBlank() },
            serialNumber = serialNumber?.takeIf { it.isNotBlank() },
            deviceId = deviceId?.takeIf { it.isNotBlank() },
            sendDeviceId = sendDeviceId,
        ).withOptions(this)
    }
    else -> null
}

/** The playlist-manager option fields every source type shares. */
private fun XtreamAccount.withOptions(row: PlaylistRow): XtreamAccount = copy(
    epgUrl = row.epgUrl,
    dnsProvider = row.dnsProvider,
    autoRefreshHours = row.autoRefreshHours,
    contentTypes = row.contentTypes.toSet(),
    categorySelections = parseCategorySelections(row.categorySelections),
)

/**
 * Keeps this device's account id when a pulled account is the same playlist under a different id.
 * Only m3u_file needs it: its locally-minted id carries a unique suffix (the local file copy lives
 * at `{id}.m3u`), while a pulled ghost has the deterministic `|synced` id — matching by fileName
 * preserves the local copy + saved content keys. Every other source type derives ids
 * deterministically, so pulled == local already. internal for tests.
 */
internal fun reconcileLocalIds(pulled: List<XtreamAccount>, local: List<XtreamAccount>): List<XtreamAccount> =
    pulled.map { acc ->
        if (acc.sourceType != SOURCE_TYPE_M3U_FILE) return@map acc
        val match = local.firstOrNull { it.sourceType == SOURCE_TYPE_M3U_FILE && it.fileName == acc.fileName }
        if (match != null) acc.copy(id = match.id) else acc
    }

/**
 * The pull's emptiness decision happens AFTER this filter: rows of only foreign source types
 * (a future client's playlists) are an empty remote for this client — they must never be
 * applied as an empty list over local state. internal for tests.
 */
internal fun usableRemoteAccounts(rows: List<PlaylistRow>): List<XtreamAccount> =
    rows.mapNotNull { it.toAccount() }

/** The wire source types this client fully understands — the push's full-replace scope. Unknown
 *  (future) types stay outside the scope, so they can never be deleted by this client. */
internal val SYNCED_SOURCE_TYPES = listOf(
    SOURCE_TYPE_XTREAM, SOURCE_TYPE_M3U_URL, SOURCE_TYPE_M3U_FILE, SOURCE_TYPE_STALKER,
)

/**
 * RPC params for `sync_push_iptv_playlists`. Every push is scoped to p_source_types (the source
 * types this client understands) so the full-replace can never delete a newer client's rows of a
 * type we don't know; p_only_if_empty is only set on the legacy-migration push. internal for tests.
 */
internal fun playlistPushParams(
    profileId: Int,
    accounts: List<XtreamAccount>,
    onlyIfEmpty: Boolean = false,
): JsonObject = buildJsonObject {
    put("p_profile_id", profileId)
    put("p_playlists", playlistPushPayload(accounts))
    if (onlyIfEmpty) put("p_only_if_empty", true)
    put("p_source_types", JsonArray(SYNCED_SOURCE_TYPES.map(::JsonPrimitive)))
}

/**
 * Per-row JSON for `sync_push_iptv_playlists` — field names match the iptv_playlists migration
 * exactly. Omissions are contract: blank name, null epg_url, null per-type extras, and all-null
 * category_selections are left out so the RPC's coalesce defaults apply. internal for tests.
 */
internal fun playlistPushPayload(accounts: List<XtreamAccount>): JsonArray = buildJsonArray {
    accounts.forEachIndexed { index, acc ->
        addJsonObject {
            put("source_type", acc.sourceType)
            acc.name.takeIf { it.isNotBlank() }?.let { put("name", it) }
            put("enabled", acc.enabled)
            put("sort_order", index)
            put("base_url", acc.baseUrl)
            put("username", acc.username)
            put("password", acc.password)
            when (acc.sourceType) {
                SOURCE_TYPE_M3U_URL -> {
                    put("url", acc.baseUrl)                       // the playlist URL IS the base
                    acc.userAgent?.let { put("user_agent", it) }
                }
                SOURCE_TYPE_M3U_FILE -> {
                    acc.fileName?.let { put("file_name", it) }    // metadata only; bytes stay local
                    acc.userAgent?.let { put("user_agent", it) }
                }
                SOURCE_TYPE_STALKER -> {
                    put("portal_url", acc.baseUrl)                // mobile keeps the portal in baseUrl
                    put("mac_address", acc.macAddress)
                    acc.stalkerUsername?.let { put("stalker_username", it) }
                    acc.stalkerPassword?.let { put("stalker_password", it) }
                    acc.serialNumber?.let { put("serial_number", it) }
                    acc.deviceId?.let { put("device_id", it) }
                    put("send_device_id", acc.sendDeviceId)
                }
            }
            acc.epgUrl?.let { put("epg_url", it) }
            put("dns_provider", acc.dnsProvider)
            put("auto_refresh_hours", acc.autoRefreshHours)
            put("content_types", JsonArray(acc.contentTypes.map { JsonPrimitive(it) }))
            val cs = acc.categorySelections
            if (!cs.allNull) {
                put("category_selections", buildJsonObject {
                    cs.live?.let { put("live", JsonArray(it.map(::JsonPrimitive))) }
                    cs.movies?.let { put("movies", JsonArray(it.map(::JsonPrimitive))) }
                    cs.series?.let { put("series", JsonArray(it.map(::JsonPrimitive))) }
                })
            }
        }
    }
}

/** Lenient decode of the jsonb category_selections column: any malformed shape -> all-null (= all). */
internal fun parseCategorySelections(element: JsonElement?): CategorySelections {
    val obj = element as? JsonObject ?: return CategorySelections()
    fun list(key: String): List<String>? =
        (obj[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    return CategorySelections(live = list("live"), movies = list("movies"), series = list("series"))
}
