package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.analytics.Breadcrumbs
import com.nuvio.app.core.telemetry.api.PlaybackEngine
import com.nuvio.app.core.telemetry.api.PlaybackKind
import com.nuvio.app.core.telemetry.api.PlaybackSignal
import com.nuvio.app.core.telemetry.api.TelemetryAccess
import kotlinx.atomicfu.atomic

/**
 * What Swift can read from the OS and Kotlin/Native cannot (no cinterop for `os_proc_available_memory` / `task_vm_info`).
 * Swift implements this once at launch and registers it; the shared sampler pulls from it (off Main). A value < 0 means
 * "not available": the key is left out, nothing is invented.
 */
interface AppleMemoryProbe {
    /** `task_vm_info.phys_footprint` in MB: what the OS counts against the app's memory limit. */
    fun footprintMb(): Long

    /** `os_proc_available_memory()` in MB: headroom left before the OS ends the app. */
    fun availableMb(): Long
}

/**
 * The Swift doors of the Apple telemetry lane (iPhone, iPad, Apple TV). Swift registers its sources here and calls the
 * few reporters; the decisions (consent, closed vocabularies, send policy) stay in the shared telemetry port.
 */
object AppleTelemetryBridge {
    private val probe = atomic<AppleMemoryProbe?>(null)
    private val qa = atomic<((String) -> Unit)?>(null)

    internal val memoryProbe: AppleMemoryProbe? get() = probe.value

    /** Set only when the QA launch argument is present (Swift decides); the host reads its presence once at start. */
    internal val qaSink: ((String) -> Unit)? get() = qa.value

    fun registerMemoryProbe(probe: AppleMemoryProbe) { this.probe.value = probe }

    /** Call BEFORE `IosTelemetry.start()`: the host reads the mirror switch once when it is created. */
    fun registerQaSink(sink: (String) -> Unit) { qa.value = sink }

    /** The marker found the previous run unfinished. [kind]/[engine] are "" when nothing was playing. */
    fun recordUncleanExit(wasPlaying: Boolean, kind: String, engine: String, memWarnings: Int) {
        TelemetryAccess.current().recordUncleanExit(
            wasPlaying = wasPlaying,
            kind = kind.takeIf { it.isNotEmpty() },
            engine = engine.takeIf { it.isNotEmpty() },
            memWarnings = memWarnings,
        )
    }

    /** `UIApplication.didReceiveMemoryWarningNotification`. Counted in `session_health` (`memwarn:foreground|background`). */
    fun recordMemoryWarning(foreground: Boolean) {
        TelemetryAccess.current().recordPlayback(PlaybackSignal.MemoryWarning(foreground))
    }

    /** One payload, as counts under [MetricKitEvent.COUNT_KEYS]. Sent only when something non-routine is in it. */
    fun recordMetricKit(source: String, counts: Map<String, Int>, windowHours: Int) {
        val props = MetricKitEvent.build(source, counts, windowHours) ?: return
        if (PlaybackTelemetryPrivacy.violations(props).isNotEmpty()) return
        TelemetryAccess.current().emit(MetricKitEvent.EVENT, props)
    }

    /** Registered by Swift: (kind, engine) when playback starts, ("", "") when it stops. */
    fun registerPlaybackSink(sink: (String, String) -> Unit) { ApplePlaybackBreadcrumbs.register(sink) }

    /** Wires the shared player's breadcrumbs (iPhone/iPad) to the Swift sink. Idempotent. */
    fun installPlaybackBreadcrumbs() { ApplePlaybackBreadcrumbs.install() }
}

/**
 * Playback state for the unclean-exit marker. On iPhone/iPad the shared player calls [Breadcrumbs.playbackStarted] and
 * `playbackStopped`; Apple TV's SwiftUI player session calls [tvSessionOpened] / [tvSessionClosed] itself (it never used
 * the shared breadcrumbs). Kind and engine are reduced to the contract's closed sets here.
 */
object ApplePlaybackBreadcrumbs : Breadcrumbs.CrashWriter {
    private val sink = atomic<((String, String) -> Unit)?>(null)

    internal fun register(sink: (String, String) -> Unit) { this.sink.value = sink }

    internal fun install() {
        if (Breadcrumbs.crashWriter == null) Breadcrumbs.crashWriter = this
    }

    override fun onScreen(name: String) {}

    override fun onPlaybackStarted(kind: String, engine: String, surface: String) = publish(kind, engine)

    override fun onPlaybackStopped() = publish("", "")

    /** Apple TV: a player session now has an engine open. [engine] is the lane label (`libmpv`, `avplayer`). */
    fun tvSessionOpened(live: Boolean, engine: String) {
        publish(if (live) "live" else "vod", engine)
        TelemetryAccess.current().onPlaybackScopeOpened()
    }

    fun tvSessionClosed() {
        publish("", "")
        TelemetryAccess.current().onPlaybackScopeClosed()
    }

    private fun publish(kind: String, engine: String) {
        val out = sink.value ?: return
        if (kind.isEmpty()) { out("", ""); return }
        val k = kind.lowercase().let { v -> if (PlaybackKind.entries.any { it.label == v }) v else "other" }
        out(k, PlaybackEngine.fromLabel(engine).label)
    }
}
