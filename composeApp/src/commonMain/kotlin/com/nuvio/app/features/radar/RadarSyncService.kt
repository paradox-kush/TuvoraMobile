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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.nuvio.app.features.radar.alerts.SportsAlertSettings

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
        // Present only for leagues the user added themselves — see RadarFollow.
        val name: String? = null,
        val badge: String? = null,
        val banner: String? = null,
        val keywords: List<String>? = null,
        val custom: Boolean = false,
    )

    /** A followed club. Every column is populated — there is no team catalog to defer to. */
    @Serializable
    private data class TeamFollowRow(
        @SerialName("team_id") val teamId: String,
        val name: String = "",
        val sport: String = "",
        val badge: String? = null,
        @SerialName("league_id") val leagueId: String? = null,
        val league: String? = null,
        val keywords: List<String>? = null,
        @SerialName("sort_order") val sortOrder: Int = 0,
    )

    private fun authed(): Boolean {
        val s = AuthRepository.state.value
        return s is AuthState.Authenticated && !s.isAnonymous
    }

    /** Debounced push after a local change (called from RadarRepository.persist()). */
    fun triggerPush() {
        pushJob?.cancel()
        // Snapshot profile AND state NOW — reading uiState after the debounce races
        // onProfileChanged's state reset, and a full-replace push of the reset (empty)
        // state under the old profile id would wipe that profile's remote follows.
        val profileId = ProfileRepository.activeProfileId
        val snapshot = RadarRepository.uiState.value
        pushJob = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            if (ProfileRepository.activeProfileId != profileId) return@launch
            // Dropped during a pull or offline: spoilerDirty / alertsDirty stay set and the next pull pushes them (RadarPrefsPull.pushAfterPull).
            if (isSyncingFromRemote || !authed()) return@launch
            pushToRemote(profileId, snapshot)
        }
    }

    private suspend fun pushToRemote(
        profileId: Int,
        state: RadarUiState = RadarRepository.uiState.value,
    ) {
        runCatching {
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_follows", buildJsonArray {
                    state.follows.forEachIndexed { index, follow ->
                        addJsonObject {
                            put("league_id", follow.leagueId)
                            put("sport", follow.sport)
                            put("sort_order", index)
                            if (follow.custom) {
                                put("custom", true)
                                follow.name?.let { put("name", it) }
                                follow.badge?.let { put("badge", it) }
                                follow.banner?.let { put("banner", it) }
                                if (follow.keywords.isNotEmpty()) {
                                    put("keywords", buildJsonArray { follow.keywords.forEach { add(it) } })
                                }
                            }
                        }
                    }
                })
                put("p_prefs", radarPrefsPayload(state.prefs))
                // Always sent, even when empty: the RPC reads a MISSING p_teams as "this
                // client has nothing to say about teams" and leaves the remote rows alone, so
                // omitting it here would make unfollowing your last club un-syncable.
                put("p_teams", buildJsonArray {
                    state.teamFollows.forEachIndexed { index, team ->
                        addJsonObject {
                            put("team_id", team.teamId)
                            put("name", team.name)
                            put("sport", team.sport)
                            put("sort_order", index)
                            team.badge?.let { put("badge", it) }
                            team.leagueId?.let { put("league_id", it) }
                            team.league?.let { put("league", it) }
                            if (team.keywords.isNotEmpty()) {
                                put("keywords", buildJsonArray { team.keywords.forEach { add(it) } })
                            }
                        }
                    }
                })
            }
            SupabaseProvider.client.postgrest.rpc("sync_push_radar", params)
            // Clears spoilerDirty / alertsDirty only if the local values still equal what this push carried (RadarPrefsPull.afterPush).
            RadarRepository.onPrefsPushed(profileId, state.prefs)
            log.d { "pushToRemote — ${state.follows.size} follows, ${state.teamFollows.size} teams" }
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
            val teamRows = SupabaseProvider.client.postgrest
                .from("radar_team_follows")
                .select {
                    filter { eq("profile_id", profileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<TeamFollowRow>()
            val prefsRow = SupabaseProvider.client.postgrest
                .from("radar_prefs")
                .select { filter { eq("profile_id", profileId) } }
                .decodeList<RadarPrefsRow>()
                .firstOrNull()
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            if (followRows.isEmpty() && teamRows.isEmpty() && prefsRow == null) {
                val local = RadarRepository.uiState.value
                if (local.follows.isNotEmpty() || local.teamFollows.isNotEmpty() || local.prefs != RadarPrefs()) {
                    log.i { "pullFromServer — remote empty, migrating local radar state up" }
                    pushToRemote(profileId)
                }
                return@runCatching
            }
            isSyncingFromRemote = true
            RadarRepository.applyFromRemote(
                profileId = profileId,
                follows = followRows.map {
                    RadarFollow(
                        leagueId = it.leagueId,
                        sport = it.sport,
                        sortOrder = it.sortOrder,
                        name = it.name,
                        badge = it.badge,
                        banner = it.banner,
                        keywords = it.keywords.orEmpty(),
                        custom = it.custom,
                    )
                },
                prefs = prefsRow,
                teams = teamRows.map {
                    RadarTeamFollow(
                        teamId = it.teamId,
                        name = it.name,
                        sport = it.sport,
                        badge = it.badge,
                        leagueId = it.leagueId,
                        league = it.league,
                        keywords = it.keywords.orEmpty(),
                        sortOrder = it.sortOrder,
                    )
                },
            )
            isSyncingFromRemote = false
            // Covers a push that failed, ran offline, was dropped during this pull, or died with the app.
            if (RadarPrefsPull.pushAfterPull(RadarRepository.uiState.value.prefs)) triggerPush()
            log.i { "pullFromServer — applied ${followRows.size} follows, ${teamRows.size} teams" }
        }.onFailure { e ->
            isSyncingFromRemote = false
            log.e(e) { "pullFromServer — FAILED" }
        }
    }
}

/**
 * sync_push_radar's `p_prefs`. The delay is sanitized: the backend's check accepts only 0 / 15 / 30 / 60. `alerts` is
 * sent only while [RadarPrefs.alertsDirty] (review round 1): the backend keeps its stored value when the key is absent,
 * so a device that merely pushes follows can never overwrite another device's newer alert settings.
 */
internal fun radarPrefsPayload(prefs: RadarPrefs): JsonObject = buildJsonObject {
    put("featured_event_id", prefs.featuredEventId)
    put("opt_in_state", prefs.optInState)
    put("promo_dismissed", prefs.promoDismissed)
    put("scores_shown", prefs.scoresShown)
    put("score_delay_s", ScoreSpoilerSettings.sanitizeDelay(prefs.scoreDelaySeconds))
    if (prefs.alertsDirty) put("alerts", SportsAlertSettings.toJson(prefs.alerts))
}

/** One radar_prefs row as pulled. */
@Serializable
internal data class RadarPrefsRow(
    @SerialName("featured_event_id") val featuredEventId: String = "",
    @SerialName("opt_in_state") val optInState: String = RadarOptIn.UNSET,
    @SerialName("promo_dismissed") val promoDismissed: Boolean = false,
    // Columns added 2026-11-23. Null = the row predates them (or an older backend omits them): the LOCAL value stays —
    // a default here would silently reset a device's Hide scores / delay on every pull.
    @SerialName("scores_shown") val scoresShown: Boolean? = null,
    @SerialName("score_delay_s") val scoreDelaySeconds: Int? = null,
    // Column added for sports alerts. Null (or not an object) = the row predates it: the LOCAL alerts stay.
    @SerialName("alerts") val alerts: JsonElement? = null,
)

/**
 * Pure decisions for Hide scores / delay and sports-alerts sync. The pending flags are [RadarPrefs.spoilerDirty] and
 * [RadarPrefs.alertsDirty], persisted in each profile's local blob, so they are per profile and survive a restart; no
 * counters, no global state. The two flags are independent: an unpushed alerts change never holds back a pulled
 * Hide scores value, and the other way round.
 */
internal object RadarPrefsPull {
    /**
     * A pulled row onto this profile's [local] prefs. Its featured / opt-in / promo columns always apply. While
     * [RadarPrefs.spoilerDirty] the local Hide scores / delay stay (an unpushed change); otherwise the row's values
     * apply, and a column the row lacks (pre-migration row, older backend) keeps the local value.
     */
    fun merge(local: RadarPrefs, remote: RadarPrefsRow): RadarPrefs {
        val dirty = local.spoilerDirty
        return RadarPrefs(
            featuredEventId = remote.featuredEventId,
            optInState = remote.optInState,
            promoDismissed = remote.promoDismissed,
            scoresShown = if (dirty) local.scoresShown else remote.scoresShown ?: local.scoresShown,
            scoreDelaySeconds = if (dirty) local.scoreDelaySeconds
            else remote.scoreDelaySeconds?.let(ScoreSpoilerSettings::sanitizeDelay) ?: local.scoreDelaySeconds,
            spoilerDirty = dirty,
            // While alertsDirty the local object stays; otherwise the row's object applies (read tolerantly), and a row
            // without one (null column, older backend, not an object) keeps the local value.
            alerts = if (local.alertsDirty) local.alerts
            else (remote.alerts as? JsonObject)?.let(SportsAlertSettings::fromJson) ?: local.alerts,
            alertsDirty = local.alertsDirty,
        )
    }

    /** A push carrying [pushed] succeeded: clean only if [current] still holds the same values (a later toggle stays dirty). */
    fun afterPush(current: RadarPrefs, pushed: RadarPrefs): RadarPrefs {
        val spoilerSame = current.scoresShown == pushed.scoresShown &&
            ScoreSpoilerSettings.sanitizeDelay(current.scoreDelaySeconds) == ScoreSpoilerSettings.sanitizeDelay(pushed.scoreDelaySeconds)
        val alertsSame = current.alerts == pushed.alerts
        val clearSpoiler = current.spoilerDirty && spoilerSame
        // Only a push whose snapshot carried `alerts` (it was dirty, so the key was sent) can confirm them.
        val clearAlerts = current.alertsDirty && pushed.alertsDirty && alertsSame
        if (!clearSpoiler && !clearAlerts) return current
        return current.copy(
            spoilerDirty = current.spoilerDirty && !clearSpoiler,
            alertsDirty = current.alertsDirty && !clearAlerts,
        )
    }

    /** After every pull: a change still dirty (failed / offline / dropped push, or a restart) goes out now. */
    fun pushAfterPull(prefs: RadarPrefs): Boolean = prefs.spoilerDirty || prefs.alertsDirty

    /**
     * The local prefs a pull for [pullProfileId] merges onto: what is on screen when that profile is the shown one,
     * else that profile's own stored blob — never another profile's (dirty) state.
     */
    fun localFor(pullProfileId: Int, currentProfileId: Int, current: RadarPrefs, stored: () -> RadarPrefs): RadarPrefs =
        if (pullProfileId == currentProfileId) current else stored()
}
