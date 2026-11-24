package com.nuvio.app.features.radar.alerts

import com.nuvio.app.core.telemetry.api.TelemetryAccess

/**
 * `sports_alert` — one event per decision (outcome shown / held / dropped, with the drop or hold reason) and one per
 * banner action (outcome `acted`). Props: outcome, reason, kind, sport (topic slug or `other`), batch_size, action.
 * NEVER a team, league or event id, a name or a score.
 */
internal object SportsAlertTelemetry {
    const val EVENT = "sports_alert"

    fun props(
        outcome: String,
        kind: AlertKind?,
        sport: String?,
        batchSize: Int = 1,
        reason: String? = null,
        action: String? = null,
    ): Map<String, Any> = buildMap {
        put("outcome", outcome)
        reason?.let { put("reason", it) }
        kind?.let { put("kind", it.wire) }
        put("sport", SportsAlertTopicPolicy.slug(sport) ?: "other")
        put("batch_size", batchSize)
        action?.let { put("action", it) }
    }

    fun decision(decision: AlertDecision, alert: SportsAlert): Map<String, Any> = when (decision) {
        is AlertDecision.ShowNow -> props("shown", alert.kind, alert.sport, batchSize = 1)
        is AlertDecision.HoldUntil -> props("held", alert.kind, alert.sport, reason = decision.reason.wire)
        is AlertDecision.Drop -> props("dropped", alert.kind, alert.sport, reason = decision.reason.wire)
    }

    fun shown(banner: AlertBanner): Map<String, Any> {
        val first = banner.alerts.firstOrNull()
        val sameSport = banner.alerts.all { it.sport == first?.sport }
        return props(
            "shown",
            kind = if (banner.isSummary) null else first?.kind,
            sport = if (sameSport) first?.sport else null,
            batchSize = banner.alerts.size,
        )
    }

    fun dropped(alert: SportsAlert, reason: DropReason): Map<String, Any> =
        props("dropped", alert.kind, alert.sport, reason = reason.wire)

    /** [action]: `open_game` | `watch` | `open_sports` | `dismissed`. */
    fun acted(banner: AlertBanner, action: String): Map<String, Any> {
        val first = banner.alerts.firstOrNull()
        return props(
            "acted",
            kind = if (banner.isSummary) null else first?.kind,
            sport = if (banner.alerts.all { it.sport == first?.sport }) first?.sport else null,
            batchSize = banner.alerts.size,
            action = action,
        )
    }

    fun send(props: Map<String, Any>) {
        runCatching { TelemetryAccess.current().emit(EVENT, props) }
    }
}
