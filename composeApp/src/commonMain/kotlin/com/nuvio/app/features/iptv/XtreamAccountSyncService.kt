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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Syncs Xtream IPTV accounts (playlists) per profile to Supabase, mirroring CollectionSyncService.
 * Push = full-replace RPC on change (debounced); pull = direct RLS-scoped select on login. Only runs
 * for a real (non-anonymous) session — the local "Anonymous" state keeps playlists device-local.
 *
 * v2 (playlist manager): reads/writes the `iptv_playlists` table (same as NuvioTV) so accounts
 * sync cross-device. The push is scoped to `source_type='xtream'` so it never deletes the m3u/
 * stalker/file rows a TV wrote. Legacy fallback: when iptv_playlists has no xtream rows, read the
 * old `xtream_accounts` table, apply it, migrate it up (only-if-empty), then clear it — one-shot,
 * matching NuvioTV. Without this, the phone (old table) and TV (new table) never saw each other.
 */
object XtreamAccountSyncService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("XtreamAccountSyncService")
    private const val PUSH_DEBOUNCE_MS = 600L
    private const val WIRE_XTREAM = "xtream"

    @Volatile
    var isSyncingFromRemote: Boolean = false
    private var pushJob: Job? = null

    /** iptv_playlists row — mobile only reads the xtream-relevant columns. */
    @Serializable
    private data class PlaylistRow(
        @SerialName("source_type") val sourceType: String = WIRE_XTREAM,
        @SerialName("base_url") val baseUrl: String? = null,
        val username: String? = null,
        val password: String? = null,
        val name: String? = null,
        val enabled: Boolean = true,
        @SerialName("sort_order") val sortOrder: Int = 0,
    )

    /** Legacy xtream_accounts row (pre-playlist-manager clients). */
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

    /** [onlyIfEmpty] guards the legacy migration push so a two-device first-login race is a no-op. */
    private suspend fun pushToRemote(profileId: Int, onlyIfEmpty: Boolean = false) {
        runCatching {
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            val accounts = XtreamRepository.uiState.value.accounts
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_playlists", buildJsonArray {
                    accounts.forEachIndexed { index, acc ->
                        addJsonObject {
                            put("source_type", WIRE_XTREAM)
                            put("base_url", acc.baseUrl)
                            put("username", acc.username)
                            put("password", acc.password)
                            put("name", acc.name)
                            put("enabled", acc.enabled)
                            put("sort_order", index)
                        }
                    }
                })
                // Scope the full-replace to xtream only — never delete m3u/stalker/file rows a TV wrote.
                put("p_source_types", buildJsonArray { add(WIRE_XTREAM) })
                if (onlyIfEmpty) put("p_only_if_empty", true)
            }
            SupabaseProvider.client.postgrest.rpc("sync_push_iptv_playlists", params)
            log.d { "pushToRemote — ${accounts.size} accounts (iptv_playlists)" }
        }.onFailure { e -> log.e(e) { "pushToRemote — FAILED" } }
    }

    /**
     * Pull this profile's playlists on login. Reads `iptv_playlists` (xtream rows); when it has none,
     * falls back to legacy `xtream_accounts`, applies it, migrates it up, then clears the legacy rows.
     * Empty everywhere + non-empty local => push local up.
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

            // Emptiness is decided AFTER filtering to xtream rows: a table holding only foreign
            // source types (a TV's m3u/stalker) must behave like an empty remote, not wipe local.
            val xtreamAccounts = rows.filter { it.sourceType == WIRE_XTREAM }.mapNotNull { it.toAccountOrNull() }
            if (xtreamAccounts.isNotEmpty()) {
                applyRemote(profileId, xtreamAccounts)
                log.i { "pullFromServer — applied ${xtreamAccounts.size} accounts (iptv_playlists)" }
                return@runCatching
            }

            // Legacy fallback: rows written by pre-playlist-manager clients.
            val legacy = SupabaseProvider.client.postgrest
                .from("xtream_accounts")
                .select {
                    filter { eq("profile_id", profileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<LegacyRow>()
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            if (legacy.isNotEmpty()) {
                val accounts = legacy.map {
                    XtreamAccount(
                        id = "${it.baseUrl}|${it.username}",
                        name = it.name ?: it.baseUrl,
                        baseUrl = it.baseUrl,
                        username = it.username,
                        password = it.password,
                        enabled = it.enabled,
                    )
                }
                applyRemote(profileId, accounts)
                // One-way migration: copy legacy rows into the new table (only-if-empty), then clear
                // the legacy rows so they can't resurrect deleted playlists on a later login.
                pushToRemote(profileId, onlyIfEmpty = true)
                clearLegacyRemote(profileId)
                log.i { "pullFromServer — migrated ${accounts.size} legacy accounts to iptv_playlists" }
                return@runCatching
            }

            if (XtreamRepository.uiState.value.accounts.isNotEmpty()) {
                log.i { "pullFromServer — remote empty, migrating local playlists up" }
                pushToRemote(profileId)
            }
        }.onFailure { e ->
            isSyncingFromRemote = false
            log.e(e) { "pullFromServer — FAILED" }
        }
    }

    private suspend fun applyRemote(profileId: Int, accounts: List<XtreamAccount>) {
        isSyncingFromRemote = true
        XtreamRepository.applyFromRemote(profileId, accounts)
        isSyncingFromRemote = false
    }

    /** One-shot legacy cleanup after a successful migration push. Best-effort. */
    private suspend fun clearLegacyRemote(profileId: Int) {
        runCatching {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_accounts", buildJsonArray {})
            }
            SupabaseProvider.client.postgrest.rpc("sync_push_xtream_accounts", params)
            log.d { "clearLegacyRemote — cleared xtream_accounts for profile $profileId" }
        }.onFailure { e -> log.e(e) { "clearLegacyRemote — FAILED" } }
    }

    private fun PlaylistRow.toAccountOrNull(): XtreamAccount? {
        val base = baseUrl ?: return null
        val user = username ?: return null
        val pass = password ?: return null
        return XtreamAccount(
            id = "$base|$user",
            name = name ?: base,
            baseUrl = base,
            username = user,
            password = pass,
            enabled = enabled,
        )
    }
}
