package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.telemetry.api.DiagArea
import com.nuvio.app.core.telemetry.api.DiagEntry
import com.nuvio.app.core.telemetry.api.ReportCategory
import com.nuvio.app.core.telemetry.api.ReportContext
import com.nuvio.app.core.telemetry.api.ReportResult
import com.nuvio.app.core.telemetry.api.ReportSurface
import com.nuvio.app.core.telemetry.api.TelemetryPort
import com.nuvio.app.core.telemetry.api.FirstRunNote
import com.nuvio.app.core.telemetry.api.FirstRunStep
import com.nuvio.app.core.telemetry.api.PlaybackSignal
import com.nuvio.app.core.telemetry.api.PlaybackTelemetryBuckets
import com.nuvio.app.core.telemetry.api.PlaybackTelemetryKeys
import com.nuvio.app.core.telemetry.api.QaMirror
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import com.nuvio.app.core.analytics.PostHogPrivacy
import com.nuvio.app.core.build.AppFeaturePolicy

/**
 * The one place that decides what leaves the device. Every SDK call in the shared code goes through here via
 * [TelemetryHost]; feature code only sees [TelemetryPort].
 */
internal class TelemetryService(
    private val host: TelemetryHost,
    internal val recorder: FlightRecorder = FlightRecorder(),
    private val rateLimit: ReportRateLimit = ReportRateLimit(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : TelemetryPort {

    // Read BEFORE the code is generated: a code already on disk is an existing-install signal (first-run funnel).
    private val supportCodeExistedAtStart: Boolean = SupportCodeGenerator.isValid(runCatching { host.loadSupportCode() }.getOrNull())

    override val supportCode: String = resolveSupportCode()

    private val health = SessionHealth(host.nowMs())
    private val screen = atomic<String?>(null)
    private val lastMemory = atomic<Map<String, Long>>(emptyMap())
    private val hardware = atomic<Map<String, Any>>(emptyMap())
    private val samplerJob = atomic<Job?>(null)
    private val sentCrashIds = atomic<Set<String>>(emptySet())

    // First-run funnel: state is read-modify-written under this lock so a step can never be emitted twice.
    private val funnelLock = SynchronizedObject()
    private var funnelState: FirstRunState? = null
    private var funnelStarted = false
    private val visit = FirstLeaveVisit(host.nowMs())
    private val sdkReady = atomic(false)
    private val heldFunnelEvents = atomic<List<Map<String, Any>>>(emptyList())

    // Freed first thing in the crash handler so an OutOfMemoryError still leaves room to write a record.
    private val memoryReserve = atomic<ByteArray?>(ByteArray(MEMORY_RESERVE_BYTES))

    // Ring persistence runs on one background worker: the callers (often Main) only signal.
    private val persistRequests = Channel<Unit>(Channel.CONFLATED)
    private val forcePersist = atomic(false)
    private val previousTrail: String? = host.store.read(RING_FILE)?.takeIf { it.length <= DiagTrailPolicy.MAX_BYTES * 2 }

    // The PREVIOUS run's last memory sample, read once: an `app_exit` (reported one launch late) carries it.
    private val previousMemory: MemoryRecord? = MemoryContext.decode(runCatching { host.store.read(MemoryContext.FILE) }.getOrNull())
    private val lastMemoryRecordedAtMs = atomic(0L)
    private val qaEnabled: Boolean = runCatching { host.qaMirrorEnabled }.getOrDefault(false)

    init {
        scope.launch { for (ignored in persistRequests) persistNow() }
    }

    private fun resolveSupportCode(): String {
        val stored = host.loadSupportCode()
        if (SupportCodeGenerator.isValid(stored)) return stored!!
        return SupportCodeGenerator.generate(host::randomBelow).also { runCatching { host.saveSupportCode(it) } }
    }

    fun start() {
        recorder.record(DiagEntry(host.nowMs(), DiagArea.LIFECYCLE, "app_start"))
    }

    /**
     * Call once the SDK is configured (registering before setup is a silent no-op). Registers the support code at
     * once, then the hardware profile when its probe (codec lists, display modes) finishes off the caller's thread.
     */
    fun onSdkReady() {
        sdkReady.value = true
        flushHeldFunnelEvents()
        runCatching { host.registerSuperProperties(mapOf(SUPPORT_CODE_PROPERTY to supportCode) + buildProperties()) }
        scope.launch {
            val hw = runCatching { host.hardwareProfile() }.getOrDefault(emptyMap())
            hardware.value = hw
            if (hw.isNotEmpty()) runCatching { host.registerSuperProperties(hw + ramClassOf(hw)) }
        }
    }

    /**
     * First-run funnel start, once per process (a second call is ignored). Decides new vs existing ONCE when no state is
     * persisted: existing = a support code was already on disk when this service started, or [otherExistingSignals]
     * (playlists, add-ons, watch history; only asked then). Call it before anything else creates user state.
     */
    fun startFirstRunFunnel(otherExistingSignals: () -> Boolean) {
        val emit = synchronized(funnelLock) {
            if (funnelStarted) return
            funnelStarted = true
            val stored = FirstRunStateCodec.decode(runCatching { host.store.read(FIRST_RUN_FILE) }.getOrNull())
            val t = FirstRunFunnel.start(stored, host.nowMs()) {
                supportCodeExistedAtStart || runCatching(otherExistingSignals).getOrDefault(true)
            }
            commitFunnel(stored, t)
        }
        emit?.let(::deliverFunnelEvent)
    }

    override fun firstRunStep(step: FirstRunStep, props: Map<String, Any>) {
        val emit = synchronized(funnelLock) {
            val t = FirstRunFunnel.record(funnelState, step, host.nowMs(), props)
            commitFunnel(funnelState, t)
        }
        emit?.let(::deliverFunnelEvent)
    }

    /** First-visit facts for `first_leave`. Persisted ones go through the same lock and store as the steps; nothing is sent here. */
    override fun firstRunNote(note: FirstRunNote) {
        synchronized(funnelLock) {
            if (note is FirstRunNote.Screen) { visit.screen(note.name); return }
            val before = funnelState
            val next = FirstRunFunnel.note(before, note) ?: return
            commitFunnel(before, FirstRunTransition(next, null))
        }
    }

    /** Caller holds [funnelLock]. Persists a changed state (best effort: a failed write keeps the in-memory gate). */
    private fun commitFunnel(before: FirstRunState?, t: FirstRunTransition): Map<String, Any>? {
        val next = t.state
        if (next != null) {
            funnelState = next
            if (next != before) runCatching { host.store.writeAtomic(FIRST_RUN_FILE, FirstRunStateCodec.encode(next)) }
        } else if (funnelState == null) {
            funnelState = before
        }
        return t.emit
    }

    /** Before the SDK is up (Android registers features first) a step waits; the consent check runs when it is sent. */
    private fun deliverFunnelEvent(props: Map<String, Any>) {
        if (!sdkReady.value) {
            while (true) {
                val cur = heldFunnelEvents.value
                if (heldFunnelEvents.compareAndSet(cur, cur + props)) break
            }
            if (!sdkReady.value) return
            flushHeldFunnelEvents()
            return
        }
        emit(FirstRunFunnel.EVENT, props)
    }

    private fun flushHeldFunnelEvents() {
        val held = heldFunnelEvents.getAndSet(emptyList())
        for (props in held) emit(FirstRunFunnel.EVENT, props)
    }

    /** Re-register after any SDK reset (a reset clears super properties). */
    fun reRegisterSuperProperties() {
        runCatching {
            host.registerSuperProperties(hardware.value + ramClassOf(hardware.value) + buildProperties() + (SUPPORT_CODE_PROPERTY to supportCode))
        }
    }

    /** `ram_class` rides every event with the hardware profile (failure diagnostics included), from the OS-reported total. */
    private fun ramClassOf(hw: Map<String, Any>): Map<String, Any> {
        val totalMb = (hw["hw_ram_total_mb"] as? Number)?.toLong() ?: return emptyMap()
        return mapOf("ram_class" to PlaybackTelemetryBuckets.ramClass(totalMb))
    }

    private fun buildProperties(): Map<String, Any> = TelemetryBuildProperties.of(
        buildFlavor = AppFeaturePolicy.buildFlavor,
        installSource = runCatching { host.installSource() }.getOrNull(),
    )

    override fun displaySupportCode(): String = SupportCodeGenerator.display(supportCode)

    override fun consentEnabled(): Boolean = runCatching { host.consentEnabled() }.getOrDefault(false)

    override fun record(entry: DiagEntry) {
        recorder.record(entry)
        persistRingIfDue(force = false)
    }

    override fun emit(event: String, props: Map<String, Any>) {
        val decision = TelemetrySendPolicy.decide(event, props)
        recorder.record(entryFor(event, props))
        if (event == TelemetrySendPolicy.SCREEN_EVENT) {
            (props["\$screen_name"] as? String)?.let { screen.value = it }
        }
        when (decision) {
            SendDecision.RECORD_ONLY -> persistRingIfDue(force = false)
            SendDecision.COUNT -> { health.countEvent(event, props); persistRingIfDue(force = false) }
            SendDecision.SEND -> {
                val error = TelemetrySendPolicy.attachesTrail(event, props)
                val out = if (event == APP_EXIT_EVENT) props + MemoryContext.exitContext(props, previousMemory) else props
                sendEvent(event, out, withTrail = error)
                persistRingIfDue(force = error)
            }
        }
    }

    override fun reportError(event: String, props: Map<String, Any>) {
        recorder.record(DiagEntry(host.nowMs(), areaOf(event), codeOf(event), props.filterKeys { it in DiagTrailPolicy.ALLOWED_FIELDS }))
        health.count("error_${codeOf(event)}")
        sendEvent(event, props, withTrail = true)
        persistRingIfDue(force = true)
    }

    override fun recordPlayback(signal: PlaybackSignal) {
        // QA mirror first: local only, exact values, independent of the reports switch (it never leaves the device).
        if (qaEnabled) {
            val (ev, fields) = QaMirror.fieldsFor(signal)
            QaMirror.mirror(true, host::qaMirrorLine, ev, fields)
        }
        // Counts only. With the switch off nothing is hoarded and nothing is sent.
        if (!consentEnabled()) return
        for (key in PlaybackTelemetryKeys.forSignal(signal)) health.countPlaybackKey(key)
    }

    override fun recordUncleanExit(wasPlaying: Boolean, kind: String?, engine: String?, memWarnings: Int) {
        if (!consentEnabled()) return
        val props = LinkedHashMap<String, Any>()
        props["reason"] = UNCLEAN_EXIT_REASON
        props["was_playing"] = wasPlaying
        if (wasPlaying) {
            kind?.let { props["playback_kind"] = it.lowercase().take(24) }
            engine?.let { props["playback_engine"] = it.lowercase().take(24) }
        }
        if (memWarnings > 0) props["mem_warnings"] = memWarnings.coerceAtMost(MAX_MEM_WARNINGS)
        val sent = props + MemoryContext.exitContext(props, previousMemory)
        // Closed values only: the validator drops the whole event rather than send an open string.
        if (PlaybackTelemetryPrivacy.violations(sent).isNotEmpty()) return
        sendEvent(APP_EXIT_EVENT, sent, withTrail = true)
        persistRingIfDue(force = true)
    }

    override fun reportException(error: Throwable, props: Map<String, Any>) {
        if (!consentEnabled()) return
        runCatching { host.captureException(error, PostHogPrivacy.sanitize(props) + (SUPPORT_CODE_PROPERTY to supportCode)) }
    }

    private fun sendEvent(event: String, props: Map<String, Any>, withTrail: Boolean) {
        if (!consentEnabled()) return
        // Second guard besides the call sites: sensitive keys (url, token, ...) dropped, URLs in strings redacted.
        val out = LinkedHashMap<String, Any>(PostHogPrivacy.sanitize(props))
        out["schema_version"] = SCHEMA_VERSION
        if (withTrail) {
            out["diag_trail"] = DiagTrailPolicy.serialize(recorder.snapshot(), host.nowMs(), maxEntries = ERROR_TRAIL_ENTRIES)
        }
        runCatching { host.capture(event, out, null) }
    }

    override suspend fun sendUserReport(
        category: ReportCategory,
        surface: ReportSurface,
        context: ReportContext,
    ): ReportResult {
        if (!consentEnabled()) return ReportResult.ConsentOff
        val now = host.nowMs()
        val wait = rateLimit.tryAcquire(now)
        if (wait > 0L) return ReportResult.RateLimited(ReportRateLimit.secondsLeft(wait))
        return try {
            val memory = withContext(Dispatchers.Default) { runCatching { host.memorySample() }.getOrDefault(emptyMap()) }
            if (memory.isNotEmpty()) lastMemory.value = memory
            val report = buildMap<String, Any> {
                put("schema_version", SCHEMA_VERSION)
                put("report_id", newId())
                put("category", category.name.lowercase())
                put("surface", surface.name.lowercase())
                put("app_version", host.appVersion)
                DiagTrailPolicy.codeOrOther(context.failureCode)?.let { put("failure_code", it) }
                DiagTrailPolicy.codeOrOther(context.engine)?.let { put("engine", it) }
                screen.value?.let { put("screen", it) }
                for ((k, v) in memory) put("mem_$k", v)
                putAll(hardware.value)
                put("diag_trail", DiagTrailPolicy.serialize(recorder.snapshot(), now, maxEntries = REPORT_TRAIL_ENTRIES))
            }
            host.capture(USER_REPORT_EVENT, report, null)
            host.flush()
            recorder.record(DiagEntry(now, DiagArea.LIFECYCLE, "report_sent"))
            ReportResult.Sent(displaySupportCode())
        } catch (c: CancellationException) {
            rateLimit.release()
            throw c
        } catch (_: Throwable) {
            rateLimit.release()
            ReportResult.Failed
        }
    }

    /**
     * Only records. Counters are NOT reset here: iOS `.inactive -> .active` (Control Centre, a call banner) and Android
     * config changes reach this without a background in between, and a reset would silently drop the counts.
     */
    override fun onForeground() {
        val now = host.nowMs()
        recorder.record(DiagEntry(now, DiagArea.LIFECYCLE, "foreground"))
        synchronized(funnelLock) { visit.foreground(now) }
    }

    override fun onBackground() {
        val now = host.nowMs()
        recorder.record(DiagEntry(now, DiagArea.LIFECYCLE, "background"))
        // first_leave: the first time the app leaves the foreground before anything played. Captured here, synchronously,
        // so the flush below carries it; once-gate, consent and the existing-install rule are the funnel's.
        val leave = synchronized(funnelLock) {
            commitFunnel(funnelState, FirstRunFunnel.record(funnelState, FirstRunStep.FIRST_LEAVE, now, visit.props(now)))
        }
        leave?.let(::deliverFunnelEvent)
        // Memory probe, summary, disk and flush all happen off the caller's thread (it is usually Main).
        scope.launch {
            // The process may be suspended any moment now: get the leave event out before the slower work.
            if (leave != null) runCatching { host.flush() }
            sampleMemoryOnce()
            sendHealth()
            persistRingIfDue(force = true)
            runCatching { host.flush() }
        }
    }

    /** Sends `session_health` and resets the counters only AFTER the SDK accepted it. */
    internal fun sendHealth() {
        val now = host.nowMs()
        if (!consentEnabled()) { health.discard(now); return }
        val pending = health.snapshot(now) ?: return
        val accepted = try { host.capture(SESSION_HEALTH_EVENT, pending.payload, null); true } catch (_: Throwable) { false }
        if (accepted) health.commit(pending, now)
    }

    override fun onPlaybackScopeOpened() {
        if (samplerJob.value?.isActive == true) return
        val job = scope.launch {
            while (isActive) {
                sampleMemoryOnce()
                delay(MEMORY_SAMPLE_INTERVAL_MS)
            }
        }
        samplerJob.getAndSet(job)?.cancel()
    }

    override fun onPlaybackScopeClosed() {
        samplerJob.getAndSet(null)?.cancel()
    }

    internal fun sampleMemoryOnce() {
        val sample = runCatching { host.memorySample() }.getOrDefault(emptyMap())
        if (sample.isEmpty()) return
        val now = host.nowMs()
        lastMemory.value = sample
        health.noteMemory(sample["java_mb"], sample["pss_mb"])
        // The ring keeps one memory entry a minute even while the sampler runs every 10 s.
        val lastRecorded = lastMemoryRecordedAtMs.value
        if (now - lastRecorded >= MEMORY_RING_INTERVAL_MS && lastMemoryRecordedAtMs.compareAndSet(lastRecorded, now)) {
            recorder.record(DiagEntry(now, DiagArea.MEMORY, "sample", sample))
        }
        if (qaEnabled) QaMirror.mirror(true, host::qaMirrorLine, "mem", sample.filterKeys { it in MemoryContext.PROPERTY_KEYS })
        // Persisted so a kill (low memory, native crash) still leaves the last numbers for the next launch's `app_exit`.
        if (consentEnabled()) {
            runCatching { host.store.writeAtomic(MemoryContext.FILE, MemoryContext.encode(MemoryRecord(now, sample))) }
        } else {
            runCatching { host.store.delete(MemoryContext.FILE) }
        }
    }

    override fun previousSessionTrail(): String? = previousTrail

    // --- crash path ---------------------------------------------------------------------------------------

    /**
     * Runs in the dying thread. No coroutines, no DI, no locks: the ring is read lock-free. Writes nothing when
     * reports are off (a crash from while they were off must not be sent after they are turned on). A minimal
     * fixed-shape record goes down first, then the full one replaces it, so an OutOfMemoryError that kills the
     * second allocation still leaves a record. Never throws.
     */
    fun handleCrash(threadName: String, error: Throwable) {
        try {
            memoryReserve.value = null
            if (!runCatching { host.consentEnabled() }.getOrDefault(false)) return
            val now = host.nowMs()
            val minimal = CrashRecord(
                crash_id = newId(),
                support_code = supportCode,
                app_version = host.appVersion,
                at_ms = now,
                thread = DiagTrailPolicy.threadLabel(threadName),
                exception_type = CrashRecordCodec.exceptionType(error),
            )
            host.store.writeAtomic(PENDING_CRASH_FILE, CrashRecordCodec.encode(minimal))
            val full = minimal.copy(
                stack = CrashRecordCodec.framesOf(error),
                trail = DiagTrailPolicy.serialize(recorder.tail(DiagTrailPolicy.MAX_ENTRIES), now),
                memory = lastMemory.value,
                screen = screen.value,
            )
            host.store.writeAtomic(PENDING_CRASH_FILE, CrashRecordCodec.encode(full))
        } catch (_: Throwable) {
            // Never let crash capture replace the real crash.
        }
    }

    /**
     * At-least-once with an idempotency key (`crash_id`). A record stays on disk until delivery is confirmed: right
     * after flush when the host's queue is durable, otherwise once the app has run [DELIVERY_CONFIRM_MS] longer and
     * flushed again (a quick kill after the first flush can still lose the event; the next launch re-sends the same
     * id). Cancellation is rethrown; nothing else reaches startup.
     */
    override suspend fun sendPendingCrash() {
        try {
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(SEND_PENDING_DEADLINE_MS) {
                    for (name in listOf(SENDING_CRASH_FILE, PENDING_CRASH_FILE)) sendCrashFile(name)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // A failed send leaves the file for the next launch; it never reaches startup.
        }
    }

    private fun sendCrashFile(name: String) {
        val text = host.store.read(name) ?: return
        val record = CrashRecordCodec.decode(text)
        if (record == null || !consentEnabled()) {
            // Unreadable, or reports are off: keep nothing on disk.
            host.store.delete(name)
            return
        }
        if (record.crash_id in sentCrashIds.value) return
        host.capture(CRASH_EVENT, crashProperties(record), record.at_ms)
        host.flush()
        sentCrashIds.value = sentCrashIds.value + record.crash_id
        if (host.deliveryDurable) {
            host.store.delete(name)
        } else {
            scope.launch {
                delay(DELIVERY_CONFIRM_MS)
                runCatching { host.flush() }
                if (host.store.read(name)?.contains(record.crash_id) == true) host.store.delete(name)
            }
        }
    }

    private fun crashProperties(r: CrashRecord): Map<String, Any> = buildMap {
        put("schema_version", CrashRecordCodec.SCHEMA_VERSION)
        put("crash_id", r.crash_id)
        put(SUPPORT_CODE_PROPERTY, r.support_code)
        put("crashed_at_ms", r.at_ms)
        put("crashed_app_version", r.app_version)
        put("thread", r.thread)
        put("exception_type", r.exception_type)
        put("stack", r.stack.joinToString("\n"))
        r.trail?.let { put("diag_trail", it) }
        r.screen?.let { put("screen", it) }
        for ((k, v) in r.memory) put("mem_$k", v)
        putAll(hardware.value)
    }

    // --- ring snapshot ------------------------------------------------------------------------------------

    private fun persistRingIfDue(force: Boolean) {
        if (force) forcePersist.value = true
        persistRequests.trySend(Unit)
    }

    /** Worker-side: consent gate, delta gate, JSON, atomic write. Off the caller's thread. */
    private fun persistNow() {
        val force = forcePersist.getAndSet(false)
        if (!consentEnabled()) {
            // Reports are off: the ring stays in memory only, and a snapshot from earlier is removed.
            host.store.delete(RING_FILE)
            return
        }
        val now = host.nowMs()
        if (!recorder.shouldPersist(now, force = force)) return
        val ok = runCatching {
            host.store.writeAtomic(RING_FILE, DiagTrailPolicy.serialize(recorder.snapshot(), now, maxEntries = RING_PERSIST_ENTRIES))
        }.getOrDefault(false)
        if (!ok) recorder.markDirty()
    }

    private fun newId(): String = buildString(24) { repeat(24) { append(ID_ALPHABET[host.randomBelow(ID_ALPHABET.length)]) } }

    private fun areaOf(event: String): DiagArea = when {
        event.startsWith("iptv") -> DiagArea.IPTV_LOAD
        event.startsWith("epg") -> DiagArea.EPG
        event == TelemetrySendPolicy.SCREEN_EVENT -> DiagArea.SCREEN
        event.startsWith("app_") || event.startsWith("session") -> DiagArea.LIFECYCLE
        else -> DiagArea.PLAYBACK
    }

    private fun codeOf(event: String): String =
        event.lowercase().filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "event" }.take(40)

    private fun entryFor(event: String, props: Map<String, Any>): DiagEntry {
        val fields = LinkedHashMap<String, Any>()
        for ((k, v) in props) {
            if (k == "\$screen_name") fields["screen"] = v else fields[k] = v
        }
        return DiagEntry(host.nowMs(), areaOf(event), codeOf(event), fields)
    }

    companion object {
        const val SUPPORT_CODE_PROPERTY = "support_code"
        const val USER_REPORT_EVENT = "user_issue_report"
        const val CRASH_EVENT = "app_crash_report"
        const val SESSION_HEALTH_EVENT = "session_health"
        const val PENDING_CRASH_FILE = "pending_crash.json"
        const val SENDING_CRASH_FILE = "sending.json"
        const val RING_FILE = "ring.json"
        const val FIRST_RUN_FILE = "first_run_funnel.json"
        const val SCHEMA_VERSION = 1
        const val ERROR_TRAIL_ENTRIES = 40
        const val REPORT_TRAIL_ENTRIES = 80
        const val RING_PERSIST_ENTRIES = 80
        /** At most every 10 s, and only while a playback scope is open (the sampler runs nowhere else). */
        const val MEMORY_SAMPLE_INTERVAL_MS = 10_000L
        const val MEMORY_RING_INTERVAL_MS = 60_000L
        const val APP_EXIT_EVENT = "app_exit"
        const val UNCLEAN_EXIT_REASON = "unclean_exit_suspected"
        private const val MAX_MEM_WARNINGS = 1000
        const val SEND_PENDING_DEADLINE_MS = 10_000L
        const val DELIVERY_CONFIRM_MS = 90_000L
        private const val MEMORY_RESERVE_BYTES = 32 * 1024
        private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
