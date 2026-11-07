package com.tuvora.tvos.telemetry

import com.nuvio.app.core.analytics.AnalyticsSink
import com.nuvio.app.core.telemetry.api.DiagEntry
import com.nuvio.app.core.telemetry.api.ReportCategory
import com.nuvio.app.core.telemetry.api.ReportContext
import com.nuvio.app.core.telemetry.api.ReportResult
import com.nuvio.app.core.telemetry.api.ReportSurface
import com.nuvio.app.core.telemetry.api.TelemetryPort
import com.tuvora.tvos.screens.TvPrivacySettings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import kotlin.time.Clock

/**
 * Swift's one entry point to Telemetry v2 on Apple TV: the support code, "Send report", and the crash record.
 * Logic stays in Kotlin; Swift renders results and calls [sendReport] from a Task (off Main).
 *
 * Until the Mobile lane's `core/telemetry` is merged, the port is [InterimTvTelemetryPort]. Merging swaps
 * `port` for the real one; nothing in Swift changes (see TelemetryContractStub.kt).
 */
object TvTelemetry {
    private var port: TelemetryPort = InterimTvTelemetryPort()

    /** `K7Q2-9MPX`. Safe to call before [TvAppGraph.start]: it only needs UserDefaults. */
    val displaySupportCode: String get() = TvSupportCode.display(port.supportCode)

    /** The raw 8 characters, for the PostHog `support_code` super property. */
    val supportCode: String get() = port.supportCode

    suspend fun sendReport(category: ReportCategory, surface: ReportSurface, engine: String?, failureCode: String?): ReportResult =
        port.sendUserReport(category, surface, ReportContext(engine = engine, failureCode = failureCode))

    /** Sends a crash record left by the previous run (at-least-once: sending.json survives a failed send). */
    fun sendPendingCrash() = TvCrashFiles.sendPending()

    /** Infers the category from what the player knows; the error screen has no picker. */
    fun categoryFor(failureCode: String?): ReportCategory = when {
        failureCode == null -> ReportCategory.OTHER
        else -> ReportCategory.WONT_PLAY
    }
}

/** The pre-merge port. Consent and the 120 s limit are enforced here, in Kotlin, never in Swift. */
internal class InterimTvTelemetryPort : TelemetryPort {
    private val limit = TvReportRateLimit()

    override val supportCode: String by lazy { TvSupportStore.load() ?: TvSupportStore.createAndSave() }

    // The flight recorder ring is the Mobile lane's; nothing to record into until it lands.
    override fun record(entry: DiagEntry) = Unit

    override fun reportError(event: String, props: Map<String, Any>) {
        if (!TvPrivacySettings.diagnostics.value) return
        AnalyticsSink.capture(event, props + ("support_code" to supportCode))
    }

    override suspend fun sendUserReport(category: ReportCategory, surface: ReportSurface, context: ReportContext): ReportResult {
        if (!TvPrivacySettings.diagnostics.value) return ReportResult.ConsentOff
        val now = Clock.System.now().toEpochMilliseconds()
        val wait = limit.retryInSec(now)
        if (wait > 0) return ReportResult.RateLimited(wait)
        limit.markSent(now)
        val props = mutableMapOf<String, Any>(
            "schema_version" to 1,
            "report_id" to NSUUID().UUIDString(),
            "support_code" to supportCode,
            "category" to category.name.lowercase(),
            "surface" to surface.name.lowercase(),
            "platform" to "tvos",
        )
        context.engine?.let { props["engine"] = it }
        context.failureCode?.let { props["failure_code"] = it }
        AnalyticsSink.capture("user_issue_report", props)
        return ReportResult.Sent(TvSupportCode.display(supportCode))
    }
}

/** The support code lives in the durable storage tier (TvStorageTierPolicy): a cache purge must not rotate it. */
internal object TvSupportStore {
    const val KEY = "telemetry_support_code"
    fun load(): String? = (NSUserDefaults.standardUserDefaults.stringForKey(KEY))?.takeIf { TvSupportCode.isValid(it) }

    fun createAndSave(): String {
        val code = TvSupportCode.generate(::secureRandomInt)
        NSUserDefaults.standardUserDefaults.setObject(code, forKey = KEY)
        return code
    }
}

/** Uniform in [0, bound) from SecRandomCopyBytes, rejecting the biased tail. */
@OptIn(ExperimentalForeignApi::class)
internal fun secureRandomInt(bound: Int): Int {
    val limit = 0x10000 - (0x10000 % bound)
    val bytes = ByteArray(2)
    while (true) {
        bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, 2u, it.addressOf(0)) }
        val v = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (v < limit) return v % bound
    }
}

/** `files/telemetry/pending_crash.json` (contract) under the app's Caches data directory. */
@OptIn(ExperimentalForeignApi::class)
internal object TvCrashFiles {
    private fun ensureDir(): String {
        val d = com.nuvio.app.core.storage.AppleDataDirectory.databases() + "/telemetry"
        NSFileManager.defaultManager.createDirectoryAtPath(d, withIntermediateDirectories = true, attributes = null, error = null)
        return d
    }

    /**
     * Called from the unhandled-exception hook: synchronous, no coroutines, never throws. Writes via a temp
     * file and rename so a half-written record is never read back.
     */
    fun writeSynchronously(throwable: Throwable) {
        runCatching {
            val d = ensureDir()
            val json = TvCrashRecord.encode(
                crashId = NSUUID().UUIDString(),
                supportCode = TvSupportStore.load() ?: "",
                appVersion = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "",
                atMs = Clock.System.now().toEpochMilliseconds(),
                thread = "main",
                exceptionType = throwable::class.qualifiedName ?: throwable::class.simpleName ?: "Throwable",
                stackFrames = TvCrashRecord.framesOf(throwable.stackTraceToString()),
                memoryPhysicalBytes = platform.Foundation.NSProcessInfo.processInfo.physicalMemory.toLong(),
            )
            val tmp = "$d/pending_crash.json.tmp"
            (NSString.create(string = json)).writeToFile(tmp, atomically = false, encoding = NSUTF8StringEncoding, error = null)
            val fm = NSFileManager.defaultManager
            fm.removeItemAtPath("$d/pending_crash.json", error = null)
            fm.moveItemAtPath(tmp, toPath = "$d/pending_crash.json", error = null)
        }
    }

    /** pending -> sending -> capture -> delete. A leftover sending.json is re-sent (same crash_id; queries dedupe). */
    fun sendPending() {
        runCatching {
            val d = ensureDir()
            val fm = NSFileManager.defaultManager
            val pending = "$d/pending_crash.json"
            val sending = "$d/sending.json"
            if (fm.fileExistsAtPath(pending)) {
                fm.removeItemAtPath(sending, error = null)
                fm.moveItemAtPath(pending, toPath = sending, error = null)
            }
            if (!fm.fileExistsAtPath(sending)) return
            val text = NSString.create(contentsOfFile = sending, encoding = NSUTF8StringEncoding, error = null)?.toString() ?: return
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject ?: return
            fun str(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (!TvPrivacySettings.diagnostics.value) { fm.removeItemAtPath(sending, error = null); return }
            AnalyticsSink.capture("app_crash_report", buildMap<String, Any> {
                put("schema_version", 1)
                str("crash_id")?.let { put("crash_id", it) }
                str("support_code")?.let { put("support_code", it) }
                str("app_version")?.let { put("crash_app_version", it) }
                str("at_ms")?.toLongOrNull()?.let { put("crashed_at_ms", it) }
                str("exception_type")?.let { put("exception_type", it) }
                put("stack", (obj["stack"] as? kotlinx.serialization.json.JsonArray)?.joinToString("\n") {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                }?.take(8000) ?: "")
                put("platform", "tvos")
            })
            fm.removeItemAtPath(sending, error = null)
        }
    }
}
