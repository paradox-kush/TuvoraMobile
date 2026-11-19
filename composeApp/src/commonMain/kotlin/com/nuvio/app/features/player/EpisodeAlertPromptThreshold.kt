package com.nuvio.app.features.player

import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration

/**
 * The same last-stretch threshold that raises the next-episode card, without its side effects (no source preload), so
 * the end-of-episode alert prompt appears at the same moment even when there is no next episode to preload.
 */
internal fun PlayerScreenRuntime.isAtEpisodeEndForAlertPrompt(): Boolean {
    if (playbackSnapshotKey != activePlaybackKey || playbackSnapshot.isLoading ||
        !initialSeekApplied || isScrubbingTimeline || errorMessage != null ||
        isShortPlaceholderDuration(playbackSnapshot.durationMs)
    ) return false
    return playbackSnapshot.isEnded || PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = playbackSnapshot.positionMs,
        durationMs = playbackSnapshot.durationMs,
        skipIntervals = skipIntervals,
        thresholdMode = playerSettingsUiState.nextEpisodeThresholdMode,
        thresholdPercent = playerSettingsUiState.nextEpisodeThresholdPercent,
        thresholdMinutesBeforeEnd = playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
    )
}
