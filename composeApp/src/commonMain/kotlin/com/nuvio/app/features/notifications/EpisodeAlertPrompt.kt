package com.nuvio.app.features.notifications

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioPrimaryButton
import kotlinx.coroutines.flow.StateFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.alert_prompt_accept
import nuvio.composeapp.generated.resources.alert_prompt_body
import nuvio.composeapp.generated.resources.alert_prompt_change_in_settings
import nuvio.composeapp.generated.resources.alert_prompt_decline
import nuvio.composeapp.generated.resources.alert_prompt_title
import nuvio.composeapp.generated.resources.settings_alerts_denied_button
import nuvio.composeapp.generated.resources.settings_alerts_denied_title
import org.jetbrains.compose.resources.stringResource

/** What the player knows when an episode nears its end. Null input means the prompt does not apply (a film, live TV). */
internal class EpisodeAlertPromptInput(
    val contentType: String,
    val contentId: String,
    val showTitle: String,
    /** The next episode has aired; null when there is no later episode. */
    val nextEpisodeAired: Boolean?,
    /** The viewer is in the last stretch of the episode (the same threshold that shows the next-episode card). */
    val atEnd: Boolean,
)

/** The prompt's reads and writes as plain functions; [Default] is the one place the repository is bound. */
internal class EpisodeAlertPromptController(
    val state: StateFlow<EpisodeReleaseNotificationsUiState>,
    val shouldOffer: (contentType: String, contentId: String, nextEpisodeAired: Boolean?) -> Boolean,
    val markPrompted: (contentType: String, contentId: String) -> Unit,
    val accept: (contentType: String, contentId: String, title: String) -> Unit,
    val openSystemSettings: () -> Unit,
) {
    companion object {
        val Default = EpisodeAlertPromptController(
            state = EpisodeReleaseNotificationsRepository.uiState,
            shouldOffer = EpisodeReleaseNotificationsRepository::shouldOfferEndOfEpisodePrompt,
            markPrompted = EpisodeReleaseNotificationsRepository::markPrompted,
            accept = { type, id, title -> EpisodeReleaseNotificationsRepository.requestShowAlert(type, id, title) },
            openSystemSettings = EpisodeReleaseNotificationsRepository::openSystemNotificationSettings,
        )
    }
}

private enum class PromptPhase { Undecided, Asking, Accepted, Hidden }

/**
 * Offered once per show, when the viewer is nearly caught up, only while alerts are not already on for that show and
 * "ask at the end of an episode" is on in Settings › Notifications. Accepting asks for the OS permission right then;
 * a refusal says so here and offers the phone's settings.
 */
@Composable
internal fun EpisodeAlertPrompt(
    input: EpisodeAlertPromptInput?,
    modifier: Modifier = Modifier,
    controller: EpisodeAlertPromptController = EpisodeAlertPromptController.Default,
) {
    if (input == null) return
    val state by controller.state.collectAsStateWithLifecycle()
    var phase by remember(input.contentId) { mutableStateOf(PromptPhase.Undecided) }

    LaunchedEffect(input.contentId, input.atEnd, phase) {
        if (phase == PromptPhase.Undecided && input.atEnd) {
            if (controller.shouldOffer(input.contentType, input.contentId, input.nextEpisodeAired)) {
                controller.markPrompted(input.contentType, input.contentId)
                phase = PromptPhase.Asking
            } else {
                phase = PromptPhase.Hidden
            }
        }
    }
    LaunchedEffect(phase, state.isEnabled) {
        if (phase == PromptPhase.Accepted && state.isEnabled) phase = PromptPhase.Hidden
    }

    val denied = phase == PromptPhase.Accepted && state.permissionDenied
    val visible = input.atEnd && (phase == PromptPhase.Asking || denied)

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it / 2 }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it / 2 }) + fadeOut(),
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(Color(0xFF191919).copy(alpha = 0.92f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (denied) {
                Text(
                    text = stringResource(Res.string.settings_alerts_denied_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                NuvioPrimaryButton(
                    text = stringResource(Res.string.settings_alerts_denied_button),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = controller.openSystemSettings,
                )
            } else {
                Text(
                    text = stringResource(Res.string.alert_prompt_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.alert_prompt_body, input.showTitle, AlertClock.words(state.alertMinute)),
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodySmall,
                )
                NuvioPrimaryButton(
                    text = stringResource(Res.string.alert_prompt_accept),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        phase = PromptPhase.Accepted
                        controller.accept(input.contentType, input.contentId, input.showTitle)
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.alert_prompt_decline),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { phase = PromptPhase.Hidden }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = stringResource(Res.string.alert_prompt_change_in_settings),
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
