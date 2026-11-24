package com.nuvio.app.features.radar.alerts

import co.touchlab.kermit.Logger
import com.nuvio.app.core.contracts.PlaybackContext
import com.nuvio.app.features.radar.RevealedGames
import com.nuvio.app.features.radar.ScoreSpoilerSettings
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** What the controller reads about the device at decision time. Production: [SportsAlerts]; tests: a fake. */
internal interface SportsAlertInputs {
    fun nowMs(): Long
    fun localMinuteOfDay(nowMs: Long): Int
    fun playback(): PlaybackContext
    fun settings(): SportsAlertSettings
    fun followedLeagueIds(): Set<String>
    fun followedTeamIds(): Set<String>
    fun spoiler(): ScoreSpoilerSettings
    fun revealed(): RevealedGames
    /** Every live channel playing now (the players' own state; no lookup). */
    fun playingChannelIds(): Set<String>
}

/**
 * The alerts state holder: received payloads in, one [banner] out. Runs [SportsAlertPolicy], keeps the in-memory
 * [AlertLedger] (cap and batch counters are per device), arms ONE local timer at [SportsAlertPolicy.nextTimerAtMs] for
 * held alerts, starts the watch lookup a watch-check hold waits on (and re-runs the policy when it answers), and
 * auto-dismisses after [dismissAfterMs]. Confined to [scope]'s dispatcher (Main in production). No polling; the only
 * requests it can start are the game screen's own Watch lookups ([SportsAlertActions]).
 */
internal class SportsAlertController(
    private val inputs: SportsAlertInputs,
    private val scope: CoroutineScope,
    private val actions: SportsAlertActions,
    private val dismissAfterMs: Long = PHONE_DISMISS_MS,
    private val report: (Map<String, Any>) -> Unit = SportsAlertTelemetry::send,
) {
    private val log = Logger.withTag("SportsAlerts")
    private val lock = SynchronizedObject()
    private var ledger = AlertLedger()
    private var timerJob: Job? = null
    private var dismissJob: Job? = null
    private var lookupJob: Job? = null

    private val _banner = MutableStateFlow<AlertBanner?>(null)
    val banner: StateFlow<AlertBanner?> = _banner.asStateFlow()

    /** The game whose page is open (the hub's game screen reports it while shown). */
    private val openGame = MutableStateFlow<String?>(null)

    fun setOpenGame(eventId: String?) {
        openGame.value = eventId
    }

    /** Clears the game page only if [eventId] is still the one reported (a fast switch must not clear the new one). */
    fun clearOpenGame(eventId: String) {
        openGame.update { if (it == eventId) null else it }
    }

    /** A broadcast payload from a sports-alerts topic. Malformed or other-version payloads are ignored. */
    fun onPayload(payload: JsonObject) {
        val alert = SportsAlert.parse(payload)
        // DIAG (diag/sports-alerts-trace, never main): every received payload.
        if (alert == null) log.w { "DIAG alert payload unparsed: keys=${payload.keys}" }
        alert?.let(::onAlert)
    }

    fun onAlert(alert: SportsAlert) {
        val settings = inputs.settings()
        val ctx = context(setOf(alert.eventId))
        val decision = synchronized(lock) {
            val (decision, next) = SportsAlertPolicy.onAlert(alert, settings, ctx, ledger)
            ledger = next
            decision
        }
        log.w { "DIAG alert ${alert.key} kind=${alert.kind} -> ${decision::class.simpleName} ${(decision as? AlertDecision.Drop)?.reason ?: (decision as? AlertDecision.HoldUntil)?.reason ?: ""} playback=${ctx.playback} quietMin=${ctx.localMinuteOfDay} hidden=${!ctx.spoiler.shown}" }
        when (decision) {
            is AlertDecision.ShowNow -> show(decision.banner)
            is AlertDecision.HoldUntil -> {
                report(SportsAlertTelemetry.held(alert, decision.reason))
                if (decision.reason == HoldReason.WatchCheck) checkWatch(alert.eventId)
                armTimer()
            }
            is AlertDecision.Drop -> {
                // Never silent in the log, even where telemetry stays quiet (a fast device clock shows up here).
                if (decision.reason == DropReason.Stale) log.w { "stale alert dropped: ${ctx.nowMs - alert.atMs} ms old" }
                SportsAlertTelemetry.dropped(alert, decision.reason)?.let(report)
            }
        }
    }

    /** The banner's button (tap on phone; hold OK / Select on TV). Returns what the shell must do, or null. */
    fun act(): BannerAction? {
        val current = _banner.value ?: return null
        val action = current.action
        report(
            SportsAlertTelemetry.action(
                current,
                when (action) {
                    is BannerAction.OpenGame -> "open_game"
                    is BannerAction.Watch -> "watch"
                    BannerAction.OpenSports -> "open_sports"
                },
            ),
        )
        clear()
        return action
    }

    /** Swipe up / x / auto-dismiss. */
    fun dismiss(byUser: Boolean = true) {
        val current = _banner.value ?: return
        if (byUser) report(SportsAlertTelemetry.action(current, "dismissed"))
        clear()
    }

    /** The app went to the background: drop everything held (it would be old news on return) and its timer. */
    fun clearHeld() {
        synchronized(lock) { ledger = SportsAlertPolicy.clearHeld(ledger) }
        timerJob?.cancel()
        timerJob = null
    }

    /** A profile switch or sign-out: nothing held for the old profile may surface under the new one. */
    fun reset() {
        synchronized(lock) { ledger = AlertLedger() }
        timerJob?.cancel()
        actions.resetForProfile()
        openGame.value = null
        clear()
    }

    private fun context(eventIds: Set<String>): AlertContext {
        val now = inputs.nowMs()
        return AlertContext(
            nowMs = now,
            localMinuteOfDay = inputs.localMinuteOfDay(now),
            playback = inputs.playback(),
            openGameEventId = openGame.value,
            playingChannelIds = inputs.playingChannelIds(),
            watch = actions.watchStates(eventIds, now),
            followedLeagueIds = inputs.followedLeagueIds(),
            followedTeamIds = inputs.followedTeamIds(),
            spoiler = inputs.spoiler(),
            revealed = inputs.revealed(),
        )
    }

    /** A watch-check hold: ask once (single-flight, cached); the answer releases the hold early. */
    private fun checkWatch(eventId: String) {
        actions.ensureKnown(eventId, inputs::nowMs) { _, _ ->
            scope.launch {
                synchronized(lock) { ledger = SportsAlertPolicy.onWatchResolved(eventId, ledger, inputs.nowMs()) }
                flush()
            }
        }
    }

    private fun show(banner: AlertBanner) {
        report(SportsAlertTelemetry.shown(banner))
        lookupJob?.cancel()
        _banner.value = banner
        lookupJob = actions.resolve(banner, inputs::nowMs) { watch ->
            // Only upgrade the banner that asked, and only while it is still up.
            _banner.update { current -> if (current != null && current.id == banner.id) current.copy(action = watch) else current }
        }
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(dismissAfterMs)
            if (_banner.value?.id == banner.id) dismiss(byUser = false)
        }
    }

    private fun clear() {
        lookupJob?.cancel()
        lookupJob = null
        dismissJob?.cancel()
        dismissJob = null
        _banner.value = null
    }

    /** One one-shot local timer for the earliest held alert; re-armed after every hold and flush. No polling. */
    private fun armTimer() {
        val at = synchronized(lock) { SportsAlertPolicy.nextTimerAtMs(ledger) }
        timerJob?.cancel()
        if (at == null) return
        timerJob = scope.launch {
            delay((at - inputs.nowMs()).coerceAtLeast(0L))
            flush()
        }
    }

    private fun flush() {
        val settings = inputs.settings()
        val pendingGames = synchronized(lock) { ledger.pending.map { it.alert.eventId }.toSet() }
        val ctx = context(pendingGames)
        val result = synchronized(lock) {
            SportsAlertPolicy.onTimer(settings, ctx, ledger).also { ledger = it.ledger }
        }
        result.dropped.forEach { (alert, reason) -> SportsAlertTelemetry.dropped(alert, reason)?.let(report) }
        result.held.forEach { (alert, reason) ->
            report(SportsAlertTelemetry.held(alert, reason))
            // The flush asks too: an alert re-held for the watch check is never dropped as unknown without a lookup.
            if (reason == HoldReason.WatchCheck) checkWatch(alert.eventId)
        }
        result.banner?.let(::show)
        armTimer()
    }

    companion object {
        /** Phone and desktop; TV and Apple TV use [TV_DISMISS_MS]. */
        const val PHONE_DISMISS_MS = 8_000L
        const val TV_DISMISS_MS = 10_000L
    }
}
