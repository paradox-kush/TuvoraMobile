package com.nuvio.app.features.radar

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
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Syncs Sports Centre follows + featured prefs per profile to Supabase, mirroring
 * XtreamAccountSyncService: debounced full-replace push RPC on change; pull = direct
 * RLS-scoped selects on login. Anonymous/local sessions stay device-local.
 */
object RadarSyncService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("RadarSyncService")
    private const val PUSH_DEBOUNCE_MS = 600L

    @Volatile
    var isSyncingFromRemote: Boolean = false
    private var pushJob: Job? = null

    @Serializable
    private data class FollowRow(
        @SerialName("league_id") val leagueId: String,
        val sport: String = "",
        @SerialName("sort_order") val sortOrder: Int = 0,
    )

    @Serializable
    private data class PrefsRow(
        @SerialName("featured_event_id") val featuredEventId: String = "",
        @SerialName("opt_in_state") val optInState: String = RadarOptIn.UNSET,
        @SerialName("promo_dismissed") val promoDismissed: Boolean = false,
    )

    private fun authed(): Boolean {
        val s = AuthRepository.state.value
        return s is AuthState.Authenticated && !s.isAnonymous
    }

    /** Debounced push after a local change (called from RadarRepository.persist()). */
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
            val state = RadarRepository.uiState.value
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_follows", buildJsonArray {
                    state.follows.forEachIndexed { index, follow ->
                        addJsonObject {
                            put("league_id", follow.leagueId)
                            put("sport", follow.sport)
                            put("sort_order", index)
                        }
                    }
                })
                putJsonObject("p_prefs") {
                    put("featured_event_id", state.prefs.featuredEventId)
                    put("opt_in_state", state.prefs.optInState)
                    put("promo_dismissed", state.prefs.promoDismissed)
                }
            }
            SupabaseProvider.client.postgrest.rpc("sync_push_radar", params)
            log.d { "pushToRemote — ${state.follows.size} follows" }
        }.onFailure { e -> log.e(e) { "pushToRemote — FAILED" } }
    }

    /** Pull this profile's follows+prefs on login. Empty remote + non-empty local => migrate up. */
    suspend fun pullFromServer(profileId: Int) {
        if (!authed() || ProfileRepository.activeProfileId != profileId) return
        runCatching {
            val followRows = SupabaseProvider.client.postgrest
                .from("radar_follows")
                .select {
                    filter { eq("profile_id", profileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<FollowRow>()
            val prefsRow = SupabaseProvider.client.postgrest
                .from("radar_prefs")
                .select { filter { eq("profile_id", profileId) } }
                .decodeList<PrefsRow>()
                .firstOrNull()
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            if (followRows.isEmpty() && prefsRow == null) {
                val local = RadarRepository.uiState.value
                if (local.follows.isNotEmpty() || local.prefs != RadarPrefs()) {
                    log.i { "pullFromServer — remote empty, migrating local radar state up" }
                    pushToRemote(profileId)
                }
                return@runCatching
            }
            isSyncingFromRemote = true
            RadarRepository.applyFromRemote(
                profileId = profileId,
                follows = followRows.map { RadarFollow(it.leagueId, it.sport, it.sortOrder) },
                prefs = prefsRow?.let { RadarPrefs(it.featuredEventId, it.optInState, it.promoDismissed) },
            )
            isSyncingFromRemote = false
            log.i { "pullFromServer — applied ${followRows.size} follows" }
        }.onFailure { e ->
            isSyncingFromRemote = false
            log.e(e) { "pullFromServer — FAILED" }
        }
    }
}
