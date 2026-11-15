package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.api.BasketballStripBlock
import com.nuvio.app.features.sportsdetail.api.FieldDrive
import com.nuvio.app.features.sportsdetail.api.HockeyStripBlock
import com.nuvio.app.features.sportsdetail.api.ScoringRun
import com.nuvio.app.features.sportsdetail.api.FootballFieldBlock
import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LeaderItem
import com.nuvio.app.features.sportsdetail.api.LeadersBlock
import com.nuvio.app.features.sportsdetail.api.LineupBlock
import com.nuvio.app.features.sportsdetail.api.LineupPlayer
import com.nuvio.app.features.sportsdetail.api.LineupSide
import com.nuvio.app.features.sportsdetail.api.LinescoreBlock
import com.nuvio.app.features.sportsdetail.api.LiveInfo
import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.SoccerEvent
import com.nuvio.app.features.sportsdetail.api.SoccerStripBlock
import com.nuvio.app.features.sportsdetail.api.StandingsGroup
import com.nuvio.app.features.sportsdetail.api.StandingsTableBlock
import com.nuvio.app.features.sportsdetail.api.StandingsTableRow
import com.nuvio.app.features.sportsdetail.api.StatRow
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineItem
import com.nuvio.app.features.sportsdetail.api.WinPoint
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import com.nuvio.app.features.radar.RadarTime

/**
 * DEBUG-ONLY canned snapshots for simulator passes when no live game is available (launch the Debug app with
 * `-gameDemo <football|soccer>`; Swift gates this behind `#if DEBUG`). Not used by any production path. Each kind has
 * three successive snapshots so the once-per-update animations can be watched.
 */
object TvGameScreenDemo {
    const val STEPS = 3

    fun detail(kind: String, step: Int): GameDetail? = when (kind) {
        "football" -> football(step.coerceIn(0, STEPS - 1))
        "soccer" -> soccer(step.coerceIn(0, STEPS - 1))
        "hockey" -> hockey(step.coerceIn(0, STEPS - 1))
        "basketball" -> basketball(step.coerceIn(0, STEPS - 1))
        else -> null
    }

    private fun now() = RadarTime.nowMs()

    private fun play(id: Int, time: String, side: String, text: String, period: String, kind: String? = null, score: String? = null) =
        TimelineItem(time = time, period = period, side = side, text = text, kind = kind, id = "$id", score = score)

    /** One point per minute ending now, so key events can be placed by wall clock exactly like the real feed. */
    private fun history(vararg v: Int): List<WinPoint> = v.mapIndexed { i, h -> WinPoint(t = now() - (v.size - 1 - i) * 60_000L, home = h) }

    private fun chartPlay(n: Int, index: Int, kind: String, side: String?, text: String, period: String = "1", id: String) =
        TimelineItem(time = "", period = period, side = side, text = text, kind = kind, id = id, wallclockMs = now() - (n - 1 - index) * 60_000L)

    private fun football(step: Int): GameDetail {
        val homeScore = listOf("14", "14", "21")[step]
        val awayScore = listOf("10", "10", "10")[step]
        val field = when (step) {
            0 -> FootballFieldBlock(yardLine = 25, possession = "home", down = 1, distance = 10, line = "1st & 10 at NYG 25", homeTimeouts = 3, awayTimeouts = 2,
                drive = FieldDrive(startYardLine = 18, summary = "3 plays, 7 yards, 1:12"))
            1 -> FootballFieldBlock(yardLine = 41, possession = "home", down = 1, distance = 10, line = "1st & 10 at WSH 41", homeTimeouts = 3, awayTimeouts = 2,
                drive = FieldDrive(startYardLine = 18, summary = "6 plays, 23 yards, 2:40"))
            else -> FootballFieldBlock(yardLine = 62, possession = "away", down = 1, distance = 10, line = "1st & 10 at NYG 38", homeTimeouts = 2, awayTimeouts = 2,
                drive = FieldDrive(startYardLine = 75, summary = "1 play, 13 yards, 0:09"))
        }
        val plays = listOf(
            listOf(play(3, "9:12", "home", "S. Barkley rush up the middle for 4 yards", "2nd quarter"), play(2, "9:48", "away", "Punt by WSH for 44 yards", "2nd quarter"), play(1, "11:02", "home", "D. Jones pass complete to W. Robinson for 11 yards", "2nd quarter")),
            listOf(play(4, "8:31", "home", "D. Jones pass complete to S. Barkley for 16 yards", "2nd quarter", "score"), play(3, "9:12", "home", "S. Barkley rush up the middle for 4 yards", "2nd quarter"), play(2, "9:48", "away", "Punt by WSH for 44 yards", "2nd quarter")),
            listOf(play(6, "7:15", "away", "J. Daniels kickoff return to the NYG 38", "2nd quarter"), play(5, "7:20", "home", "D. Jones pass to W. Robinson for 6 yards, TOUCHDOWN", "2nd quarter", "score", "21–10"), play(4, "8:31", "home", "D. Jones pass complete to S. Barkley for 16 yards", "2nd quarter")),
        )[step]
        val win = listOf(listOf(58, 59, 60, 61), listOf(58, 59, 60, 61, 63), listOf(58, 59, 60, 61, 63, 69))[step]
        return GameDetail(
            gameId = "demo:football", sport = "American Football", leagueId = "4391", leagueName = "NFL", round = "10", venue = "MetLife Stadium",
            state = GameState.LIVE,
            header = TeamsHeader(
                Side("New York Giants", short = "NYG", record = "6-3", score = homeScore, color = "0b2265", altColor = "a71930"),
                Side("Washington Commanders", short = "WSH", record = "5-4", score = awayScore, color = "5a1414", altColor = "ffb612", rank = null),
            ),
            live = LiveInfo(clock = "9:12", periodLabel = "2nd", detail = "2nd Quarter - 9:12", period = 2),
            blocks = listOf<GameBlock>(
                field,
                WinProbabilityBlock(homePercent = win.last(), source = "polymarket", attribution = "via Polymarket", history = history(*win.toIntArray()), asOfMs = now()),
                PlaysBlock(plays, chartItems = buildList {
                    val n = win.size
                    add(chartPlay(n, 1, "periodStart", null, "Start of period 2", "2", "ps2"))
                    add(chartPlay(n, 2, "score", "home", "Field goal good", "1", "fg1"))
                    if (step >= 2) add(chartPlay(n, n - 1, "score", "home", "D. Jones pass to W. Robinson, TOUCHDOWN", "2", "td1"))
                }),
                StatsBlock(listOf(StatRow("Total Yards", "212", "164"), StatRow("Turnovers", "0", "1"), StatRow("3rd down", "4/7", "2/6"), StatRow("Penalties", "3", "5"))),
                LeadersBlock(listOf(LeaderItem("Passing", "D. Jones", "14/19 · 171", "home"), LeaderItem("Rushing", "S. Barkley", "17 · 88", "home"), LeaderItem("Receiving", "W. Robinson", "5 · 62", "home"))),
                LinescoreBlock(listOf("1", "2", "3", "4", "T"), listOf(listOf("7", "7", "", "", homeScore), listOf("3", "7", "", "", awayScore)), listOf("NYG", "WSH")),
                StandingsTableBlock(listOf(StandingsGroup("NFC East", listOf("W", "L", "T", "PCT"), listOf(
                    StandingsTableRow("1", "Philadelphia", listOf("8", "1", "0", ".889")), StandingsTableRow("2", "New York", listOf("6", "3", "0", ".667"), true),
                    StandingsTableRow("3", "Washington", listOf("5", "4", "0", ".556"), true), StandingsTableRow("4", "Dallas", listOf("3", "6", "0", ".333"))))),
                ),
            ),
            asOf = mapOf("espn" to now()),
            uiSwitches = setOf("chartMarkers"),
        )
    }

    private fun xi(prefix: String, formation: String): List<LineupPlayer> =
        (1..11).map { LineupPlayer(number = "$it", name = "$prefix$it", pos = if (it == 1) "G" else "M", starter = true, place = it) }

    private fun soccer(step: Int): GameDetail {
        val home = listOf("1", "1", "2")[step]
        val last = listOf(
            SoccerEvent("yellow", "away", "38'", "Yellow card Palmer"),
            SoccerEvent("corner", "home", "41'", "Corner, Arsenal"),
            SoccerEvent("goal", "home", "44'", "Goal! Arsenal 2, Chelsea 1. Saka"),
        )[step]
        val scorers = listOf(listOf("Saka 23'"), listOf("Saka 23'"), listOf("Saka 23'", "Odegaard 44'"))[step]
        val win = listOf(listOf(52, 54, 58, 60), listOf(52, 54, 58, 60, 61), listOf(52, 54, 58, 60, 61, 70))[step]
        val plays = listOf(
            listOf(play(3, "38'", "away", "Yellow card Palmer, Chelsea", "1", "card"), play(2, "23'", "home", "Goal! Arsenal 1, Chelsea 0. Saka", "1", "goal"), play(1, "11'", "home", "Corner, Arsenal", "1")),
            listOf(play(4, "41'", "home", "Corner, Arsenal", "1"), play(3, "38'", "away", "Yellow card Palmer, Chelsea", "1", "card"), play(2, "23'", "home", "Goal! Arsenal 1, Chelsea 0. Saka", "1", "goal")),
            listOf(play(5, "44'", "home", "Goal! Arsenal 2, Chelsea 1. Saka", "1", "goal"), play(4, "41'", "home", "Corner, Arsenal", "1"), play(3, "38'", "away", "Yellow card Palmer, Chelsea", "1", "card")),
        )[step]
        return GameDetail(
            gameId = "demo:soccer", sport = "Soccer", leagueId = "4328", leagueName = "Premier League", venue = "Emirates Stadium", state = GameState.LIVE,
            header = TeamsHeader(
                Side("Arsenal", short = "ARS", record = "8-2-1", score = home, color = "ef0107", altColor = "ffffff", rank = null),
                Side("Chelsea", short = "CHE", record = "6-3-2", score = "1", color = "034694", altColor = "ffffff"),
            ),
            live = LiveInfo(clock = "44'", periodLabel = null, detail = "1st Half - 44'"),
            blocks = listOf<GameBlock>(
                SoccerStripBlock(lastEvent = last, homePossession = listOf(56, 57, 58)[step], homeCorners = listOf(3, 4, 4)[step], awayCorners = 2,
                    homeYellow = 0, awayYellow = 1, homeRed = 0, awayRed = 0, homeScorers = scorers, awayScorers = listOf("Palmer 31' (pen)")),
                WinProbabilityBlock(homePercent = win.last(), drawPercent = 22, awayPercent = 100 - win.last() - 22, source = "polymarket", attribution = "via Polymarket", history = history(*win.toIntArray()), asOfMs = now()),
                PlaysBlock(plays, chartItems = buildList {
                    val n = win.size
                    add(chartPlay(n, 2, "goal", "home", "Goal! Arsenal 1, Chelsea 0. Saka", "1", "gs1"))
                    if (step >= 2) add(chartPlay(n, n - 1, "goal", "home", "Goal! Arsenal 2, Chelsea 1. Saka", "1", "gs2"))
                }),
                StatsBlock(listOf(StatRow("Shots", "9", "5"), StatRow("Shots on target", "4", "2"), StatRow("Fouls", "7", "9"), StatRow("Possession", "56%", "44%"))),
                LineupBlock(listOf(LineupSide("home", "4-3-3", xi("A", "4-3-3")), LineupSide("away", "4-2-3-1", xi("C", "4-2-3-1")))),
                StandingsTableBlock(listOf(StandingsGroup("Premier League", listOf("P", "W", "D", "L", "GD", "PTS"), listOf(
                    StandingsTableRow("1", "1  Arsenal", listOf("11", "8", "2", "1", "+17", "26"), true), StandingsTableRow("2", "2  Man City", listOf("11", "7", "2", "2", "+12", "23")),
                    StandingsTableRow("3", "5  Chelsea", listOf("11", "6", "3", "2", "+8", "21"), true)))),
                ),
            ),
            asOf = mapOf("espn" to now()),
            uiSwitches = setOf("chartMarkers"),
        )
    }

    private fun hockey(step: Int): GameDetail {
        val home = listOf("2", "2", "3")[step]
        val strip = when (step) {
            0 -> HockeyStripBlock(powerPlaySide = null, homeShots = 20, awayShots = 14, homePowerPlay = "0/1", awayPowerPlay = "0/0", homeFaceoffs = 14, awayFaceoffs = 11)
            1 -> HockeyStripBlock(powerPlaySide = "home", powerPlaySecondsLeft = 72, homeShots = 22, awayShots = 14, homePowerPlay = "0/2", awayPowerPlay = "0/0", homeFaceoffs = 15, awayFaceoffs = 11)
            else -> HockeyStripBlock(powerPlaySide = null, homeShots = 24, awayShots = 15, homePowerPlay = "1/2", awayPowerPlay = "0/0", homeFaceoffs = 16, awayFaceoffs = 12)
        }
        val plays = listOf(
            listOf(play(3, "12:40", "away", "Hit by BUF", "2nd period"), play(2, "13:10", "home", "Shot on goal by DAL", "2nd period"), play(1, "14:02", "away", "Penalty to BUF, tripping", "2nd period", "penalty")),
            listOf(play(4, "11:05", "home", "Shot on goal by DAL", "2nd period"), play(3, "12:40", "away", "Hit by BUF", "2nd period"), play(2, "13:10", "home", "Shot on goal by DAL", "2nd period")),
            listOf(play(5, "9:51", "home", "Goal by DAL, power play", "2nd period", "ppg", "3–1"), play(4, "11:05", "home", "Shot on goal by DAL", "2nd period"), play(3, "12:40", "away", "Hit by BUF", "2nd period")),
        )[step]
        return GameDetail(
            gameId = "demo:hockey", sport = "Ice Hockey", leagueId = "4380", leagueName = "NHL", venue = "American Airlines Center", state = GameState.LIVE,
            header = TeamsHeader(
                Side("Dallas Stars", short = "DAL", record = "9-4-1", score = home, color = "006847", altColor = "8f8f8c"),
                Side("Buffalo Sabres", short = "BUF", record = "7-6-1", score = "1", color = "003087", altColor = "ffb81c"),
            ),
            live = LiveInfo(clock = "9:51", periodLabel = "2nd", detail = "2nd Period - 9:51", period = 2),
            blocks = listOf<GameBlock>(
                strip,
                WinProbabilityBlock(homePercent = listOf(64, 66, 74)[step], source = "polymarket", attribution = "via Polymarket", history = history(60, 61, 63, listOf(64, 66, 74)[step]), asOfMs = now()),
                PlaysBlock(plays, chartItems = buildList {
                    val n = 4
                    add(chartPlay(n, 1, "periodStart", null, "Start of period 2", "2", "ps2"))
                    add(chartPlay(n, 2, "goal", "home", "Goal by DAL", "1", "hg1"))
                    if (step >= 2) add(chartPlay(n, n - 1, "ppg", "home", "Goal by DAL, power play", "2", "hg2"))
                }),
                StatsBlock(listOf(StatRow("Shots", "20", "14"), StatRow("Hits", "12", "18"), StatRow("Penalty Minutes", "2", "4"))),
                StandingsTableBlock(listOf(StandingsGroup("Central", listOf("W", "L", "OTL", "PTS"), listOf(
                    StandingsTableRow("1", "Dallas", listOf("9", "4", "1", "19"), true), StandingsTableRow("2", "Colorado", listOf("8", "4", "2", "18"))))),
                ),
            ),
            asOf = mapOf("espn" to now()),
            uiSwitches = setOf("chartMarkers"),
        )
    }

    private fun basketball(step: Int): GameDetail {
        val strip = when (step) {
            0 -> BasketballStripBlock(homeFouls = 3, awayFouls = 4, homeBonus = "NONE", awayBonus = "NONE", homeTimeouts = 5, awayTimeouts = 4, possession = "home")
            1 -> BasketballStripBlock(homeFouls = 4, awayFouls = 4, homeBonus = "DOUBLE", awayBonus = "NONE", homeTimeouts = 5, awayTimeouts = 4, possession = "away")
            else -> BasketballStripBlock(homeFouls = 4, awayFouls = 5, homeBonus = "DOUBLE", awayBonus = "NONE", homeTimeouts = 4, awayTimeouts = 4, possession = "home",
                run = ScoringRun("home", 10))   // demo only: the real run is behind the `basketballRun` switch (OFF)
        }
        val plays = listOf(
            listOf(play(3, "4:10", "home", "Jump shot by DAL", "3rd quarter", "score", "70–68"), play(2, "4:31", "away", "Foul on BOS", "3rd quarter"), play(1, "4:50", "home", "Rebound by DAL", "3rd quarter")),
            listOf(play(4, "3:44", "away", "Shooting foul on DAL", "3rd quarter"), play(3, "4:10", "home", "Jump shot by DAL", "3rd quarter", "score", "70–68"), play(2, "4:31", "away", "Foul on BOS", "3rd quarter")),
            listOf(play(5, "3:02", "home", "Three point shot by DAL", "3rd quarter", "score", "73–68"), play(4, "3:44", "away", "Shooting foul on DAL", "3rd quarter"), play(3, "4:10", "home", "Jump shot by DAL", "3rd quarter", "score", "70–68")),
        )[step]
        return GameDetail(
            gameId = "demo:basketball", sport = "Basketball", leagueId = "4387", leagueName = "NBA", venue = "American Airlines Center", state = GameState.LIVE,
            header = TeamsHeader(
                Side("Dallas Mavericks", short = "DAL", record = "7-5", score = listOf("70", "70", "73")[step], color = "00538c", altColor = "b8c4ca"),
                Side("Boston Celtics", short = "BOS", record = "9-3", score = "68", color = "007a33", altColor = "ba9653"),
            ),
            live = LiveInfo(clock = "3:02", periodLabel = "3rd", detail = "3rd Quarter - 3:02", period = 3),
            blocks = listOf<GameBlock>(
                strip,
                WinProbabilityBlock(homePercent = listOf(52, 54, 58)[step], source = "polymarket", attribution = "via Polymarket", history = history(48, 50, 51, listOf(52, 54, 58)[step]), asOfMs = now()),
                PlaysBlock(plays),
                StatsBlock(listOf(StatRow("Field Goal %", "48%", "44%"), StatRow("Rebounds", "30", "28"), StatRow("Turnovers", "8", "6"))),
                StandingsTableBlock(listOf(StandingsGroup("West", listOf("W", "L", "PCT", "GB"), listOf(
                    StandingsTableRow("1", "Dallas", listOf("7", "5", ".583", "2.0"), true), StandingsTableRow("2", "Denver", listOf("8", "3", ".727", "–"))))),
                ),
            ),
            asOf = mapOf("espn" to now()),
            uiSwitches = setOf("chartMarkers"),
        )
    }
}
