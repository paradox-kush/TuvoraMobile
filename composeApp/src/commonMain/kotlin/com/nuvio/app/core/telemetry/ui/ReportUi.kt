package com.nuvio.app.core.telemetry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nuvio.app.core.telemetry.api.ReportCategory
import com.nuvio.app.core.telemetry.api.ReportContext
import com.nuvio.app.core.telemetry.api.ReportResult
import com.nuvio.app.core.telemetry.api.ReportSurface
import com.nuvio.app.core.telemetry.api.TelemetryAccess
import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.report_category_app_closed
import nuvio.composeapp.generated.resources.report_category_black_screen
import nuvio.composeapp.generated.resources.report_category_freezes
import nuvio.composeapp.generated.resources.report_category_other
import nuvio.composeapp.generated.resources.report_category_wont_play
import nuvio.composeapp.generated.resources.report_consent_off
import nuvio.composeapp.generated.resources.report_discord_hint
import nuvio.composeapp.generated.resources.report_failed
import nuvio.composeapp.generated.resources.report_rate_limited
import nuvio.composeapp.generated.resources.report_sent
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** The localized label for a category chip / picker row. */
@Composable
internal fun reportCategoryLabel(category: ReportCategory): String = stringResource(
    when (category) {
        ReportCategory.WONT_PLAY -> Res.string.report_category_wont_play
        ReportCategory.BLACK_SCREEN -> Res.string.report_category_black_screen
        ReportCategory.FREEZES -> Res.string.report_category_freezes
        ReportCategory.APP_CLOSED -> Res.string.report_category_app_closed
        ReportCategory.OTHER -> Res.string.report_category_other
    },
)

/**
 * What the screen says after "Send report". Every outcome has its own words and none of the failures looks like
 * success. The confirmation does not claim delivery we cannot confirm: it says the report was sent from here.
 */
internal suspend fun reportResultMessage(result: ReportResult): String = when (result) {
    is ReportResult.Sent -> getString(Res.string.report_sent, result.supportCode) + "\n" + getString(Res.string.report_discord_hint)
    ReportResult.ConsentOff -> getString(Res.string.report_consent_off)
    is ReportResult.RateLimited -> getString(Res.string.report_rate_limited)
    ReportResult.Failed -> getString(Res.string.report_failed)
}

/** Holds the "sending" state so a double press cannot double-send (the rate limit also guards). */
internal class ReportSender(private val scope: CoroutineScope) {
    var sending by mutableStateOf(false)
        private set

    /** Fires and forgets; the outcome arrives as a toast. [onResult] lets a caller react (e.g. close a picker). */
    fun send(
        category: ReportCategory,
        surface: ReportSurface,
        context: ReportContext = ReportContext(),
        onResult: (ReportResult) -> Unit = {},
    ) {
        if (sending) return
        sending = true
        scope.launch {
            try {
                val result = try {
                    TelemetryAccess.current().sendUserReport(category, surface, context)
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    ReportResult.Failed
                }
                NuvioToastController.show(reportResultMessage(result), durationMillis = 5000L)
                onResult(result)
            } finally {
                sending = false
            }
        }
    }
}

@Composable
internal fun rememberReportSender(): ReportSender {
    val scope = rememberCoroutineScope()
    return remember(scope) { ReportSender(scope) }
}
