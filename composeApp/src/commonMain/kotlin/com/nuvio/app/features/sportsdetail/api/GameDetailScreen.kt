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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.nuvio.app.features.sportsdetail.internal.GameMomentPolicy
import com.nuvio.app.features.sportsdetail.internal.GameMomentTracker
import com.nuvio.app.features.sportsdetail.internal.GameSection
import com.nuvio.app.features.sportsdetail.internal.GameSectionsPolicy
import com.nuvio.app.features.sportsdetail.internal.ui.BlockContent
import com.nuvio.app.features.sportsdetail.internal.ui.GameScoreboard
import com.nuvio.app.features.sportsdetail.internal.ui.GameTabRow
import com.nuvio.app.features.sportsdetail.internal.ui.GameTabsUi
import com.nuvio.app.features.sportsdetail.internal.ui.LastPlayCard
import com.nuvio.app.features.sportsdetail.internal.ui.LocalMoments
import com.nuvio.app.features.sportsdetail.internal.ui.BasketballStrip
import com.nuvio.app.features.sportsdetail.internal.ui.FootballFieldStrip
import com.nuvio.app.features.sportsdetail.internal.ui.HockeyStrip
import com.nuvio.app.features.sportsdetail.internal.ui.PitchLayout
import com.nuvio.app.features.sportsdetail.internal.ui.PitchLineup
import com.nuvio.app.features.sportsdetail.internal.ui.PlaysCard
import com.nuvio.app.features.sportsdetail.internal.ui.SoccerStrip
import com.nuvio.app.features.sportsdetail.internal.ui.SituationLineCard
import com.nuvio.app.features.sportsdetail.internal.ui.SlimScoreBar
import com.nuvio.app.features.sportsdetail.internal.ui.WatchPrimary
import com.nuvio.app.features.sportsdetail.internal.ui.WinChanceCard
import com.nuvio.app.features.sportsdetail.internal.ui.GameBackButton
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
    val tracker = remember(controller) { GameMomentTracker() }
    LaunchedEffect(controller, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { tracker.reset(); controller.run() }
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
            is GameDetailUiState.Loaded -> LoadedContent(s, tracker, onPlayChannel, onAddPlaylist, onFindChannels, onRetryWatch = { scope.launch { controller.retryWatch() } })
        }
    }
}

@Composable
private fun LoadedContent(
    s: GameDetailUiState.Loaded,
    tracker: GameMomentTracker,
    onPlayChannel: (String) -> Unit,
    onAddPlaylist: () -> Unit,
    onFindChannels: () -> Unit,
    onRetryWatch: () -> Unit,
) {
    val detail = s.detail
    val batch = remember(detail) { tracker.accept(detail) }
    val sections = remember(detail) { GameSectionsPolicy.sections(detail) }
    var chosen by rememberSaveable { mutableStateOf(GameSection.OVERVIEW) }
    val section = GameTabsUi.selected(sections, chosen)
    val showTabs = GameSectionsPolicy.showsTabRow(detail)
    val primary = WatchPrimary.offer(s.watch)
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
    CompositionLocalProvider(LocalMoments provides batch) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (GameDetailUiPolicy.isWide(maxWidth.value.toInt())) {
                Row(Modifier.fillMaxSize().padding(pad), horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
                    LazyColumn(Modifier.weight(0.44f), verticalArrangement = gap) {
                        item(key = "score") { GameScoreboard(detail) }
                        s.staleLabel?.let { item(key = "stale") { StaleChip(it) } }
                        stripItems(detail)
                        item(key = "watch") { watchCard() }
                        overviewLeft(detail)
                    }
                    Column(Modifier.weight(0.56f)) {
                        if (showTabs) GameTabRow(sections, section, { chosen = it })
                        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = gap) { sectionItems(section, detail, wide = true) }
                    }
                }
            } else {
                val listState = rememberLazyListState()
                val slim by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.none { it.key == "score" || it.key == "watch" } } }
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = pad, verticalArrangement = gap) {
                    item(key = "score") { GameScoreboard(detail) }
                    s.staleLabel?.let { item(key = "stale") { StaleChip(it) } }
                    stripItems(detail)
                    item(key = "watch") { watchCard() }
                    stickyHeader(key = "tabs") {
                        Column {
                            if (slim) SlimScoreBar(detail, canPlay = primary != null, onPlay = { primary?.let { onPlayChannel(it.channelId) } })
                            if (showTabs) GameTabRow(sections, section, { chosen = it })
                        }
                    }
                    sectionItems(section, detail, wide = false)
                }
            }
        }
    }
}

/** Sport strips pinned directly under the scoreboard (scrolling header, above the Watch row). */
private fun LazyListScope.stripItems(detail: GameDetail) {
    detail.blocks.filterIsInstance<FootballFieldBlock>().firstOrNull()?.let { f -> item(key = "field") { FootballFieldStrip(f, detail) } }
    detail.blocks.filterIsInstance<SoccerStripBlock>().firstOrNull()?.let { b -> item(key = "soccer") { SoccerStrip(b, detail) } }
    detail.blocks.filterIsInstance<BasketballStripBlock>().firstOrNull()?.let { b -> item(key = "basketball") { BasketballStrip(b, detail) } }
    detail.blocks.filterIsInstance<HockeyStripBlock>().firstOrNull()?.let { b -> item(key = "hockey") { HockeyStrip(b, detail) } }
}

/** Left pane of the wide layout: win chance, last play and the situation line. */
private fun LazyListScope.overviewLeft(detail: GameDetail) {
    val blocks = GameSectionsPolicy.blocksFor(GameSection.OVERVIEW, detail)
    blocks.filterIsInstance<WinProbabilityBlock>().firstOrNull()?.let { w -> item(key = "win") { WinChanceCard(w, detail) } }
    GameMomentPolicy.newest(detail)?.let { p -> item(key = "last") { LastPlayCard(p, detail) } }
    if (blocks.none { it is FootballFieldBlock }) blocks.filterIsInstance<SituationBlock>().firstOrNull()?.let { sit -> item(key = "sit") { SituationLineCard(sit) } }
}

/** The selected section's cards. In the wide layout Overview omits what the left pane already shows. */
private fun LazyListScope.sectionItems(section: GameSection, detail: GameDetail, wide: Boolean) {
    val blocks = GameSectionsPolicy.blocksFor(section, detail)
    when (section) {
        GameSection.OVERVIEW -> {
            if (!wide) overviewLeft(detail)
            items(blocks.filter { it !is WinProbabilityBlock && it !is SituationBlock && it !is FootballFieldBlock && it !is SoccerStripBlock && it !is BasketballStripBlock && it !is HockeyStripBlock }) { BlockContent(it, detail) }
        }
        GameSection.PLAYS -> blocks.forEach { b ->
            if (b is PlaysBlock) {
                item(key = "plays") { PlaysCard(b, detail) }
            } else item { BlockContent(b, detail) }
        }
        GameSection.LINEUPS -> items(blocks) { b ->
            if (b is LineupBlock && PitchLayout.canDraw(b)) PitchLineup(b, detail) else BlockContent(b, detail)
        }
        else -> items(blocks) { BlockContent(it, detail) }
    }
}
