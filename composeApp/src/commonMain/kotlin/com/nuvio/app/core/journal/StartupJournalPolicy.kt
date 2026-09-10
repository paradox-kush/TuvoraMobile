package com.nuvio.app.core.journal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The four terminal states an operation attempt can reach. A missing outcome (an entry left with
 * `outcome == null` by a run that never came back) is NOT one of these while that run is current —
 * it becomes [INTERRUPTED_UNKNOWN] only when a LATER run sweeps it, and even then it means exactly
 * "unknown cause", never "confirmed crash": a reboot, an OS reclaim, a user force-stop, or an update
 * all leave the same trace.
 */
internal enum class JournalOutcome { COMPLETED, EXPECTED_FAILURE, CANCELLED, INTERRUPTED_UNKNOWN }

@Serializable
internal data class JournalEntry(
    val op: String,
    val subject: String,
    val generation: Long,
    val runId: Long,
    val build: String,
    val startedAtMs: Long,
    val outcome: JournalOutcome? = null,
    val interruptCount: Int = 0,
    val lastFailureMs: Long = 0L,
)

@Serializable
internal data class Journal(
    val version: Int = StartupJournalPolicy.VERSION,
    val lastRunId: Long = 0L,
    val entries: List<JournalEntry> = emptyList(),
)

/**
 * Pure decision layer for the startup journal — a single small, versioned, bounded record of risky
 * operation attempts and their outcomes, shared by the resolver backoff (Step B) and the crash-loop
 * recovery gate (Step C). No storage, clock, or coroutines here, so every rule is unit-tested on
 * every runner. The impure edges (durable persistence with verification, run identity, the clock)
 * live in `StartupJournal` + `StartupJournalStore`.
 *
 * Invariants this layer guarantees:
 *  - attempt-before-work: [begin] writes an entry with `outcome == null` BEFORE the risky work runs.
 *  - handled outcomes are not abnormal deaths: [record] stamps an outcome, so [startRun]'s sweep
 *    (which only touches `outcome == null`) never re-labels a completed/failed/cancelled attempt.
 *  - missing evidence stays UNKNOWN: [startRun] marks a prior run's unfinished attempt
 *    INTERRUPTED_UNKNOWN — deferred with bounded backoff, never called a crash.
 *  - no stale clobber: [record] applies only when the caller's [generation] matches the current
 *    entry, so an overlapping newer attempt is never overwritten by a late completion.
 *  - bounded: [decode] rejects an oversized/corrupt/foreign-version blob, and the entry list is
 *    capped at [MAX_ENTRIES].
 */
internal object StartupJournalPolicy {
    const val VERSION = 1
    const val MAX_ENTRIES = 64

    // Operation keys (here so both the pure layer and the facade name one source of truth).
    const val OP_INDEX_BUILD = "xtream_index_build"
    const val OP_STARTUP_INTERACTIVE = "startup_interactive"
    const val SUBJECT_NONE = ""

    /** Reject a stored blob larger than this outright (corruption / tampering) — the journal is a
     *  handful of small entries; this is generous headroom over [MAX_ENTRIES] * a small entry. */
    const val MAX_BLOB_CHARS = 131_072

    /** Byte cap for the file READ (before an in-memory string exists). > [MAX_BLOB_CHARS] so a valid
     *  UTF-8 blob always fits, while a corrupt/tampered file is rejected without fully loading. */
    const val MAX_BLOB_BYTES = 262_144

    /** Bounded backoff for interrupted automatic work, indexed by consecutive interrupt count
     *  (clamped to the last bucket): 1st→1 min, 2nd→5 min, 3rd→15 min, 4th+→60 min. Bounded so a
     *  persistently-failing op never grows the wait without limit and never busy-loops. */
    val INTERRUPT_BACKOFF_MS = longArrayOf(0L, 60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L)

    /** Durable failure backoff window — matches the resolver's existing in-memory 1 h backoff so
     *  porting it here keeps behaviour compatible while making it survive a process death. */
    const val EXPECTED_FAILURE_BACKOFF_MS = 60L * 60_000L

    /** Consecutive pre-interactive interruptions before the recovery gate enters safe mode. Two, not
     *  one: a single unfinished startup is more often a reboot / OS reclaim / user force-stop than a
     *  code fault, and must not gate optional features on that alone. */
    const val SAFE_MODE_THRESHOLD = 2

    /** Recovery gate decision (Step C): should this run withhold optional startup work because the
     *  last [SAFE_MODE_THRESHOLD] launches in a row failed to reach interactive readiness? */
    fun shouldEnterSafeMode(startupInterruptCount: Int): Boolean = startupInterruptCount >= SAFE_MODE_THRESHOLD

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(j: Journal): String = json.encodeToString(Journal.serializer(), j)

    /** Decode a stored blob. Resets to empty on null, oversize, corruption, or a version mismatch —
     *  but preserves [Journal.lastRunId] across a version change so run identity stays monotonic
     *  through an app update. Caps the entry list. */
    fun decode(raw: String?): Journal {
        if (raw == null || raw.length > MAX_BLOB_CHARS) return Journal()
        val parsed = runCatching { json.decodeFromString(Journal.serializer(), raw) }.getOrNull() ?: return Journal()
        if (parsed.version != VERSION) return Journal(version = VERSION, lastRunId = parsed.lastRunId)
        return parsed.copy(entries = capEntries(parsed.entries))
    }

    private fun capEntries(entries: List<JournalEntry>): List<JournalEntry> =
        if (entries.size <= MAX_ENTRIES) entries else entries.sortedByDescending { it.startedAtMs }.take(MAX_ENTRIES)

    private fun List<JournalEntry>.find(op: String, subject: String) =
        firstOrNull { it.op == op && it.subject == subject }

    /**
     * Advance to a new run. Assigns `runId = lastRunId + 1` and marks any entry still in flight
     * (`outcome == null`) from a PRIOR run as [JournalOutcome.INTERRUPTED_UNKNOWN], bumping its
     * interrupt count for bounded backoff. An in-flight entry from the CURRENT run is left alone.
     */
    fun startRun(j: Journal, nowMs: Long): Pair<Journal, Long> {
        val runId = j.lastRunId + 1
        val swept = j.entries.map { e ->
            if (e.outcome == null && e.runId < runId) {
                e.copy(
                    outcome = JournalOutcome.INTERRUPTED_UNKNOWN,
                    interruptCount = e.interruptCount + 1,
                    lastFailureMs = nowMs,
                )
            } else {
                e
            }
        }
        return j.copy(lastRunId = runId, entries = swept) to runId
    }

    /** Begin an attempt: bump the op's generation, write `outcome = null` with this run/build/start.
     *  Preserves the prior interrupt/backoff state so consecutive interruptions escalate. Returns the
     *  new [Journal] and the generation token the caller passes back to [record]. */
    fun begin(j: Journal, op: String, subject: String, runId: Long, build: String, nowMs: Long): Pair<Journal, Long> {
        val prev = j.entries.find(op, subject)
        val generation = (prev?.generation ?: 0L) + 1
        val entry = JournalEntry(
            op = op,
            subject = subject,
            generation = generation,
            runId = runId,
            build = build,
            startedAtMs = nowMs,
            outcome = null,
            interruptCount = prev?.interruptCount ?: 0,
            lastFailureMs = prev?.lastFailureMs ?: 0L,
        )
        val others = j.entries.filterNot { it.op == op && it.subject == subject }
        return j.copy(entries = capEntries(others + entry)) to generation
    }

    /** Record a terminal [outcome] for the attempt identified by [generation]. A stale/overlapping
     *  completion (generation != the current entry's) is IGNORED so a newer attempt is never
     *  clobbered. COMPLETED clears the backoff state; EXPECTED_FAILURE / INTERRUPTED_UNKNOWN stamp a
     *  failure time; CANCELLED is not a failure and does not bump backoff. */
    fun record(j: Journal, op: String, subject: String, generation: Long, outcome: JournalOutcome, nowMs: Long): Journal {
        val cur = j.entries.find(op, subject) ?: return j
        if (cur.generation != generation) return j
        val updated = when (outcome) {
            JournalOutcome.COMPLETED -> cur.copy(outcome = outcome, interruptCount = 0, lastFailureMs = 0L)
            JournalOutcome.EXPECTED_FAILURE -> cur.copy(outcome = outcome, lastFailureMs = nowMs)
            JournalOutcome.CANCELLED -> cur.copy(outcome = outcome)
            JournalOutcome.INTERRUPTED_UNKNOWN -> cur.copy(outcome = outcome, interruptCount = cur.interruptCount + 1, lastFailureMs = nowMs)
        }
        return j.copy(entries = j.entries.map { if (it.op == op && it.subject == subject) updated else it })
    }

    /** True if automatic work for this op should be DEFERRED right now: within the bounded interrupt
     *  backoff after an interruption, or within the failure window after an expected failure. */
    fun shouldDefer(j: Journal, op: String, subject: String, nowMs: Long): Boolean {
        val e = j.entries.find(op, subject) ?: return false
        // The backoff is measured against a PERSISTED wall-clock timestamp (the only clock stable
        // across process death and reboot — see StartupJournalController). Wall-clock can move
        // backward (NTP/user correction) or the whole journal can be restored from a backup or another
        // device, leaving lastFailureMs in the FUTURE relative to nowMs. A future timestamp is not a
        // trustworthy "recently failed" signal, so fail OPEN — never defer indefinitely. NOTE:
        // clamping the delta with coerceAtLeast(0) would be WRONG here: it treats a rollback as "just
        // failed" and restarts the FULL backoff, locking the op out. The count-based crash-loop gate
        // (shouldEnterSafeMode) is clock-independent and still protects against a genuine loop.
        val elapsed = nowMs - e.lastFailureMs
        if (elapsed < 0L) return false
        return when (e.outcome) {
            JournalOutcome.INTERRUPTED_UNKNOWN -> elapsed < interruptBackoffMs(e.interruptCount)
            JournalOutcome.EXPECTED_FAILURE -> elapsed < EXPECTED_FAILURE_BACKOFF_MS
            else -> false
        }
    }

    fun interruptBackoffMs(count: Int): Long {
        if (count <= 0) return 0L
        return INTERRUPT_BACKOFF_MS[minOf(count, INTERRUPT_BACKOFF_MS.size - 1)]
    }

    /** Consecutive interruptions currently recorded for an op — the crash-loop signal for the Step C
     *  gate (e.g. how many launches in a row failed to reach interactive readiness). */
    fun interruptCount(j: Journal, op: String, subject: String): Int =
        j.entries.find(op, subject)?.takeIf { it.outcome == JournalOutcome.INTERRUPTED_UNKNOWN }?.interruptCount ?: 0
}
