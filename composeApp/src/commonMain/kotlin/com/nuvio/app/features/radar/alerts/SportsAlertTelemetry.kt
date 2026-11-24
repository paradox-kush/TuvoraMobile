package com.nuvio.app.features.radar.alerts

import com.nuvio.app.core.telemetry.api.TelemetryAccess

/**
 * `sports_alert` (review round 1, same on every platform). Schema: `outcome`, `reason`, `kind`, `sport` (topic slug or
 * `other`), `batch`, `action`. NEVER a team, league or event id, a name or a score.
 *  - Sent: `shown`, and `action` (open_game / watch / open_sports / dismissed).
 *  - Count only (the send policy folds them into session_health): `held`, and drops for [COUNTED_DROPS].
 *  - Never emitted: every other drop (not_followed, duplicate, stale, disabled, muted, kind_off): they are the normal
 *    case of a per-sport broadcast and would be noise.
 */
internal object SportsAlertTelemetry {
    const val EVENT = "sports_alert"

    val COUNTED_DROPS: Set<DropReason> = setOf(
        DropReason.WatchingThisGame, DropReason.QuietHours, DropReason.Playback, DropReason.ScoresHidden,
        DropReason.HourlyCap, DropReason.Superseded, DropReason.WatchUnknown,
    )

    fun props(
        outcome: String,
        kind: AlertKind?,
        sport: String?,
        batch: Int = 1,
        reason: String? = null,
        action: String? = null,
    ): Map<String, Any> = buildMap {
        put("outcome", outcome)
        reason?.let { put("reason", it) }
        kind?.let { put("kind", it.wire) }
        put("sport", SportsAlertTopicPolicy.slug(sport) ?: "other")
        put("batch", batch)
        action?.let { put("action", it) }
    }

    fun held(alert: SportsAlert, reason: HoldReason): Map<String, Any> =
        props("held", alert.kind, alert.sport, reason = reason.wire)

    /** Null for a drop that is never emitted. */
    fun dropped(alert: SportsAlert, reason: DropReason): Map<String, Any>? =
        if (reason in COUNTED_DROPS) props("dropped", alert.kind, alert.sport, reason = reason.wire) else null

    fun shown(banner: AlertBanner): Map<String, Any> = props(
        "shown",
        kind = if (banner.isSummary) null else banner.alerts.firstOrNull()?.kind,
        sport = commonSport(banner),
        batch = banner.alerts.size,
    )

    /** [action]: `open_game` | `watch` | `open_sports` | `dismissed`. */
    fun action(banner: AlertBanner, action: String): Map<String, Any> = props(
        "action",
        kind = if (banner.isSummary) null else banner.alerts.firstOrNull()?.kind,
        sport = commonSport(banner),
        batch = banner.alerts.size,
        action = action,
    )

    private fun commonSport(banner: AlertBanner): String? {
        val first = banner.alerts.firstOrNull()?.sport
        return if (banner.alerts.all { it.sport == first }) first else null
    }

    fun send(props: Map<String, Any>) {
        runCatching { TelemetryAccess.current().emit(EVENT, props) }
    }
}
