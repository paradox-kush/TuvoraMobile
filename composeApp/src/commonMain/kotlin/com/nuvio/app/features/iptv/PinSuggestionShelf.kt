package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioToastPlacement
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.landscapePosterWidth
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.components.HomePosterCard
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.pin_suggest_not_now
import nuvio.composeapp.generated.resources.pin_suggest_pin
import nuvio.composeapp.generated.resources.pin_suggest_pin_all
import nuvio.composeapp.generated.resources.pin_suggest_pin_cd
import nuvio.composeapp.generated.resources.pin_suggest_pinned_many_toast
import nuvio.composeapp.generated.resources.pin_suggest_pinned_one_toast
import nuvio.composeapp.generated.resources.pin_suggest_title
import nuvio.composeapp.generated.resources.pin_suggest_turn_off
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The IPTV hub's "You keep coming back to these 4" rail: the channels the viewer plays most, each marked Pin, with one
 * slim line under it (Pin all, Not now, Turn off in Settings). It is shown only when [PinSuggestionPolicy] says so, and it
 * never moves anything by itself: Pin makes a channel a favourite (the first rows of Live TV), Not now snoozes it, and
 * the third link goes to the IPTV settings where the switch lives. Apple TV has its own SwiftUI group.
 */
@Composable
internal fun PinSuggestionShelf(
    suggested: List<PlayedChannel>,
    sectionPadding: Dp,
    onPlay: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val tileWidth = landscapePosterWidth(rememberPosterCardStyleUiState().widthDp)
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10)) {
        NuvioShelfSection(
            title = stringResource(Res.string.pin_suggest_title, suggested.size),
            entries = suggested,
            headerHorizontalPadding = sectionPadding,
            rowContentPadding = PaddingValues(horizontal = sectionPadding),
            key = { it.contentId },
        ) { channel ->
            Column(
                modifier = Modifier.width(tileWidth),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
            ) {
                HomePosterCard(
                    item = channel.toSuggestionPreview(),
                    useLandscapeBackdropMode = true,
                    onClick = { onPlay(channel.contentId) },
                )
                PinPill(
                    label = stringResource(Res.string.pin_suggest_pin),
                    description = stringResource(Res.string.pin_suggest_pin_cd, channel.name),
                    onClick = {
                        if (PinSuggestions.pin(channel)) {
                            scope.launch {
                                NuvioToastController.show(
                                    message = getString(Res.string.pin_suggest_pinned_one_toast, channel.name),
                                    placement = NuvioToastPlacement.Bottom,
                                )
                            }
                        }
                    },
                )
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = sectionPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
        ) {
            PinPill(
                label = stringResource(Res.string.pin_suggest_pin_all, suggested.size),
                filled = true,
                onClick = {
                    val pinned = PinSuggestions.pinAll(suggested)
                    if (pinned > 0) {
                        scope.launch {
                            NuvioToastController.show(
                                message = getString(Res.string.pin_suggest_pinned_many_toast, pinned),
                                placement = NuvioToastPlacement.Bottom,
                            )
                        }
                    }
                },
            )
            SlimLink(stringResource(Res.string.pin_suggest_not_now)) { PinSuggestions.store.snooze() }
            SlimLink(stringResource(Res.string.pin_suggest_turn_off), onClick = onOpenSettings)
        }
    }
}

@Composable
private fun PinPill(
    label: String,
    onClick: () -> Unit,
    filled: Boolean = false,
    description: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .clip(tokens.shapes.chip)
            .background(if (filled) tokens.colors.accent else tokens.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = tokens.components.chipHorizontalPadding, vertical = tokens.components.chipVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        Icon(
            imageVector = Icons.Filled.PushPin,
            contentDescription = description,
            tint = if (filled) tokens.colors.onAccent else tokens.colors.textSecondary,
            modifier = Modifier.size(NuvioTokens.Icon.xs),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) tokens.colors.onAccent else tokens.colors.textPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun SlimLink(label: String, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = tokens.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(tokens.shapes.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s6, vertical = tokens.components.chipVerticalPadding),
    )
}

internal fun PlayedChannel.toSuggestionPreview(): MetaPreview = MetaPreview(
    id = contentId,
    type = "tv",
    name = name,
    poster = logo,
    logo = logo,
    posterShape = PosterShape.Landscape,
)
