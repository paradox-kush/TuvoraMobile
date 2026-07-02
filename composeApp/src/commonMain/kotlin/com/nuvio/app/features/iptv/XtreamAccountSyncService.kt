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
 * Playlist-manager P1: reads/writes the new `iptv_playlists` table. When it's empty, the pull
 * falls back to the legacy `xtream_accounts` rows (written by older app versions) and migrates
 * them up to the new table. The legacy RPC is never written again (no dual-write).
 */
object XtreamAccountSyncService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("XtreamAccountSyncService")
    private const val PUSH_DEBOUNCE_MS = 600L

    @Volatile
    var isSyncingFromRemote: Boolean = false
    private var pushJob: Job? = null

    /** `iptv_playlists` row (only the columns this client uses; the rest ignore-unknown away). */
    @Serializable
    private data class PlaylistRow(
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
    )

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
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_playlists", playlistPushPayload(accounts))
            }
            SupabaseProvider.client.postgrest.rpc("sync_push_iptv_playlists", params)
            log.d { "pushToRemote — ${accounts.size} playlists" }
        }.onFailure { e -> log.e(e) { "pushToRemote — FAILED" } }
    }

    /**
     * Pull this profile's playlists on login. New table first; empty new table falls back to
     * the legacy rows (then migrates them up); both empty + non-empty local => migrate local up.
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
            if (rows.isNotEmpty()) {
                // Rows exist in the new table — it is the source of truth, even if some rows
                // are source types this P1 client can't use yet (skipped, never pushed over).
                apply(profileId, rows.mapNotNull { it.toAccount() })
                return@runCatching
            }

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
                pushToRemote(profileId)   // write them to the NEW table (legacy table stays for old clients)
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

    /** P1 clients only understand xtream rows; other source types (P2/P4) are skipped, not synced down. */
    private fun PlaylistRow.toAccount(): XtreamAccount? {
        if (sourceType != "xtream") return null
        val base = baseUrl ?: return null
        val user = username ?: return null
        return XtreamAccount(
            id = "$base|$user",
            name = name ?: base,
            baseUrl = base,
            username = user,
            password = password ?: "",
            enabled = enabled,
            sourceType = sourceType,
            epgUrl = epgUrl,
            dnsProvider = dnsProvider,
            autoRefreshHours = autoRefreshHours,
            contentTypes = contentTypes.toSet(),
            categorySelections = parseCategorySelections(categorySelections),
        )
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

/**
 * Per-row JSON for `sync_push_iptv_playlists` — field names match the iptv_playlists migration
 * exactly. Omissions are contract: blank name, null epg_url, and all-null category_selections
 * are left out so the RPC's coalesce defaults apply. internal for tests.
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
