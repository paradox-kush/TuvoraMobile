package com.nuvio.app.features.radar.alerts

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * In-app sports alerts (research/sports-alerts-spec-2026-11-24.md). The backend broadcasts one small public payload per
 * moment on a per-sport Realtime topic; every open app decides on its own what to show with [SportsAlertPolicy].
 * Nothing here knows about the user beyond the settings and follows passed in.
 */

/** The moment an alert reports. Wire values match the backend payload's `kind`. */
enum class AlertKind(val wire: String) {
    Start("start"),
    Score("score"),
    Final("final"),
    ;

    companion object {
        fun fromWire(value: String?): AlertKind? = entries.firstOrNull { it.wire == value }
    }
}

/** How much of a team's / league's / game's moments to show. Wire values are the `alerts` JSON's. */
enum class AlertLevel(val wire: String) {
    All("all"),
    StartAndFinal("start_final"),
    FinalOnly("final"),
    Off("off"),
    ;

    fun includes(kind: AlertKind): Boolean = when (this) {
        All -> true
        StartAndFinal -> kind != AlertKind.Score
        FinalOnly -> kind == AlertKind.Final
        Off -> false
    }

    companion object {
        fun fromWire(value: String?): AlertLevel? = entries.firstOrNull { it.wire == value }
    }
}

/** Whether a banner may appear while something plays. Wire values are the `alerts` JSON's `during_playback`. */
enum class DuringPlayback(val wire: String) {
    Always("always"),
    LiveTvOnly("live"),
    Never("never"),
    ;

    companion object {
        fun fromWire(value: String?): DuringPlayback? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One moment, as broadcast (payload `v:1`): `{v, key, eventId, kind, sport, leagueId, league, homeId, awayId, home, away,
 * homeScore, awayScore, progress, corrected, at}`. [sport] is normalised (lower case, `_` → space: "ice hockey").
 */
data class SportsAlert(
    val key: String,
    val eventId: String,
    val kind: AlertKind,
    val sport: String,
    val leagueId: String?,
    val league: String?,
    val homeId: String?,
    val awayId: String?,
    val home: String,
    val away: String,
    val homeScore: Int?,
    val awayScore: Int?,
    val progress: String?,
    val corrected: Boolean,
    val atMs: Long,
) {
    companion object {
        const val PAYLOAD_VERSION = 1

        /** Tolerant: a payload of another version, or without key / event / kind / home / time, is ignored (null). */
        fun parse(payload: JsonObject): SportsAlert? {
            if (payload.int("v") != PAYLOAD_VERSION) return null
            val key = payload.text("key") ?: return null
            val eventId = payload.text("eventId") ?: return null
            val kind = AlertKind.fromWire(payload.text("kind")) ?: return null
            val home = payload.text("home") ?: return null
            val at = payload.long("at") ?: return null
            return SportsAlert(
                key = key,
                eventId = eventId,
                kind = kind,
                sport = normalizeSport(payload.text("sport")),
                leagueId = payload.text("leagueId"),
                league = payload.text("league"),
                homeId = payload.text("homeId"),
                awayId = payload.text("awayId"),
                home = home,
                away = payload.text("away").orEmpty(),
                homeScore = payload.int("homeScore"),
                awayScore = payload.int("awayScore"),
                progress = payload.text("progress"),
                corrected = (payload["corrected"] as? JsonPrimitive)?.booleanOrNull ?: false,
                atMs = at,
            )
        }

        fun normalizeSport(raw: String?): String = raw.orEmpty().trim().lowercase().replace('_', ' ')
    }
}

private fun JsonObject.text(name: String): String? =
    // JsonNull is a JsonPrimitive whose contentOrNull is null.
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

private fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.trim()?.toLongOrNull() }

/** What tapping / holding a banner does. */
sealed interface BannerAction {
    val label: String

    /** Opens the game's page (Sports hub). Shown at once on a single-game banner. */
    data class OpenGame(val eventId: String) : BannerAction {
        override val label: String get() = "Open game"
    }

    /** The matched channel, once the Watch lookup already running for this banner returns Found. */
    data class Watch(val eventId: String, val channelId: String, val channelName: String) : BannerAction {
        override val label: String get() = "Watch"
    }

    /** A summary banner: the Sports tab. */
    data object OpenSports : BannerAction {
        override val label: String get() = "Open Sports"
    }
}

/**
 * What the banner shows. A single game has [title] + [subtitle] (league · progress); a summary of several games has
 * "N sports updates" + up to 3 [lines]. [alerts] holds every alert it carries (one per game).
 */
data class AlertBanner(
    val id: String,
    val title: String,
    val subtitle: String?,
    val lines: List<String>,
    val alerts: List<SportsAlert>,
    val action: BannerAction,
) {
    val isSummary: Boolean get() = alerts.size > 1
}
