package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.DetailLoad
import com.nuvio.app.features.sportsdetail.api.FieldDrive
import com.nuvio.app.features.sportsdetail.api.FootballFieldBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameRef
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LineupBlock
import com.nuvio.app.features.sportsdetail.api.LineupPlayer
import com.nuvio.app.features.sportsdetail.api.SituationBlock
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.SoccerEvent
import com.nuvio.app.features.sportsdetail.api.SoccerStripBlock
import com.nuvio.app.features.sportsdetail.internal.BlockType
import com.nuvio.app.features.sportsdetail.internal.EspnAssembler
import com.nuvio.app.features.sportsdetail.internal.EspnSource
import com.nuvio.app.features.sportsdetail.internal.FieldGeometry
import com.nuvio.app.features.sportsdetail.internal.GameDetailSession
import com.nuvio.app.features.sportsdetail.internal.GameMomentPolicy
import com.nuvio.app.features.sportsdetail.internal.GameSection
import com.nuvio.app.features.sportsdetail.internal.GameSectionsPolicy
import com.nuvio.app.features.sportsdetail.internal.LineupPitchPolicy
import com.nuvio.app.features.sportsdetail.internal.Moment
import com.nuvio.app.features.sportsdetail.internal.SourceResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase B: the football field strip, the soccer strip, pitch lineups and their moments. */
class GameScreenStripsBTest {
    private val asm = EspnAssembler(bundledMapping())

    // ---- FieldGeometry --------------------------------------------------------------------------------------------------------

    @Test fun `field - x is the share of the way from the home goal line`() {
        assertEquals(0.25f, FieldGeometry.x(25), 0.0001f)
        assertEquals(0.58f, FieldGeometry.x(58), 0.0001f)
        assertEquals(0f, FieldGeometry.x(0), 0.0001f)
        assertEquals(1f, FieldGeometry.x(100), 0.0001f)
        assertEquals(0f, FieldGeometry.x(-5), 0.0001f, "clamped")
        assertEquals(1f, FieldGeometry.x(140), 0.0001f, "clamped")
    }

    @Test fun `field - the side with the ball attacks the other goal`() {
        assertEquals(1f, FieldGeometry.attackingToward("home"))
        assertEquals(0f, FieldGeometry.attackingToward("away"))
    }

    @Test fun `field - line to gain follows the direction of attack`() {
        assertEquals(0.35f, FieldGeometry.firstDownX(25, 10, "home")!!, 0.0001f, "home ball at 25 1st and 10")
        assertEquals(0.53f, FieldGeometry.firstDownX(58, 5, "away")!!, 0.0001f, "away ball at 58 2nd and 5")
    }

    @Test fun `field - goal to go or unknown down and distance have no line to gain`() {
        assertNull(FieldGeometry.firstDownX(95, 5, "home"), "4th and goal: the goal line IS the line to gain")
        assertNull(FieldGeometry.firstDownX(97, 10, "home"), "it would pass the goal line")
        assertNull(FieldGeometry.firstDownX(4, 5, "away"))
        assertNull(FieldGeometry.firstDownX(25, null, "home"))
        assertNull(FieldGeometry.firstDownX(25, 0, "home"))
        assertNull(FieldGeometry.firstDownX(25, -1, "home"))
        assertNull(FieldGeometry.firstDownX(25, 10, null))
    }

    @Test fun `field - the drive span is ordered and absent without a start`() {
        assertEquals(0.25f to 0.58f, FieldGeometry.driveSpan(25, 58)!!.let { it.first to it.second })
        assertEquals(0.25f to 0.58f, FieldGeometry.driveSpan(58, 25)!!.let { it.first to it.second })
        assertNull(FieldGeometry.driveSpan(null, 58))
    }

    // ---- FootballFieldBlock assembler (live CFB captures 2026-11-14) -------------------------------------------------------------

    private fun field(event: String) = asm.assemble(BlockType.FIELD, "football", parse(event), null) as? FootballFieldBlock

    @Test fun `field block - 4th and goal in the red zone with the home team on the ball`() {
        val b = assertNotNull(field(GameScreenFixtures.cfbLiveEvent))
        assertEquals(
            FootballFieldBlock(
                yardLine = 95, possession = "home", down = 4, distance = 5, line = "4th & Goal at TTU 5", redZone = true,
                homeTimeouts = 3, awayTimeouts = 0, drive = FieldDrive(25, "12 plays, 70 yards, 2:52"), source = "espn",
            ),
            b,
        )
    }

    @Test fun `field block - open field 2nd and 6 on the other capture`() {
        val b = assertNotNull(field(GameScreenFixtures.cfbLiveEvent2))
        assertEquals(51, b.yardLine); assertEquals("home", b.possession); assertEquals(2, b.down); assertEquals(6, b.distance)
        assertEquals("2nd & 6 at UTAH 49", b.line); assertEquals(false, b.redZone)
        assertEquals(3, b.homeTimeouts); assertEquals(3, b.awayTimeouts)
    }

    @Test fun `field block - the possession team id maps to away`() {
        val b = assertNotNull(field(GameScreenFixtures.cfbLiveEvent.replace("\"possession\":\"197\"", "\"possession\":\"2641\"")))
        assertEquals("away", b.possession)
    }

    @Test fun `field block - between plays down and distance are null`() {
        val b = assertNotNull(field(GameScreenFixtures.cfbLiveEvent.replace("\"distance\":5", "\"distance\":-1").replace("\"down\":4", "\"down\":0")))
        assertNull(b.down); assertNull(b.distance)
        assertEquals("4th & Goal at TTU 5", b.line, "the feed text is still the feed's")
    }

    @Test fun `field block - a missing yard line or one outside 0 to 100 drops the block`() {
        assertNull(field(GameScreenFixtures.cfbLiveEvent.replace("\"yardLine\":95", "\"yardLine\":101")))
        assertNull(field(GameScreenFixtures.cfbLiveEvent.replace("\"yardLine\":95", "\"yardLineX\":95")))
        assertNull(field(GameScreenFixtures.cfbLiveEvent.replace("\"yardLine\":95", "\"yardLine\":-3")))
    }

    @Test fun `field block - without a possession id it falls back to the last play team unless the drive ended`() {
        val noPossession = GameScreenFixtures.cfbLiveEvent.replace("\"possession\":\"197\"", "\"possessionX\":\"197\"")
        assertEquals("home", field(noPossession)?.possession, "the last play was run by the home team and the drive has no result")
        val ended = noPossession.replace("\"description\":\"12 plays, 70 yards, 2:52\"", "\"description\":\"12 plays, 70 yards, 2:52\",\"result\":\"PUNT\"")
        assertNull(field(ended)?.possession, "a drive with a result may have changed hands: no guess")
        assertNotNull(field(ended))
    }

    @Test fun `field block - only the football profile has it and an event with no situation has none`() {
        assertNull(asm.assemble(BlockType.FIELD, "basketball", parse(GameScreenFixtures.cfbLiveEvent), null))
        assertNull(field(GameScreenFixtures.nflEvent))
    }

    @Test fun `field block is live only and the old situation line stays for old clients`() = runBlocking<Unit> {
        class Espn(val event: String) : EspnSource {
            override suspend fun scoreboard(espnPath: String, yyyymmdd: String) = SourceResult.Ok(parse("""{"events":[$event]}"""))
            override suspend fun summary(espnPath: String, eventId: String): SourceResult<JsonElement> = SourceResult.Empty
        }
        val ref = GameRef("espn:401856863", "4391", null, "401856863", "Oklahoma State", "Texas Tech", null, 1_794_000_000_000L)
        suspend fun load(event: String): GameDetail {
            val s = GameDetailSession(ref, { bundledMapping() }, null, Espn(event), nowMs = { 1_794_000_500_000L }, report = {})
            return assertIs<DetailLoad.Loaded>(s.tick()).detail
        }
        val live = load(GameScreenFixtures.cfbLiveEvent)
        assertEquals(GameState.LIVE, live.state)
        assertTrue(live.blocks.any { it is FootballFieldBlock }, "live: field")
        assertTrue(live.blocks.any { it is SituationBlock }, "the situation block still exists for the old text card")
        val final = load(GameScreenFixtures.cfbLiveEvent.replace("\"state\":\"in\"", "\"state\":\"post\""))
        assertEquals(GameState.FINAL, final.state)
        assertTrue(final.blocks.none { it is FootballFieldBlock }, "final: no field")
    }

    @Test fun `sections - new clients hide the situation card when the field is there`() {
        val sit = SituationBlock("4th & Goal")
        val field = FootballFieldBlock(95, "home")
        assertEquals(listOf(sit), GameSectionsPolicy.blocksFor(GameSection.OVERVIEW, detailOf(sit)))
        assertEquals(listOf(field), GameSectionsPolicy.blocksFor(GameSection.OVERVIEW, detailOf(sit, field)))
    }

    // ---- SoccerStripBlock ----------------------------------------------------------------------------------------------------------

    private fun strip(event: String, summary: String? = null) =
        asm.assemble(BlockType.SOCCER_STRIP, "soccer", parse(event), summary?.let(::parse)) as? SoccerStripBlock

    private fun live(event: String) = event.replace("\"state\":\"post\"", "\"state\":\"in\"")

    @Test fun `soccer strip - a final match keeps scorers cards possession and corners but no last event`() {
        val b = assertNotNull(strip(GameScreenFixtures.eplEvent))
        assertEquals(listOf("Gómez 15'", "Gross 42' (pen)"), b.homeScorers)
        assertEquals(emptyList(), b.awayScorers)
        assertEquals(4, b.homeYellow); assertEquals(2, b.awayYellow)
        assertEquals(0, b.homeRed); assertEquals(1, b.awayRed)
        assertEquals(75, b.homePossession)
        assertEquals(7, b.homeCorners); assertEquals(3, b.awayCorners)
        assertNull(b.lastEvent)
        assertEquals("espn", b.source)
    }

    @Test fun `soccer strip - live the last event is the newest of goal yellow and red`() {
        val b = assertNotNull(strip(live(GameScreenFixtures.eplEvent)))
        val e = assertNotNull(b.lastEvent)
        assertEquals("yellow", e.kind); assertEquals("away", e.side); assertEquals("69'", e.minute)
        assertTrue(e.text.contains("Schade"), e.text)
    }

    private val commentary71 = """{"commentary":[{"time":{"value":4300.0,"displayValue":"71'"},"text":"Corner, Brentford. Conceded by Jan Paul van Hecke.",
        "play":{"id":"c1","type":{"text":"Corner Awarded"},"team":{"displayName":"Brentford"},"period":{"number":2},"clock":{"displayValue":"71'"}}}]}"""

    @Test fun `soccer strip - a fresher commentary item replaces the last event with the feed text`() {
        val e = assertNotNull(strip(live(GameScreenFixtures.eplEvent), commentary71)?.lastEvent)
        assertEquals(SoccerEvent("corner", "away", "71'", "Corner, Brentford. Conceded by Jan Paul van Hecke."), e)
    }

    @Test fun `soccer strip - an older commentary item never replaces a newer scoreboard event`() {
        val old = commentary71.replace("4300.0", "100.0").replace("71'", "2'")
        assertEquals("yellow", strip(live(GameScreenFixtures.eplEvent), old)?.lastEvent?.kind)
    }

    @Test fun `soccer strip - commentary kinds map and unlisted ones are ignored`() {
        fun kindOf(type: String) = strip(live(GameScreenFixtures.eplEvent), commentary71.replace("Corner Awarded", type))?.lastEvent?.takeIf { it.minute == "71'" }?.kind
        assertEquals("sub", kindOf("Substitution"))
        assertEquals("shot", kindOf("Shot On Target"))
        assertEquals("offside", kindOf("Offside"))
        assertEquals("var", kindOf("VAR - Referee decision cancelled"))
        assertNull(kindOf("Aerial"), "chatter is not an event")
    }

    private fun soccer(details: String, possession: Pair<String, String>? = "60.4" to "39.6", state: String = "in") = """{"id":"1","status":{"displayClock":"10'","period":1,"type":{"state":"$state"}},
        "competitions":[{"details":[$details],"competitors":[
        {"homeAway":"home","team":{"id":"331","displayName":"Brighton","abbreviation":"BHA"},"score":"1","statistics":[${possession?.let { """{"name":"possessionPct","displayValue":"${it.first}"},""" } ?: ""}{"name":"wonCorners","displayValue":"2"}]},
        {"homeAway":"away","team":{"id":"337","displayName":"Brentford","abbreviation":"BRE"},"score":"0","statistics":[{"name":"wonCorners","displayValue":"1"}]}]}]}"""

    private fun detail(type: String, min: Int, team: String, who: String, flags: String = "") =
        """{"type":{"text":"$type"},"clock":{"value":${min * 60}.0,"displayValue":"$min'"},"team":{"id":"$team"},"scoringPlay":${"scoringPlay" in flags || type.contains("Goal") || type.contains("Scored")},
        "yellowCard":${"yellow" in flags},"redCard":${"red" in flags},"penaltyKick":${"pen" in flags},"ownGoal":${"og" in flags},
        "athletesInvolved":[{"displayName":"$who","shortName":"$who"}]}"""

    @Test fun `soccer strip - an own goal is listed under the team that got the goal`() {
        val b = assertNotNull(strip(soccer(detail("Own Goal", 10, "331", "K. Ajer", "og"))))
        assertEquals(listOf("Ajer 10' (og)"), b.homeScorers)
        assertEquals(emptyList(), b.awayScorers)
    }

    @Test fun `soccer strip - a penalty is marked and scorers come out in time order`() {
        val b = assertNotNull(strip(soccer(listOf(detail("Penalty - Scored", 41, "337", "I. Toney", "pen"), detail("Goal", 23, "337", "B. Mbeumo")).joinToString(","))))
        assertEquals(listOf("Mbeumo 23'", "Toney 41' (pen)"), b.awayScorers, "23' before 41' although the feed listed them the other way")
    }

    @Test fun `soccer strip - initials are dropped and a double barrelled name keeps its tail`() {
        val b = assertNotNull(strip(soccer(detail("Goal", 5, "331", "K. De Bruyne"))))
        assertEquals(listOf("De Bruyne 5'"), b.homeScorers)
    }

    @Test fun `soccer strip - a block with nothing to show is dropped and a partial one keeps what exists`() {
        assertNull(strip("""{"id":"1","status":{"type":{"state":"in"}},"competitions":[{"competitors":[{"homeAway":"home","team":{"id":"1"}},{"homeAway":"away","team":{"id":"2"}}]}]}"""))
        val onlyPossession = assertNotNull(strip(soccer("", state = "in")))
        assertEquals(60, onlyPossession.homePossession)
        assertNull(onlyPossession.lastEvent)
        val noPossession = assertNotNull(strip(soccer(detail("Goal", 5, "331", "X. Y"), possession = null)))
        assertNull(noPossession.homePossession)
    }

    @Test fun `soccer strip - only the soccer profile has it`() {
        assertNull(asm.assemble(BlockType.SOCCER_STRIP, "basketball", parse(GameScreenFixtures.eplEvent), null))
    }

    @Test fun `soccer strip is visible live and final but not before kickoff`() = runBlocking<Unit> {
        class Espn(val event: String) : EspnSource {
            override suspend fun scoreboard(espnPath: String, yyyymmdd: String) = SourceResult.Ok(parse("""{"events":[$event]}"""))
            override suspend fun summary(espnPath: String, eventId: String): SourceResult<JsonElement> = SourceResult.Empty
        }
        val ref = GameRef("espn:401879244", "4328", null, "401879244", "Brighton & Hove Albion", "Brentford", null, 1_794_000_000_000L)
        suspend fun load(event: String) = assertIs<DetailLoad.Loaded>(GameDetailSession(ref, { bundledMapping() }, null, Espn(event), nowMs = { 1_794_000_500_000L }, report = {}).tick()).detail
        assertTrue(load(GameScreenFixtures.eplEvent).blocks.any { it is SoccerStripBlock }, "final")
        assertTrue(load(live(GameScreenFixtures.eplEvent)).blocks.any { it is SoccerStripBlock }, "live")
        assertTrue(load(GameScreenFixtures.eplEvent.replace("\"state\":\"post\"", "\"state\":\"pre\"")).blocks.none { it is SoccerStripBlock }, "upcoming")
    }

    // ---- LineupPlayer.place + LineupPitchPolicy ----------------------------------------------------------------------------------

    @Test fun `lineup players carry the formation place`() {
        val lineup = assertIs<LineupBlock>(asm.assemble(BlockType.LINEUP, "soccer", parse(GameScreenFixtures.eplEvent), parse(GameScreenFixtures.eplSummary)))
        val home = lineup.sides.first { it.side == "home" }
        val starters = home.players.filter { it.starter }
        assertEquals((1..11).toList(), starters.map { it.place }.sortedBy { it })
        assertEquals("1", starters.first { it.place == 1 }.number)
        assertEquals("4-2-3-1", home.formation)
        assertNull(home.players.first { !it.starter }.place, "a substitute has no place")
    }

    private fun xi(vararg places: Int) = places.map { LineupPlayer("$it", "P$it", "X", true, place = it) }

    @Test fun `pitch - a 4-3-3 is the keeper then rows of 4 3 and 3 spread evenly`() {
        val spots = LineupPitchPolicy.positions("4-3-3", xi(*(1..11).toList().toIntArray()))
        assertEquals(11, spots.size)
        val byPlace = spots.associateBy { it.player.place }
        assertEquals(0.5f, byPlace[1]!!.y, 0.0001f, "keeper in the middle")
        val rows = listOf(2..5, 6..8, 9..11).map { r -> r.map { byPlace[it]!! } }
        assertEquals(listOf(4, 3, 3), rows.map { it.size })
        rows.forEach { row -> assertEquals(1, row.map { it.x }.distinct().size, "a row shares one depth") }
        assertTrue(byPlace[1]!!.x < rows[0][0].x && rows[0][0].x < rows[1][0].x && rows[1][0].x < rows[2][0].x, "deeper rows are further up the pitch")
        assertEquals(listOf(0.2f, 0.4f, 0.6f, 0.8f), rows[0].map { it.y })
        assertEquals(listOf(0.25f, 0.5f, 0.75f), rows[1].map { it.y })
        assertTrue(spots.all { it.x in 0f..1f && it.y in 0f..1f })
    }

    @Test fun `pitch - both formations from the real feed lay out 11 dots each`() {
        val lineup = assertIs<LineupBlock>(asm.assemble(BlockType.LINEUP, "soccer", parse(GameScreenFixtures.eplEvent), parse(GameScreenFixtures.eplSummary)))
        for (side in lineup.sides) {
            val spots = LineupPitchPolicy.positions(side.formation, side.players)
            assertEquals(11, spots.size, side.side)
            assertEquals(side.formation!!.split("-").size + 1, spots.map { it.x }.distinct().size, "the keeper plus one depth per formation row (${side.formation})")
        }
    }

    @Test fun `pitch - an unknown formation or missing places fall back to no layout`() {
        val full = xi(*(1..11).toList().toIntArray())
        assertEquals(emptyList(), LineupPitchPolicy.positions(null, full))
        assertEquals(emptyList(), LineupPitchPolicy.positions("garbage", full))
        assertEquals(emptyList(), LineupPitchPolicy.positions("4-4-3", full), "rows must add up to the 10 outfield players")
        assertEquals(emptyList(), LineupPitchPolicy.positions("4-3-3", full.map { it.copy(place = null) }))
        assertEquals(emptyList(), LineupPitchPolicy.positions("4-3-3", full.take(10)))
        assertEquals(emptyList(), LineupPitchPolicy.positions("4-3-3", full.map { if (it.place == 11) it.copy(place = 10) else it }), "duplicate place")
        assertEquals(emptyList(), LineupPitchPolicy.positions("", full))
    }

    @Test fun `pitch - substitutes are ignored`() {
        val subs = (12..14).map { LineupPlayer("$it", "S$it", "X", false, place = null) }
        assertEquals(11, LineupPitchPolicy.positions("4-4-2", xi(*(1..11).toList().toIntArray()) + subs).size)
    }

    // ---- Moments (Phase B) --------------------------------------------------------------------------------------------------------

    private fun fieldDetail(f: FootballFieldBlock, home: String = "7", away: String = "3") =
        detailOf(f, sport = "American Football", home = Side("Home", short = "HOM", score = home), away = Side("Away", short = "AWY", score = away))

    @Test fun `moments - a gain with the same team on the ball is one FieldMove`() {
        val prev = fieldDetail(FootballFieldBlock(30, "home", down = 3, distance = 8))
        val next = fieldDetail(FootballFieldBlock(42, "home", down = 1, distance = 10))
        assertEquals(listOf<Moment>(Moment.FieldMove(30, 42, loss = false, firstDown = true)), GameMomentPolicy.diff(prev, next))
    }

    @Test fun `moments - a loss is the ball moving away from the attacked goal for either side`() {
        val h = GameMomentPolicy.diff(fieldDetail(FootballFieldBlock(40, "home", down = 1)), fieldDetail(FootballFieldBlock(37, "home", down = 2)))
        assertEquals(listOf<Moment>(Moment.FieldMove(40, 37, loss = true, firstDown = false)), h)
        val aGain = GameMomentPolicy.diff(fieldDetail(FootballFieldBlock(58, "away", down = 1)), fieldDetail(FootballFieldBlock(53, "away", down = 2)))
        assertEquals(listOf<Moment>(Moment.FieldMove(58, 53, loss = false, firstDown = false)), aGain)
        val aLoss = GameMomentPolicy.diff(fieldDetail(FootballFieldBlock(58, "away", down = 1)), fieldDetail(FootballFieldBlock(63, "away", down = 2)))
        assertEquals(listOf<Moment>(Moment.FieldMove(58, 63, loss = true, firstDown = false)), aLoss)
    }

    @Test fun `moments - a jump over several plays is one FieldMove and a still ball is none`() {
        val jump = GameMomentPolicy.diff(fieldDetail(FootballFieldBlock(20, "home", down = 1)), fieldDetail(FootballFieldBlock(71, "home", down = 2)))
        assertEquals(1, jump.size)
        assertEquals(emptyList(), GameMomentPolicy.diff(fieldDetail(FootballFieldBlock(20, "home", down = 1)), fieldDetail(FootballFieldBlock(20, "home", down = 2))))
    }

    @Test fun `moments - a change of possession flips the arrow without a slide`() {
        val d = GameMomentPolicy.diff(fieldDetail(FootballFieldBlock(30, "home", down = 4)), fieldDetail(FootballFieldBlock(70, "away", down = 1)))
        assertEquals(listOf<Moment>(Moment.PossessionChange("away")), d)
    }

    @Test fun `moments - a touchdown is a score of 6 or more for the side that had the ball`() {
        val prev = fieldDetail(FootballFieldBlock(97, "home", down = 1), home = "7", away = "3")
        val next = fieldDetail(FootballFieldBlock(35, "away", down = 1), home = "14", away = "3")
        val d = GameMomentPolicy.diff(prev, next)
        assertTrue(Moment.Touchdown("home") in d, d.toString())
        assertTrue(Moment.ScoreChange("home", "14") in d)
        assertTrue(Moment.PossessionChange("away") in d)
        val fg = GameMomentPolicy.diff(prev, fieldDetail(FootballFieldBlock(35, "away", down = 1), home = "10", away = "3"))
        assertTrue(fg.none { it is Moment.Touchdown }, "a field goal is 3")
        val other = GameMomentPolicy.diff(prev, fieldDetail(FootballFieldBlock(97, "home", down = 1), home = "7", away = "10"))
        assertTrue(other.none { it is Moment.Touchdown }, "the other team scoring 3 is not a touchdown by the ball carrier")
    }

    @Test fun `moments - no field in the previous snapshot or an unknown possession animates nothing`() {
        val noField = detailOf(sport = "American Football", home = Side("H", score = "7"), away = Side("A", score = "3"))
        assertEquals(emptyList(), GameMomentPolicy.diff(noField, fieldDetail(FootballFieldBlock(50, "home"))))
        assertEquals(emptyList(), GameMomentPolicy.diff(fieldDetail(FootballFieldBlock(50, null)), fieldDetail(FootballFieldBlock(60, null))))
    }

    @Test fun `moments - a new soccer event pops once and a repeated one does not`() {
        val goal = SoccerEvent("goal", "home", "23'", "Saka")
        fun d(e: SoccerEvent?) = detailOf(SoccerStripBlock(lastEvent = e), sport = "Soccer")
        assertEquals(listOf<Moment>(Moment.SoccerMoment(goal)), GameMomentPolicy.diff(d(null), d(goal)))
        assertEquals(listOf<Moment>(Moment.SoccerMoment(goal)), GameMomentPolicy.diff(d(SoccerEvent("yellow", "away", "20'", "X")), d(goal)))
        assertEquals(emptyList(), GameMomentPolicy.diff(d(goal), d(goal.copy())))
        assertEquals(emptyList(), GameMomentPolicy.diff(d(goal), d(null)), "the event leaving (final) animates nothing")
    }
}
