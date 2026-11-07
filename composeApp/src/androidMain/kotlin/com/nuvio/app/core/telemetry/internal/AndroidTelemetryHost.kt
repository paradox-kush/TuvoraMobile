package com.nuvio.app.core.telemetry.internal

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Debug
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.features.settings.SentrySettingsRepository
import com.posthog.PostHog
import java.io.File
import java.security.SecureRandom
import java.util.Date

/** Files under `files/telemetry/`: crash record and ring snapshot (app-private storage). */
internal class AndroidTelemetryFileStore(context: Context) : TelemetryFileStore {
    private val dir = File(context.filesDir, "telemetry")

    override fun read(name: String): String? = runCatching {
        File(dir, name).takeIf { it.isFile && it.length() <= MAX_READ_BYTES }?.readText()
    }.getOrNull()

    override fun writeAtomic(name: String, text: String): Boolean = runCatching {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(dir, name))
    }.getOrDefault(false)

    override fun rename(from: String, to: String): Boolean =
        runCatching { File(dir, from).renameTo(File(dir, to)) }.getOrDefault(false)

    override fun delete(name: String) {
        runCatching { File(dir, name).delete() }
    }

    private companion object { const val MAX_READ_BYTES = 256L * 1024L }
}

/** The Android door to PostHog. The only Android file (with [AndroidPostHogSetup]) that names the SDK. */
internal class AndroidTelemetryHost(private val app: Application) : TelemetryHost {
    private val random = SecureRandom()
    private val prefs = app.getSharedPreferences("telemetry", Context.MODE_PRIVATE)

    override val appVersion: String = AppVersionConfig.VERSION_NAME
    override val store: TelemetryFileStore = AndroidTelemetryFileStore(app)

    override fun nowMs(): Long = System.currentTimeMillis()
    override fun randomBelow(bound: Int): Int = random.nextInt(bound)

    override fun loadSupportCode(): String? = prefs.getString(KEY_SUPPORT_CODE, null)
    override fun saveSupportCode(code: String) { prefs.edit().putString(KEY_SUPPORT_CODE, code).commit() }

    override fun consentEnabled(): Boolean {
        SentrySettingsRepository.ensureLoaded()
        return SentrySettingsRepository.enabled.value
    }

    override fun capture(event: String, props: Map<String, Any>, timestampMs: Long?) {
        if (timestampMs != null) {
            PostHog.capture(event = event, properties = props, timestamp = Date(timestampMs))
        } else {
            PostHog.capture(event = event, properties = props)
        }
    }

    override fun captureException(error: Throwable, props: Map<String, Any>) {
        PostHog.captureException(throwable = error, properties = props)
    }

    override fun flush() = PostHog.flush()

    override fun registerSuperProperties(props: Map<String, Any>) {
        for ((k, v) in props) PostHog.register(k, v)
    }

    override fun hardwareProfile(): Map<String, Any> = AndroidHardwareProfile.collect(app)

    override fun memorySample(): Map<String, Long> {
        val rt = Runtime.getRuntime()
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val mb = 1024L * 1024L
        return buildMap {
            put("java_mb", (rt.totalMemory() - rt.freeMemory()) / mb)
            put("java_max_mb", rt.maxMemory() / mb)
            put("native_mb", Debug.getNativeHeapAllocatedSize() / mb)
            runCatching { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024L }.getOrNull()?.let { put("pss_mb", it) }
            if (info.availMem > 0) put("ram_free_mb", info.availMem / mb)
        }
    }

    private companion object { const val KEY_SUPPORT_CODE = "support_code" }
}
