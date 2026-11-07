package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.DiagEntry
import com.nuvio.app.core.telemetry.api.ReportCategory
import com.nuvio.app.core.telemetry.api.ReportContext
import com.nuvio.app.core.telemetry.api.ReportResult
import com.nuvio.app.core.telemetry.api.ReportSurface
import com.nuvio.app.core.telemetry.api.TelemetryPort

/** A port that forwards `emit` to a lambda and ignores the rest: for tests of call sites. */
internal class RecordingTelemetryPort(private val onEmit: (String, Map<String, Any>) -> Unit) : TelemetryPort {
    val entries = ArrayList<DiagEntry>()
    override val supportCode: String = "K7Q29MPX"
    override fun displaySupportCode(): String = "K7Q2-9MPX"
    override fun record(entry: DiagEntry) { entries += entry }
    override fun reportError(event: String, props: Map<String, Any>) = onEmit(event, props)
    override fun emit(event: String, props: Map<String, Any>) = onEmit(event, props)
    override fun reportException(error: Throwable, props: Map<String, Any>) {}
    override suspend fun sendUserReport(category: ReportCategory, surface: ReportSurface, context: ReportContext): ReportResult =
        ReportResult.Sent(displaySupportCode())
    override fun consentEnabled(): Boolean = true
    override fun onBackground() {}
    override fun onForeground() {}
    override suspend fun sendPendingCrash() {}
    override fun onPlaybackScopeOpened() {}
    override fun onPlaybackScopeClosed() {}
    override fun previousSessionTrail(): String? = null
}
