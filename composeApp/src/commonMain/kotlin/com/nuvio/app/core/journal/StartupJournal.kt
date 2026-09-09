package com.nuvio.app.core.journal

import com.nuvio.app.core.build.AppVersionConfig
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** The durable-persistence seam the controller depends on. A production adapter wraps the platform
 *  [StartupJournalStore]; tests supply an in-memory fake with a write-failure toggle. */
internal interface JournalStore {
    fun read(): String?
    fun writeVerified(content: String): Boolean
}

/**
 * All journal + recovery-gate logic, parameterized on a [JournalStore], a clock, and a build id, so
 * the whole lifecycle is driven by integration tests with an in-memory store and a controllable
 * clock — no process, OOM, or 192 MiB heap required. [StartupJournal] wraps one production instance.
 *
 * CONCURRENCY: read-modify-write is serialized by a single monitor. Every mutation, under the lock,
 * reads the in-memory [journal], applies one pure [StartupJournalPolicy] step, persists, and swaps.
 * Concurrent writers cannot lose updates because the whole RMW is atomic — the generation guard in
 * [StartupJournalPolicy.record] is an ADDITIONAL protection against a stale completion, not the only
 * one. All entry points (UI, WorkManager worker, JobService) run in the one app process (no
 * `android:process`), so this in-process lock is sufficient; there is no cross-process writer.
 *
 * PERSISTENCE: [JournalStore.writeVerified] proves the bytes are written and re-readable now (the
 * write reached the filesystem and is self-consistent) — it does NOT by itself prove power-loss
 * durability. The threat this design targets is PROCESS DEATH (OS kill / crash), against which the
 * attempt record is on disk before the risky work runs and survives into the next launch. Physical
 * power-loss durability additionally depends on the platform store's fsync/atomic-rename (documented
 * per store).
 */
internal class StartupJournalController(
    private val store: JournalStore,
    private val clock: () -> Long,
    private val buildId: () -> String,
) {
    private val lock = SynchronizedObject()

    @Volatile private var initialized = false
    @Volatile private var healthy = false
    @Volatile private var runId = 0L
    private var journal = Journal()

    // Recovery-gate state.
    @Volatile private var gated = false
    @Volatile private var safeMode = false
    @Volatile private var uiLaunchMarked = false
    @Volatile private var startupToken: Long? = null

    val isHealthy: Boolean get() { ensureInitialized(); return healthy }
    val currentRunId: Long get() { ensureInitialized(); return runId }

    /** Stable for the whole run: decided once by [decideStartupMode]/[markUiLaunchStarted] and never
     *  recomputed, so recording the interactive milestone does NOT flip it off mid-run and restart
     *  the withheld work. */
    val isSafeMode: Boolean get() { ensureInitialized(); return safeMode }

    fun ensureInitialized() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val raw = runCatching { store.read() }.getOrNull()
            val (advanced, newRun) = StartupJournalPolicy.startRun(StartupJournalPolicy.decode(raw), clock())
            runId = newRun
            journal = advanced
            healthy = persistLocked(advanced)
            initialized = true
        }
    }

    private fun persistLocked(j: Journal): Boolean =
        runCatching { store.writeVerified(StartupJournalPolicy.encode(j)) }.getOrDefault(false)

    /** Records an attempt before risky work; null if it could not be durably persisted → the caller
     *  MUST withhold the risky work this run. */
    fun beginAttempt(op: String, subject: String): Long? {
        ensureInitialized()
        synchronized(lock) {
            if (!healthy) return null
            val (updated, generation) = StartupJournalPolicy.begin(journal, op, subject, runId, buildId(), clock())
            if (!persistLocked(updated)) {
                healthy = false
                return null
            }
            journal = updated
            return generation
        }
    }

    fun record(op: String, subject: String, token: Long, outcome: JournalOutcome) {
        synchronized(lock) {
            val updated = StartupJournalPolicy.record(journal, op, subject, token, outcome, clock())
            if (updated !== journal) {
                journal = updated
                persistLocked(updated)
            }
        }
    }

    fun shouldDefer(op: String, subject: String): Boolean {
        ensureInitialized()
        synchronized(lock) { return StartupJournalPolicy.shouldDefer(journal, op, subject, clock()) }
    }

    fun interruptCount(op: String, subject: String): Int {
        ensureInitialized()
        synchronized(lock) { return StartupJournalPolicy.interruptCount(journal, op, subject) }
    }

    // --- Recovery gate ---

    /**
     * DECISION ONLY — safe to call on ANY process start, including a headless worker/JobService run.
     * Sweeps + advances the run and decides safe mode from prior consecutive PRE-INTERACTIVE
     * interruptions. It does NOT open a startup attempt, so a headless run never accumulates a failed
     * `startup_interactive`. Idempotent.
     */
    fun decideStartupMode(): Boolean {
        ensureInitialized()
        synchronized(lock) {
            if (gated) return safeMode
            gated = true
            safeMode = StartupJournalPolicy.shouldEnterSafeMode(
                StartupJournalPolicy.interruptCount(journal, StartupJournalPolicy.OP_STARTUP_INTERACTIVE, StartupJournalPolicy.SUBJECT_NONE),
            )
            return safeMode
        }
    }

    /**
     * Call ONLY from a real UI-launch entry point (the Activity / first content), once per process.
     * Opens the "reached interactive" attempt. A headless run never calls this, so it cannot inflate
     * the crash-loop counter. Idempotent per process (a config-change recreate does not re-open).
     */
    fun markUiLaunchStarted() {
        decideStartupMode()
        synchronized(lock) {
            if (uiLaunchMarked) return
            uiLaunchMarked = true
        }
        startupToken = beginAttempt(StartupJournalPolicy.OP_STARTUP_INTERACTIVE, StartupJournalPolicy.SUBJECT_NONE)
    }

    /**
     * READINESS milestone. Call once the navigation host has composed a real, navigable screen route
     * (see [StartupJournal] for the exact condition). Records the startup attempt COMPLETED, which
     * resets the loop counter for the NEXT launch. It deliberately does NOT clear [isSafeMode] for the
     * CURRENT run, so already-withheld work is not restarted mid-run. Idempotent.
     */
    fun markInteractiveReached() {
        val token = startupToken ?: return
        startupToken = null
        record(StartupJournalPolicy.OP_STARTUP_INTERACTIVE, StartupJournalPolicy.SUBJECT_NONE, token, JournalOutcome.COMPLETED)
    }
}

/**
 * Process-wide singleton over one [StartupJournalController]. Production call sites use these names
 * unchanged; the controller is what tests instantiate directly.
 *
 * READINESS CONDITION (Step C): [markInteractiveReached] fires from the first real screen-route
 * breadcrumb (`Breadcrumbs.screenChanged`) — i.e. the navigation host composed a navigable, non-
 * splash screen, which in safe mode is reachable because optional work is deferred, keeping the shell
 * usable. This is the crash-loop discriminator (a device dying before first frame never reaches it).
 * KNOWN GAP: this is "a route composed", not a verified first-DRAW; a stricter frame-rendered signal
 * is a documented follow-up, not wired this turn.
 */
internal object StartupJournal {
    const val OP_INDEX_BUILD = StartupJournalPolicy.OP_INDEX_BUILD
    const val OP_STARTUP_INTERACTIVE = StartupJournalPolicy.OP_STARTUP_INTERACTIVE
    const val SUBJECT_NONE = StartupJournalPolicy.SUBJECT_NONE

    private val impl = StartupJournalController(
        store = ProductionJournalStore,
        clock = ::journalNowMs,
        buildId = { AppVersionConfig.VERSION_NAME.ifBlank { "dev" }.take(48) },
    )

    val isHealthy: Boolean get() = impl.isHealthy
    val currentRunId: Long get() = impl.currentRunId
    val isSafeMode: Boolean get() = impl.isSafeMode

    fun ensureInitialized() = impl.ensureInitialized()
    fun beginAttempt(op: String, subject: String): Long? = impl.beginAttempt(op, subject)
    fun record(op: String, subject: String, token: Long, outcome: JournalOutcome) = impl.record(op, subject, token, outcome)
    fun shouldDefer(op: String, subject: String): Boolean = impl.shouldDefer(op, subject)
    fun interruptCount(op: String, subject: String): Int = impl.interruptCount(op, subject)
    fun decideStartupMode(): Boolean = impl.decideStartupMode()
    fun markUiLaunchStarted() = impl.markUiLaunchStarted()
    fun markInteractiveReached() = impl.markInteractiveReached()
}

private object ProductionJournalStore : JournalStore {
    override fun read(): String? = StartupJournalStore.read()
    override fun writeVerified(content: String): Boolean = StartupJournalStore.writeVerified(content)
}
