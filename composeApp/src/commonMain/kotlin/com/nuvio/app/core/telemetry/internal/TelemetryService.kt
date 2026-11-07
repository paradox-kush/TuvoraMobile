package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.telemetry.api.DiagArea
import com.nuvio.app.core.telemetry.api.DiagEntry
import com.nuvio.app.core.telemetry.api.ReportCategory
import com.nuvio.app.core.telemetry.api.ReportContext
import com.nuvio.app.core.telemetry.api.ReportResult
import com.nuvio.app.core.telemetry.api.ReportSurface
import com.nuvio.app.core.telemetry.api.TelemetryPort
import kotlinx.atomicfu.atomic
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

    override val supportCode: String = resolveSupportCode()

    private val health = SessionHealth(host.nowMs())
    private val screen = atomic<String?>(null)
    private val lastMemory = atomic<Map<String, Long>>(emptyMap())
    private val hardware = atomic<Map<String, Any>>(emptyMap())
    private val samplerJob = atomic<Job?>(null)
    private val sentCrashIds = atomic<Set<String>>(emptySet())

    // Freed first thing in the crash handler so an OutOfMemoryError still leaves room to write a record.
    private val memoryReserve = atomic<ByteArray?>(ByteArray(MEMORY_RESERVE_BYTES))

    // Ring persistence runs on one background worker: the callers (often Main) only signal.
    private val persistRequests = Channel<Unit>(Channel.CONFLATED)
    private val forcePersist = atomic(false)
    private val previousTrail: String? = host.store.read(RING_FILE)?.takeIf { it.length <= DiagTrailPolicy.MAX_BYTES * 2 }

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
        runCatching { host.registerSuperProperties(mapOf(SUPPORT_CODE_PROPERTY to supportCode)) }
        scope.launch {
            val hw = runCatching { host.hardwareProfile() }.getOrDefault(emptyMap())
            hardware.value = hw
            if (hw.isNotEmpty()) runCatching { host.registerSuperProperties(hw) }
        }
    }

    /** Re-register after any SDK reset (a reset clears super properties). */
    fun reRegisterSuperProperties() {
        runCatching { host.registerSuperProperties(hardware.value + (SUPPORT_CODE_PROPERTY to supportCode)) }
    }

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
                sendEvent(event, props, withTrail = error)
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
        recorder.record(DiagEntry(host.nowMs(), DiagArea.LIFECYCLE, "foreground"))
    }

    override fun onBackground() {
        recorder.record(DiagEntry(host.nowMs(), DiagArea.LIFECYCLE, "background"))
        // Memory probe, summary, disk and flush all happen off the caller's thread (it is usually Main).
        scope.launch {
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
        lastMemory.value = sample
        health.noteMemory(sample["java_mb"], sample["pss_mb"])
        recorder.record(DiagEntry(host.nowMs(), DiagArea.MEMORY, "sample", sample))
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
        const val SCHEMA_VERSION = 1
        const val ERROR_TRAIL_ENTRIES = 40
        const val REPORT_TRAIL_ENTRIES = 80
        const val RING_PERSIST_ENTRIES = 80
        const val MEMORY_SAMPLE_INTERVAL_MS = 60_000L
        const val SEND_PENDING_DEADLINE_MS = 10_000L
        const val DELIVERY_CONFIRM_MS = 90_000L
        private const val MEMORY_RESERVE_BYTES = 32 * 1024
        private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
