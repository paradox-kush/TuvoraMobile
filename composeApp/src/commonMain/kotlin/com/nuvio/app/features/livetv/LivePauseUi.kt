package com.nuvio.app.features.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioColorTokens
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.features.iptv.BoundedLoad
import com.nuvio.app.features.iptv.LoadStatus
import com.nuvio.app.features.livetv.pause.LivePauseFailure
import com.nuvio.app.features.livetv.pause.LivePauseState
import com.nuvio.app.features.livetv.pause.LivePauseUiText
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_livetv_pause_failed_no_space
import nuvio.composeapp.generated.resources.compose_livetv_pause_failed_provider_refused
import nuvio.composeapp.generated.resources.compose_livetv_pause_failed_sequence_reset
import nuvio.composeapp.generated.resources.compose_livetv_pause_failed_stalled
import nuvio.composeapp.generated.resources.compose_livetv_pause_failed_timed_out
import nuvio.composeapp.generated.resources.compose_livetv_pause_failed_unsupported
import nuvio.composeapp.generated.resources.compose_livetv_pause_go_live
import nuvio.composeapp.generated.resources.compose_livetv_pause_preparing
import nuvio.composeapp.generated.resources.compose_livetv_pause_retry
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The failure message for [reason]; shown with Retry and Go live. */
private fun failureMessage(reason: LivePauseFailure): StringResource = when (reason) {
    LivePauseFailure.NO_SPACE -> Res.string.compose_livetv_pause_failed_no_space
    LivePauseFailure.PROVIDER_REFUSED -> Res.string.compose_livetv_pause_failed_provider_refused
    LivePauseFailure.STALLED -> Res.string.compose_livetv_pause_failed_stalled
    LivePauseFailure.SEQUENCE_RESET -> Res.string.compose_livetv_pause_failed_sequence_reset
    LivePauseFailure.UNSUPPORTED_STREAM -> Res.string.compose_livetv_pause_failed_unsupported
    LivePauseFailure.TIMED_OUT -> Res.string.compose_livetv_pause_failed_timed_out
}

/**
 * Preparing: a spinner that ends at its OWN deadline (the UI backstop of BoundedLoad - it shows the timed-out
 * failure even if the controller never answers). Failed: the short message with Retry and Go live. Anything else
 * draws nothing. No dialog: an in-place pill, like the screen's error pill.
 */
@Composable
internal fun LivePauseStatus(
    state: LivePauseState,
    colors: NuvioColorTokens,
    onRetry: () -> Unit,
    onGoLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preparing = state as? LivePauseState.Preparing
    var expired by remember(preparing?.status) { mutableStateOf(false) }
    LaunchedEffect(preparing?.status) {
        val status = preparing?.status ?: return@LaunchedEffect
        delay((status.deadlineAtMs - TraktPlatformClock.nowEpochMs()).coerceAtLeast(0L))
        expired = BoundedLoad.effectiveAt(status, TraktPlatformClock.nowEpochMs()) is LoadStatus.Failed
    }
    val failure: LivePauseFailure? = when {
        state is LivePauseState.Failed -> state.reason
        preparing != null && expired -> LivePauseFailure.TIMED_OUT
        else -> null
    }
    when {
        failure != null -> Box(modifier, Alignment.Center) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(NuvioTokens.Radius.md))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            ) {
                Text(
                    text = stringResource(failureMessage(failure)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16)) {
                    if ((state as? LivePauseState.Failed)?.retryable != false) {
                        PauseAction(stringResource(Res.string.compose_livetv_pause_retry), colors.accent, onRetry)
                    }
                    PauseAction(stringResource(Res.string.compose_livetv_pause_go_live), Color.White, onGoLive)
                }
            }
        }
        preparing != null -> Box(modifier, Alignment.Center) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(NuvioTokens.Radius.md))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s10),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            ) {
                CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(20.dp))
                Text(
                    text = stringResource(Res.string.compose_livetv_pause_preparing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun PauseAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s6),
    )
}

/**
 * The rewind transport of a time-shifted live stream: a scrub bounded by the window the mechanism holds, with the
 * position as "-mm:ss" behind live. Drawn only for a window that has some length (a provider-archive pause has none).
 */
@Composable
internal fun LivePauseScrub(
    state: LivePauseState.Shifted,
    colors: NuvioColorTokens,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.windowEndMs <= state.windowStartMs) return
    var scrubbingMs by remember { mutableStateOf<Long?>(null) }
    val shown = (scrubbingMs ?: state.positionMs).coerceIn(state.windowStartMs, state.windowEndMs)
    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = shown.toFloat(),
            onValueChange = { scrubbingMs = it.toLong() },
            onValueChangeFinished = {
                scrubbingMs?.let(onSeek)
                scrubbingMs = null
            },
            valueRange = state.windowStartMs.toFloat()..state.windowEndMs.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
            modifier = Modifier.fillMaxWidth().height(20.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = LivePauseUiText.behind((state.windowEndMs - shown).coerceAtLeast(0L)),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/** The docked transport: the scrub on a dark strip at the foot of the player. */
@Composable
internal fun LivePauseDockedTransport(
    state: LivePauseState.Shifted,
    colors: NuvioColorTokens,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.windowEndMs <= state.windowStartMs) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s6),
    ) {
        LivePauseScrub(state, colors, onSeek)
    }
}
