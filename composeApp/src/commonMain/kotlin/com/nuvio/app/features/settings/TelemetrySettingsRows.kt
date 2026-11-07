package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.nuvio.app.core.telemetry.api.ReportCategory
import com.nuvio.app.core.telemetry.api.ReportSurface
import com.nuvio.app.core.telemetry.api.TelemetryAccess
import com.nuvio.app.core.telemetry.ui.rememberReportSender
import com.nuvio.app.core.telemetry.ui.reportCategoryLabel
import com.nuvio.app.core.ui.nuvio
import androidx.compose.runtime.collectAsState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.report_consent_off
import nuvio.composeapp.generated.resources.report_pick_category
import nuvio.composeapp.generated.resources.report_send
import nuvio.composeapp.generated.resources.report_settings_description
import nuvio.composeapp.generated.resources.report_turn_on
import nuvio.composeapp.generated.resources.support_code_hint
import nuvio.composeapp.generated.resources.support_code_title
import org.jetbrains.compose.resources.stringResource

/** Settings → About: the install's support code, readable at a glance; tap copies it. */
@Composable
internal fun SupportCodeRow(isTablet: Boolean) {
    val port = remember { TelemetryAccess.current() }
    val code = remember(port) { port.displaySupportCode() }
    if (code.isEmpty()) return
    val clipboard = LocalClipboardManager.current
    SettingsNavigationRow(
        title = stringResource(Res.string.support_code_title),
        description = stringResource(Res.string.support_code_hint),
        icon = Icons.Rounded.Badge,
        isTablet = isTablet,
        trailingContent = {
            Text(
                text = code,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.nuvio.colors.textPrimary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        },
        onClick = { runCatching { clipboard.setText(AnnotatedString(port.supportCode)) } },
    )
}

/**
 * Settings → Diagnostics → "Send report". This is the one place without context, so a picker of five appears
 * (error screens infer the category and send in one press). Consent off says so and offers the switch.
 */
@Composable
internal fun SendReportRows(isTablet: Boolean) {
    val consent by SentrySettingsRepository.enabled.collectAsState()
    val sender = rememberReportSender()
    var pickerOpen by rememberSaveable { mutableStateOf(false) }

    SettingsNavigationRow(
        title = stringResource(Res.string.report_send),
        description = if (consent) stringResource(Res.string.report_settings_description) else stringResource(Res.string.report_consent_off),
        icon = Icons.Rounded.BugReport,
        enabled = !sender.sending,
        isTablet = isTablet,
        trailingContent = if (consent) null else {
            {
                TextButton(onClick = { SentrySettingsRepository.setEnabled(true) }) {
                    Text(stringResource(Res.string.report_turn_on))
                }
            }
        },
        onClick = { if (consent) pickerOpen = !pickerOpen },
    )
    if (consent && pickerOpen) {
        for (category in ReportCategory.entries) {
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = reportCategoryLabel(category),
                description = null,
                enabled = !sender.sending,
                isTablet = isTablet,
                onClick = {
                    sender.send(category, ReportSurface.SETTINGS) { pickerOpen = false }
                },
            )
        }
    }
}
