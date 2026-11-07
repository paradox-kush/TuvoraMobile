package com.tuvora.tvos.telemetry

import com.nuvio.app.core.telemetry.api.TelemetryStartup
import com.nuvio.app.core.telemetry.internal.IosTelemetryHost
import com.nuvio.app.core.telemetry.internal.TelemetryFileStore
import com.nuvio.app.core.telemetry.internal.TelemetryHost
import com.nuvio.app.core.telemetry.internal.TelemetryRuntime
import platform.Foundation.NSUserDefaults
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook

/** A tiny string store. [NSUserDefaultsKeyValue] in the app; a map in tests. */
internal interface TvKeyValue {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

internal object NSUserDefaultsKeyValue : TvKeyValue {
    override fun get(key: String): String? = NSUserDefaults.standardUserDefaults.stringForKey(key)
    override fun set(key: String, value: String) { NSUserDefaults.standardUserDefaults.setObject(value, forKey = key) }
    override fun remove(key: String) { NSUserDefaults.standardUserDefaults.removeObjectForKey(key) }
}

/**
 * Apple TV may purge Caches while the app is not running, which is exactly when a crash record waits for the next
 * launch. The shared store writes under Caches (AppleDataDirectory), so the two crash files are mirrored into the
 * durable user-defaults tier (TvStorageTierPolicy, `tuvora.telemetry.` prefix; a record is capped well under the
 * 64 KB per-key limit). Reads fall back to the mirror; the ring snapshot stays file-only (a purge may lose it).
 */
internal class TvDurableMirrorFileStore(
    private val inner: TelemetryFileStore,
    private val kv: TvKeyValue,
    private val mirrored: Set<String> = setOf("pending_crash.json", "sending.json"),
) : TelemetryFileStore {
    private fun key(name: String) = KEY_PREFIX + name

    override fun read(name: String): String? =
        inner.read(name) ?: if (name in mirrored) kv.get(key(name)) else null

    override fun writeAtomic(name: String, text: String): Boolean {
        val file = inner.writeAtomic(name, text)
        if (name !in mirrored || text.length > MAX_MIRROR_CHARS) return file
        kv.set(key(name), text)
        return true
    }

    override fun rename(from: String, to: String): Boolean {
        val file = inner.rename(from, to)
        if (from !in mirrored) return file
        val text = kv.get(key(from)) ?: inner.read(to)
        kv.remove(key(from))
        if (to in mirrored && text != null) kv.set(key(to), text)
        return file || text != null
    }

    override fun delete(name: String) {
        inner.delete(name)
        if (name in mirrored) kv.remove(key(name))
    }

    companion object {
        const val KEY_PREFIX = "tuvora.telemetry.file."
        const val MAX_MIRROR_CHARS = 48_000
    }
}

/** The shared iOS host with the crash files made durable. Everything else is delegated unchanged. */
internal class TvTelemetryHost(inner: IosTelemetryHost = IosTelemetryHost()) : TelemetryHost by inner {
    override val store: TelemetryFileStore = TvDurableMirrorFileStore(inner.store, NSUserDefaultsKeyValue)
}

/** Starts the shared service on Apple TV: same as `IosTelemetry.start()` but over [TvTelemetryHost]. */
@OptIn(ExperimentalNativeApi::class)
internal object TvTelemetryStartup {
    private var started = false

    fun start() {
        if (started) return
        started = true
        TelemetryRuntime.start(TvTelemetryHost())
        var previous: ((Throwable) -> Unit)? = null
        previous = setUnhandledExceptionHook { throwable ->
            TelemetryStartup.onUncaught("main", throwable)
            previous?.invoke(throwable)
        }
    }
}
