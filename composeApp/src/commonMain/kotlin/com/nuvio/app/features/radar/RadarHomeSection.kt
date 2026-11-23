package com.nuvio.app.features.radar

import com.nuvio.app.core.contracts.SportsReplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens

/**
 * The Sports Centre's only home-screen presence (R3, slim by design):
 *  - one-time opt-in dialog when a featured event (World Cup…) is in its window
 *  - the featured match rail once accepted — cards open the channel-matching sheet
 *  - a dismissible "set up Radar" promo card until the user follows something
 * Everything else lives in the Sports tab.
 */
@Composable
fun RadarHomeSection(
    onOpenSportsTab: () -> Unit,
    onPlayChannel: (String) -> Unit,
    onAddPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenRecording: (String) -> Unit = {},
    onPlayReplay: (SportsReplay) -> Unit = {},
) {
    val state by RadarRepository.uiState.collectAsStateWithLifecycle()
    // Current time, plus one local re-render when a delayed score falls due (no network, only while RESUMED).
    val nowMs = rememberScoreNowMs(state)
    val featured = state.activeFeatured(nowMs).firstOrNull()
    var sheetFixture by remember { mutableStateOf<RadarFixture?>(null) }
    // Outside-tap/back is NOT an answer: hide for this session only, ask again next launch.
    // (Another dialog stacking above ours — e.g. the updater — would otherwise silently
    // persist "declined" when the user taps through to it.)
    var optInHiddenThisSession by remember { mutableStateOf(false) }

    val optInPending = featured != null &&
        (state.prefs.featuredEventId != featured.id || state.prefs.optInState == RadarOptIn.UNSET)
    val optedIn = featured != null &&
        state.prefs.featuredEventId == featured.id && state.prefs.optInState == RadarOptIn.ACCEPTED

    Column(modifier = modifier) {
        if (featured != null && optedIn) {
            val fixtures = state.upcoming(listOf(featured.leagueId), nowMs, cap = 16)
            if (fixtures.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        featured.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(NuvioTokens.Space.s8))
                    Text(
                        "${fixtures.size} matches",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenSportsTab) { Text("See all") }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                ) {
                    items(fixtures, key = { it.id ?: it.hashCode().toString() }) { fx ->
                        MatchCard(
                            fx,
                            live = state.isLive(fx, nowMs),
                            onClick = { sheetFixture = fx },
                            liveScore = fx.id?.let { state.liveScores[it] },
                            scores = state.scoreSlots(fx, nowMs),
                        )
                    }
                }
            }
        }

        if (state.follows.isEmpty() && !state.prefs.promoDismissed) {
            // NuvioSurfaceCard + NuvioPrimaryButton = the app's card/CTA building blocks
            // (HomeEmptyStateCard pattern) so this doesn't read as a foreign surface.
            NuvioSurfaceCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "Follow your sports, find your channels",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { RadarRepository.dismissPromo() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                    }
                }
                Text(
                    "Pick leagues and events to follow — Tuvora shows you what's coming up and which of your channels is showing it.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(NuvioTokens.Space.s12))
                NuvioPrimaryButton(text = "Set up Radar", onClick = onOpenSportsTab)
            }
        }
    }

    if (optInPending && !optInHiddenThisSession && featured != null) {
        AlertDialog(
            onDismissRequest = { optInHiddenThisSession = true },
            title = { Text(featured.title) },
            text = {
                Text(
                    "See live and upcoming ${featured.title} matches (${featured.from} – ${featured.to}) " +
                        "on your home screen. Either way, everything stays available in the Sports tab.",
                )
            },
            confirmButton = {
                TextButton(onClick = { RadarRepository.setOptIn(featured.id, accepted = true) }) {
                    Text("Yes, show matches")
                }
            },
            dismissButton = {
                TextButton(onClick = { RadarRepository.setOptIn(featured.id, accepted = false) }) {
                    Text("No thanks")
                }
            },
        )
    }

    sheetFixture?.let { fixture ->
        MatchChannelsSheet(
            fixture = fixture,
            league = fixture.leagueId?.let { state.leagueById(it) },
            isLive = state.isLive(fixture, RadarTime.nowMs()),
            onPlayChannel = onPlayChannel,
            onAddPlaylist = onAddPlaylist,
            onDismiss = { sheetFixture = null },
            onOpenRecording = onOpenRecording,
            onPlayReplay = onPlayReplay,
        )
    }
}
