// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0): ui/screens/calendar/CalendarScreen.kt and
// CalendarMonthView.kt, rebuilt for touch in Compose Multiplatform with Tuvora's components and tokens.
package com.nuvio.app.features.calendar.internal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.contracts.CalendarScreenContent
import com.nuvio.app.features.calendar.api.CalendarGameItem
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.MetaPreview
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private const val GAMES_TICK_MS = 60_000L

@OptIn(ExperimentalTime::class)
internal fun calendarNowMs(): Long = Clock.System.now().toEpochMilliseconds()

/** Opens a Calendar title the way a poster does: its content id, as a series or a film. */
internal fun CalendarTitleRecord.toMetaPreview(): MetaPreview = MetaPreview(
    id = calendarContentIds(this).firstOrNull() ?: key,
    type = if (type == CalendarTitleType.MOVIE) "movie" else "series",
    name = name ?: key,
    poster = poster,
)

/**
 * The page's state holder (no ViewModel layer): view mode, month anchor and the viewer's actions. Composables only
 * read and call it; every repository call runs in [scope].
 */
internal class CalendarScreenController(
    private val repository: CalendarRepository,
    private val scope: CoroutineScope,
) {
    var monthAnchor by mutableStateOf<LocalDate?>(null)
        private set

    fun opened() = repository.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)

    fun retry() = repository.requestRefresh(CalendarRefreshReason.RETRY)

    fun setViewMode(mode: CalendarViewMode) {
        scope.launch { repository.updateSettings { it.copy(viewMode = mode) } }
    }

    fun shiftMonth(current: LocalDate, months: Int) {
        monthAnchor = calendarShiftMonth(current, months)
    }

    fun hide(title: CalendarTitleRecord) {
        scope.launch { repository.setHidden(title, hidden = true) }
    }

    fun setComingUpRow(on: Boolean) {
        scope.launch { repository.updateSettings { it.copy(comingUpRow = on) } }
    }

    fun setCornerMessage(on: Boolean) {
        scope.launch { repository.updateSettings { it.copy(cornerMessage = on) } }
    }

    fun setFilter(filter: CalendarFilter, on: Boolean) {
        scope.launch {
            repository.updateSettings { current ->
                val next = if (on) current.filters + filter else current.filters - filter
                // Never an empty page by accident: the last chip stays on.
                if (next.isEmpty()) current else current.copy(filters = next)
            }
        }
    }

    fun setWindowDays(days: Int) {
        scope.launch {
            repository.updateSettings { it.copy(windowDays = days) }
        }
    }
}

internal class CalendarScreenContentImpl(private val repository: () -> CalendarRepository) : CalendarScreenContent {
    @Composable
    override fun Render(onBack: () -> Unit, onOpenTitle: (MetaPreview) -> Unit, onWatchGame: (String) -> Unit, modifier: Modifier) {
        val repo = remember { repository() }
        val scope = rememberCoroutineScope()
        val controller = remember(repo, scope) { CalendarScreenController(repo, scope) }
        CalendarScreen(controller, repo, onBack, onOpenTitle, onWatchGame, modifier)
    }
}

@Composable
internal fun CalendarScreen(
    controller: CalendarScreenController,
    repository: CalendarRepository,
    onBack: () -> Unit,
    onOpenTitle: (MetaPreview) -> Unit,
    onWatchGame: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    var nowMs by remember { mutableLongStateOf(calendarNowMs()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { controller.opened() }
    // Live / final flips with the clock, not only with new fixtures: re-read the games (a local read, no request) once a
    // minute, only while this page is RESUMED.
    LaunchedEffect(lifecycleOwner, repository) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                repository.reloadGames()
                nowMs = calendarNowMs()
                delay(GAMES_TICK_MS)
            }
        }
    }
    // The UI's own deadline backstop: re-evaluate the page when a Loading reaches its deadline, even if the
    // refresh never reports back (CLAUDE.md "every loading state has a deadline").
    LaunchedEffect(state.status) {
        nowMs = calendarNowMs()
        calendarNextUiChangeMs(state, nowMs)?.let { wait ->
            delay(wait + 50L)
            nowMs = calendarNowMs()
        }
    }
    val viewMode = state.settings.viewMode
    val ui = remember(state, nowMs, viewMode, controller.monthAnchor) {
        calendarScreenUi(state, nowMs, viewMode, controller.monthAnchor)
    }
    var showSettings by remember { mutableStateOf(false) }
    val tokens = MaterialTheme.nuvio

    Column(modifier = modifier.fillMaxSize().background(tokens.colors.background)) {
        NuvioScreenHeader(title = stringResource(Res.string.calendar_title), onBack = onBack) {
            FilterChip(
                selected = viewMode == CalendarViewMode.LIST,
                onClick = { controller.setViewMode(CalendarViewMode.LIST) },
                label = { Text(stringResource(Res.string.calendar_view_list)) },
            )
            Spacer(Modifier.width(tokens.spacing.controlGap))
            FilterChip(
                selected = viewMode == CalendarViewMode.MONTH,
                onClick = { controller.setViewMode(CalendarViewMode.MONTH) },
                label = { Text(stringResource(Res.string.calendar_view_month)) },
            )
        }
        val showSportsChip = state.games.isNotEmpty() || CalendarFilter.SPORTS !in state.settings.filters
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = tokens.spacing.screenHorizontal),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
        ) {
            CalendarFilter.entries.filter { it != CalendarFilter.SPORTS || showSportsChip }.forEach { filter ->
                FilterChip(
                    selected = filter in state.settings.filters,
                    onClick = { controller.setFilter(filter, filter !in state.settings.filters) },
                    label = { Text(calendarFilterText(filter)) },
                )
            }
        }
        if (ui.refreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = tokens.colors.accent)
        }
        if (ui.updateFailed) {
            InlineUpdateFailed(onRetry = controller::retry)
        }
        when (val body = ui.body) {
            CalendarBody.Blank -> Spacer(Modifier.fillMaxSize())
            is CalendarBody.Loading -> CalendarMessage(title = stringResource(Res.string.calendar_title), body = null, showProgress = true)
            CalendarBody.Empty -> CalendarMessage(
                title = stringResource(Res.string.calendar_empty_title),
                body = stringResource(Res.string.calendar_empty_body),
            )
            is CalendarBody.Failed -> CalendarMessage(
                title = stringResource(Res.string.calendar_failed_title),
                body = stringResource(if (body.timedOut) Res.string.calendar_failed_timeout_body else Res.string.calendar_failed_body),
                actionLabel = stringResource(Res.string.calendar_retry),
                onAction = controller::retry,
            )
            CalendarBody.Content -> when (viewMode) {
                CalendarViewMode.LIST -> CalendarList(
                    sections = ui.sections,
                    settings = state.settings,
                    showSettings = showSettings,
                    onToggleSettings = { showSettings = !showSettings },
                    controller = controller,
                    onOpen = { onOpenTitle(it.toMetaPreview()) },
                    onWatchGame = onWatchGame,
                    nowMs = nowMs,
                )
                CalendarViewMode.MONTH -> ui.month?.let { month ->
                    CalendarMonthView(
                        month = month,
                        onShift = { controller.shiftMonth(LocalDate(month.year, month.month, 1), it) },
                        onOpen = { onOpenTitle(it.toMetaPreview()) },
                        onWatchGame = onWatchGame,
                        nowMs = nowMs,
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineUpdateFailed(onRetry: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = tokens.spacing.screenHorizontal, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.calendar_update_failed),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        Text(
            text = stringResource(Res.string.calendar_retry),
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRetry).padding(8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.accent,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CalendarMessage(
    title: String,
    body: String?,
    showProgress: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Box(modifier = Modifier.fillMaxSize().padding(tokens.spacing.screenHorizontal), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showProgress) {
                LinearProgressIndicator(modifier = Modifier.width(160.dp), color = tokens.colors.accent)
            } else {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary, textAlign = TextAlign.Center)
            }
            body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, textAlign = TextAlign.Center) }
            if (actionLabel != null && onAction != null) NuvioPrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Composable
private fun CalendarList(
    sections: List<CalendarSection>,
    settings: CalendarSettings,
    showSettings: Boolean,
    onToggleSettings: () -> Unit,
    controller: CalendarScreenController,
    onOpen: (CalendarTitleRecord) -> Unit,
    onWatchGame: (String) -> Unit,
    nowMs: Long,
) {
    val tokens = MaterialTheme.nuvio
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = tokens.spacing.screenHorizontal,
            vertical = tokens.spacing.listGap,
        ),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
    ) {
        sections.forEach { section ->
            item(key = "h:${section.kind}:${section.date}") {
                Text(
                    text = calendarSectionTitle(section),
                    modifier = Modifier.padding(top = tokens.spacing.sectionGap / 2),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (section.kind == CalendarSectionKind.TODAY) tokens.colors.accent else tokens.colors.textSecondary,
                )
            }
            items(section.items, key = { "i:${section.kind}:${section.date}:${it.title.key}:${it.entry?.slot}" }) { item ->
                CalendarRow(item = item, showDate = section.kind == CalendarSectionKind.JUST_AIRED || section.kind == CalendarSectionKind.LATER, onOpen = onOpen, onHide = controller::hide, onWatchGame = onWatchGame, nowMs = nowMs)
            }
        }
        item(key = "settings") {
            CalendarSettingsCard(settings, showSettings, onToggleSettings, controller)
        }
    }
}

@Composable
private fun CalendarRow(
    item: CalendarItem,
    showDate: Boolean,
    onOpen: (CalendarTitleRecord) -> Unit,
    onHide: (CalendarTitleRecord) -> Unit,
    onWatchGame: (String) -> Unit,
    nowMs: Long,
) {
    val game = item.game
    if (game != null) {
        GameRow(item, game, showDate, onWatchGame, nowMs)
        return
    }
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().clip(tokens.shapes.compactCard).background(tokens.colors.surfaceCard)
            .clickable { onOpen(item.title) }.padding(tokens.spacing.cardPaddingCompact),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.title.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(48.dp).aspectRatio(2f / 3f).clip(tokens.shapes.poster).background(tokens.colors.surface),
        )
        Spacer(Modifier.width(tokens.spacing.controlGap))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title.name ?: item.title.key, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            calendarEntryLine(item.entry)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val whenText = buildList {
                if (showDate) item.date?.let { add(calendarDayText(it)) }
                if (item.entry != null) add(item.time?.let(::calendarTimeText) ?: stringResource(Res.string.calendar_time_unknown))
                item.title.network?.let { add(it) }
            }.joinToString(" · ")
            if (whenText.isNotEmpty()) Text(whenText, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1)
            item.entry?.label?.let { calendarLabelText(it) }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = tokens.colors.accent, fontWeight = FontWeight.SemiBold)
            }
        }
        IconButton(onClick = { onHide(item.title) }) {
            Icon(Icons.Rounded.VisibilityOff, contentDescription = stringResource(Res.string.calendar_hide_title), tint = tokens.colors.textMuted)
        }
    }
}

/** A sports game: both sides, league and kick-off; score + minute when live, score + FT when final; Watch now. */
@Composable
private fun GameRow(item: CalendarItem, game: CalendarGameItem, showDate: Boolean, onWatchGame: (String) -> Unit, nowMs: Long) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().clip(tokens.shapes.compactCard).background(tokens.colors.surfaceCard)
            .padding(tokens.spacing.cardPaddingCompact),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            GameBadge(game.homeBadge)
            if (!game.home.isNullOrBlank() && !game.away.isNullOrBlank()) GameBadge(game.awayBadge)
        }
        Spacer(Modifier.width(tokens.spacing.controlGap))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val hasSides = !game.home.isNullOrBlank() && !game.away.isNullOrBlank()
            Text(
                text = if (hasSides) stringResource(Res.string.calendar_game_versus, game.home.orEmpty(), game.away.orEmpty()) else game.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val whenText = buildList {
                if (showDate) item.date?.let { add(calendarDayText(it)) }
                item.time?.let { add(calendarTimeText(it)) }
                game.league?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString(" · ")
            if (whenText.isNotEmpty()) Text(whenText, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            calendarGameStatusText(game)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (game.state == com.nuvio.app.features.calendar.api.CalendarGameState.LIVE) tokens.colors.accent else tokens.colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (game.canWatchNow(nowMs)) {
            TextButton(onClick = { onWatchGame(game.id) }) {
                Text(stringResource(Res.string.calendar_game_watch_now), color = tokens.colors.accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun GameBadge(url: String?) {
    val tokens = MaterialTheme.nuvio
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(tokens.colors.surface),
    )
}

@Composable
private fun CalendarSettingsCard(
    settings: CalendarSettings,
    expanded: Boolean,
    onToggle: () -> Unit,
    controller: CalendarScreenController,
) {
    val tokens = MaterialTheme.nuvio
    NuvioSurfaceCard(modifier = Modifier.padding(top = tokens.spacing.sectionGap)) {
        Text(
            text = stringResource(Res.string.calendar_settings),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = tokens.colors.textPrimary,
        )
        if (expanded) {
            SettingSwitch(stringResource(Res.string.calendar_setting_coming_up_row), settings.comingUpRow, controller::setComingUpRow)
            SettingSwitch(stringResource(Res.string.calendar_setting_corner_note), settings.cornerMessage, controller::setCornerMessage)
            Text(stringResource(Res.string.calendar_setting_window), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap)) {
                CALENDAR_WINDOW_OPTIONS.forEach { days ->
                    FilterChip(selected = settings.windowDays == days, onClick = { controller.setWindowDays(days) }, label = { Text("$days") })
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textPrimary)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CalendarMonthView(
    month: CalendarMonth,
    onShift: (Int) -> Unit,
    onOpen: (CalendarTitleRecord) -> Unit,
    onWatchGame: (String) -> Unit,
    nowMs: Long,
) {
    val tokens = MaterialTheme.nuvio
    var selected by remember(month.year, month.month) { mutableStateOf(month.weeks.flatten().firstOrNull { it.isToday }?.date) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = tokens.spacing.screenHorizontal, vertical = tokens.spacing.listGap),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "month-header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onShift(-1) }) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = stringResource(Res.string.calendar_previous_month), tint = tokens.colors.textPrimary)
                }
                Text(
                    text = "${stringResource(monthResource(month.month))} ${month.year}",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.textPrimary,
                )
                IconButton(onClick = { onShift(1) }) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = stringResource(Res.string.calendar_next_month), tint = tokens.colors.textPrimary)
                }
            }
        }
        item(key = "weekdays") {
            Row(Modifier.fillMaxWidth()) {
                (1..7).forEach { day ->
                    Text(stringResource(weekdayResource(day)), modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted)
                }
            }
        }
        items(month.weeks, key = { it.first().date.toString() }) { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day ->
                    val isSelected = day.date == selected
                    Column(
                        modifier = Modifier.weight(1f).aspectRatio(0.8f).clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    isSelected -> tokens.colors.accent.copy(alpha = 0.25f)
                                    day.inMonth -> tokens.colors.surfaceCard
                                    else -> tokens.colors.surface
                                },
                            )
                            .clickable { selected = day.date }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "${day.date.day}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                day.isToday -> tokens.colors.accent
                                day.inMonth -> tokens.colors.textPrimary
                                else -> tokens.colors.textDisabled
                            },
                        )
                        if (day.items.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                repeat(day.items.size.coerceAtMost(3)) {
                                    Box(Modifier.size(5.dp).clip(CircleShape).background(tokens.colors.accent))
                                }
                            }
                        }
                    }
                }
            }
        }
        val selectedDay = month.weeks.flatten().firstOrNull { it.date == selected }
        if (selectedDay != null) {
            item(key = "day-title") {
                Text(
                    text = calendarDayText(selectedDay.date),
                    modifier = Modifier.padding(top = tokens.spacing.sectionGap / 2),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.textSecondary,
                )
            }
            items(selectedDay.items, key = { "d:${it.title.key}:${it.entry?.slot}" }) { item ->
                CalendarRow(item = item, showDate = false, onOpen = onOpen, onHide = {}, onWatchGame = onWatchGame, nowMs = nowMs)
            }
        }
    }
}

/** Kept for the month header: "October 2026" with the month number from kotlinx-datetime. */
internal fun calendarMonthNumber(date: LocalDate): Int = date.month.number
