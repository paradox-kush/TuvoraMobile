package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.internal.TelemetryFileStore
import com.nuvio.app.core.telemetry.internal.TelemetryHost

internal class FakeFileStore : TelemetryFileStore {
    val files = LinkedHashMap<String, String>()
    var failWrites = false
    /** Fails the Nth write (1-based) only, to model an allocation failure in the middle of the crash handler. */
    var failWriteNumber: Int? = null
    private var writes = 0
    override fun read(name: String): String? = files[name]
    override fun writeAtomic(name: String, text: String): Boolean {
        writes++
        if (failWrites || failWriteNumber == writes) return false
        files[name] = text
        return true
    }
    override fun rename(from: String, to: String): Boolean {
        val v = files.remove(from) ?: return false
        files[to] = v
        return true
    }
    override fun delete(name: String) { files.remove(name) }
}

internal class Sent(val event: String, val props: Map<String, Any>, val atMs: Long?)

internal class FakeTelemetryHost(
    var now: Long = 1_000_000L,
    var consent: Boolean = true,
    override val appVersion: String = "1.18.1-test",
    override val store: FakeFileStore = FakeFileStore(),
) : TelemetryHost {
    var storedCode: String? = null
    val sent = ArrayList<Sent>()
    val superProps = ArrayList<Map<String, Any>>()
    var flushes = 0
    var failCapture = false
    override var deliveryDurable: Boolean = false
    var seed = 7

    override fun nowMs(): Long = now
    override fun randomBelow(bound: Int): Int { seed = (seed * 31 + 11) and 0x7fffffff; return seed % bound }
    override fun loadSupportCode(): String? = storedCode
    override fun saveSupportCode(code: String) { storedCode = code }
    override fun consentEnabled(): Boolean = consent
    override fun capture(event: String, props: Map<String, Any>, timestampMs: Long?) {
        if (failCapture) error("sdk down")
        sent += Sent(event, props, timestampMs)
    }
    override fun captureException(error: Throwable, props: Map<String, Any>) {}
    /** A flush is a request, not a delivery: nothing here counts as delivered unless a test says so. */
    override fun flush() { flushes++ }
    override fun registerSuperProperties(props: Map<String, Any>) { superProps += props }
    override fun hardwareProfile(): Map<String, Any> = mapOf("hw_ram_total_mb" to 3000)
    override fun memorySample(): Map<String, Long> = mapOf("java_mb" to 120L, "pss_mb" to 410L)
}
