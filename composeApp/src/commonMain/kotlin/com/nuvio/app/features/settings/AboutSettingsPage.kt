package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.rememberSafeUriOpener
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Forum
import nuvio.composeapp.generated.resources.action_donate
import nuvio.composeapp.generated.resources.compose_settings_page_discord
import nuvio.composeapp.generated.resources.compose_settings_root_discord_description
import nuvio.composeapp.generated.resources.settings_donate_description
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.build.AppVersionConfig
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.about_licenses_attributions_subtitle
import nuvio.composeapp.generated.resources.about_supporters_contributors_subtitle
import nuvio.composeapp.generated.resources.compose_about_made_with
import nuvio.composeapp.generated.resources.compose_about_version_format
import nuvio.composeapp.generated.resources.compose_settings_page_licenses_attributions
import nuvio.composeapp.generated.resources.compose_settings_page_privacy_policy
import nuvio.composeapp.generated.resources.compose_settings_page_supporters_contributors
import nuvio.composeapp.generated.resources.compose_settings_root_about_section
import nuvio.composeapp.generated.resources.compose_settings_root_check_updates_description
import nuvio.composeapp.generated.resources.compose_settings_root_check_updates_title
import nuvio.composeapp.generated.resources.compose_settings_root_privacy_policy_description
import nuvio.composeapp.generated.resources.updates_debug_test_description
import nuvio.composeapp.generated.resources.updates_debug_test_title
import org.jetbrains.compose.resources.stringResource

private const val PRIVACY_POLICY_URL = "https://tuvora.co/privacy"
// Permanent invite. The community lives on Discord alone - the subreddit that was meant
// to sit alongside it was banned by Reddit the day it went up.
private const val DISCORD_URL = "https://discord.gg/wFu9T2nS8X"

internal fun LazyListScope.aboutSettingsContent(
    isTablet: Boolean,
    onSupportersContributorsClick: () -> Unit,
    onLicensesAttributionsClick: () -> Unit,
    onCheckForUpdatesClick: (() -> Unit)?,
    onTestUpdateBannerClick: (() -> Unit)?,
    showSupportersContributorsPage: Boolean,
) {
    item {
        val openUri = rememberSafeUriOpener()
        SettingsSection(
            title = stringResource(Res.string.compose_settings_root_about_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                if (showSupportersContributorsPage) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_supporters_contributors),
                        description = stringResource(Res.string.about_supporters_contributors_subtitle),
                        icon = SettingsPage.SupportersContributors.icon,
                        isTablet = isTablet,
                        onClick = onSupportersContributorsClick,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                }
                // Upstream's Supporters & Contributors page fetches THEIR ko-fi/GitHub feeds, which this
                // fork doesn't set - replaced by a plain Donate row, shown once DONATIONS_DONATE_URL is set.
                val donateUrl = CommunityConfig.DONATIONS_DONATE_URL.trim()
                if (donateUrl.isNotBlank()) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.action_donate),
                        description = stringResource(Res.string.settings_donate_description),
                        icon = Icons.Rounded.Favorite,
                        isTablet = isTablet,
                        onClick = { openUri(donateUrl) },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                }
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_discord),
                    description = stringResource(Res.string.compose_settings_root_discord_description),
                    icon = Icons.Rounded.Forum,
                    isTablet = isTablet,
                    onClick = { openUri(DISCORD_URL) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_privacy_policy),
                    description = stringResource(Res.string.compose_settings_root_privacy_policy_description),
                    icon = Icons.Rounded.Policy,
                    isTablet = isTablet,
                    onClick = { openUri(PRIVACY_POLICY_URL) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_licenses_attributions),
                    description = stringResource(Res.string.about_licenses_attributions_subtitle),
                    icon = SettingsPage.LicensesAttributions.icon,
                    isTablet = isTablet,
                    onClick = onLicensesAttributionsClick,
                )
                if (onCheckForUpdatesClick != null) {
                    SettingsGroupDivider(isTablet = isTablet)
                    UpdateChannelSettingsRow(isTablet = isTablet)
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_root_check_updates_title),
                        description = stringResource(Res.string.compose_settings_root_check_updates_description),
                        icon = SettingsSearchTarget.CheckForUpdates.icon,
                        isTablet = isTablet,
                        onClick = onCheckForUpdatesClick,
                    )
                }
                if (onTestUpdateBannerClick != null) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.updates_debug_test_title),
                        description = stringResource(Res.string.updates_debug_test_description),
                        icon = Icons.Default.BugReport,
                        isTablet = isTablet,
                        onClick = onTestUpdateBannerClick,
                    )
                }
            }
        }
    }
    settingsFooterContent(isTablet)
}

internal fun LazyListScope.settingsFooterContent(isTablet: Boolean) {
    item {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = if (isTablet) 20.dp else 16.dp),
        ) {
            MemberBrandWordmark(
                height = if (isTablet) 30.dp else 26.dp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier.height(if (isTablet) 10.dp else 8.dp),
            )
            Text(
                text = stringResource(Res.string.compose_about_made_with),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    Res.string.compose_about_version_format,
                    AppVersionConfig.VERSION_NAME,
                    AppVersionConfig.VERSION_CODE,
                ),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
