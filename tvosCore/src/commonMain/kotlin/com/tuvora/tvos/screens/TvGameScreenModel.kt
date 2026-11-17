package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.api.EventHeader
import com.nuvio.app.features.sportsdetail.api.FormBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.Header
import com.nuvio.app.features.sportsdetail.api.LeadersBlock
import com.nuvio.app.features.sportsdetail.api.LineupBlock
import com.nuvio.app.features.sportsdetail.api.LinescoreBlock
import com.nuvio.app.features.sportsdetail.api.LiveInfo
import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.SituationBlock
import com.nuvio.app.features.sportsdetail.api.SoccerStripBlock
import com.nuvio.app.features.sportsdetail.api.StandingsBlock
import com.nuvio.app.features.sportsdetail.api.StandingsGroup
import com.nuvio.app.features.sportsdetail.api.StandingsTableBlock
import com.nuvio.app.features.sportsdetail.api.StandingsTableRow
import com.nuvio.app.features.sportsdetail.api.StatRow
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineBlock
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.api.VersusHeader
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import com.nuvio.app.features.sportsdetail.internal.GameDetailUiPolicy
import com.nuvio.app.features.sportsdetail.internal.GameMomentPolicy
import com.nuvio.app.features.sportsdetail.internal.GameSection
import com.nuvio.app.features.sportsdetail.internal.GameSectionsPolicy
import com.nuvio.app.features.sportsdetail.internal.Moment
import com.nuvio.app.features.sportsdetail.internal.StandingsWindow
import com.nuvio.app.features.sportsdetail.internal.TeamColor
import com.nuvio.app.features.sportsdetail.internal.roundLabel
import com.nuvio.app.features.sportsdetail.internal.WinChanceSeries

/** Sections the right-hand panel can show (OVERVIEW is not a chip on TV: its content is the left column). */
enum class TvSection { PLAYS, STATS, LEADERS, LINEUPS, STANDINGS }

data class TvChip(val section: TvSection, val title: String)

/**
 * The selected chip is a SECTION, never a slot number: when the chip list changes (an upcoming soccer game gains Plays / Stats /
 * Leaders at kickoff) the user keeps looking at the same section. A section that is gone falls back to the first chip.
 */
object TvSectionSelection {
    fun indexOf(chips: List<TvChip>, selected: TvSection?): Int = chips.indexOfFirst { it.section == selected }.coerceAtLeast(0)
    fun resolve(chips: List<TvChip>, selected: TvSection?): TvSection? = chips.getOrNull(indexOf(chips, selected))?.section
}

data class TvScoreSide(
    val name: String,
    val shortName: String,
    val record: String?,
    /** Poll rank; null when unranked. Drawn muted before the name. */
    val rank: Int?,
    val score: String?,
    /** Contrast-safe team colour, 6 hex digits without '#'; null -> Swift falls back to the theme accent / border. */
    val colorHex: String?,
    val badgeUrl: String?,
    /** Soccer goal scorers ("Saka 23'"), at most 3 then "+N". */
    val scorers: List<String> = emptyList(),
)

data class TvScoreboard(
    /** "NHL · Round 7". */
    val leagueLine: String,
    /** The one status line: "2nd · 5:12", "Halftime", "Final"; null for an upcoming game (Swift draws the kickoff). */
    val statusMain: String?,
    /** A second line only when it adds something the main line does not already say (never the clock twice). */
    val statusSub: String?,
    val venue: String?,
    val isLive: Boolean,
    /** Team / versus games; null for event headers (F1, UFC). */
    val home: TvScoreSide?,
    val away: TvScoreSide?,
    /** Event headers only. */
    val eventName: String?,
    val eventSession: String?,
)

data class TvLastPlay(val key: String, val time: String, val tag: String?, val text: String)

data class TvWinCard(
    val chance: TvWinChance,
    val homeColorHex: String?,
    val awayColorHex: String?,
    /** The plotted line per core's shared rule (WinChanceSeries): the LEADER's value (three-way: full chance), oldest first, at most 120; empty without a series. */
    val chart: List<Float>,
    /** "home" | "away": whose line [chart] is (the headline leader), so Swift tints it and the line ends at the big number. */
    val leaderSide: String = "home",
    /** The dashed reference line in 0..1 plot space: 0.5 two-way, an even third three-way. */
    val reference: Float = 0.5f,
    val homeName: String,
    val awayName: String,
    /** At most 5 on TV; empty when the chartMarkers switch is off, there is no market series or no key events. */
    val markers: List<TvChartMarker> = emptyList(),
    val dividers: List<TvChartDivider> = emptyList(),
)

data class TvPlayRow(
    val key: String, val time: String, val period: String?, val tag: String?, val kind: String?, val text: String, val score: String?,
    /** A hockey goal the FEED marked as scored on the power play (from the play's own strength, never our clock). */
    val ppg: Boolean = false,
)

data class TvStatRowView(
    val label: String,
    val home: String,
    val away: String,
    /** Home's share of the bar 0..1; null when a value is not a plain number ("4/11"): the bar is omitted. */
    val homeFraction: Float?,
    /** "home" | "away" | null (tie / non-numeric). Honours lower-is-better rows. */
    val leader: String?,
)

data class TvByPeriod(val columns: List<String>, val rowLabels: List<String>, val rows: List<List<String>>)
data class TvLeaderRowView(val category: String, val athlete: String, val value: String, val tag: String?)
data class TvFormView(val homeName: String, val awayName: String, val home: List<String>, val away: List<String>)
data class TvLineupSideView(val name: String, val formation: String?, val players: List<String>)
data class TvStandingsRowView(val name: String, val cells: List<String>, val isGameTeam: Boolean)
data class TvStandingsGroupView(val name: String, val columns: List<String>, val rows: List<TvStandingsRowView>)

/** Everything the right-hand panel can draw, already capped for the available height. Swift picks by the selected chip. */
data class TvPanels(
    val plays: List<TvPlayRow> = emptyList(),
    val stats: List<TvStatRowView> = emptyList(),
    val byPeriod: TvByPeriod? = null,
    val leaders: List<TvLeaderRowView> = emptyList(),
    val form: TvFormView? = null,
    val lineups: List<TvLineupSideView> = emptyList(),
    val standings: List<TvStandingsGroupView> = emptyList(),
)

/** What actually changed since the previous snapshot. Each is animated once; nothing loops. */
enum class TvMomentKind {
    SCORE_CHANGE, WIN_CHANCE_MOVE, NEW_PLAY, FIELD_MOVE, TOUCHDOWN, POSSESSION_CHANGE, SOCCER_EVENT,
    POWER_PLAY_START, POWER_PLAY_END, FOUL_COUNT, BONUS_REACHED, TIMEOUT_USED,
    MARKER_ADDED, STAT_CHANGE,
}
data class TvMoment(
    val kind: TvMomentKind, val side: String?, val from: Int?, val to: Int?, val text: String?,
    /** FIELD_MOVE: the ball moved away from the goal it attacks. */
    val loss: Boolean = false,
    /** FIELD_MOVE: down returned to 1 with the same team on the ball. */
    val firstDown: Boolean = false,
    /** STAT_CHANGE: the leading side flipped (the pill cross-fades). */
    val leaderChanged: Boolean = false,
    /** NEW_PLAY: the key of that play's row in the Plays panel / last-play card, so only that row animates in. */
    val key: String? = null,
)

data class TvGameScreenModel(
    val gameId: String,
    val scoreboard: TvScoreboard,
    val lastPlay: TvLastPlay?,
    val winCard: TvWinCard?,
    val chips: List<TvChip>,
    val panels: TvPanels,
    /** No chips (F1, UFC, tennis today): the right column keeps the old situation / by-period / plays cards. */
    val situationLine: String?,
    val updatedLabel: String?,
    val moments: List<TvMoment>,
    val field: TvFieldStrip? = null,
    val soccer: TvSoccerStrip? = null,
    /** Both lineups placed on a pitch; null -> Swift lists the players (unknown formation or missing places). */
    val pitch: TvPitch? = null,
    val basketball: TvBasketballStrip? = null,
    val hockey: TvHockeyStrip? = null,
    val leftFit: TvLeftFit = TvLeftFit(showChart = true, showLastPlay = true),
    /** VoiceOver: posted only on a score change, once per update ("Dallas 2, Buffalo 0"). */
    val announcement: String?,
) {
    companion object {
        /** Dark card surface the team colours are made to read against (NuvioTheme backgroundCard family). */
        const val SURFACE_HEX = "1c1c1c"

        /**
         * Everything the game screen draws, decided here so Swift only lays it out. [previous] is the snapshot before this one
         * (null on open, after a resume or for another game: nothing animates then); [stale] = the last refresh failed.
         */
        fun from(
            detail: GameDetail, nowMs: Long, hasPreview: Boolean,
            previous: GameDetail? = null, stale: Boolean = false,
        ): TvGameScreenModel {
            val sides = sidesOf(detail.header)
            val colors = if (sides != null) TeamColor.pair(sides.first, sides.second, SURFACE_HEX) else null to null
            val moments = if (previous == null) emptyList() else GameMomentPolicy.diff(previous, detail)
            val field = TvStrips.field(detail, sides, colors)
            val soccer = TvStrips.soccer(detail, sides)
            val pitch = TvStrips.pitch(detail, colors)
            val basketball = TvStrips.basketball(detail)
            val hockey = TvStrips.hockey(detail)
            val board = scoreboard(detail, sides, colors, soccer = detail.blocks.filterIsInstance<SoccerStripBlock>().firstOrNull())
            val win = winCard(detail, sides, colors, nowMs)
            val last = lastPlay(detail)
            val leftFit = TvLeftColumnFit.fit(
                stripDp = when {
                    field != null -> TvStrips.fieldDp(field); soccer != null -> TvStrips.SOCCER_DP
                    basketball != null -> TvStrips.BASKETBALL_DP; hockey != null -> TvStrips.HOCKEY_DP; else -> 0
                },
                scorerLines = maxOf(board.home?.scorers?.size ?: 0, board.away?.scorers?.size ?: 0),
                hasLine = win?.chance?.line != null, hasChart = (win?.chart?.size ?: 0) >= 2, hasLastPlay = last != null,
                hasDividers = win?.dividers?.isNotEmpty() == true,
                hasStaleLabel = win?.chance?.staleLabel != null,
                timeoutDots = field?.let { it.homeTimeouts != null || it.awayTimeouts != null } == true,
            )
            return TvGameScreenModel(
                gameId = detail.gameId,
                scoreboard = board,
                lastPlay = last,
                winCard = win,
                chips = chips(detail),
                panels = panels(detail, hasPreview, hasChips = GameSectionsPolicy.sections(detail).size > 1),
                situationLine = detail.blocks.filterIsInstance<SituationBlock>().firstOrNull()?.line?.takeIf { it.isNotBlank() },
                updatedLabel = GameDetailUiPolicy.staleLabel(detail.asOf, nowMs) ?: if (stale) "Updating…" else null,
                moments = moments.mapNotNull(::moment),
                field = field, soccer = soccer, pitch = pitch, basketball = basketball, hockey = hockey, leftFit = leftFit,
                announcement = announcement(detail, moments),
            )
        }

        private fun sidesOf(h: Header): Pair<Side, Side>? = when (h) {
            is TeamsHeader -> h.home to h.away
            is VersusHeader -> h.a to h.b
            else -> null
        }

        /** A bare number reads through core's [roundLabel] ("Week 11" for American football, "Round 7" otherwise); a text round ("Quarterfinal") stays as the feed wrote it. */
        internal fun leagueLine(league: String, sport: String?, round: String?): String {
            val r = round?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.all(Char::isDigit)) roundLabel(sport, it) else it }
            return listOfNotNull(league.takeIf { it.isNotBlank() }, r).joinToString(" · ")
        }

        /** The sub line is kept only when it says something the main line does not (never the clock twice). */
        internal fun statusSub(main: String?, live: LiveInfo?): String? {
            val d = live?.detail?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val clock = live.clock?.trim()?.takeIf { it.isNotEmpty() }
            if (main != null && main.equals(d, ignoreCase = true)) return null
            if (clock != null && d.contains(clock, ignoreCase = true)) return null
            if (main != null && main.contains(d, ignoreCase = true)) return null
            // "Halftime" / "Intermission" are already the main line's wording.
            val lower = d.lowercase()
            if (main != null && (lower.contains("halftime") || lower.contains("half-time") || lower.contains("intermission")) &&
                (main == "Halftime" || main == "Intermission")) return null
            return d
        }

        /** At most 3 scorer lines, then "+N". */
        private fun scorers(all: List<String>?): List<String> {
            val l = all.orEmpty()
            return if (l.size <= 3) l else l.take(3) + "+${l.size - 3}"
        }

        private fun side(s: Side, hex: String?, scorers: List<String> = emptyList()) = TvScoreSide(
            name = s.name, shortName = s.short?.takeIf { it.isNotBlank() } ?: s.name, record = s.record, rank = s.rank,
            score = s.score, colorHex = hex, badgeUrl = s.badgeUrl, scorers = scorers,
        )

        private fun scoreboard(d: GameDetail, sides: Pair<Side, Side>?, colors: Pair<String?, String?>, soccer: SoccerStripBlock?): TvScoreboard {
            val main = when (d.state) {
                GameState.LIVE -> GameDetailUiPolicy.liveStatusText(d.live)
                GameState.FINAL -> "Final"
                GameState.POSTPONED -> "Postponed"
                GameState.UPCOMING -> null
            }
            val event = d.header as? EventHeader
            return TvScoreboard(
                leagueLine = leagueLine(d.leagueName, d.sport, d.round),
                statusMain = main,
                statusSub = if (d.state == GameState.LIVE) statusSub(main, d.live) else null,
                venue = d.venue?.takeIf { it.isNotBlank() },
                isLive = d.state == GameState.LIVE,
                home = sides?.let { side(it.first, colors.first, scorers(soccer?.homeScorers)) },
                away = sides?.let { side(it.second, colors.second, scorers(soccer?.awayScorers)) },
                eventName = event?.name,
                eventSession = event?.session,
            )
        }

        private fun newestPlay(d: GameDetail): TimelineItem? = GameMomentPolicy.newest(d)

        private fun lastPlay(d: GameDetail): TvLastPlay? = newestPlay(d)?.let {
            TvLastPlay(key = it.id ?: "${it.time}|${it.text}", time = it.time, tag = GameDetailUiPolicy.teamTag(it.side, d.header), text = it.text)
        }

        private fun winCard(d: GameDetail, sides: Pair<Side, Side>?, colors: Pair<String?, String?>, nowMs: Long): TvWinCard? {
            val block = d.blocks.filterIsInstance<WinProbabilityBlock>().firstOrNull() ?: return null
            val spec = WinChanceSeries.specOf(block)
            return TvWinCard(
                chance = TvWinChanceCard.from(block, d, nowMs),
                homeColorHex = colors.first, awayColorHex = colors.second,
                chart = if (WinChanceSeries.isVisible(block.history)) WinChanceSeries.chart(block.history, spec).orEmpty() else emptyList(), leaderSide = spec.leaderSide, reference = spec.reference,
                markers = TvStrips.markers(d, colors), dividers = TvStrips.dividers(d),
                homeName = sides?.first?.let { it.short?.takeIf(String::isNotBlank) ?: it.name }.orEmpty(),
                awayName = sides?.second?.let { it.short?.takeIf(String::isNotBlank) ?: it.name }.orEmpty(),
            )
        }

        private fun chips(d: GameDetail): List<TvChip> = GameSectionsPolicy.sections(d).mapNotNull {
            when (it) {
                GameSection.OVERVIEW -> null
                GameSection.PLAYS -> TvChip(TvSection.PLAYS, "Plays")
                GameSection.STATS -> TvChip(TvSection.STATS, "Stats")
                GameSection.LEADERS -> TvChip(TvSection.LEADERS, "Leaders")
                GameSection.LINEUPS -> TvChip(TvSection.LINEUPS, "Lineups")
                GameSection.STANDINGS -> TvChip(TvSection.STANDINGS, "Standings")
            }
        }

        private fun panels(d: GameDetail, preview: Boolean, hasChips: Boolean): TvPanels {
            val caps = TvPanelFit.caps(preview)
            val playItems = (GameSectionsPolicy.blocksFor(GameSection.PLAYS, d).firstOrNull()).let {
                when (it) {
                    is PlaysBlock -> it.items
                    is TimelineBlock -> it.items.asReversed()   // the scoring summary is oldest first; the panel is newest first
                    else -> emptyList()
                }
            }
            val stats = (GameSectionsPolicy.blocksFor(GameSection.STATS, d).firstOrNull() as? StatsBlock)?.rows.orEmpty()
            val leaders = (GameSectionsPolicy.blocksFor(GameSection.LEADERS, d).firstOrNull() as? LeadersBlock)?.items.orEmpty()
            val sides = sidesOf(d.header)
            val line = d.blocks.filterIsInstance<LinescoreBlock>().firstOrNull()
            val form = d.blocks.filterIsInstance<FormBlock>().firstOrNull()?.takeIf { !GameDetailUiPolicy.isEmpty(it) }
            return TvPanels(
                plays = playItems.take(caps.plays).map { playRow(it, d) },
                stats = stats.take(caps.stats).map(::statRow),
                byPeriod = if (stats.isNotEmpty() || !hasChips) line?.let { byPeriod(it, d) } else null,
                leaders = leaders.take(caps.leaders).map {
                    TvLeaderRowView(it.category, it.athlete, it.value, GameDetailUiPolicy.teamTag(it.side, d.header))
                },
                form = if (leaders.isNotEmpty() && form != null) TvFormView(
                    homeName = sides?.first?.let { it.short?.takeIf(String::isNotBlank) ?: it.name }.orEmpty(),
                    awayName = sides?.second?.let { it.short?.takeIf(String::isNotBlank) ?: it.name }.orEmpty(),
                    home = form.home, away = form.away,
                ) else null,
                lineups = (GameSectionsPolicy.blocksFor(GameSection.LINEUPS, d).firstOrNull() as? LineupBlock)?.let { lineups(it, d, caps.lineupPlayers) }.orEmpty(),
                standings = standings(d, caps.standings),
            )
        }

        private fun playRow(i: TimelineItem, d: GameDetail) = TvPlayRow(
            key = i.id ?: "${i.time}|${i.text}", time = i.time, period = periodLabel(d.sport, i.period), tag = GameDetailUiPolicy.teamTag(i.side, d.header),
            kind = i.kind, text = i.text, score = i.score, ppg = i.kind == "ppg",
        )

        private fun ordinal(n: Int) = when {
            n % 100 in 11..13 -> "${n}th"
            n % 10 == 1 -> "${n}st"
            n % 10 == 2 -> "${n}nd"
            n % 10 == 3 -> "${n}rd"
            else -> "${n}th"
        }

        /** A bare "2" from the feed reads as the sport's own word ("2nd half"); a label the feed already wrote is kept. */
        internal fun periodLabel(sport: String, raw: String?): String? {
            val p = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val n = p.toIntOrNull() ?: return p
            return when (sport.lowercase()) {
                // Same words core's assembler writes (EspnAssembler): 3 and 4 are the extra-time halves, 5 and up the shootout.
                "soccer" -> when { n <= 2 -> "${ordinal(n)} half"; n == 3 -> "Extra time 1"; n == 4 -> "Extra time 2"; else -> "Shootout" }
                "basketball", "american football" -> "${ordinal(n)} quarter"
                "ice hockey" -> "${ordinal(n)} period"
                else -> p
            }
        }

        private fun number(s: String): Double? = s.trim().removeSuffix("%").toDoubleOrNull()

        private fun statRow(r: StatRow): TvStatRowView {
            val h = number(r.home); val a = number(r.away)
            if (h == null || a == null) return TvStatRowView(r.label, r.home, r.away, null, null)
            val total = h + a
            val lower = GameDetailUiPolicy.lowerIsBetter(r.label)
            val leader = when {
                h == a -> null
                (h > a) != lower -> "home"
                else -> "away"
            }
            return TvStatRowView(r.label, r.home, r.away, if (total > 0) (h / total).toFloat() else 0.5f, leader)
        }

        private fun byPeriod(b: LinescoreBlock, d: GameDetail): TvByPeriod {
            val t = GameDetailUiPolicy.trimUnstartedPeriods(b, d.state, d.live?.period)
            return TvByPeriod(t.columns, t.rowLabels, t.rows)
        }

        private fun lineups(b: LineupBlock, d: GameDetail, cap: Int): List<TvLineupSideView> = b.sides.map { s ->
            TvLineupSideView(
                name = GameDetailUiPolicy.teamTag(s.side, d.header).orEmpty(), formation = s.formation,
                players = s.players.filter { it.starter }.take(cap).map { listOf(it.number, it.name).filter(String::isNotBlank).joinToString(" ") },
            )
        }

        private fun standings(d: GameDetail, max: Int): List<TvStandingsGroupView> {
            val block = GameSectionsPolicy.blocksFor(GameSection.STANDINGS, d).firstOrNull()
            val table = when (block) {
                is StandingsTableBlock -> block
                is StandingsBlock -> StandingsTableBlock(listOf(StandingsGroup(
                    name = "", columns = listOf("P", "PTS"),
                    rows = block.rows.map { StandingsTableRow(null, "${it.pos}  ${it.name}", listOf(it.played, it.points), isGameTeam = false) },
                )))
                else -> return emptyList()
            }
            return StandingsWindow.window(table, max).groups.map { g ->
                TvStandingsGroupView(g.name, g.columns, g.rows.map { TvStandingsRowView(it.name, it.cells, it.isGameTeam) })
            }
        }

        private fun moment(m: Moment): TvMoment? = when (m) {
            is Moment.ScoreChange -> TvMoment(TvMomentKind.SCORE_CHANGE, m.side, null, m.to.toIntOrNull(), m.to)
            is Moment.WinChanceMove -> TvMoment(TvMomentKind.WIN_CHANCE_MOVE, null, m.fromHome, m.toHome, null)
            is Moment.NewPlay -> TvMoment(TvMomentKind.NEW_PLAY, m.item.side, null, null, m.item.text, key = m.item.id ?: "${m.item.time}|${m.item.text}")
            is Moment.FieldMove -> TvMoment(TvMomentKind.FIELD_MOVE, null, m.fromYard, m.toYard, null, loss = m.loss, firstDown = m.firstDown)
            is Moment.Touchdown -> TvMoment(TvMomentKind.TOUCHDOWN, m.side, null, null, null)
            is Moment.PossessionChange -> TvMoment(TvMomentKind.POSSESSION_CHANGE, m.to, null, null, null)
            is Moment.SoccerMoment -> TvMoment(TvMomentKind.SOCCER_EVENT, m.event.side, null, null, m.event.text)
            is Moment.PowerPlay -> TvMoment(if (m.started) TvMomentKind.POWER_PLAY_START else TvMomentKind.POWER_PLAY_END, m.side, null, null, null)
            is Moment.FoulCount -> TvMoment(TvMomentKind.FOUL_COUNT, m.side, null, m.to, null)
            is Moment.BonusReached -> TvMoment(TvMomentKind.BONUS_REACHED, m.side, null, null, null)
            is Moment.TimeoutUsed -> TvMoment(TvMomentKind.TIMEOUT_USED, m.side, null, null, null)
            is Moment.MarkerAdded -> TvMoment(TvMomentKind.MARKER_ADDED, m.marker.side, null, null, m.marker.label)
            is Moment.StatChange -> TvMoment(TvMomentKind.STAT_CHANGE, null, null, null, m.label, leaderChanged = m.leaderChanged)
        }

        private fun announcement(d: GameDetail, moments: List<Moment>): String? {
            if (moments.none { it is Moment.ScoreChange }) return null
            val (h, a) = sidesOf(d.header) ?: return null
            // Full names: a city is not always the first word ("New York Rangers"), and VoiceOver reads them fine.
            return "${h.name} ${h.score.orEmpty()}, ${a.name} ${a.score.orEmpty()}"
        }
    }
}

/** Row caps so nothing clips on the fixed 1920 x 1080 canvas: no scrolling, so the panel never overflows (mirrors Android TV's PanelFit). */
object TvPanelFit {
    data class Caps(val plays: Int, val stats: Int, val leaders: Int, val standings: Int, val lineupPlayers: Int)
    fun caps(hasPreview: Boolean) =
        if (hasPreview) Caps(plays = 4, stats = 3, leaders = 2, standings = 3, lineupPlayers = 4)
        else Caps(plays = 9, stats = 6, leaders = 4, standings = 7, lineupPlayers = 8)
}
