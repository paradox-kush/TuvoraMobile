package com.nuvio.app.features.radar.alerts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.contracts.SportsAlertsHost
import com.nuvio.app.core.contracts.SportsGameRequest
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val SWIPE_DISMISS_PX = 60f

/**
 * Fork-side [SportsAlertsHost]: the banner at the top of the app (under the status bar), over every screen including
 * the player. Tap = the banner's action, swipe up = dismiss; it never takes focus and never waits on anything.
 * Thin: state and decisions are [SportsAlerts]' (the controller); this only draws and forwards taps.
 */
internal object SportsAlertBannerHostImpl : SportsAlertsHost {
    @Composable
    override fun Render(
        ownsAppRuntime: Boolean,
        onOpenSports: () -> Unit,
        onPlayChannel: (String) -> Unit,
        modifier: Modifier,
    ) {
        if (ownsAppRuntime) {
            // One foreground-bound subscription per process, for as long as the app content is up.
            DisposableEffect(Unit) {
                val session = SportsAlerts.startForApp()
                onDispose { session.dispose() }
            }
        }
        val banner by SportsAlerts.banner.collectAsStateWithLifecycle()
        SportsAlertBanner(
            banner = banner,
            onAction = {
                when (val action = SportsAlerts.act()) {
                    is BannerAction.OpenGame -> {
                        SportsGameRequest.request(action.eventId)
                        onOpenSports()
                    }
                    is BannerAction.Watch -> onPlayChannel(action.channelId)
                    BannerAction.OpenSports -> onOpenSports()
                    null -> Unit
                }
            },
            onDismiss = SportsAlerts::dismiss,
            modifier = modifier,
        )
    }
}

@Composable
internal fun SportsAlertBanner(
    banner: AlertBanner?,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    LaunchedEffect(banner?.id) { dragY.snapTo(0f) }
    // AnimatedVisibility keeps drawing while it slides out: hold the last banner (not state; [banner] drives recomposition).
    val lastShown = remember { arrayOfNulls<AlertBanner>(1) }
    if (banner != null) lastShown[0] = banner
    AnimatedVisibility(
        visible = banner != null,
        modifier = modifier,
        enter = fadeIn(tween(tokens.motion.normalMillis)) + slideInVertically(tween(tokens.motion.normalMillis)) { -it },
        exit = fadeOut(tween(tokens.motion.fastMillis)) + slideOutVertically(tween(tokens.motion.fastMillis)) { -it },
    ) {
        val shown = lastShown[0] ?: return@AnimatedVisibility
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = tokens.spacing.screenHorizontal, vertical = NuvioTokens.Space.s8),
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .offset { IntOffset(0, dragY.value.roundToInt().coerceAtMost(0)) }
                    .pointerInput(shown.id) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (dragY.value < -SWIPE_DISMISS_PX) onDismiss()
                                    else dragY.animateTo(0f, tween(tokens.motion.fastMillis))
                                }
                            },
                            onDragCancel = { scope.launch { dragY.animateTo(0f, tween(tokens.motion.fastMillis)) } },
                        ) { _, amount -> scope.launch { dragY.snapTo((dragY.value + amount).coerceAtMost(0f)) } }
                    }
                    .clickable(onClick = onAction),
                shape = tokens.shapes.card,
                color = tokens.colors.surfacePopover,
                tonalElevation = tokens.elevation.playerControls,
                shadowElevation = tokens.elevation.modal,
            ) {
                Row(
                    modifier = Modifier.padding(tokens.spacing.cardPaddingCompact),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = shown.title,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = tokens.colors.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        shown.subtitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        shown.lines.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(onClick = onAction) {
                        Text(shown.action.label, style = MaterialTheme.typography.labelLarge, color = tokens.colors.accent)
                    }
                }
            }
        }
    }
}
