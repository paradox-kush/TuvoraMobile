package com.nuvio.app.core.journal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartupJournalPolicyTest {
    private val op = "xtream_index_build"
    private val subj = "acct#movie"
    private val build = "1.6.3"

    private fun freshRun(nowMs: Long = 1_000L): Pair<Journal, Long> =
        StartupJournalPolicy.startRun(Journal(), nowMs)

    @Test
    fun `begin records an in-flight attempt with a fresh generation`() {
        val (j0, run) = freshRun()
        val (j1, gen) = StartupJournalPolicy.begin(j0, op, subj, run, build, nowMs = 2_000L)
        assertEquals(1L, gen)
        val e = j1.entries.single()
        assertNull(e.outcome, "an attempt starts in flight (outcome == null)")
        assertEquals(run, e.runId)
        assertEquals(build, e.build)
    }

    @Test
    fun `a completed outcome clears the backoff state`() {
        val (j0, run) = freshRun()
        val (j1, gen) = StartupJournalPolicy.begin(j0, op, subj, run, build, 2_000L)
        val j2 = StartupJournalPolicy.record(j1, op, subj, gen, JournalOutcome.COMPLETED, 3_000L)
        val e = j2.entries.single()
        assertEquals(JournalOutcome.COMPLETED, e.outcome)
        assertEquals(0, e.interruptCount)
        assertFalse(StartupJournalPolicy.shouldDefer(j2, op, subj, 4_000L))
    }

    @Test
    fun `an expected failure defers within the failure window and clears after it`() {
        val (j0, run) = freshRun()
        val (j1, gen) = StartupJournalPolicy.begin(j0, op, subj, run, build, 2_000L)
        val failAt = 3_000L
        val j2 = StartupJournalPolicy.record(j1, op, subj, gen, JournalOutcome.EXPECTED_FAILURE, failAt)
        assertTrue(StartupJournalPolicy.shouldDefer(j2, op, subj, failAt + 1_000L), "within the 1h window")
        assertFalse(
            StartupJournalPolicy.shouldDefer(j2, op, subj, failAt + StartupJournalPolicy.EXPECTED_FAILURE_BACKOFF_MS + 1),
            "after the 1h window",
        )
    }

    @Test
    fun `a cancelled outcome is not a failure and does not defer`() {
        val (j0, run) = freshRun()
        val (j1, gen) = StartupJournalPolicy.begin(j0, op, subj, run, build, 2_000L)
        val j2 = StartupJournalPolicy.record(j1, op, subj, gen, JournalOutcome.CANCELLED, 3_000L)
        assertEquals(JournalOutcome.CANCELLED, j2.entries.single().outcome)
        assertFalse(StartupJournalPolicy.shouldDefer(j2, op, subj, 3_100L), "cancellation is not a backoff-worthy failure")
    }

    @Test
    fun `a stale completion does not clobber a newer attempt`() {
        val (j0, run) = freshRun()
        val (j1, gen1) = StartupJournalPolicy.begin(j0, op, subj, run, build, 2_000L)
        // A newer attempt starts (generation 2) before the first completes.
        val (j2, gen2) = StartupJournalPolicy.begin(j1, op, subj, run, build, 2_500L)
        assertEquals(2L, gen2)
        // The stale gen1 completion is ignored — the current (gen2) in-flight entry is preserved.
        val j3 = StartupJournalPolicy.record(j2, op, subj, gen1, JournalOutcome.COMPLETED, 3_000L)
        val e = j3.entries.single()
        assertEquals(2L, e.generation)
        assertNull(e.outcome, "the newer attempt is still in flight; the stale completion was dropped")
    }

    @Test
    fun `startRun marks a prior run in-flight attempt as interrupted unknown`() {
        val (j0, run1) = freshRun(1_000L)
        val (j1, gen) = StartupJournalPolicy.begin(j0, op, subj, run1, build, 2_000L)
        // The process dies before recording an outcome; a later run sweeps.
        val (j2, run2) = StartupJournalPolicy.startRun(j1, 10_000L)
        assertTrue(run2 > run1)
        val e = j2.entries.single()
        assertEquals(JournalOutcome.INTERRUPTED_UNKNOWN, e.outcome, "missing evidence is UNKNOWN, not a crash")
        assertEquals(1, e.interruptCount)
        assertEquals(gen, e.generation)
    }

    @Test
    fun `startRun leaves a recorded outcome untouched`() {
        val (j0, run1) = freshRun(1_000L)
        val (j1, gen) = StartupJournalPolicy.begin(j0, op, subj, run1, build, 2_000L)
        val j2 = StartupJournalPolicy.record(j1, op, subj, gen, JournalOutcome.EXPECTED_FAILURE, 3_000L)
        val (j3, _) = StartupJournalPolicy.startRun(j2, 10_000L)
        assertEquals(JournalOutcome.EXPECTED_FAILURE, j3.entries.single().outcome, "a handled outcome is not re-swept as interrupted")
    }

    @Test
    fun `startRun does not sweep the current run in-flight attempt`() {
        val (j0, run) = freshRun(1_000L)
        val (j1, _) = StartupJournalPolicy.begin(j0, op, subj, run, build, 2_000L)
        // No new startRun — the same run's in-flight entry must stay in flight.
        assertNull(j1.entries.single().outcome)
    }

    @Test
    fun `consecutive interruptions escalate the bounded backoff`() {
        assertEquals(0L, StartupJournalPolicy.interruptBackoffMs(0))
        assertEquals(60_000L, StartupJournalPolicy.interruptBackoffMs(1))
        assertEquals(5 * 60_000L, StartupJournalPolicy.interruptBackoffMs(2))
        // Bounded: a large count clamps to the last bucket, never grows without limit.
        assertEquals(StartupJournalPolicy.interruptBackoffMs(4), StartupJournalPolicy.interruptBackoffMs(99))
    }

    @Test
    fun `an interrupted attempt defers within its bounded window`() {
        val (j0, run1) = freshRun(1_000L)
        val (j1, _) = StartupJournalPolicy.begin(j0, op, subj, run1, build, 2_000L)
        val (j2, _) = StartupJournalPolicy.startRun(j1, 10_000L) // interruptCount now 1 -> 60s window
        assertTrue(StartupJournalPolicy.shouldDefer(j2, op, subj, 10_000L + 30_000L), "within 60s")
        assertFalse(StartupJournalPolicy.shouldDefer(j2, op, subj, 10_000L + 61_000L), "after 60s")
        assertEquals(1, StartupJournalPolicy.interruptCount(j2, op, subj))
    }

    @Test
    fun `decode rejects null oversized corrupt and foreign-version blobs`() {
        assertEquals(Journal(), StartupJournalPolicy.decode(null))
        assertEquals(Journal(), StartupJournalPolicy.decode("x".repeat(StartupJournalPolicy.MAX_BLOB_CHARS + 1)))
        assertEquals(Journal(), StartupJournalPolicy.decode("{ not json"))
        // A foreign version resets entries but keeps lastRunId monotonic across an upgrade.
        val foreign = """{"version":999,"lastRunId":7,"entries":[]}"""
        val decoded = StartupJournalPolicy.decode(foreign)
        assertEquals(StartupJournalPolicy.VERSION, decoded.version)
        assertEquals(7L, decoded.lastRunId)
    }

    @Test
    fun `encode then decode round-trips a journal`() {
        val (j0, run) = freshRun()
        val (j1, gen) = StartupJournalPolicy.begin(j0, op, subj, run, build, 2_000L)
        val j2 = StartupJournalPolicy.record(j1, op, subj, gen, JournalOutcome.COMPLETED, 3_000L)
        assertEquals(j2, StartupJournalPolicy.decode(StartupJournalPolicy.encode(j2)))
    }

    @Test
    fun `decode caps the entry list to MAX_ENTRIES`() {
        val many = (1..(StartupJournalPolicy.MAX_ENTRIES + 20)).map {
            JournalEntry("op$it", "s", 1, 1, build, startedAtMs = it.toLong())
        }
        val decoded = StartupJournalPolicy.decode(StartupJournalPolicy.encode(Journal(entries = many)))
        assertEquals(StartupJournalPolicy.MAX_ENTRIES, decoded.entries.size)
    }

    @Test
    fun `safe mode triggers only at the threshold of consecutive interruptions`() {
        assertFalse(StartupJournalPolicy.shouldEnterSafeMode(0))
        assertFalse(StartupJournalPolicy.shouldEnterSafeMode(1), "a single unfinished startup is not enough")
        assertTrue(StartupJournalPolicy.shouldEnterSafeMode(StartupJournalPolicy.SAFE_MODE_THRESHOLD))
        assertTrue(StartupJournalPolicy.shouldEnterSafeMode(5))
    }

    @Test
    fun `startup interactive completion resets the loop counter across runs`() {
        // Two crashes before interactive → interruptCount 2 → safe mode. A completion resets it.
        var (j, run) = freshRun(1_000L)
        repeat(2) {
            val (j1, _) = StartupJournalPolicy.begin(j, "startup_interactive", "", run, build, 2_000L)
            val (j2, r2) = StartupJournalPolicy.startRun(j1, 3_000L) // interrupted (no completion)
            j = j2; run = r2
        }
        assertTrue(StartupJournalPolicy.shouldEnterSafeMode(StartupJournalPolicy.interruptCount(j, "startup_interactive", "")))
        val (j3, gen) = StartupJournalPolicy.begin(j, "startup_interactive", "", run, build, 4_000L)
        val j4 = StartupJournalPolicy.record(j3, "startup_interactive", "", gen, JournalOutcome.COMPLETED, 5_000L)
        assertFalse(StartupJournalPolicy.shouldEnterSafeMode(StartupJournalPolicy.interruptCount(j4, "startup_interactive", "")))
    }

    // --- clock anomalies against the PERSISTED backoff timestamp (rollback / forward jump /
    //     reboot / restored-from-backup) — the cooldown must never lock an op out indefinitely ---

    /** A journal with one recorded failure at [failAtMs] for [op]/[subj]. */
    private fun failedAt(outcome: JournalOutcome, failAtMs: Long): Journal {
        val (j0, run) = freshRun(failAtMs - 1)
        val (j1, gen) = StartupJournalPolicy.begin(j0, op, subj, run, build, failAtMs - 1)
        return StartupJournalPolicy.record(j1, op, subj, gen, outcome, failAtMs)
    }

    @Test
    fun `a wall-clock rollback after a failure does not defer forever`() {
        val failAt = 10_000_000L
        val j = failedAt(JournalOutcome.EXPECTED_FAILURE, failAt)
        // Clock rolled BACKWARD to before the stored failure: elapsed is negative. The old code did
        // negative-less-than-backoff -> true and deferred indefinitely; a future timestamp fails open.
        assertFalse(StartupJournalPolicy.shouldDefer(j, op, subj, failAt - 5_000L), "a rolled-back clock must not lock the op out")
    }

    @Test
    fun `a restored-from-backup journal with a future failure timestamp fails open`() {
        // Journal restored onto a device whose wall-clock is BEHIND the writer: stored timestamps are
        // in this device's future. The op must be allowed, not deferred forever.
        val j = failedAt(JournalOutcome.INTERRUPTED_UNKNOWN, failAtMs = 5_000_000_000L)
        assertFalse(StartupJournalPolicy.shouldDefer(j, op, subj, nowMs = 1_000L))
    }

    @Test
    fun `a large forward clock jump expires the backoff instead of extending it`() {
        val failAt = 1_000L
        val j = failedAt(JournalOutcome.EXPECTED_FAILURE, failAt)
        assertTrue(StartupJournalPolicy.shouldDefer(j, op, subj, failAt + 1_000L), "still within the window at real time")
        assertFalse(StartupJournalPolicy.shouldDefer(j, op, subj, failAt + 10L * 365 * 24 * 60 * 60 * 1000L))
    }

    @Test
    fun `across a reboot the swept interruption backoff is measured on surviving wall-clock`() {
        // Attempt opens, process dies (reboot) mid-op; the next start sweeps it to INTERRUPTED_UNKNOWN
        // stamped at post-reboot wall-clock. Wall-clock survives reboot, so the bounded backoff
        // measures correctly: deferred right after, cleared once the window elapses.
        val (j0, run) = freshRun(1_000L)
        val (jInflight, _) = StartupJournalPolicy.begin(j0, op, subj, run, build, 1_000L)
        val rebootAt = 2_000L
        val (jSwept, _) = StartupJournalPolicy.startRun(jInflight, rebootAt)
        assertEquals(JournalOutcome.INTERRUPTED_UNKNOWN, jSwept.entries.single { it.op == op }.outcome, "swept to interrupted")
        val backoff = StartupJournalPolicy.interruptBackoffMs(1)
        assertTrue(StartupJournalPolicy.shouldDefer(jSwept, op, subj, rebootAt + backoff - 1))
        assertFalse(StartupJournalPolicy.shouldDefer(jSwept, op, subj, rebootAt + backoff + 1))
    }
}
