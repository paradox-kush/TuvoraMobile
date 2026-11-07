package com.nuvio.app.features.sportsdetail.api

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.sportsdetail.internal.GameDetailController
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiState
import com.nuvio.app.features.sportsdetail.internal.ui.BlockContent
import com.nuvio.app.features.sportsdetail.internal.ui.GameBackButton
import com.nuvio.app.features.sportsdetail.internal.ui.GameHeader
import com.nuvio.app.features.sportsdetail.internal.ui.SituationCard
import com.nuvio.app.features.sportsdetail.internal.ui.StaleChip
import com.nuvio.app.features.sportsdetail.internal.ui.WatchCard
import kotlin.time.Clock

/**
 * The live game screen (phone, iPhone, iPad). Thin: all state/decisions live in [GameDetailController]; polling runs
 * ONLY while RESUMED. [onNoData] fires when no source has anything for this game — the caller shows today's match sheet.
 */
@Composable
fun GameDetailScreen(
    ref: GameRef,
    onBack: () -> Unit,
    onNoData: () -> Unit,
    onPlayChannel: (String) -> Unit,
    onAddPlaylist: () -> Unit,
    onFindChannels: () -> Unit,
    modifier: Modifier = Modifier,
    /** Reports the freshest state of THIS game (ESPN-backed) so lists can stop saying LIVE: (isLive, period like "3rd"). Final -> (false, null). */
    onFreshState: (live: Boolean, periodLabel: String?) -> Unit = { _, _ -> },
) {
    val service = LocalSportsDetail.current
    val watch = LocalGameWatch.current
    PlatformBackHandler(enabled = true, onBack = onBack)
    if (service == null) {
        LaunchedEffect(Unit) { onNoData() }
        return
    }
    val controller = remember(ref.gameId, service) {
        GameDetailController(ref, service.open(ref), watch, service::refreshConfig, { Clock.System.now().toEpochMilliseconds() })
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(service) { service.syncMapping() }
    LaunchedEffect(controller, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { controller.run() }
    }
    val scope = rememberCoroutineScope()
    val raw by controller.state.collectAsStateWithLifecycle()
    // UI backstop: a Loading that outlives its deadline is shown as Failed whatever the controller is doing.
    var clock by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(raw) {
        (raw as? GameDetailUiState.Loading)?.let {
            delay((it.deadlineAtMs - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0L) + 50L)
            clock = Clock.System.now().toEpochMilliseconds()
        }
    }
    val state = GameDetailUiPolicy.effective(raw, clock)
    LaunchedEffect(state) { if (state is GameDetailUiState.Empty) onNoData() }
    LaunchedEffect(raw) {
        (raw as? GameDetailUiState.Loaded)?.detail?.let { d ->
            when (d.state) {
                GameState.LIVE -> onFreshState(true, d.live?.periodLabel)
                GameState.FINAL -> onFreshState(false, null)
                else -> {}
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.nuvio.colors.background)) {
        // Own top bar: the shared header collapses under iOS native navigation (no back control, content under the island).
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = NuvioTokens.Space.s16, top = NuvioTokens.Space.s8, end = NuvioTokens.Space.s16)) {
            GameBackButton(onClick = onBack)
        }
        when (val s = state) {
            is GameDetailUiState.Loading, GameDetailUiState.Empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
            is GameDetailUiState.Failed -> Column(
                Modifier.fillMaxSize().padding(NuvioTokens.Space.s24),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (s.timedOut) "This game took too long to load." else "Couldn't load this game.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.nuvio.colors.textSecondary,
                )
                Spacer(Modifier.height(NuvioTokens.Space.s12))
                NuvioPrimaryButton("Retry", onClick = controller::retry)
            }
            is GameDetailUiState.Loaded -> LoadedContent(s, onPlayChannel, onAddPlaylist, onFindChannels, onRetryWatch = { scope.launch { controller.retryWatch() } })
        }
    }
}

@Composable
private fun LoadedContent(
    s: GameDetailUiState.Loaded,
    onPlayChannel: (String) -> Unit,
    onAddPlaylist: () -> Unit,
    onFindChannels: () -> Unit,
    onRetryWatch: () -> Unit,
) {
    val detail = s.detail
    val blocks = detail.blocks.filterNot(GameDetailUiPolicy::isEmpty)
    val teams = detail.header as? TeamsHeader
    val situation = blocks.filterIsInstance<SituationBlock>().firstOrNull()
    val win = blocks.filterIsInstance<WinProbabilityBlock>().firstOrNull()
    val rest = blocks.filter { it !is SituationBlock && it !is WinProbabilityBlock }
    val watchCard: @Composable () -> Unit = {
        WatchCard(
            ui = s.watch,
            onPlay = { onPlayChannel(it.channelId) },
            onPlayChannelId = onPlayChannel,
            onAddPlaylist = onAddPlaylist,
            onFindChannels = onFindChannels,
            onRetry = onRetryWatch,
        )
    }
    val gap = Arrangement.spacedBy(NuvioTokens.Space.s12)
    // Bottom inset: the last row must clear the floating bottom navigation.
    val pad = PaddingValues(start = NuvioTokens.Space.s16, end = NuvioTokens.Space.s16, top = NuvioTokens.Space.s8, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16))
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (GameDetailUiPolicy.isWide(maxWidth.value.toInt())) {
            Column(Modifier.fillMaxSize().padding(pad)) {
                GameHeader(detail)
                s.staleLabel?.let { Spacer(Modifier.height(NuvioTokens.Space.s8)); StaleChip(it) }
                Spacer(Modifier.height(NuvioTokens.Space.s12))
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = gap) {
                        item { SituationCard(situation, win, teams?.home?.name.orEmpty(), teams?.away?.name.orEmpty(), detail.header) }
                        item { watchCard() }
                        items(rest.filter(GameDetailUiPolicy::isPrimaryColumn)) { BlockContent(it, detail) }
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = gap) {
                        items(rest.filterNot(GameDetailUiPolicy::isPrimaryColumn)) { BlockContent(it, detail) }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = gap) {
                item { GameHeader(detail) }
                s.staleLabel?.let { item { StaleChip(it) } }
                item { SituationCard(situation, win, teams?.home?.name.orEmpty(), teams?.away?.name.orEmpty(), detail.header) }
                item { watchCard() }
                items(rest) { BlockContent(it, detail) }
            }
        }
    }
}
