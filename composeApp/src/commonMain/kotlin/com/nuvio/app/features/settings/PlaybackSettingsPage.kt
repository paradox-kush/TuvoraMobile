package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.player.AndroidLibmpvVideoOutput
import com.nuvio.app.features.player.PlaybackBufferSettings
import com.nuvio.app.features.player.playbackHeapBufferMaxMb
import com.nuvio.app.features.player.VodCacheSizeMode
import com.nuvio.app.features.player.VodCacheSizing
import com.nuvio.app.features.player.exoNativeMemoryInfo
import com.nuvio.app.features.player.isExoNativeMemorySupported
import com.nuvio.app.features.player.playbackCacheUsableSpaceBytes
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.AudioLanguageOption
import com.nuvio.app.features.player.SubtitleSideMargin
import com.nuvio.app.features.player.LiveBufferPolicy
import com.nuvio.app.features.player.AvailableLanguageOptions
import com.nuvio.app.features.player.ExternalPlayerApp
import com.nuvio.app.features.player.ExternalPlayerPlatform
import com.nuvio.app.features.player.IosAudioOutputMode
import com.nuvio.app.features.player.IosHardwareDecoderMode
import com.nuvio.app.features.player.localizedLabel
import com.nuvio.app.features.player.IosTargetPrimaries
import com.nuvio.app.features.player.IosTargetTransfer
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.isPictureInPictureBlockedBySystem
import com.nuvio.app.features.player.openPictureInPictureSystemSettings
import com.nuvio.app.features.player.platformSupportsPictureInPicture
import com.nuvio.app.features.player.STREAM_AUTO_PLAY_TIMEOUT_VALUES
import com.nuvio.app.features.player.SubtitleBackgroundColorSwatches
import com.nuvio.app.features.player.SubtitleColorSwatches
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.features.player.formatPlaybackSpeedLabel
import com.nuvio.app.features.player.languageLabelForCode
import com.nuvio.app.features.player.subtitleFontSizeRangeSp
import com.nuvio.app.features.player.toStorageHexString
import com.nuvio.app.features.p2p.P2pConsentDialog
import com.nuvio.app.features.p2p.P2pCacheClearResult
import com.nuvio.app.features.p2p.P2pCacheSize
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.isIos
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.playbackSettingsContent(
    isTablet: Boolean,
    page: SettingsPage,
    onPageChange: (SettingsPage) -> Unit,
) {
    item {
        val settings by remember {
            PlayerSettingsRepository.ensureLoaded()
            PlayerSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        when (page) {
            SettingsPage.Playback -> {
                val sections = visiblePlaybackSections(isIos, settings.androidPlaybackEngine, P2pSettingsRepository.isVisible)
                SettingsGroup(isTablet = isTablet) {
                    sections.forEachIndexed { index, section ->
                        if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(section.page.titleRes),
                            description = stringResource(section.descriptionRes),
                            isTablet = isTablet,
                            onClick = { onPageChange(section.page) },
                        )
                    }
                }
            }
            SettingsPage.PlaybackPlayer -> PlaybackPlayerSettings(isTablet, settings)
            SettingsPage.PlaybackStreamSelection -> PlaybackStreamSettings(isTablet, settings)
            SettingsPage.PlaybackUpNext -> PlaybackUpNextSettings(isTablet, settings)
            SettingsPage.PlaybackSkipSegments -> PlaybackSkipSettings(isTablet, settings)
            SettingsPage.PlaybackInterface -> PlaybackInterfaceSettings(isTablet, settings)
            SettingsPage.PlaybackAudio -> PlaybackAudioSettings(isTablet, settings)
            SettingsPage.PlaybackSubtitles -> PlaybackSubtitleSettings(isTablet, settings)
            SettingsPage.PlaybackVideo -> PlaybackVideoSettings(isTablet, settings)
            SettingsPage.PlaybackBuffer -> if (!isIos) PlaybackBufferSettings(isTablet, settings)
            SettingsPage.PlaybackP2p -> if (P2pSettingsRepository.isVisible) PlaybackP2pSettings(isTablet)
            else -> Unit
        }
    }
}
