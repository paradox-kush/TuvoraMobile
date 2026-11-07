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

/**
 * The one place that decides what leaves the device. Every SDK call in the shared code goes through here via
 * [TelemetryHost]; feature code only sees [TelemetryPort].
 */
internal class TelemetryService(
    private val host: TelemetryHost,
    internal val recorder: FlightRecorder = FlightRecorder(),
    private val rateLimit: ReportRateLimit = ReportRateLimit(),
) : TelemetryPort {

    override val supportCode: String = resolveSupportCode()

    private val health = atomic(SessionHealth(host.nowMs()))
    private val screen = atomic<String?>(null)
    private val lastMemory = atomic<Map<String, Long>>(emptyMap())
    private val hardware = atomic<Map<String, Any>>(emptyMap())
    private val samplerJob = atomic<Job?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val previousTrail: String? = host.store.read(RING_FILE)?.takeIf { it.length <= DiagTrailPolicy.MAX_BYTES * 2 }

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
            SendDecision.COUNT -> { health.value.countEvent(event, props); persistRingIfDue(force = false) }
            SendDecision.SEND -> {
                val error = TelemetrySendPolicy.attachesTrail(event, props)
                sendEvent(event, props, withTrail = error)
                persistRingIfDue(force = error)
            }
        }
    }

    override fun reportError(event: String, props: Map<String, Any>) {
        recorder.record(DiagEntry(host.nowMs(), areaOf(event), codeOf(event), props.filterKeys { it in DiagTrailPolicy.ALLOWED_FIELDS }))
        health.value.count("error_${codeOf(event)}")
        sendEvent(event, props, withTrail = true)
        persistRingIfDue(force = true)
    }

    override fun reportException(error: Throwable, props: Map<String, Any>) {
        if (!consentEnabled()) return
        runCatching { host.captureException(error, props + (SUPPORT_CODE_PROPERTY to supportCode)) }
    }

    private fun sendEvent(event: String, props: Map<String, Any>, withTrail: Boolean) {
        if (!consentEnabled()) return
        val out = LinkedHashMap<String, Any>(props)
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
                context.failureCode?.let { put("failure_code", DiagTrailPolicy.scrubText(it, 48)) }
                context.engine?.let { put("engine", DiagTrailPolicy.scrubText(it, 24)) }
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

    override fun onForeground() {
        health.value = SessionHealth(host.nowMs())
        recorder.record(DiagEntry(host.nowMs(), DiagArea.LIFECYCLE, "foreground"))
    }

    override fun onBackground() {
        val now = host.nowMs()
        recorder.record(DiagEntry(now, DiagArea.LIFECYCLE, "background"))
        sampleMemoryOnce()
        health.value.summary(now)?.let { summary ->
            if (consentEnabled()) runCatching { host.capture(SESSION_HEALTH_EVENT, summary, null) }
        }
        persistRingIfDue(force = true)
        runCatching { host.flush() }
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
        health.value.noteMemory(sample["java_mb"], sample["pss_mb"])
        recorder.record(DiagEntry(host.nowMs(), DiagArea.MEMORY, "sample", sample))
    }

    override fun previousSessionTrail(): String? = previousTrail

    // --- crash path ---------------------------------------------------------------------------------------

    /**
     * Runs in the dying thread. No coroutines, no DI, no blocking lock: the ring is tried with a short timeout and
     * the record is written without the trail if it cannot be had. Never throws.
     */
    fun handleCrash(threadName: String, error: Throwable) {
        try {
            val now = host.nowMs()
            val tail = recorder.tail(DiagTrailPolicy.MAX_ENTRIES)
            val record = CrashRecord(
                crash_id = newId(),
                support_code = supportCode,
                app_version = host.appVersion,
                at_ms = now,
                thread = DiagTrailPolicy.scrubText(threadName, 48),
                exception_type = CrashRecordCodec.exceptionType(error),
                message = CrashRecordCodec.messageOf(error),
                stack = CrashRecordCodec.framesOf(error),
                trail = tail?.let { DiagTrailPolicy.serialize(it, now) },
                memory = lastMemory.value,
                screen = screen.value,
            )
            host.store.writeAtomic(PENDING_CRASH_FILE, CrashRecordCodec.encode(record))
        } catch (_: Throwable) {
            // Never let crash capture replace the real crash.
        }
    }

    /**
     * At-least-once with an idempotency key: pending -> sending, capture, flush, delete. A leftover `sending` file
     * from a run that died mid-send is sent again with the same `crash_id`. Cancellation is rethrown.
     */
    override suspend fun sendPendingCrash() {
        try {
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(SEND_PENDING_DEADLINE_MS) {
                    if (host.store.read(PENDING_CRASH_FILE) != null && host.store.read(SENDING_CRASH_FILE) == null) {
                        host.store.rename(PENDING_CRASH_FILE, SENDING_CRASH_FILE)
                    }
                    val text = host.store.read(SENDING_CRASH_FILE) ?: return@withTimeoutOrNull
                    val record = CrashRecordCodec.decode(text)
                    if (record == null || !consentEnabled()) {
                        // Unreadable, or the user turned reports off: keep nothing on disk.
                        host.store.delete(SENDING_CRASH_FILE)
                        return@withTimeoutOrNull
                    }
                    host.capture(CRASH_EVENT, crashProperties(record), record.at_ms)
                    host.flush()
                    host.store.delete(SENDING_CRASH_FILE)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // A failed send leaves sending.json for the next launch; it never reaches startup.
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
        put("message", r.message.orEmpty())
        put("stack", r.stack.joinToString("\n"))
        r.trail?.let { put("diag_trail", it) }
        r.screen?.let { put("screen", it) }
        for ((k, v) in r.memory) put("mem_$k", v)
        putAll(hardware.value)
    }

    // --- ring snapshot ------------------------------------------------------------------------------------

    private fun persistRingIfDue(force: Boolean) {
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
        private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
