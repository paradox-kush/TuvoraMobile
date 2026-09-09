package com.nuvio.app.core.journal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the journal + recovery-gate lifecycle, driven by an in-memory store and a
 * controllable clock — no process, OOM, or 192 MiB heap. A reused [FakeStore] models the on-disk
 * file surviving a process death; a NEW controller on that store models a restart.
 *
 * These exercise the tested PRODUCTION WIRING logic (the controller the singleton delegates to), as
 * distinct from the pure-policy coverage in StartupJournalPolicyTest.
 */
class StartupJournalControllerTest {

    private class FakeStore(var failWrites: Boolean = false) : JournalStore {
        var content: String? = null
        var writes = 0
        override fun read(): String? = content
        override fun writeVerified(content: String): Boolean {
            if (failWrites) return false
            this.content = content
            writes++
            return true
        }
    }

    private class FakeClock(var now: Long = 1_000L) {
        fun ms() = now
    }

    private fun controller(store: FakeStore, clock: FakeClock, build: String = "1.0") =
        StartupJournalController(store, clock::ms) { build }

    /** A new process on the SAME persisted store. */
    private fun restart(store: FakeStore, clock: FakeClock) = controller(store, clock)

    private val idx = StartupJournalPolicy.OP_INDEX_BUILD
    private val sub = "acct#movie"

    // T1 + T2: terminate after a persisted attempt before completion; restart → deferred; a clean
    // completion then clears exactly that attempt.
    @Test
    fun `interrupted build defers on the next run then a completion clears it`() {
        val store = FakeStore()
        val clock = FakeClock(1_000L)
        assertTrue(controller(store, clock).beginAttempt(idx, sub) != null, "attempt persisted")
        clock.now = 2_000L
        val c2 = restart(store, clock)
        assertTrue(c2.shouldDefer(idx, sub), "interrupted build is deferred next run")
        clock.now = 2_000L + 61_000L
        assertFalse(c2.shouldDefer(idx, sub), "deferral clears after the interrupt window")
        val t = c2.beginAttempt(idx, sub)!!
        c2.record(idx, sub, t, JournalOutcome.COMPLETED)
        assertFalse(c2.shouldDefer(idx, sub), "completed build no longer defers")
    }

    // T3: repeated HEADLESS launches must not accumulate a failed startup_interactive.
    @Test
    fun `repeated headless launches never enter safe mode`() {
        val store = FakeStore()
        val clock = FakeClock()
        repeat(5) {
            clock.now += 1_000L
            assertFalse(restart(store, clock).decideStartupMode(), "headless-only run is never safe mode")
        }
    }

    // T4: a UI crash loop DOES gate (and a headless run in between does not reset it).
    @Test
    fun `two consecutive pre-interactive ui crashes enter safe mode`() {
        val store = FakeStore()
        val clock = FakeClock()
        repeat(2) {
            clock.now += 1_000L
            restart(store, clock).markUiLaunchStarted() // opens attempt, dies before interactive
        }
        clock.now += 1_000L
        restart(store, clock).decideStartupMode() // a headless run between crashes must not reset
        clock.now += 1_000L
        assertTrue(restart(store, clock).decideStartupMode(), "two ui crashes → safe mode")
    }

    // T4b: headless then a UI launch in the SAME process opens the attempt only at UI launch.
    @Test
    fun `a ui launch after headless opens the attempt only at ui launch`() {
        val store = FakeStore()
        val clock = FakeClock()
        val c = controller(store, clock)
        c.decideStartupMode()
        val before = store.writes
        c.markUiLaunchStarted()
        assertTrue(store.writes > before, "the ui launch opened and persisted the attempt")
    }

    // T5: recovery works with analytics disabled — the journal is its own store, not consent-gated.
    @Test
    fun `recovery works without any analytics — the store is independent`() {
        val store = FakeStore()
        val c = controller(store, FakeClock())
        c.markUiLaunchStarted()
        assertTrue(store.writes > 0, "persistence happens with no analytics involved")
    }

    // T6: persistence failure → risky work withheld.
    @Test
    fun `persistence failure withholds risky work`() {
        val c = controller(FakeStore(failWrites = true), FakeClock())
        assertFalse(c.isHealthy, "a failed initial write marks the journal unhealthy")
        assertNull(c.beginAttempt(idx, sub), "no attempt token → caller withholds the build")
    }

    // T7: a stale completion cannot overwrite a newer attempt.
    @Test
    fun `a stale completion cannot clobber a newer attempt`() {
        val store = FakeStore()
        val clock = FakeClock()
        val c = controller(store, clock)
        val gen1 = c.beginAttempt(idx, sub)!!
        val gen2 = c.beginAttempt(idx, sub)!!
        assertTrue(gen2 > gen1)
        c.record(idx, sub, gen1, JournalOutcome.COMPLETED) // stale
        clock.now += 10_000L
        assertTrue(restart(store, clock).shouldDefer(idx, sub), "stale completion did not clear the newer attempt")
    }

    // T8: recovery remains active for the current run — the milestone does not restart withheld work.
    @Test
    fun `safe mode stays active for the run after the interactive milestone`() {
        val store = FakeStore()
        val clock = FakeClock()
        repeat(2) { clock.now += 1_000L; restart(store, clock).markUiLaunchStarted() }
        clock.now += 1_000L
        val c = restart(store, clock)
        c.markUiLaunchStarted()
        assertTrue(c.isSafeMode, "safe mode entered")
        c.markInteractiveReached()
        assertTrue(c.isSafeMode, "still safe mode this run — withheld work is not restarted")
    }

    // A: the headless-init gap. A worker reads isSafeMode but never calls decideStartupMode; the
    // getter alone does NOT decide, so on a crash-looped journal it reads fail-open false. This is
    // the exact behaviour the Mobile headless worker hit (nothing app-scoped decided the mode).
    @Test
    fun `isSafeMode without a startup decision is fail-open even on a crash-looped journal`() {
        val store = FakeStore()
        val clock = FakeClock()
        repeat(2) { clock.now += 1_000L; restart(store, clock).markUiLaunchStarted() } // two pre-interactive UI crashes
        clock.now += 1_000L
        val worker = restart(store, clock) // a fresh (worker) process on the persisted journal
        assertFalse(worker.isSafeMode, "reading isSafeMode alone does not decide — fail-open, the bug")
        assertTrue(worker.decideStartupMode(), "an application-scoped decision (the fix) reports the crash loop")
        assertTrue(worker.isSafeMode, "after the decision the worker sees safe mode")
    }

    // A: a worker-only cold start (decideStartupMode, never markUiLaunchStarted — as the app-scoped
    // bootstrap will now do on every process start) enters safe mode from the persisted loop and does
    // NOT open a UI attempt, so it cannot inflate the crash-loop counter.
    @Test
    fun `worker-only cold start decides safe mode without opening a ui attempt`() {
        val store = FakeStore()
        val clock = FakeClock()
        repeat(2) { clock.now += 1_000L; restart(store, clock).markUiLaunchStarted() }
        clock.now += 1_000L
        val worker = restart(store, clock)
        assertTrue(worker.decideStartupMode(), "worker-only run reads the crash loop from disk")
        assertEquals(
            2,
            worker.interruptCount(StartupJournalPolicy.OP_STARTUP_INTERACTIVE, StartupJournalPolicy.SUBJECT_NONE),
            "the worker run added no startup_interactive attempt",
        )
    }

    // A: repeated initialization is idempotent — the app-scoped decide, then the Activity's
    // markUiLaunchStarted, then any redundant decide, advance exactly ONE run and open ONE UI attempt.
    @Test
    fun `app-scoped decide then activity mark then repeat is one run and one ui attempt`() {
        val store = FakeStore()
        val clock = FakeClock()
        val proc = controller(store, clock)
        proc.decideStartupMode() // Application.onCreate (fix)
        val runAfterDecide = proc.currentRunId
        proc.decideStartupMode() // redundant call must not re-sweep / re-advance
        assertEquals(runAfterDecide, proc.currentRunId, "no second run advanced")
        val writesBeforeUi = store.writes
        proc.markUiLaunchStarted() // MainActivity.onCreate
        val writesAfterUi = store.writes
        proc.markUiLaunchStarted() // config-change recreate must not re-open
        assertEquals(writesAfterUi, store.writes, "the second markUiLaunchStarted opened nothing")
        assertTrue(writesAfterUi > writesBeforeUi, "exactly the UI launch opened the attempt")
    }

    // A: storage failure during a headless start — unhealthy journal, fail-open safe mode (cannot read
    // the loop), and risky work withheld. No crash.
    @Test
    fun `headless start with a failing store is unhealthy and withholds risky work`() {
        val c = controller(FakeStore(failWrites = true), FakeClock())
        assertFalse(c.decideStartupMode(), "cannot read the loop from a broken store → fail-open")
        assertFalse(c.isHealthy)
        assertNull(c.beginAttempt(idx, sub), "no token → the resolver withholds the index build")
    }

    // Two different ops do not clobber each other (serialized RMW keeps both).
    @Test
    fun `distinct ops both persist without clobbering`() {
        val store = FakeStore()
        val clock = FakeClock()
        val c = controller(store, clock)
        c.beginAttempt(idx, "a")
        c.beginAttempt(idx, "b")
        clock.now += 10_000L
        val r = restart(store, clock)
        assertTrue(r.shouldDefer(idx, "a"), "op a survived")
        assertTrue(r.shouldDefer(idx, "b"), "op b survived")
    }
}
