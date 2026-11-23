package com.nuvio.app.features.radar

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Hide scores + score delay (research/hide-scores-spec-2026-11-23.md). Per profile, synced in radar_prefs
 * (`scores_shown`, `score_delay_s`). The default is today's behaviour: scores shown, no delay.
 */
data class ScoreSpoilerSettings(val shown: Boolean = true, val delaySeconds: Int = 0) {
    /** The delay that applies, in ms: a value outside [ALLOWED_DELAYS] reads as Off. */
    val delayMs: Long get() = sanitizeDelay(delaySeconds) * 1000L

    /** The hub's toggle chip ("Scores shown" / "Scores hidden"). */
    val toggleLabel: String get() = if (shown) "Scores shown" else "Scores hidden"

    /** The hub's delay chip, shown only while scores are shown ("Delay: Off" / "Delay: 15 s"). */
    val delayLabel: String
        get() = sanitizeDelay(delaySeconds).let { if (it == 0) "Delay: Off" else "Delay: $it s" }

    companion object {
        val ALLOWED_DELAYS: List<Int> = listOf(0, 15, 30, 60)

        /** Anything but 0 / 15 / 30 / 60 reads as 0 (Off). */
        fun sanitizeDelay(seconds: Int): Int = if (seconds in ALLOWED_DELAYS) seconds else 0

        /** The chip's cycle: Off → 15 → 30 → 60 → Off. */
        fun nextDelay(current: Int): Int {
            val i = ALLOWED_DELAYS.indexOf(sanitizeDelay(current))
            return ALLOWED_DELAYS[(i + 1) % ALLOWED_DELAYS.size]
        }
    }
}

/** What a score slot may show right now. */
sealed interface ScoreVisibility<out V>

/** Show [value] (null = there is no score to show, e.g. a game that has not started). */
data class ScoreShown<out V>(val value: V?) : ScoreVisibility<V>

/** The user hid scores: show no numbers, no winner emphasis. */
data object ScoreHidden : ScoreVisibility<Nothing>

/** A delay is on and no snapshot is old enough yet: show "–". */
data object ScorePending : ScoreVisibility<Nothing>

/**
 * The one decision every score surface asks (hub, home shelf, calendar, game page, Apple TV; future alerts and
 * storylines too). Pure: settings, what was observed and the buffer in, a [ScoreVisibility] out.
 */
object ScoreSpoilerPolicy {

    fun scoreFor(
        settings: ScoreSpoilerSettings,
        eventId: String?,
        observed: RadarLiveScore?,
        fixtureFinishedAtFirstSight: Boolean,
        buffer: DelayedValueBuffer<String, RadarLiveScore>,
        nowMs: Long,
    ): ScoreVisibility<RadarLiveScore> = resolve(settings, eventId, observed, fixtureFinishedAtFirstSight, buffer, nowMs)

    /**
     * Generic form (the game page buffers its whole live model): hidden → [ScoreHidden]; no delay, or a game already
     * finished the first time we saw it → the newest; otherwise the newest buffered value at least the delay old, or
     * [ScorePending] when there is a score but none is old enough. Never a fresher value than the delay allows.
     */
    fun <V : Any> resolve(
        settings: ScoreSpoilerSettings,
        key: String?,
        observed: V?,
        finishedAtFirstSight: Boolean,
        buffer: DelayedValueBuffer<String, V>,
        nowMs: Long,
    ): ScoreVisibility<V> {
        if (!settings.shown) return ScoreHidden
        val delayMs = settings.delayMs
        if (delayMs == 0L || finishedAtFirstSight || key == null) return ScoreShown(observed)
        buffer.valueAt(key, nowMs, delayMs)?.let { return ScoreShown(it) }
        return if (observed == null) ScoreShown(null) else ScorePending
    }

    /** Hidden gates the spoiler-bearing game-page parts (scorers, last play, win chance, box score, plays…). */
    fun hidesDetails(settings: ScoreSpoilerSettings): Boolean = !settings.shown
}

/** What a card's two score slots render. [pending] = the delay's "–" placeholders, which never dim a side. */
data class ScoreSlots(val home: String?, val away: String?, val pending: Boolean = false) {
    val hasPair: Boolean get() = !home.isNullOrBlank() && !away.isNullOrBlank()

    /** Winner emphasis (dim the trailing side) only for a real, shown pair. */
    val comparable: Boolean get() = hasPair && !pending

    companion object {
        const val PLACEHOLDER = "–"
        val NONE = ScoreSlots(null, null)
        val PENDING = ScoreSlots(PLACEHOLDER, PLACEHOLDER, pending = true)
    }
}

fun ScoreVisibility<RadarLiveScore>.slots(): ScoreSlots = when (this) {
    is ScoreShown -> value?.let { ScoreSlots(it.homeScore?.takeIf(String::isNotBlank), it.awayScore?.takeIf(String::isNotBlank)) } ?: ScoreSlots.NONE
    ScoreHidden -> ScoreSlots.NONE
    ScorePending -> ScoreSlots.PENDING
}

/**
 * Time-stamped snapshots per key, so a screen can show the value from N seconds ago. [record] stores a snapshot only
 * when it differs from the key's latest; snapshots are pruned to the newest one old enough for [retainMs] (the longest
 * delay) plus everything newer, capped at [maxPerKey]. Thread-safe: fetches record on a background dispatcher while
 * the UI reads.
 */
class DelayedValueBuffer<K : Any, V : Any>(
    private val maxPerKey: Int = 8,
    private val retainMs: Long = MAX_DELAY_MS,
) {
    private data class Snapshot<V>(val atMs: Long, val value: V)

    private val lock = SynchronizedObject()
    private val byKey = HashMap<K, List<Snapshot<V>>>()

    fun record(key: K, value: V, atMs: Long) = synchronized(lock) {
        val list = byKey[key].orEmpty()
        if (list.lastOrNull()?.value == value) return@synchronized
        byKey[key] = prune(list + Snapshot(atMs, value), atMs)
    }

    /** The newest snapshot at least [delayMs] old at [nowMs] (the newest when [delayMs] is 0), or null if none is. */
    fun valueAt(key: K, nowMs: Long, delayMs: Long): V? = synchronized(lock) {
        val list = byKey[key] ?: return@synchronized null
        if (delayMs <= 0L) return@synchronized list.lastOrNull()?.value
        list.lastOrNull { it.atMs <= nowMs - delayMs }?.value
    }

    /** When the next snapshot not yet due becomes due, across every key; null when nothing is pending. */
    fun nextChangeAtMs(nowMs: Long, delayMs: Long): Long? = synchronized(lock) {
        if (delayMs <= 0L) return@synchronized null
        var next: Long? = null
        for (list in byKey.values) {
            val due = list.firstOrNull { it.atMs > nowMs - delayMs }?.atMs?.plus(delayMs) ?: continue
            if (next == null || due < next) next = due
        }
        next
    }

    fun contains(key: K): Boolean = synchronized(lock) { byKey.containsKey(key) }

    fun snapshotCount(key: K): Int = synchronized(lock) { byKey[key]?.size ?: 0 }

    private fun prune(list: List<Snapshot<V>>, nowMs: Long): List<Snapshot<V>> {
        val anchor = list.indexOfLast { it.atMs <= nowMs - retainMs }
        val kept = if (anchor > 0) list.subList(anchor, list.size).toList() else list
        return if (kept.size <= maxPerKey) kept else listOf(kept.first()) + kept.takeLast(maxPerKey - 1)
    }

    companion object {
        /** The longest allowed delay: the buffer never needs anything older. */
        const val MAX_DELAY_MS: Long = 60_000L
    }
}

/**
 * The hub's per-profile score history: every observed score (recorded where fetch results land, never in UI), and
 * whether each fixture was already finished the first time we saw it (old news is never delayed).
 */
class LiveScoreLedger {
    val buffer = DelayedValueBuffer<String, RadarLiveScore>()
    private val lock = SynchronizedObject()
    private val seen = HashSet<String>()
    private val finishedFirst = HashSet<String>()

    /** [score] null = seen without a score (upcoming). Only the numbers are buffered: the clock is never delayed here. */
    fun observe(key: String, score: RadarLiveScore?, finished: Boolean, atMs: Long) {
        synchronized(lock) {
            if (seen.add(key) && finished) finishedFirst.add(key)
        }
        if (score != null) buffer.record(key, RadarLiveScore(eventId = key, homeScore = score.homeScore, awayScore = score.awayScore), atMs)
    }

    fun finishedAtFirstSight(key: String): Boolean = synchronized(lock) { key in finishedFirst }
}
