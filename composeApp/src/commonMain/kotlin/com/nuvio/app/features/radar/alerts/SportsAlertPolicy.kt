package com.nuvio.app.features.radar.alerts

import com.nuvio.app.core.contracts.PlaybackContext
import com.nuvio.app.features.radar.RevealedGames
import com.nuvio.app.features.radar.ScoreSpoilerSettings

/** Everything the decision reads about this device right now. Built per alert by the controller; pure data. */
data class AlertContext(
    val nowMs: Long,
    /** Device-local minute of day (0..1439), for quiet hours. */
    val localMinuteOfDay: Int,
    val playback: PlaybackContext,
    /** The game whose page is open, if any. */
    val openGameEventId: String?,
    /** The game the live channel now playing carries, known only from an already cached Watch lookup. */
    val watchingEventId: String?,
    val followedLeagueIds: Set<String>,
    val followedTeamIds: Set<String>,
    /** The profile's global Hide scores / delay. */
    val spoiler: ScoreSpoilerSettings,
    /** Games revealed one at a time while scores are hidden (they count as shown). */
    val revealed: RevealedGames,
)

enum class HoldReason(val wire: String) { Delay("delay"), Batch("batch") }

/** Why an alert was not shown. [wire] goes to telemetry. */
enum class DropReason(val wire: String) {
    Disabled("disabled"),
    Duplicate("duplicate"),
    Stale("stale"),
    NotFollowed("not_followed"),
    Muted("muted"),
    KindOff("kind_off"),
    WatchingThisGame("watching_this_game"),
    QuietHours("quiet_hours"),
    Playback("during_playback"),
    ScoresHidden("scores_hidden"),
    HourlyCap("hourly_cap"),
    /** A newer moment of the same game replaced it in a batch. */
    Superseded("superseded"),
}

data class Held(val alert: SportsAlert, val receivedAtMs: Long, val releaseAtMs: Long, val reason: HoldReason)

/** The device's in-memory alert state (never stored, never synced): cap counter, seen keys, held alerts. */
data class AlertLedger(
    val shownAtMs: List<Long> = emptyList(),
    /** Insertion-ordered (oldest first), capped at [SportsAlertPolicy.SEEN_KEYS_MAX]. */
    val seenKeys: Set<String> = emptySet(),
    val pending: List<Held> = emptyList(),
    val lastBannerAtMs: Long? = null,
)

sealed interface AlertDecision {
    data class ShowNow(val banner: AlertBanner) : AlertDecision
    data class HoldUntil(val atMs: Long, val reason: HoldReason) : AlertDecision
    data class Drop(val reason: DropReason) : AlertDecision
}

/** A timer flush. Destructures like the spec's `Pair<AlertBanner?, AlertLedger>`; [dropped] feeds telemetry. */
data class AlertFlush(
    val banner: AlertBanner?,
    val ledger: AlertLedger,
    val dropped: List<Pair<SportsAlert, DropReason>> = emptyList(),
)

/**
 * The one show / hold / drop decision every app runs (spec section 3). Pure: an alert, the settings, the device context
 * and the ledger in; a decision and the next ledger out. Gates, in order, first failing gate wins:
 *  1. Disabled, Duplicate, Stale.
 *  2. NotFollowed; Muted (game off, or every followed route muted); KindOff (the level excludes the kind).
 *     Level = game override, else the team level if a followed team plays, else the league level.
 *  3. WatchingThisGame (page open, or the playing channel is this game's cached Watch channel).
 *  4. QuietHours: drop, never hold (a goal at 23:10 is worthless at 08:00).
 *  5. During playback.
 *  6. Spoilers: hidden drops Score, keeps Start, shows Final without numbers; a delay holds Score and Final.
 *  7. Hourly cap (a batch banner counts once).
 *  8. Batching (leading edge).
 */
object SportsAlertPolicy {
    const val MAX_ALERT_AGE_MS: Long = 5 * 60_000L
    const val SEEN_KEYS_MAX: Int = 200
    const val HOUR_MS: Long = 60 * 60_000L
    const val SUMMARY_LINES: Int = 3

    fun onAlert(
        alert: SportsAlert,
        settings: SportsAlertSettings,
        ctx: AlertContext,
        ledger: AlertLedger,
    ): Pair<AlertDecision, AlertLedger> {
        if (!settings.enabled) return AlertDecision.Drop(DropReason.Disabled) to ledger
        if (alert.key in ledger.seenKeys) return AlertDecision.Drop(DropReason.Duplicate) to ledger
        val seen = ledger.copy(seenKeys = remember(ledger.seenKeys, alert.key))
        if (ctx.nowMs - alert.atMs > MAX_ALERT_AGE_MS) return AlertDecision.Drop(DropReason.Stale) to seen
        followGate(alert, settings, ctx.followedLeagueIds, ctx.followedTeamIds, ctx.nowMs)?.let { return AlertDecision.Drop(it) to seen }
        presenceGate(alert, settings, ctx)?.let { return AlertDecision.Drop(it) to seen }
        val numbers = showsNumbers(alert, ctx)
        if (alert.kind == AlertKind.Score && !numbers) return AlertDecision.Drop(DropReason.ScoresHidden) to seen
        val delayMs = ctx.spoiler.delayMs
        if (numbers && delayMs > 0 && alert.kind != AlertKind.Start) {
            val at = ctx.nowMs + delayMs
            return AlertDecision.HoldUntil(at, HoldReason.Delay) to
                seen.copy(pending = seen.pending + Held(alert, ctx.nowMs, at, HoldReason.Delay))
        }
        if (capReached(settings, ctx.nowMs, seen)) return AlertDecision.Drop(DropReason.HourlyCap) to seen
        batchReleaseAt(settings, ctx.nowMs, seen)?.let { at ->
            return AlertDecision.HoldUntil(at, HoldReason.Batch) to
                seen.copy(pending = seen.pending + Held(alert, ctx.nowMs, at, HoldReason.Batch))
        }
        return AlertDecision.ShowNow(banner(listOf(alert), ctx)) to seen.shown(ctx.nowMs)
    }

    /**
     * The one-shot local timer fired: releases every held alert that is due. Each is re-checked against what may have
     * changed while it waited (settings, follows, the game page now open, quiet hours, playback, Hide scores). A release
     * that lands inside the batch window waits for the window; otherwise the survivors flush as one banner: per game the
     * newest moment (Final beats Score), one game = its banner, several = one summary. The cap counts the banner once.
     */
    fun onTimer(settings: SportsAlertSettings, ctx: AlertContext, ledger: AlertLedger): AlertFlush {
        val (due, later) = ledger.pending.partition { it.releaseAtMs <= ctx.nowMs }
        if (due.isEmpty()) return AlertFlush(null, ledger)
        val dropped = ArrayList<Pair<SportsAlert, DropReason>>()
        val survivors = due.filter { held ->
            val reason = recheck(held.alert, settings, ctx)
            if (reason != null) dropped += held.alert to reason
            reason == null
        }
        val next = ledger.copy(pending = later)
        if (survivors.isEmpty()) return AlertFlush(null, next, dropped)
        batchReleaseAt(settings, ctx.nowMs, next)?.let { at ->
            val rehold = survivors.map { it.copy(releaseAtMs = at, reason = HoldReason.Batch) }
            return AlertFlush(null, next.copy(pending = later + rehold), dropped)
        }
        val kept = supersede(survivors.map { it.alert })
        survivors.map { it.alert }.filter { it !in kept }.forEach { dropped += it to DropReason.Superseded }
        if (capReached(settings, ctx.nowMs, next)) {
            kept.forEach { dropped += it to DropReason.HourlyCap }
            return AlertFlush(null, next, dropped)
        }
        return AlertFlush(banner(kept, ctx), next.shown(ctx.nowMs), dropped)
    }

    /** When the one local timer should fire next; null when nothing is held. No network, ever. */
    fun nextTimerAtMs(ledger: AlertLedger): Long? = ledger.pending.minOfOrNull { it.releaseAtMs }

    // --- gates ---------------------------------------------------------------------------------------------------

    /** Gate 2. Shared with the topic policy's idea of "followed and not muted". */
    internal fun followGate(
        alert: SportsAlert,
        settings: SportsAlertSettings,
        followedLeagueIds: Set<String>,
        followedTeamIds: Set<String>,
        nowMs: Long,
    ): DropReason? {
        val followedTeams = listOfNotNull(alert.homeId, alert.awayId).filter { it in followedTeamIds }
        val leagueFollowed = alert.leagueId != null && alert.leagueId in followedLeagueIds
        if (followedTeams.isEmpty() && !leagueFollowed) return DropReason.NotFollowed
        val level = when (settings.gameOverride(alert.eventId, nowMs)) {
            AlertLevel.Off -> return DropReason.Muted
            AlertLevel.All -> AlertLevel.All
            else -> when {
                followedTeams.any { it !in settings.mutedTeams } -> settings.teamLevel
                leagueFollowed && alert.leagueId !in settings.mutedLeagues -> settings.leagueLevel
                else -> return DropReason.Muted
            }
        }
        return if (level.includes(alert.kind)) null else DropReason.KindOff
    }

    /** Gates 3–5: what the viewer is doing right now. Re-run when a held alert is released. */
    private fun presenceGate(alert: SportsAlert, settings: SportsAlertSettings, ctx: AlertContext): DropReason? {
        if (alert.eventId == ctx.openGameEventId || alert.eventId == ctx.watchingEventId) return DropReason.WatchingThisGame
        if (settings.quietOn && inQuietHours(ctx.localMinuteOfDay, settings.quietStartMin, settings.quietEndMin)) {
            return DropReason.QuietHours
        }
        val blocked = when (settings.duringPlayback) {
            DuringPlayback.Always -> false
            DuringPlayback.LiveTvOnly -> ctx.playback == PlaybackContext.Vod
            DuringPlayback.Never -> ctx.playback != PlaybackContext.None
        }
        return if (blocked) DropReason.Playback else null
    }

    private fun recheck(alert: SportsAlert, settings: SportsAlertSettings, ctx: AlertContext): DropReason? {
        if (!settings.enabled) return DropReason.Disabled
        followGate(alert, settings, ctx.followedLeagueIds, ctx.followedTeamIds, ctx.nowMs)?.let { return it }
        presenceGate(alert, settings, ctx)?.let { return it }
        if (alert.kind == AlertKind.Score && !showsNumbers(alert, ctx)) return DropReason.ScoresHidden
        return null
    }

    /** Start == end means off; start > end crosses midnight (22:00–08:00 covers 23:30 and 07:59, not 08:00). */
    fun inQuietHours(minute: Int, startMin: Int, endMin: Int): Boolean = when {
        startMin == endMin -> false
        startMin < endMin -> minute in startMin until endMin
        else -> minute >= startMin || minute < endMin
    }

    private fun showsNumbers(alert: SportsAlert, ctx: AlertContext): Boolean =
        ctx.spoiler.shown || alert.eventId in ctx.revealed

    private fun capReached(settings: SportsAlertSettings, nowMs: Long, ledger: AlertLedger): Boolean =
        settings.maxPerHour > 0 && ledger.shownAtMs.count { nowMs - it < HOUR_MS } >= settings.maxPerHour

    /**
     * Where a new alert must wait for the batch: inside the window after the last banner, or behind a batch already
     * held. Null = no batch to join, show now (leading edge).
     */
    private fun batchReleaseAt(settings: SportsAlertSettings, nowMs: Long, ledger: AlertLedger): Long? {
        val batchMs = settings.batchSeconds * 1000L
        if (batchMs <= 0L) return null
        ledger.pending.filter { it.reason == HoldReason.Batch }.minOfOrNull { it.releaseAtMs }?.let { return it }
        val last = ledger.lastBannerAtMs ?: return null
        return if (nowMs - last < batchMs) last + batchMs else null
    }

    /** Per game, the newest moment; a Final always beats a Score or Start of the same game. In arrival order. */
    private fun supersede(alerts: List<SportsAlert>): List<SportsAlert> {
        val winners = alerts.groupBy { it.eventId }.values.map { game ->
            game.lastOrNull { it.kind == AlertKind.Final } ?: game.maxBy { it.atMs }
        }.toSet()
        return alerts.filter { it in winners }
    }

    private fun remember(keys: Set<String>, key: String): Set<String> {
        val next = keys + key
        return if (next.size <= SEEN_KEYS_MAX) next else next.drop(next.size - SEEN_KEYS_MAX).toSet()
    }

    private fun AlertLedger.shown(nowMs: Long): AlertLedger =
        copy(shownAtMs = shownAtMs.filter { nowMs - it < HOUR_MS } + nowMs, lastBannerAtMs = nowMs)

    // --- banner text ---------------------------------------------------------------------------------------------

    fun banner(alerts: List<SportsAlert>, ctx: AlertContext): AlertBanner {
        if (alerts.size == 1) {
            val alert = alerts.single()
            return AlertBanner(
                id = alert.key,
                title = headline(alert, showsNumbers(alert, ctx)),
                subtitle = listOfNotNull(alert.league, alert.progress).joinToString(" · ").ifBlank { null },
                lines = emptyList(),
                alerts = alerts,
                action = BannerAction.OpenGame(alert.eventId),
            )
        }
        return AlertBanner(
            id = "batch:" + alerts.joinToString("|") { it.key },
            title = "${alerts.size} sports updates",
            subtitle = null,
            lines = alerts.take(SUMMARY_LINES).map { headline(it, showsNumbers(it, ctx)) },
            alerts = alerts,
            action = BannerAction.OpenSports,
        )
    }

    /** `Arsenal v Chelsea has started` · `Goal · Arsenal 1–0 Chelsea` · `Full time · Arsenal 2–1 Chelsea` (hidden: no numbers). */
    fun headline(alert: SportsAlert, numbers: Boolean): String = when (alert.kind) {
        AlertKind.Start -> "${matchup(alert)} has started"
        AlertKind.Score -> {
            val word = when {
                alert.corrected -> "Score corrected"
                alert.sport in GOAL_SPORTS -> "Goal"
                else -> "Score"
            }
            "$word · ${scoreLine(alert)}"
        }
        AlertKind.Final -> "Full time · ${if (numbers) scoreLine(alert) else matchup(alert)}"
    }

    private fun matchup(alert: SportsAlert): String =
        if (alert.away.isBlank()) alert.home else "${alert.home} v ${alert.away}"

    private fun scoreLine(alert: SportsAlert): String {
        val h = alert.homeScore ?: return matchup(alert)
        val a = alert.awayScore ?: return matchup(alert)
        return if (alert.away.isBlank()) "${alert.home} $h–$a" else "${alert.home} $h–$a ${alert.away}"
    }

    private val GOAL_SPORTS = setOf("soccer", "ice hockey")
}
