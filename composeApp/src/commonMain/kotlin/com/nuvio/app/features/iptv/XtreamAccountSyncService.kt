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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
            when (activationFor(profileId)) {
                PlaylistSyncActivation.V2_ACTIVE -> runV2Sync(profileId)
                PlaylistSyncActivation.V1_LEGACY -> pushToRemote(profileId)
                // Adopted profile with v2 turned off: never fall back to destructive v1. Retain
                // pending; push nothing until v2 is re-enabled.
                PlaylistSyncActivation.V2_PAUSED ->
                    log.w { "triggerPush — v2 paused for profile $profileId; pending retained, no v1 write" }
            }
        }
    }

    /** Per-profile activation: reads the stored sync-state to decide whether this profile has adopted
     *  v2, then applies the rollout policy. */
    private fun activationFor(profileId: Int): PlaylistSyncActivation {
        val st = decodePlaylistSyncState(XtreamAccountStorage.loadPlaylistSyncStateJson(profileId))
        val adopted = st.revision > 0 || st.pending.isNotEmpty()
        return PlaylistSyncConfig.activationFor(adopted)
    }

    private suspend fun pushToRemote(profileId: Int) {
        runCatching {
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            // B24 §2: capture authority AND the outgoing accounts in ONE synchronous snapshot, so
            // the authority decision and the payload cannot desync and a profile switch during the
            // network call below cannot redirect the payload. Send the SNAPSHOT's profile + accounts,
            // never the live active-profile state.
            val snapshot = XtreamRepository.captureOutgoingPush()
            if (snapshot.profileId != profileId) return@runCatching // loaded state is for another profile
            // A full-replace push whose local state is not authoritative (a Recovered/Corrupt local
            // decode, or an unloaded store) would delete-then-insert an empty/truncated set and wipe
            // the server. Withhold it; a genuine user deletion stays authoritative and still pushes
            // (B24). This also covers the login-time flush, not just the debounced edit push.
            if (!snapshot.canFullReplace) {
                log.w { "pushToRemote — withheld: local playlist state not authoritative; preserving server (B24)" }
                return@runCatching
            }
            // No lock is held across this network call; the payload is the immutable snapshot, and a
            // failure here changes NO local authority/synced state — it can neither authorize a
            // future push nor mark a newer edit synced (this push records nothing locally).
            SupabaseProvider.client.postgrest
                .rpc("sync_push_iptv_playlists", playlistPushParams(snapshot.profileId, snapshot.accounts))
            log.d { "pushToRemote — ${snapshot.accounts.size} playlists" }
        }.onFailure { e -> log.e(e) { "pushToRemote — FAILED" } }
    }

    // ---------------------------------------------------------------------------------------------
    // B24 v2 — the REAL revision-contract sync path (debug/local only; see PlaylistSyncConfig).
    // Drives the shared PlaylistV2SyncEngine through a transport that calls the actual v2 RPCs, using
    // the real repository state + reconcile core. Serialized per profile by [v2Mutex] (the network
    // calls happen INSIDE the engine, NOT inside any storage lock).
    // ---------------------------------------------------------------------------------------------
    private val v2Mutex = kotlinx.coroutines.sync.Mutex()
    private val pullJson = Json { ignoreUnknownKeys = true }

    /** A fresh mutation id (128 bits of randomness) — persisted, so it is stable across retries and
     *  restarts and rotated only after a committed push. */
    private fun newPlaylistMutationId(): String =
        "m-" + kotlin.random.Random.nextLong().toULong().toString(16) + kotlin.random.Random.nextLong().toULong().toString(16)

    private val v2Transport = object : PlaylistSyncTransport {
        override suspend fun pull(profileId: Int): PlaylistPullResponse {
            val result: JsonObject = SupabaseProvider.client.postgrest
                .rpc("sync_pull_iptv_playlists_v2", buildJsonObject { put("p_profile_id", profileId) })
                .decodeAs()
            val revision = (result["revision"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val generation = (result["generation"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val rowsJson = result["playlists"] as? JsonArray ?: JsonArray(emptyList())
            val rows = rowsJson.mapNotNull { el ->
                runCatching { pullJson.decodeFromJsonElement(PlaylistRow.serializer(), el) }.getOrNull()
            }
            val accounts = usableRemoteAccounts(rows)
            return PlaylistPullResponse(revision, accounts, generation)
        }

        override suspend fun push(
            profileId: Int,
            expectedRevision: Long?,
            accounts: List<XtreamAccount>,
            deleteAll: Boolean,
            mutationId: String,
            expectedGeneration: Long?,
        ): PlaylistPushResponse {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_expected_revision", if (expectedRevision == null) JsonNull else JsonPrimitive(expectedRevision))
                put("p_playlists", playlistPushPayload(accounts))
                put("p_source_types", JsonArray(SYNCED_SOURCE_TYPES.map(::JsonPrimitive)))
                put("p_delete_all", deleteAll)
                put("p_mutation_id", mutationId)
                put("p_expected_generation", if (expectedGeneration == null) JsonNull else JsonPrimitive(expectedGeneration))
            }
            val result: JsonObject = SupabaseProvider.client.postgrest
                .rpc("sync_push_iptv_playlists_v2", params).decodeAs()
            return when ((result["status"] as? JsonPrimitive)?.content) {
                "ok" -> PlaylistPushResponse.Ok(
                    revision = (result["revision"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                    deduped = (result["deduped"] as? JsonPrimitive)?.content?.toBoolean() ?: false,
                )
                "conflict" -> {
                    val curRows = (result["current_rows"] as? JsonArray ?: JsonArray(emptyList()))
                        .mapNotNull { el -> runCatching { pullJson.decodeFromJsonElement(PlaylistRow.serializer(), el) }.getOrNull() }
                    PlaylistPushResponse.Conflict(
                        currentRevision = (result["current_revision"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                        currentRows = usableRemoteAccounts(curRows),
                    )
                }
                else -> PlaylistPushResponse.Rejected(result.toString())
            }
        }
    }

    private fun v2Engine() = PlaylistV2SyncEngine(
        transport = v2Transport,
        loadState = { decodePlaylistSyncState(XtreamAccountStorage.loadPlaylistSyncStateJson(it)) },
        saveState = { p, s -> XtreamAccountStorage.savePlaylistSyncStateJson(p, encodePlaylistSyncState(s)) },
        currentAccounts = { XtreamRepository.uiState.value.accounts },
        canPush = { XtreamRepository.canPushFullReplace() },
        applyLocal = { p, accounts -> XtreamRepository.applyFromRemote(p, reconcileLocalIds(accounts, XtreamRepository.uiState.value.accounts)) },
        stillActive = { ProfileRepository.activeProfileId == it },
        newMutationId = { newPlaylistMutationId() },
    )

    /** Runs one full v2 sync for [profileId], serialized per call. Rejections/conflicts are logged. */
    suspend fun runV2Sync(profileId: Int) {
        if (ProfileRepository.activeProfileId != profileId) return
        v2Mutex.lock()
        val outcome = try {
            v2Engine().sync(profileId)
        } catch (e: Throwable) {
            log.e(e) { "runV2Sync — FAILED (no v1 fallback; server preserved)" }
            PlaylistSyncOutcome.PUSH_FAILED
        } finally {
            v2Mutex.unlock()
        }
        log.i { "runV2Sync(profile $profileId) — $outcome" }
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
        when (activationFor(profileId)) {
            PlaylistSyncActivation.V2_ACTIVE -> { runV2Sync(profileId); return }
            // Adopted-but-paused: don't run a v1 pull/apply that could overwrite local; freeze the
            // profile (pending retained) until v2 is re-enabled.
            PlaylistSyncActivation.V2_PAUSED -> {
                log.w { "pullFromServer — v2 paused for profile $profileId; skipping (local + pending preserved)" }
                return
            }
            PlaylistSyncActivation.V1_LEGACY -> Unit // fall through to the legacy v1 pull below
        }
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
                val local = XtreamRepository.uiState.value.accounts
                apply(profileId, preserveDeviceLocalPrefs(reconcileLocalIds(playlists, local), local))
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
    // Shared across every source type (B04): an Xtream/Stalker playlist carries a per-playlist UA
    // too, so it must ride the pull the same way it rides the push — not just the M3U branches.
    userAgent = row.userAgent?.takeIf { it.isNotBlank() },
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

/**
 * Carries this device's catch-up and guide preferences across a pull (NuvioTV's twin of the same
 * name).
 *
 * A pull REPLACES the account list with objects rebuilt from the wire, so any field the payload
 * does not carry comes back as its constructor default. The catch-up container preference, the
 * manual time correction, the LEARNED dialect winner and the guide EPG offset are deliberately not
 * on the wire — they tune ONE panel's behaviour as reached from THIS device, and a shared column
 * would need a backend migration to hold them.
 *
 * Without this, every sync would silently reset them, which is exactly the kind of "my setting
 * keeps un-setting itself" bug that is impossible to report and miserable to find. internal for
 * tests.
 */
internal fun preserveDeviceLocalPrefs(
    pulled: List<XtreamAccount>,
    local: List<XtreamAccount>,
): List<XtreamAccount> = pulled.map { acc ->
    val match = local.firstOrNull { it.id == acc.id } ?: return@map acc
    acc.copy(
        catchUpPreferM3u8 = match.catchUpPreferM3u8,
        catchUpTimeCorrectionMinutes = match.catchUpTimeCorrectionMinutes,
        catchUpWinner = match.catchUpWinner,
        guideEpgCorrectionMinutes = match.guideEpgCorrectionMinutes,
    )
}

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
                }
                SOURCE_TYPE_M3U_FILE -> {
                    acc.fileName?.let { put("file_name", it) }    // metadata only; bytes stay local
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
            // user_agent is a SHARED column applying to every source type (catalog + EPG + player
            // stream; see StreamUserAgentPolicy) — not an M3U-only extra. Writing it here (not in the
            // per-type when) is what stops an Xtream/Stalker playlist's UA from silently reverting to
            // blank on the next login pull (B04). Omitted when null so the RPC's default applies.
            acc.userAgent?.let { put("user_agent", it) }
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
