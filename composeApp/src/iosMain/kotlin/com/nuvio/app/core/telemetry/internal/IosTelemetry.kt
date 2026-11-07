package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.analytics.AnalyticsSink
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.storage.AppleDataDirectory
import com.nuvio.app.core.telemetry.api.TelemetryStartup
import com.nuvio.app.features.settings.SentrySettingsRepository
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSProcessInfo
import platform.Foundation.writeToFile
import platform.posix.arc4random_uniform
import platform.posix.uname
import platform.posix.utsname
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIUserInterfaceIdiomPhone
import platform.UIKit.UIUserInterfaceIdiomTV
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/** App-private files in `<documents>/telemetry/` (Apple TV's own `AppleDataDirectory` points at Caches). */
@OptIn(ExperimentalForeignApi::class)
internal class IosTelemetryFileStore : TelemetryFileStore {
    private val dir: String by lazy {
        val d = AppleDataDirectory.documents() + "/telemetry"
        NSFileManager.defaultManager.createDirectoryAtPath(d, withIntermediateDirectories = true, attributes = null, error = null)
        d
    }

    override fun read(name: String): String? = runCatching {
        val path = "$dir/$name"
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) null
        else NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)?.toString()?.takeIf { it.length <= MAX_READ_CHARS }
    }.getOrNull()

    override fun writeAtomic(name: String, text: String): Boolean = runCatching {
        // `atomically = true` writes a temp file and renames, so a crash mid-write never leaves half a file.
        @Suppress("CAST_NEVER_SUCCEEDS")
        (text as NSString).writeToFile("$dir/$name", atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }.getOrDefault(false)

    override fun rename(from: String, to: String): Boolean = runCatching {
        val fm = NSFileManager.defaultManager
        fm.removeItemAtPath("$dir/$to", null)
        fm.moveItemAtPath("$dir/$from", "$dir/$to", null)
    }.getOrDefault(false)

    override fun delete(name: String) {
        runCatching { NSFileManager.defaultManager.removeItemAtPath("$dir/$name", null) }
    }

    private companion object { const val MAX_READ_CHARS = 256 * 1024 }
}

/** The Apple door to PostHog: the Swift bridge registers sinks on [AnalyticsSink]; only this file calls them. */
@OptIn(ExperimentalForeignApi::class)
internal class IosTelemetryHost : TelemetryHost {
    override val appVersion: String = AppVersionConfig.VERSION_NAME
    override val store: TelemetryFileStore = IosTelemetryFileStore()

    override fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
    override fun randomBelow(bound: Int): Int = arc4random_uniform(bound.convert()).toInt()

    override fun loadSupportCode(): String? = NSUserDefaults.standardUserDefaults.stringForKey(SUPPORT_CODE_KEY)
    override fun saveSupportCode(code: String) { NSUserDefaults.standardUserDefaults.setObject(code, forKey = SUPPORT_CODE_KEY) }

    override fun consentEnabled(): Boolean {
        SentrySettingsRepository.ensureLoaded()
        return SentrySettingsRepository.enabled.value
    }

    override fun capture(event: String, props: Map<String, Any>, timestampMs: Long?) {
        if (timestampMs != null) AnalyticsSink.captureAt(event, props, timestampMs) else AnalyticsSink.capture(event, props)
    }

    override fun captureException(error: Throwable, props: Map<String, Any>) {
        AnalyticsSink.capture("process_exit_exception", props + ("exception_type" to CrashRecordCodec.exceptionType(error)))
    }

    override fun flush() = AnalyticsSink.flush()

    override fun registerSuperProperties(props: Map<String, Any>) = AnalyticsSink.registerSuperProps(props)

    override fun hardwareProfile(): Map<String, Any> = buildMap {
        put("hw_model", modelIdentifier())
        put("hw_ram_total_mb", (NSProcessInfo.processInfo.physicalMemory / (1024UL * 1024UL)).toLong())
        put("hw_cpu_cores", NSProcessInfo.processInfo.activeProcessorCount.toInt())
        put("hw_os_version", NSProcessInfo.processInfo.operatingSystemVersionString)
        put(
            "hw_device_class",
            when (UIDevice.currentDevice.userInterfaceIdiom) {
                UIUserInterfaceIdiomPhone -> "iphone"
                UIUserInterfaceIdiomPad -> "ipad"
                UIUserInterfaceIdiomTV -> "apple_tv"
                else -> "ios"
            },
        )
    }

    // Kotlin/Native has no cinterop for os_proc_available_memory / task_vm_info here (see AppMemory), so the
    // sample is the physical total; the Swift side feeds pressure events into AppMemory instead.
    override fun memorySample(): Map<String, Long> =
        mapOf("ram_total_mb" to (NSProcessInfo.processInfo.physicalMemory / (1024UL * 1024UL)).toLong())

    private fun modelIdentifier(): String = memScoped {
        val info = alloc<utsname>()
        uname(info.ptr)
        info.machine.toKString()
    }

    private companion object { const val SUPPORT_CODE_KEY = "tuvora.support_code" }
}

/**
 * iOS / Apple TV start-up for Telemetry v2. Swift calls [start] first thing in `init()` (before any shared code runs):
 * it creates the service and installs the Kotlin unhandled-exception hook, which writes the crash record. Native
 * (Mach/signal) crashes stay with PostHog's own error autocapture, which sends on the next launch.
 */
@OptIn(ExperimentalNativeApi::class)
object IosTelemetry {
    private var started = false

    fun start() {
        if (started) return
        started = true
        TelemetryRuntime.start(IosTelemetryHost())
        // Hooks chain: the one MainViewController installs later calls back into this one for real crashes.
        var previous: ((Throwable) -> Unit)? = null
        previous = setUnhandledExceptionHook { throwable ->
            TelemetryStartup.onUncaught("main", throwable)
            previous?.invoke(throwable)
        }
    }

    /** Call after Swift registered the sinks on `AnalyticsSink` and PostHog is set up. */
    fun onSdkReady() {
        TelemetryRuntime.service?.onSdkReady()
    }

    fun onForeground() { TelemetryRuntime.service?.onForeground() }
    fun onBackground() { TelemetryRuntime.service?.onBackground() }

    /** Sends a crash record from the previous run (off Main, with a deadline inside). */
    suspend fun sendPendingCrash() { TelemetryRuntime.service?.sendPendingCrash() }
}
