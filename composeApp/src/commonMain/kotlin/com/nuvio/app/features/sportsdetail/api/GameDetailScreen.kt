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
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.features.radar.ScoreSpoilerSettings
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
import com.nuvio.app.features.sportsdetail.internal.GameRevealChip
import com.nuvio.app.features.sportsdetail.internal.revealChip
import com.nuvio.app.features.sportsdetail.internal.showsHiddenCard
import com.nuvio.app.core.ui.NuvioSurfaceCard
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
import com.nuvio.app.features.sportsdetail.internal.ui.LocalPlayed
import com.nuvio.app.features.sportsdetail.internal.ui.PlayedLedger
import com.nuvio.app.features.sportsdetail.internal.ui.SwitchFade
import com.nuvio.app.features.sportsdetail.internal.ui.rememberReduceMotion
import com.nuvio.app.features.sportsdetail.internal.ui.WatchPrimary
import com.nuvio.app.features.sportsdetail.internal.ui.WinChanceCard
import com.nuvio.app.features.sportsdetail.internal.ui.GameBackButton
import com.nuvio.app.features.sportsdetail.internal.ui.GameTopFade
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
    /** The user's Hide scores / score delay (per profile; the hub owns it). Required so no caller can skip the policy. */
    scoreSettings: ScoreSpoilerSettings,
    /** This game is revealed on its own ("Show this game's score", session only); [scoreSettings] already reads as shown. */
    revealedHere: Boolean,
    /** The profile's global Hide scores is off (the per-game chips have nothing to do then). */
    globalScoresShown: Boolean,
    /** "Show this game's score": reveals ONLY this game (page and its cards); the global setting is untouched. */
    onRevealGame: () -> Unit,
    /** "Hide again": puts this game back under the global Hide scores. */
    onHideGame: () -> Unit,
    /** Sports alerts for this game ("Alerts: Default / All / Off"); null = not a followed game, no chip. */
    alertsChip: GameAlertsChip? = null,
    onAlertsChip: () -> Unit = {},
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
            is GameDetailUiState.Loaded -> {
                // Hide scores / delay: the controller recorded every snapshot where it landed; render the one the setting
                // allows, and re-render ONCE when the next delayed snapshot falls due (local, only while RESUMED).
                var scoreTick by remember { mutableLongStateOf(0L) }
                val scoreNow = maxOf(Clock.System.now().toEpochMilliseconds(), scoreTick)
                val nextDue = controller.nextScoreChangeAtMs(scoreSettings, scoreNow)
                LaunchedEffect(nextDue, lifecycleOwner) {
                    val due = nextDue ?: return@LaunchedEffect
                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        delay((due - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0L) + 50L)
                        scoreTick = Clock.System.now().toEpochMilliseconds()
                    }
                }
                val view = controller.spoilerView(s.detail, scoreSettings, scoreNow)
                LoadedContent(
                    s.copy(detail = view.detail), tracker, onPlayChannel, onAddPlaylist, onFindChannels,
                    onRetryWatch = { scope.launch { controller.retryWatch() } },
                    revealChip = view.revealChip(revealedHere, globalScoresShown), hiddenCard = view.showsHiddenCard,
                    onRevealGame = onRevealGame, onHideGame = onHideGame,
                    alertsChip = alertsChip, onAlertsChip = onAlertsChip,
                )
            }
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
    revealChip: GameRevealChip,
    hiddenCard: Boolean,
    onRevealGame: () -> Unit,
    onHideGame: () -> Unit,
    alertsChip: GameAlertsChip?,
    onAlertsChip: () -> Unit,
) {
    val detail = s.detail
    val alertsItem: @Composable () -> Unit = {
        alertsChip?.let { Chip(label = it.label, selected = it.selected, onClick = onAlertsChip, enabled = it.enabled) }
    }
    val onChip = if (revealChip == GameRevealChip.HIDE_AGAIN) onHideGame else onRevealGame
    // The card already carries the reveal action, so the chip steps aside for it.
    val showChip = revealChip != GameRevealChip.NONE && !hiddenCard
    val spoilerItem: @Composable () -> Unit = {
        if (hiddenCard) ScoresHiddenCard(onRevealGame)
        else Chip(label = revealChip.label.orEmpty(), selected = revealChip == GameRevealChip.SHOW_THIS_GAME, onClick = onChip)
    }
    val batch = remember(detail) { tracker.accept(detail) }
    val played = remember { PlayedLedger() }
    val sections = remember(detail) { GameSectionsPolicy.sections(detail) }
    var chosen by rememberSaveable { mutableStateOf(GameSection.OVERVIEW) }
    var switchSeq by remember { mutableStateOf(0) }
    val reduce = rememberReduceMotion()
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
    CompositionLocalProvider(LocalMoments provides batch, LocalPlayed provides played) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (GameDetailUiPolicy.isWide(maxWidth.value.toInt())) {
                Row(Modifier.fillMaxSize().padding(pad), horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
                    LazyColumn(Modifier.weight(0.44f), verticalArrangement = gap) {
                        item(key = "score") { GameScoreboard(detail) }
                        if (showChip || hiddenCard) item(key = "spoiler") { spoilerItem() }
                        if (alertsChip != null) item(key = "alerts") { alertsItem() }
                        s.staleLabel?.let { item(key = "stale") { StaleChip(it) } }
                        stripItems(detail)
                        item(key = "watch") { watchCard() }
                        overviewLeft(detail)
                    }
                    Column(Modifier.weight(0.56f)) {
                        if (showTabs) GameTabRow(sections, section, { if (it != chosen) { chosen = it; switchSeq++ } })
                        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = gap) { sectionItems(section, detail, wide = true, fadeKey = switchSeq, reduce = reduce) }
                    }
                }
            } else {
                val listState = rememberLazyListState()
                val slim by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.none { it.key == "score" || it.key == "watch" } } }
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = pad, verticalArrangement = gap) {
                    item(key = "score") { GameScoreboard(detail) }
                        if (showChip || hiddenCard) item(key = "spoiler") { spoilerItem() }
                    if (alertsChip != null) item(key = "alerts") { alertsItem() }
                    s.staleLabel?.let { item(key = "stale") { StaleChip(it) } }
                    stripItems(detail)
                    item(key = "watch") { watchCard() }
                    stickyHeader(key = "tabs") {
                        Column {
                            if (slim) SlimScoreBar(detail, canPlay = primary != null, onPlay = { primary?.let { onPlayChannel(it.channelId) } })
                            if (showTabs) GameTabRow(sections, section, { if (it != chosen) { chosen = it; switchSeq++ } })
                        }
                    }
                    sectionItems(section, detail, wide = false, fadeKey = switchSeq, reduce = reduce)
                }
                // Soft edge where scrolled content meets the fixed back-button header (no hard dark band).
                val scrolled by remember { derivedStateOf { listState.canScrollBackward } }
                if (GameTopFade.visible(scrolled, slim)) {
                    val bg = MaterialTheme.nuvio.colors.background
                    Box(
                        Modifier.fillMaxWidth().height(NuvioTokens.Space.s16)
                            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(bg, androidx.compose.ui.graphics.Color.Transparent))),
                    )
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
private fun LazyListScope.sectionItems(section: GameSection, detail: GameDetail, wide: Boolean, fadeKey: Int, reduce: Boolean) {
    val blocks = GameSectionsPolicy.blocksFor(section, detail)
    when (section) {
        GameSection.OVERVIEW -> {
            if (!wide) overviewLeft(detail)
            items(blocks.filter { it !is WinProbabilityBlock && it !is SituationBlock && it !is FootballFieldBlock && it !is SoccerStripBlock && it !is BasketballStripBlock && it !is HockeyStripBlock }) { SwitchFade(fadeKey, reduce) { BlockContent(it, detail) } }
        }
        GameSection.PLAYS -> blocks.forEach { b ->
            if (b is PlaysBlock) {
                item(key = "plays") { SwitchFade(fadeKey, reduce) { PlaysCard(b, detail) } }
            } else item { SwitchFade(fadeKey, reduce) { BlockContent(b, detail) } }
        }
        GameSection.LINEUPS -> items(blocks) { b ->
            SwitchFade(fadeKey, reduce) { if (b is LineupBlock && PitchLayout.canDraw(b)) PitchLineup(b, detail) else BlockContent(b, detail) }
        }
        else -> items(blocks) { SwitchFade(fadeKey, reduce) { BlockContent(it, detail) } }
    }
}

/** A started game whose Overview Hide scores emptied: say why it is blank, and offer this game's score. */
@Composable
private fun ScoresHiddenCard(onReveal: () -> Unit) {
    NuvioSurfaceCard {
        Text("Scores are hidden", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.nuvio.colors.textPrimary)
        Spacer(Modifier.height(NuvioTokens.Space.s4))
        Text(
            "Results, stats and plays for this game stay hidden until you choose to see them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textSecondary,
        )
        Spacer(Modifier.height(NuvioTokens.Space.s12))
        NuvioPrimaryButton(GameRevealChip.SHOW_THIS_GAME.label.orEmpty(), onClick = onReveal)
    }
}
