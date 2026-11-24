package com.nuvio.app.features.radar.alerts

import com.nuvio.app.core.contracts.PlaybackContext
import com.nuvio.app.features.radar.RevealedGames
import com.nuvio.app.features.radar.ScoreSpoilerSettings

/**
 * What this device knows about the channel that carries a game, from the Watch lookup (cached per game, 15 min).
 * Unknown = never asked, still asking, or the lookup failed.
 */
sealed interface WatchState {
    data object Unknown : WatchState
    /** Every channel the matcher thinks carries the game, any confidence (not only the Watch button's). */
    data class Channel(val channelIds: Set<String>) : WatchState
    /** Asked and answered: no playlist, or no channel lists this game. */
    data object NoChannel : WatchState
}

/** Everything the decision reads about this device right now. Built by the controller; pure data. */
data class AlertContext(
    val nowMs: Long,
    /** Device-local minute of day (0..1439), for quiet hours. */
    val localMinuteOfDay: Int,
    val playback: PlaybackContext,
    /** The game whose page is open, if any. */
    val openGameEventId: String?,
    /** Every live channel playing right now (multiview: every tile). */
    val playingChannelIds: Set<String>,
    /** Per game, what the Watch lookup knows; a game missing here is [WatchState.Unknown]. */
    val watch: Map<String, WatchState>,
    val followedLeagueIds: Set<String>,
    val followedTeamIds: Set<String>,
    /** The profile's global Hide scores / delay. */
    val spoiler: ScoreSpoilerSettings,
    /** Games revealed one at a time while scores are hidden (they count as shown). */
    val revealed: RevealedGames,
) {
    fun watchOf(eventId: String): WatchState = watch[eventId] ?: WatchState.Unknown
}

enum class HoldReason(val wire: String) { Delay("delay"), Batch("batch"), WatchCheck("watch_check") }

/** Why an alert was not shown. [wire] goes to telemetry (only the ones [SportsAlertTelemetry] allows). */
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
    /** Live TV was playing and the game's channel was still unknown at the watch-check deadline: a score could spoil it. */
    WatchUnknown("watch_unknown"),
}

data class Held(
    val alert: SportsAlert,
    val receivedAtMs: Long,
    val releaseAtMs: Long,
    val reason: HoldReason,
    /** When this alert's watch check started (its 6 s run from here), or null if it never needed one. */
    val watchCheckAtMs: Long? = null,
)

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
    /** Alerts held again by this flush (watch check / delay / batch), for telemetry. */
    val held: List<Pair<SportsAlert, HoldReason>> = emptyList(),
)

/**
 * The one show / hold / drop decision every app runs (spec section 3 + review round 1). Pure: an alert, the settings,
 * the device context and the ledger in; a decision and the next ledger out. Gates, in order, first failing gate wins:
 *  1. Disabled, Duplicate, Stale (judged by receive time when `at` is more than a minute in the future: clock skew).
 *  2. NotFollowed; Muted (game off, or every followed route muted; a mute beats a per-game All); KindOff.
 *     Level = game override, else the team level if a followed team plays, else the league level.
 *  3. WatchingThisGame: the page is open, or the game's known channel is among those playing. Unknown channel while
 *     live TV plays something: hold [WATCH_CHECK_MS] for the lookup ([HoldReason.WatchCheck], Score / Final only).
 *  4. QuietHours: drop, never hold.  5. During playback.
 *  6. Spoilers: hidden drops Score, keeps Start, shows Final without numbers; a delay holds Score and Final.
 *     Still Unknown at the watch-check deadline: Score dropped (WatchUnknown), Final without numbers.
 *  7. Hourly cap (a batch banner counts once).  8. Batching (leading edge).
 * A held alert is re-evaluated from gate 2 when released, so each hold re-runs what may have changed meanwhile.
 */
object SportsAlertPolicy {
    const val MAX_ALERT_AGE_MS: Long = 5 * 60_000L
    /** `at` further ahead of the device clock than this is clock skew: the receive time stands in for it. */
    const val CLOCK_SKEW_ALLOWANCE_MS: Long = 60_000L
    const val WATCH_CHECK_MS: Long = 6_000L
    /** A held alert released later than this (a suspended timer, a sleeping device) is old news: dropped as stale. */
    const val RELEASE_GRACE_MS: Long = 60_000L
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
        if (isStale(alert, ctx.nowMs)) return AlertDecision.Drop(DropReason.Stale) to seen
        return when (val step = evaluate(alert, settings, ctx, receivedAtMs = ctx.nowMs, watchCheckAtMs = null)) {
            is Step.Drop -> AlertDecision.Drop(step.reason) to seen
            is Step.Hold -> AlertDecision.HoldUntil(step.atMs, step.reason) to
                seen.copy(pending = seen.pending + Held(alert, ctx.nowMs, step.atMs, step.reason, step.watchCheckAtMs))
            is Step.Ready -> {
                if (capReached(settings, ctx.nowMs, seen)) return AlertDecision.Drop(DropReason.HourlyCap) to seen
                batchReleaseAt(settings, ctx.nowMs, seen)?.let { at ->
                    return AlertDecision.HoldUntil(at, HoldReason.Batch) to
                        seen.copy(pending = seen.pending + Held(alert, ctx.nowMs, at, HoldReason.Batch))
                }
                AlertDecision.ShowNow(banner(listOf(alert to step.numbers))) to seen.shown(ctx.nowMs)
            }
        }
    }

    /**
     * The one-shot local timer fired (or a watch lookup answered, see [onWatchResolved]): every held alert that is due
     * is evaluated again. Still waiting → held again; ready → flushed together: per game the newest moment (Final beats
     * Score), one game = its banner, several = one summary; a flush inside the batch window waits for the window.
     */
    fun onTimer(settings: SportsAlertSettings, ctx: AlertContext, ledger: AlertLedger): AlertFlush {
        val (due, later) = ledger.pending.partition { it.releaseAtMs <= ctx.nowMs }
        if (due.isEmpty()) return AlertFlush(null, ledger)
        val dropped = ArrayList<Pair<SportsAlert, DropReason>>()
        val reheld = ArrayList<Held>()
        val ready = ArrayList<Pair<Held, Boolean>>()
        for (held in due) {
            if (expired(held, ctx)) {
                dropped += held.alert to DropReason.Stale
                continue
            }
            when (val step = evaluate(held.alert, settings, ctx, held.receivedAtMs, held.watchCheckAtMs)) {
                is Step.Drop -> dropped += held.alert to step.reason
                is Step.Hold -> reheld += held.copy(releaseAtMs = step.atMs, reason = step.reason, watchCheckAtMs = step.watchCheckAtMs)
                is Step.Ready -> ready += held to step.numbers
            }
        }
        val next = ledger.copy(pending = later + reheld)
        val heldOut = reheld.map { it.alert to it.reason }
        if (ready.isEmpty()) return AlertFlush(null, next, dropped, heldOut)
        batchReleaseAt(settings, ctx.nowMs, next)?.let { at ->
            val batch = ready.map { (h, _) -> h.copy(releaseAtMs = at, reason = HoldReason.Batch) }
            return AlertFlush(null, next.copy(pending = next.pending + batch), dropped, heldOut + batch.map { it.alert to it.reason })
        }
        val kept = supersede(ready.map { (h, numbers) -> h.alert to numbers })
        ready.map { it.first.alert }.filter { a -> kept.none { it.first == a } }.forEach { dropped += it to DropReason.Superseded }
        if (capReached(settings, ctx.nowMs, next)) {
            kept.forEach { dropped += it.first to DropReason.HourlyCap }
            return AlertFlush(null, next, dropped, heldOut)
        }
        return AlertFlush(banner(kept), next.shown(ctx.nowMs), dropped, heldOut)
    }

    /** The watch lookup for [eventId] answered: its watch-check holds are due now (the caller then runs [onTimer]). */
    fun onWatchResolved(eventId: String, ledger: AlertLedger, nowMs: Long): AlertLedger = ledger.copy(
        pending = ledger.pending.map {
            if (it.reason == HoldReason.WatchCheck && it.alert.eventId == eventId) it.copy(releaseAtMs = nowMs) else it
        },
    )

    /** When the one local timer should fire next; null when nothing is held. No network, ever. */
    fun nextTimerAtMs(ledger: AlertLedger): Long? = ledger.pending.minOfOrNull { it.releaseAtMs }

    /** Going to the background: nothing held survives (spec review round 2). Seen keys and the cap counter stay. */
    fun clearHeld(ledger: AlertLedger): AlertLedger = ledger.copy(pending = emptyList())

    /** Games whose channel a watch-check hold is waiting on (the controller looks them up). */
    fun watchChecks(ledger: AlertLedger): Set<String> =
        ledger.pending.filter { it.reason == HoldReason.WatchCheck }.map { it.alert.eventId }.toSet()

    // --- the gates ---------------------------------------------------------------------------------------------

    private sealed interface Step {
        data class Drop(val reason: DropReason) : Step
        data class Hold(val atMs: Long, val reason: HoldReason, val watchCheckAtMs: Long?) : Step
        data class Ready(val numbers: Boolean) : Step
    }

    /** Released over a minute late, or older than max age + the score delay: old news (dropped, never emitted). */
    private fun expired(held: Held, ctx: AlertContext): Boolean =
        ctx.nowMs - held.releaseAtMs > RELEASE_GRACE_MS ||
            ctx.nowMs - held.receivedAtMs > MAX_ALERT_AGE_MS + ctx.spoiler.delayMs

    /**
     * Gates 1 (disabled) to 6 for an alert received at [receivedAtMs], whose watch check (if any) started at
     * [watchCheckAtMs]. Time-based, so a re-run after any hold is exact. Parity rules (review round 2): a hold released
     * over an unknown live channel starts its own watch check; the score delay always counts from receipt; a full time
     * downgraded at the watch deadline still waits out the delay.
     */
    private fun evaluate(
        alert: SportsAlert,
        settings: SportsAlertSettings,
        ctx: AlertContext,
        receivedAtMs: Long,
        watchCheckAtMs: Long?,
    ): Step {
        if (!settings.enabled) return Step.Drop(DropReason.Disabled)
        followGate(alert, settings, ctx.followedLeagueIds, ctx.followedTeamIds, ctx.nowMs)?.let { return Step.Drop(it) }
        // Gate 3, the definite part.
        if (alert.eventId == ctx.openGameEventId) return Step.Drop(DropReason.WatchingThisGame)
        val watch = ctx.watchOf(alert.eventId)
        if (watch is WatchState.Channel && watch.channelIds.any { it in ctx.playingChannelIds }) return Step.Drop(DropReason.WatchingThisGame)
        // Gates 4-5 before any wait: an alert they drop never costs a lookup.
        if (settings.quietOn && inQuietHours(ctx.localMinuteOfDay, settings.quietStartMin, settings.quietEndMin)) {
            return Step.Drop(DropReason.QuietHours)
        }
        val playbackBlocked = when (settings.duringPlayback) {
            DuringPlayback.Always -> false
            DuringPlayback.LiveTvOnly -> ctx.playback == PlaybackContext.Vod
            DuringPlayback.Never -> ctx.playback != PlaybackContext.None
        }
        if (playbackBlocked) return Step.Drop(DropReason.Playback)
        val spoilerShows = showsNumbers(alert, ctx)
        if (alert.kind == AlertKind.Score && !spoilerShows) return Step.Drop(DropReason.ScoresHidden)
        var numbers = spoilerShows
        // Gate 3, the uncertain part: live TV is playing a channel and we do not know whether it carries this game.
        val mayBeWatching = watch == WatchState.Unknown && ctx.playback == PlaybackContext.LiveTv &&
            ctx.playingChannelIds.isNotEmpty() && alert.kind != AlertKind.Start
        if (mayBeWatching) {
            // Never drop for "unknown" without asking: a check starts now unless this alert already has one running.
            val started = watchCheckAtMs ?: return Step.Hold(ctx.nowMs + WATCH_CHECK_MS, HoldReason.WatchCheck, ctx.nowMs)
            val deadline = started + WATCH_CHECK_MS
            if (ctx.nowMs < deadline) return Step.Hold(deadline, HoldReason.WatchCheck, started)
            if (alert.kind == AlertKind.Score) return Step.Drop(DropReason.WatchUnknown)
            numbers = false
        }
        // The delay counts from receipt and applies whenever the spoiler setting shows numbers, even to a full time
        // shown without them at the watch deadline.
        val delayMs = ctx.spoiler.delayMs
        if (spoilerShows && delayMs > 0 && alert.kind != AlertKind.Start && ctx.nowMs < receivedAtMs + delayMs) {
            return Step.Hold(receivedAtMs + delayMs, HoldReason.Delay, watchCheckAtMs)
        }
        return Step.Ready(numbers)
    }

    /** Gate 2. A mute (team or league) wins over a per-game All; a per-game Off always mutes. */
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
        val override = settings.gameOverride(alert.eventId, nowMs)
        if (override == AlertLevel.Off) return DropReason.Muted
        // A mute removes its route. With no route left the game is muted, whatever this game's own All says.
        val teamActive = followedTeams.any { it !in settings.mutedTeams }
        val leagueActive = leagueFollowed && alert.leagueId !in settings.mutedLeagues
        if (!teamActive && !leagueActive) return DropReason.Muted
        if (override == AlertLevel.All) return null
        val level = if (teamActive) settings.teamLevel else settings.leagueLevel
        return if (level.includes(alert.kind)) null else DropReason.KindOff
    }

    /** Start == end means off; start > end crosses midnight (22:00–08:00 covers 23:30 and 07:59, not 08:00). */
    fun inQuietHours(minute: Int, startMin: Int, endMin: Int): Boolean = when {
        startMin == endMin -> false
        startMin < endMin -> minute in startMin until endMin
        else -> minute >= startMin || minute < endMin
    }

    /** Older than [MAX_ALERT_AGE_MS]; an `at` beyond the skew allowance in the future is judged by receive time. */
    private fun isStale(alert: SportsAlert, nowMs: Long): Boolean {
        val at = if (alert.atMs - nowMs > CLOCK_SKEW_ALLOWANCE_MS) nowMs else alert.atMs
        return nowMs - at > MAX_ALERT_AGE_MS
    }

    private fun showsNumbers(alert: SportsAlert, ctx: AlertContext): Boolean =
        ctx.spoiler.shown || alert.eventId in ctx.revealed

    private fun capReached(settings: SportsAlertSettings, nowMs: Long, ledger: AlertLedger): Boolean =
        settings.maxPerHour > 0 && ledger.shownAtMs.count { nowMs - it < HOUR_MS } >= settings.maxPerHour

    /**
     * Where a ready alert must wait for the batch: inside the window after the last banner, or behind a batch already
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
    private fun supersede(alerts: List<Pair<SportsAlert, Boolean>>): List<Pair<SportsAlert, Boolean>> {
        val winners = alerts.groupBy { it.first.eventId }.values.map { game ->
            game.lastOrNull { it.first.kind == AlertKind.Final } ?: game.maxBy { it.first.atMs }
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

    /** [alerts]: each alert with whether its numbers may show. */
    fun banner(alerts: List<Pair<SportsAlert, Boolean>>): AlertBanner {
        if (alerts.size == 1) {
            val (alert, numbers) = alerts.single()
            return AlertBanner(
                id = alert.key,
                title = headline(alert, numbers),
                subtitle = listOfNotNull(alert.league, alert.progress).joinToString(" · ").ifBlank { null },
                lines = emptyList(),
                alerts = listOf(alert),
                action = BannerAction.OpenGame(alert.eventId),
            )
        }
        return AlertBanner(
            id = "batch:" + alerts.joinToString("|") { it.first.key },
            title = "${alerts.size} sports updates",
            subtitle = null,
            lines = alerts.take(SUMMARY_LINES).map { (a, numbers) -> headline(a, numbers) },
            alerts = alerts.map { it.first },
            action = BannerAction.OpenSports,
        )
    }

    /** `Arsenal v Chelsea has started` · `Goal · Arsenal 1–0 Chelsea` · `Full time · Arsenal 2–1 Chelsea` (no numbers: `v`). */
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
