package com.nuvio.app.features.livetv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.pause.LivePauseState
import com.nuvio.app.features.player.LocalLiveTransportSlot
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.AudioTrackModal
import com.nuvio.app.features.player.PlayerControlsShell
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerLayoutMetrics
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PictureMemoryScope
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.StreamInfoLine
import com.nuvio.app.features.player.SubtitleAutoSyncUiState
import com.nuvio.app.features.player.SubtitleModal
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.player.VideoZoom
import com.nuvio.app.features.player.VideoZoomPanel
import com.nuvio.app.features.player.playerHorizontalSafePadding

/**
 * F28 on phone/tablet (+ B123): fullscreen Live TV draws the regular player's controls
 * ([PlayerControlsShell] in live mode — its LIVE badge instead of a scrubber) and the regular
 * player's own pickers ([SubtitleModal], [AudioTrackModal], [VideoZoomPanel]), with the live
 * keep/drop list from [LiveFullscreenControlsPolicy]: channel down/up in place of ±10 s; no speed,
 * sources, episodes or lock. Aspect cycles Fit/Fill/Zoom and manual zoom is lane F's — the picture
 * the live player was missing (B123). Retry stays the screen's own error pill, shown only on error.
 *
 * A replay (catch-up) keeps its own transport; this is only drawn for live.
 */
@Composable
internal fun LiveFullscreenControls(
    visible: Boolean,
    title: String,
    programmeTitle: String?,
    snapshot: PlayerPlaybackSnapshot,
    controller: PlayerEngineController?,
    resizeMode: PlayerResizeMode,
    zoom: VideoZoom,
    channelCount: Int,
    streamInfoLines: List<StreamInfoLine>,
    showStreamInfo: Boolean,
    onStreamInfoAnimationComplete: () -> Unit,
    onStreamInfoClick: () -> Unit,
    onPlayPause: () -> Unit,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onAspect: () -> Unit,
    onZoomChanged: (VideoZoom) -> Unit,
    onBack: () -> Unit,
    onInteraction: () -> Unit,
    /** Live pause / rewind: while shifted the badge row becomes the rewind transport (scrub + "-mm:ss - LIVE"). */
    pauseState: LivePauseState = LivePauseState.Off,
    onSeekInWindow: (Long) -> Unit = {},
    onGoLive: () -> Unit = {},
) {
    // Track lists are read when a picker opens (engines answer from observed state); the tick
    // re-reads them after a pick so the selection mark follows.
    var trackTick by remember { mutableIntStateOf(0) }
    var showSubtitles by remember { mutableStateOf(false) }
    var showAudio by remember { mutableStateOf(false) }
    var showZoom by remember { mutableStateOf(false) }
    val subtitleTracks: List<SubtitleTrack> = remember(controller, trackTick, showSubtitles, snapshot.isPlaying) {
        controller?.getSubtitleTracks().orEmpty()
    }
    val audioTracks: List<AudioTrack> = remember(controller, trackTick, showAudio, snapshot.isPlaying) {
        controller?.getAudioTracks().orEmpty()
    }
    val buttons = LiveFullscreenControlsPolicy.buttons(
        failed = false, // the screen's error pill owns Retry
        subtitleTracks = subtitleTracks.size,
        audioTracks = audioTracks.size,
        channelCount = channelCount,
    )
    val settings by PlayerSettingsRepository.uiState.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val metrics = remember(maxWidth) { PlayerLayoutMetrics.fromWidth(maxWidth) }
        val safePadding = playerHorizontalSafePadding()
        val colors = MaterialTheme.nuvio.colors
        val shifted = pauseState as? LivePauseState.Shifted
        val transport: (@Composable () -> Unit)? = shifted?.let { s ->
            {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                    LivePauseScrub(s, colors, onSeek = { onSeekInWindow(it); onInteraction() })
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp)) {
                        LiveBadge(danger = colors.danger, behindLiveMs = s.behindLiveMs, onGoLive = onGoLive)
                    }
                }
            }
        }
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
          CompositionLocalProvider(LocalLiveTransportSlot provides transport) {
            PlayerControlsShell(
                title = title,
                streamTitle = programmeTitle.orEmpty(),
                providerName = "",
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = null,
                playbackSnapshot = snapshot,
                displayedPositionMs = snapshot.positionMs,
                metrics = metrics,
                resizeMode = resizeMode,
                isLocked = false,
                onInteraction = onInteraction,
                isLive = true,
                streamInfoLines = streamInfoLines,
                showStreamInfo = showStreamInfo,
                onStreamInfoAnimationComplete = onStreamInfoAnimationComplete,
                onLockToggle = null,
                onBack = onBack,
                onTogglePlayback = onPlayPause,
                onSeekBack = {},
                onSeekForward = {},
                onResizeModeClick = onAspect,
                onVideoZoomClick = { showZoom = true; onInteraction() },
                onSpeedClick = null,
                onSubtitleClick = if (buttons.subtitles) ({ showSubtitles = true; onInteraction() }) else null,
                onAudioClick = if (buttons.audio) ({ showAudio = true; onInteraction() }) else null,
                onStreamInfoClick = onStreamInfoClick,
                onScrubChange = {},
                onScrubFinished = {},
                horizontalSafePadding = safePadding,
                onPreviousChannel = if (buttons.channelZap) onPreviousChannel else null,
                onNextChannel = if (buttons.channelZap) onNextChannel else null,
            )
          }
        }

        SubtitleModal(
            visible = showSubtitles,
            subtitleTracks = subtitleTracks,
            selectedSubtitleIndex = subtitleTracks.firstOrNull { it.isSelected }?.index ?: -1,
            // Live has no add-on subtitles: an IPTV channel id means nothing to a subtitle add-on.
            addonSubtitles = emptyList(),
            selectedAddonSubtitleId = null,
            isLoadingAddonSubtitles = false,
            preferredSubtitleLanguage = settings.preferredSubtitleLanguage,
            secondaryPreferredSubtitleLanguage = settings.secondaryPreferredSubtitleLanguage,
            subtitleStyle = settings.subtitleStyle,
            subtitleDelayMs = 0,
            selectedAddonSubtitle = null,
            subtitleAutoSyncState = SubtitleAutoSyncUiState(),
            onBuiltInTrackSelected = { index ->
                controller?.selectSubtitleTrack(index)
                trackTick++
            },
            onAddonSubtitleSelected = {},
            onFetchAddonSubtitles = {},
            onStyleChanged = { style ->
                PlayerSettingsRepository.setSubtitleStyle(style)
                controller?.applySubtitleStyle(style)
            },
            onSubtitleDelayChanged = { controller?.setSubtitleDelayMs(it) },
            onSubtitleDelayReset = { controller?.setSubtitleDelayMs(0) },
            onAutoSyncCapture = {},
            onAutoSyncCueSelected = {},
            onAutoSyncReload = {},
            onDismiss = { showSubtitles = false },
        )
        AudioTrackModal(
            visible = showAudio,
            audioTracks = audioTracks,
            selectedIndex = audioTracks.firstOrNull { it.isSelected }?.index ?: -1,
            onTrackSelected = { index ->
                controller?.selectAudioTrack(index)
                trackTick++
                showAudio = false
            },
            onDismiss = { showAudio = false },
        )
        VideoZoomPanel(
            visible = showZoom,
            zoom = zoom,
            onZoomChanged = onZoomChanged,
            onDismiss = { showZoom = false },
            memoryScope = PictureMemoryScope.Channel,
        )
    }
}
