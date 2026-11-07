package com.tuvora.tvos.telemetry

import com.nuvio.app.core.telemetry.api.ReportCategory
import com.nuvio.app.core.telemetry.api.ReportCategoryInference
import com.nuvio.app.core.telemetry.api.ReportContext
import com.nuvio.app.core.telemetry.api.ReportResult
import com.nuvio.app.core.telemetry.api.ReportSurface
import com.nuvio.app.core.telemetry.api.TelemetryAccess

/**
 * Swift's one entry point to the shared Telemetry v2 port (composeApp `core/telemetry`). All logic (support code,
 * consent, the 120 s limit, the event, the flight recorder, crash records) lives there; this only gives Swift
 * a small surface and the Apple TV player's category inference. Swift calls [sendReport] from a Task off Main.
 */
object TvTelemetry {
    /** First thing in the app's init: the service, and the crash hook that writes the record (durable on Apple TV). */
    fun start() = TvTelemetryStartup.start()

    /** `K7Q2-9MPX`. Empty until the service has started (IosTelemetry.start in the Swift app init). */
    val displaySupportCode: String get() = TelemetryAccess.current().displaySupportCode()

    /** From the error card the category is inferred from the failure; Settings passes the viewer's pick. */
    fun inferredCategory(failureCode: String?): ReportCategory = ReportCategoryInference.fromFailureCode(failureCode)

    suspend fun sendReport(category: ReportCategory, surface: ReportSurface, engine: String?, failureCode: String?): ReportResult =
        TelemetryAccess.current().sendUserReport(category, surface, ReportContext(failureCode = failureCode, engine = engine))
}
