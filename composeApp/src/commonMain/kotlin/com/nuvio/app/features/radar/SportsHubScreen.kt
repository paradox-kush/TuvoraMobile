package com.nuvio.app.features.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.iptv.XtreamRepository

/**
 * Sports Centre tab: featured event banners, live & upcoming fixtures for followed leagues,
 * per-league rows, and a browse-with-follow-toggles hierarchy. Tapping a match opens the
 * channel-matching sheet ("which of my channels shows this?"). Works with no IPTV playlist
 * (fixture guide + add-playlist CTA in the sheet).
 */
@Composable
fun SportsHubScreen(
    onPlayChannel: (String) -> Unit,
    onAddPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenRecording: (String) -> Unit = {},
) {
    val state by RadarRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { RadarRepository.ensureLoaded() }

    var browseCategory by remember { mutableStateOf<RadarCategory?>(null) }
    var browsing by remember { mutableStateOf(false) }
    var sheetFixture by remember { mutableStateOf<RadarFixture?>(null) }
    // Discovery drill-in: a league/event page listing everything happening in it.
    var leaguePage by remember { mutableStateOf<RadarLeague?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val tabletTopInset = if (maxWidth >= 768.dp) TABLET_TOP_BAR_INSET else 0.dp
        val isWide = maxWidth >= 768.dp
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = tabletTopInset)) {
            when {
                leaguePage != null -> LeagueFixturesPage(
                    state = state,
                    league = leaguePage!!,
                    onBack = { leaguePage = null },
                    onFixtureClick = { sheetFixture = it },
                )
                browsing && isWide -> BrowseTwoPane(
                    state = state,
                    selected = browseCategory,
                    onSelect = { browseCategory = it },
                    onOpenLeague = { leaguePage = it },
                    onBack = { browsing = false; browseCategory = null },
                )
                browsing && browseCategory != null -> BrowseLeagues(
                    state = state,
                    category = browseCategory!!,
                    onOpenLeague = { leaguePage = it },
                    onBack = { browseCategory = null },
                )
                browsing -> BrowseCategories(
                    state = state,
                    onSelect = { browseCategory = it },
                    onBack = { browsing = false },
                )
                else -> SportsOverview(
                    state = state,
                    onOpenBrowse = { browsing = true },
                    onOpenLeague = { leaguePage = it },
                    onFixtureClick = { sheetFixture = it },
                )
            }
        }
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
        )
    }
}

// --- overview (the tab's main scroll) -----------------------------------------

@Composable
private fun SportsOverview(
    state: RadarUiState,
    onOpenBrowse: () -> Unit,
    onOpenLeague: (RadarLeague) -> Unit,
    onFixtureClick: (RadarFixture) -> Unit,
) {
    val nowMs = RadarTime.nowMs()
    val featured = state.activeFeatured(nowMs)
    val upcoming = state.upcoming(
        leagueIds = state.followedLeagueIds + featured.map { it.leagueId },
        nowMs = nowMs,
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = NuvioTokens.Space.s24),
    ) {
        if (featured.isNotEmpty()) {
            item(key = "featured") {
                SectionTitle("Featured Events")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                ) {
                    items(featured, key = { it.id }) { event ->
                        val fixtures = state.fixturesByLeague[event.leagueId].orEmpty()
                        FeaturedBannerCard(
                            event = event,
                            matchCount = fixtures.count { (it.startEpochMs ?: 0) >= nowMs - 4 * 60 * 60 * 1000L },
                            onClick = {
                                // Into the event: every match, live + recent — discovery first.
                                state.leagueById(event.leagueId)?.let(onOpenLeague)
                            },
                        )
                    }
                }
            }
        }
        if (upcoming.isNotEmpty()) {
            item(key = "upcoming") {
                SectionTitle("Live & Upcoming")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                ) {
                    items(upcoming, key = { it.id ?: it.hashCode().toString() }) { fx ->
                        MatchCard(fx, live = state.isLive(fx, nowMs), onClick = { onFixtureClick(fx) })
                    }
                }
            }
        } else if (state.loadingFixtures && (state.follows.isNotEmpty() || featured.isNotEmpty())) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            }
        }
        if (state.follows.isEmpty()) {
            item(key = "follow-cta") { FollowCta(onOpenBrowse) }
        } else {
            items(state.follows, key = { "league-${it.leagueId}" }) { follow ->
                val league = state.leagueById(follow.leagueId) ?: return@items
                val fixtures = state.upcoming(listOf(league.id), nowMs, cap = 12)
                if (fixtures.isNotEmpty()) {
                    SectionTitle(league.name, onClick = { onOpenLeague(league) })
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                    ) {
                        items(fixtures, key = { "league-${league.id}-${it.id ?: it.hashCode()}" }) { fx ->
                            MatchCard(fx, live = state.isLive(fx, nowMs), onClick = { onFixtureClick(fx) })
                        }
                    }
                }
            }
        }
        item(key = "browse") {
            SectionTitle("Browse sports")
            state.catalog.categories.forEach { category ->
                CategoryRowItem(
                    category = category,
                    followedCount = category.leagues.count { it.id in state.followedLeagueIds },
                    onClick = onOpenBrowse,
                )
            }
        }
    }
}

@Composable
private fun FollowCta(onOpenBrowse: () -> Unit) {
    // HomeEmptyStateCard pattern: NuvioSurfaceCard + NuvioPrimaryButton.
    NuvioSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
    ) {
        Text(
            "Follow your sports",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(NuvioTokens.Space.s8))
        Text(
            "Pick leagues and events to follow — upcoming matches show up here, and Tuvora finds which of your channels is showing them.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NuvioTokens.Space.s16))
        NuvioPrimaryButton(text = "Browse sports", onClick = onOpenBrowse)
    }
}

// --- browse -------------------------------------------------------------------

@Composable
private fun BrowseHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                // Explicit token color — LocalContentColor defaults to black on the dark bg here.
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun BrowseCategories(state: RadarUiState, onSelect: (RadarCategory) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BrowseHeader("Pick a sport", onBack)
        Text(
            "Track the leagues and events you care about. They'll appear on the Sports tab when they're coming up — tap one to find which of your channels is showing it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16),
        )
        Spacer(Modifier.height(NuvioTokens.Space.s8))
        LazyColumn(contentPadding = PaddingValues(bottom = NuvioTokens.Space.s24)) {
            items(state.catalog.categories, key = { it.name }) { category ->
                CategoryRowItem(
                    category = category,
                    followedCount = category.leagues.count { it.id in state.followedLeagueIds },
                    onClick = { onSelect(category) },
                )
            }
        }
    }
}

@Composable
private fun BrowseLeagues(
    state: RadarUiState,
    category: RadarCategory,
    onOpenLeague: (RadarLeague) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BrowseHeader(category.name, onBack)
        LeagueToggleList(state, category, onOpenLeague)
    }
}

@Composable
private fun BrowseTwoPane(
    state: RadarUiState,
    selected: RadarCategory?,
    onSelect: (RadarCategory?) -> Unit,
    onOpenLeague: (RadarLeague) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BrowseHeader("Follow your sports", onBack)
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(320.dp), contentPadding = PaddingValues(bottom = NuvioTokens.Space.s24)) {
                items(state.catalog.categories, key = { it.name }) { category ->
                    CategoryRowItem(
                        category = category,
                        followedCount = category.leagues.count { it.id in state.followedLeagueIds },
                        selected = category.name == selected?.name,
                        onClick = { onSelect(category) },
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                val category = selected ?: state.catalog.categories.firstOrNull()
                if (category != null) LeagueToggleList(state, category, onOpenLeague)
            }
        }
    }
}

@Composable
private fun LeagueToggleList(
    state: RadarUiState,
    category: RadarCategory,
    onOpenLeague: (RadarLeague) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = NuvioTokens.Space.s24)) {
        items(category.leagues, key = { it.id }) { league ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Tap = go INSIDE the league (discovery); the switch is just for following.
                    .clickable { onOpenLeague(league) }
                    .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = league.badge,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.width(NuvioTokens.Space.s12))
                Column(Modifier.weight(1f)) {
                    Text(league.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    league.sport?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(
                    checked = league.id in state.followedLeagueIds,
                    onCheckedChange = { RadarRepository.toggleFollow(league) },
                    // Settings' switch palette (accent track) — never default M3 colors.
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.nuvio.colors.onAccent,
                        checkedTrackColor = MaterialTheme.nuvio.colors.accent,
                        uncheckedThumbColor = MaterialTheme.nuvio.colors.textMuted,
                        uncheckedTrackColor = MaterialTheme.nuvio.colors.borderDefault,
                    ),
                )
            }
        }
    }
}

@Composable
private fun CategoryRowItem(
    category: RadarCategory,
    followedCount: Int,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                else Modifier
            )
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Artwork-first like the rest of the app: the category's flagship league badge.
        AsyncImage(
            model = category.leagues.firstOrNull()?.badge,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(NuvioTokens.Space.s12))
        Column(Modifier.weight(1f)) {
            Text(category.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (followedCount > 0) "$followedCount followed · ${category.leagues.size} to track"
                else "${category.leagues.size} to track",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- league / event page (discovery drill-in) -----------------------------------

@Composable
private fun LeagueFixturesPage(
    state: RadarUiState,
    league: RadarLeague,
    onBack: () -> Unit,
    onFixtureClick: (RadarFixture) -> Unit,
) {
    // Browsing must not require following — fetch this league on demand.
    LaunchedEffect(league.id) { RadarRepository.ensureLeagueLoaded(league.id) }
    val nowMs = RadarTime.nowMs()
    val upcoming = state.upcoming(listOf(league.id), nowMs, cap = 40)
    val recent = state.recent(league.id, nowMs)
    val loaded = state.fixturesByLeague.containsKey(league.id)

    Column(Modifier.fillMaxSize()) {
        BrowseHeader(league.name, onBack)
        LazyColumn(contentPadding = PaddingValues(bottom = NuvioTokens.Space.s24)) {
            item(key = "league-header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(model = league.badge, contentDescription = null, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.width(NuvioTokens.Space.s12))
                    Column(Modifier.weight(1f)) {
                        league.sport?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            if (upcoming.any { state.isLive(it, nowMs) }) "Live now" else "${upcoming.size} upcoming",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        if (league.id in state.followedLeagueIds) "Following" else "Follow",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = league.id in state.followedLeagueIds,
                        onCheckedChange = { RadarRepository.toggleFollow(league) },
                        modifier = Modifier.padding(start = NuvioTokens.Space.s8),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.nuvio.colors.onAccent,
                            checkedTrackColor = MaterialTheme.nuvio.colors.accent,
                            uncheckedThumbColor = MaterialTheme.nuvio.colors.textMuted,
                            uncheckedTrackColor = MaterialTheme.nuvio.colors.borderDefault,
                        ),
                    )
                }
            }
            if (!loaded) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                }
            }
            if (upcoming.isNotEmpty()) {
                item(key = "up-title") { SectionTitle("Live & Upcoming") }
                items(upcoming, key = { "up-${it.id ?: it.hashCode()}" }) { fx ->
                    MatchCard(
                        fx,
                        live = state.isLive(fx, nowMs),
                        onClick = { onFixtureClick(fx) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
                    )
                }
            }
            if (recent.isNotEmpty()) {
                item(key = "recent-title") { SectionTitle("Recent results") }
                items(recent, key = { "rec-${it.id ?: it.hashCode()}" }) { fx ->
                    MatchCard(
                        fx,
                        live = false,
                        onClick = { onFixtureClick(fx) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
                    )
                }
            }
            if (loaded && upcoming.isEmpty() && recent.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No scheduled matches right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(NuvioTokens.Space.s16),
                    )
                }
            }
        }
    }
}

// --- cards ---------------------------------------------------------------------

@Composable
private fun FeaturedBannerCard(event: RadarFeaturedEvent, matchCount: Int, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .width(300.dp)
            .height(120.dp)
            .clip(RoundedCornerShape(NuvioTokens.Space.s12))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = event.banner ?: event.badge,
            contentDescription = event.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(NuvioTokens.Space.s10)) {
            Text(
                event.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (matchCount > 0) "$matchCount upcoming" else "${event.from} – ${event.to}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
internal fun MatchCard(
    fixture: RadarFixture,
    live: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(240.dp),
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(NuvioTokens.Space.s12)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fixture.roundLabel ?: fixture.league ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (live) LiveBadge()
            }
            Spacer(Modifier.height(NuvioTokens.Space.s6))
            Text(
                fixture.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(NuvioTokens.Space.s6))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fixture.startEpochMs?.let { radarWhenLabel(it) } ?: "Time TBC",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                fixture.scoreLabel?.let { score ->
                    Spacer(Modifier.width(NuvioTokens.Space.s8))
                    Text(
                        score,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LiveBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTokens.Space.s4))
            .background(Color(0xFFD32F2F))
            .padding(horizontal = NuvioTokens.Space.s6, vertical = 2.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
        Spacer(Modifier.width(4.dp))
        Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionTitle(text: String, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (onClick != null) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- match sheet -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MatchChannelsSheet(
    fixture: RadarFixture,
    league: RadarLeague?,
    isLive: Boolean,
    onPlayChannel: (String) -> Unit,
    onAddPlaylist: () -> Unit,
    onDismiss: () -> Unit,
    onOpenRecording: (String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val xtreamState by XtreamRepository.uiState.collectAsStateWithLifecycle()
    var matches by remember(fixture) { mutableStateOf<List<RadarChannelMatcher.ChannelMatch>>(emptyList()) }
    var recordings by remember(fixture) { mutableStateOf<List<RadarChannelMatcher.RecordingHit>>(emptyList()) }
    var matching by remember(fixture) { mutableStateOf(true) }
    val hasPlaylists = xtreamState.accounts.any { it.enabled }
    val fixtureStarted = (fixture.startEpochMs ?: Long.MAX_VALUE) <= RadarTime.nowMs()

    LaunchedEffect(fixture) {
        XtreamRepository.ensureLoaded()
        if (XtreamRepository.uiState.value.accounts.any { it.enabled }) {
            // Recordings only make sense once the match has started; probe alongside channels.
            if (fixtureStarted) {
                launch { recordings = runCatching { RadarChannelMatcher.findRecordings(fixture) }.getOrDefault(emptyList()) }
            }
            matches = RadarChannelMatcher.match(fixture, league, onPartial = { matches = it })
        }
        matching = false
    }

    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = NuvioTokens.Space.s16)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fixture.displayTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (isLive) LiveBadge()
            }
            Text(
                listOfNotNull(
                    fixture.roundLabel ?: fixture.league,
                    fixture.startEpochMs?.let { radarWhenLabel(it) },
                    fixture.venue,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(NuvioTokens.Space.s12))
            if (recordings.isNotEmpty()) {
                Text(
                    "RECORDINGS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(NuvioTokens.Space.s6))
                recordings.forEach { rec ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onOpenRecording(rec.contentId)
                            }
                            .padding(vertical = NuvioTokens.Space.s8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = rec.poster,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(NuvioTokens.Space.s6)),
                        )
                        Spacer(Modifier.width(NuvioTokens.Space.s12))
                        Column(Modifier.weight(1f)) {
                            Text(
                                rec.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                rec.playlistName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(NuvioTokens.Space.s8))
            }
            Text(
                "CHANNELS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(NuvioTokens.Space.s6))
            when {
                !hasPlaylists -> {
                    Text(
                        "Add an IPTV playlist to find and watch this match on your channels.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onDismiss(); onAddPlaylist() }) { Text("Add IPTV playlist") }
                }
                matches.isEmpty() && matching -> Box(
                    Modifier.fillMaxWidth().height(80.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(strokeWidth = 2.dp) }
                matches.isEmpty() -> Text(
                    "None of your channels list this match. Matching depends on your playlist's EPG and channel names.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn {
                    items(matches, key = { it.channel.contentId }) { match ->
                        // Started/finished + archived channel -> catch-up Replay of the programme.
                        val replayId = if (fixtureStarted) RadarChannelMatcher.replayFor(match, fixture) else null
                        ChannelMatchRow(
                            match = match,
                            onReplay = replayId?.let { id ->
                                {
                                    onDismiss()
                                    onPlayChannel(id)
                                }
                            },
                        ) {
                            RadarChannelMatcher.ensurePlayable(match)
                            onDismiss()
                            onPlayChannel(match.channel.contentId)
                        }
                    }
                    if (matching) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(NuvioTokens.Space.s8), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(NuvioTokens.Space.s24))
        }
    }
}

@Composable
private fun ChannelMatchRow(
    match: RadarChannelMatcher.ChannelMatch,
    onReplay: (() -> Unit)? = null,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(vertical = NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = match.channel.logo,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(NuvioTokens.Space.s6)),
        )
        Spacer(Modifier.width(NuvioTokens.Space.s12))
        Column(Modifier.weight(1f)) {
            Text(
                match.channel.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val programme = match.programme
            Text(
                when {
                    programme != null ->
                        "${programme.title} · ${RadarTime.formatTime(programme.startMs)} – ${RadarTime.formatTime(programme.endMs)}"
                    else -> match.channel.playlistName
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onReplay != null) {
            TextButton(onClick = onReplay, contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s8)) {
                Text("↩ Replay", style = MaterialTheme.typography.labelMedium)
            }
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = MaterialTheme.colorScheme.primary)
    }
}


private val TABLET_TOP_BAR_INSET = 72.dp
