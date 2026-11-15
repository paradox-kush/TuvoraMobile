package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.BasketballStripBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.HockeyStripBlock
import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.ScoringRun
import com.nuvio.app.features.sportsdetail.internal.BasketballRunPolicy
import com.nuvio.app.features.sportsdetail.internal.BasketballStripPolicy
import com.nuvio.app.features.sportsdetail.internal.BlockType
import com.nuvio.app.features.sportsdetail.internal.EspnAssembler
import com.nuvio.app.features.sportsdetail.internal.GameMomentPolicy
import com.nuvio.app.features.sportsdetail.internal.HockeyPlay
import com.nuvio.app.features.sportsdetail.internal.HockeyStrength
import com.nuvio.app.features.sportsdetail.internal.Moment
import com.nuvio.app.features.sportsdetail.internal.PowerPlayClock
import com.nuvio.app.features.sportsdetail.internal.RunPlay
import com.nuvio.app.features.sportsdetail.internal.SportsMappingParser
import com.nuvio.app.features.sportsdetail.internal.SportsPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase C: basketball strip (direct) and hockey strip (power-play side direct, clock derived behind a switch). */
class GameScreenStripsCTest {
    private val asm = EspnAssembler(bundledMapping())

    // ---- switches --------------------------------------------------------------------------------------------------------------

    @Test fun `switches - both derived switches ship off in the bundled mapping`() {
        val m = bundledMapping()
        assertFalse(m.switchOn("hockeyPowerPlayClock"))
        assertFalse(m.switchOn("basketballRun"))
        assertTrue(bundledJson.contains("\"uiSwitches\":{\"hockeyPowerPlayClock\":false,\"basketballRun\":false"))
    }

    private val bundledJson get() = squeezeJson(com.nuvio.app.features.sportsdetail.internal.BundledSportsMapping.JSON)
    private fun withSwitches(body: String) = mappingOf(bundledJson.replace(Regex("\"uiSwitches\":\\{[^}]*\\}"), "\"uiSwitches\":{$body}"))

    @Test fun `switches - only a literal boolean true turns one on`() {
        val m = withSwitches("\"hockeyPowerPlayClock\":true,\"basketballRun\":\"true\",\"chartMarkers\":1")
        assertTrue(m.switchOn("hockeyPowerPlayClock"))
        assertFalse(m.switchOn("basketballRun"), "a string is not a boolean")
        assertFalse(m.switchOn("chartMarkers"), "a number is not a boolean")
        assertFalse(m.switchOn("neverHeardOf"))
    }

    @Test fun `switches - a mapping with no uiSwitches key has every switch off`() {
        val without = bundledJson.replace(Regex(",?\"uiSwitches\":\\{[^}]*\\}"), "")
        assertFalse(without.contains("uiSwitches"))
        assertEquals(emptySet(), mappingOf(without).uiSwitches)
        assertTrue(SportsMappingParser.parse(without) is SportsMappingParser.ParseResult.Ok)
    }

    @Test fun `mapping - basketballStrip and hockeyStrip are appended and read the summary`() {
        val m = bundledMapping()
        assertEquals("summary", m.profiles["basketball"]!!.spec(BlockType.BASKETBALL_STRIP)?.from)
        assertEquals("header.competitions[0].competitors[*]", m.profiles["basketball"]!!.spec(BlockType.BASKETBALL_STRIP)?.path)
        assertEquals("plays[*]", m.profiles["basketball"]!!.spec(BlockType.BASKETBALL_STRIP)?.str("plays"))
        assertEquals("summary", m.profiles["hockey"]!!.spec(BlockType.HOCKEY_STRIP)?.from)
        assertEquals("boxscore.teams[*].statistics[*]", m.profiles["hockey"]!!.spec(BlockType.HOCKEY_STRIP)?.str("stats"))
        assertTrue(m.profiles["basketball"]!!.blocks.last() == "basketballStrip" && m.profiles["hockey"]!!.blocks.last() == "hockeyStrip")
        assertEquals("strength.text", m.profiles["hockey"]!!.spec(BlockType.PLAYS)?.str("strength"))
    }

    // ---- BasketballStripPolicy.inBonus ---------------------------------------------------------------------------------------------

    @Test fun `inBonus - the competitor whose own bonusState is not NONE is the one in the bonus`() {
        val b = BasketballStripBlock(homeBonus = "DOUBLE", awayBonus = "NONE")
        assertTrue(BasketballStripPolicy.inBonus("home", b))
        assertFalse(BasketballStripPolicy.inBonus("away", b))
        assertTrue(BasketballStripPolicy.inBonus("away", BasketballStripBlock(awayBonus = "single")), "case-insensitive")
        assertFalse(BasketballStripPolicy.inBonus("home", BasketballStripBlock()), "unknown is not in the bonus")
        assertFalse(BasketballStripPolicy.inBonus("home", BasketballStripBlock(homeBonus = "")))
    }

    // ---- BasketballRunPolicy ---------------------------------------------------------------------------------------------------------

    private fun p(side: String?, pts: Int) = RunPlay(side, pts, scoring = true)
    private val miss = RunPlay("home", 0, scoring = false)

    @Test fun `run - 3 plus 3 plus 2 unanswered is an 8 point run for that side`() {
        assertEquals(ScoringRun("home", 8), BasketballRunPolicy.run(listOf(p("away", 2), p("home", 3), p("home", 3), p("home", 2))))
    }

    @Test fun `run - alternating scoring is no run and 7 is not enough`() {
        assertNull(BasketballRunPolicy.run(listOf(p("home", 2), p("away", 2), p("home", 3), p("away", 3))))
        assertNull(BasketballRunPolicy.run(listOf(p("home", 3), p("home", 2), p("home", 2))))
    }

    @Test fun `run - the opponent scoring resets it`() {
        assertNull(BasketballRunPolicy.run(listOf(p("home", 3), p("home", 3), p("home", 2), p("away", 1))))
        assertNull(BasketballRunPolicy.run(listOf(p("home", 3), p("home", 3), p("home", 2), p("away", 2), p("home", 2))))
    }

    @Test fun `run - non scoring plays are ignored and the run survives them and the period break`() {
        assertEquals(ScoringRun("away", 9), BasketballRunPolicy.run(listOf(p("home", 2), p("away", 3), miss, p("away", 3), miss, miss, p("away", 3), miss)))
    }

    @Test fun `run - a scoring play with no known team is ambiguous so there is no run`() {
        assertNull(BasketballRunPolicy.run(listOf(p("home", 3), p("home", 3), p(null, 2), p("home", 2))))
        assertNull(BasketballRunPolicy.run(emptyList()))
    }

    @Test fun `run - a long run reports its full length`() {
        assertEquals(ScoringRun("home", 14), BasketballRunPolicy.run(listOf(p("away", 2)) + List(7) { p("home", 2) }))
    }

    // ---- Basketball strip assembler --------------------------------------------------------------------------------------------------

    private fun strip(summary: String, m: com.nuvio.app.features.sportsdetail.internal.SportsMapping = bundledMapping()) =
        EspnAssembler(m).assemble(BlockType.BASKETBALL_STRIP, "basketball", parse(GameScreenFixtures.nbaEvent), parse(summary)) as? BasketballStripBlock

    @Test fun `basketball strip - fouls bonus and timeouts come straight from the header competitors`() {
        val b = assertNotNull(strip(GameScreenFixtures.nbaSummary))
        assertEquals(
            BasketballStripBlock(homeFouls = 2, awayFouls = 4, homeBonus = "DOUBLE", awayBonus = "NONE", homeTimeouts = 3, awayTimeouts = 3, possession = null, run = null, source = "espn"),
            b,
        )
    }

    private fun summaryWith(hs: String, plays: String = "") = """{"header":{"competitions":[{"competitors":[$hs]}]},"plays":[$plays]}"""
    private val homeC = """{"homeAway":"home","team":{"id":"14"},"fouls":{"teamFoulsCurrent":5,"bonusState":"NONE"},"timeoutsRemaining":2,"possession":true}"""
    private val awayC = """{"homeAway":"away","team":{"id":"8"},"fouls":{"teamFoulsCurrent":1,"bonusState":"DOUBLE"},"timeoutsRemaining":4,"possession":false}"""

    @Test fun `basketball strip - possession is the competitor flagged true`() {
        assertEquals("home", strip(summaryWith("$homeC,$awayC"))?.possession)
        assertEquals("away", strip(summaryWith("${homeC.replace("true", "false")},${awayC.replace("false", "true")}"))?.possession)
        assertNull(strip(summaryWith("${homeC.replace("true", "false")},$awayC"))?.possession)
    }

    @Test fun `basketball strip - a missing field hides only that element and nothing at all hides the block`() {
        val noFouls = assertNotNull(strip(summaryWith("""{"homeAway":"home","team":{"id":"14"},"timeoutsRemaining":2},$awayC""")))
        assertNull(noFouls.homeFouls); assertNull(noFouls.homeBonus); assertEquals(2, noFouls.homeTimeouts); assertEquals(1, noFouls.awayFouls)
        assertNull(strip(summaryWith("""{"homeAway":"home","team":{"id":"14"}},{"homeAway":"away","team":{"id":"8"}}""")))
        assertNull(strip("{}"))
    }

    private fun scoring(id: Int, team: String, pts: Int) = """{"id":"$id","scoringPlay":true,"scoreValue":$pts,"team":{"id":"$team"},"text":"x","type":{"text":"Layup"},"period":{"number":2,"displayValue":"2nd Quarter"},"clock":{"displayValue":"5:00"}}"""
    private val eightZero = listOf(scoring(1, "8", 2), scoring(2, "14", 3), scoring(3, "14", 3), scoring(4, "14", 2)).joinToString(",")

    @Test fun `basketball strip - the run is derived only while its switch is on`() {
        val s = summaryWith("$homeC,$awayC", eightZero)
        assertNull(strip(s)?.run, "off by default: null even though the plays make an 8-0 run")
        val on = withSwitches("\"basketballRun\":true,\"hockeyPowerPlayClock\":false")
        assertEquals(ScoringRun("home", 8), strip(s, on)?.run)
        assertNull(strip(summaryWith("$homeC,$awayC", listOf(scoring(1, "8", 2), scoring(2, "14", 3)).joinToString(",")), on)?.run, "under 8 points is no run")
    }

    // ---- HockeyStrength --------------------------------------------------------------------------------------------------------------

    private fun hp(side: String?, strength: String?, pen: String? = null, min: Int? = null, goal: Boolean = false, period: Int = 1, el: Int? = 0) =
        HockeyPlay(period, el, side, penaltyMinutes = min, penaltyType = pen, goal = goal, strength = strength)

    @Test fun `strength - power play is the play's own team and shorthanded is the other one`() {
        assertEquals("home", HockeyStrength.powerPlaySide(listOf(hp("away", "Even Strength"), hp("home", "Power Play"))))
        assertEquals("home", HockeyStrength.powerPlaySide(listOf(hp("away", "Shorthanded"))), "away is short so home has the power play")
        assertEquals("away", HockeyStrength.powerPlaySide(listOf(hp("home", "Shorthanded"))))
    }

    @Test fun `strength - even strength and no data are no power play`() {
        assertNull(HockeyStrength.powerPlaySide(listOf(hp("home", "Power Play"), hp("home", "Even Strength"))))
        assertNull(HockeyStrength.powerPlaySide(listOf(hp("home", "Power Play"), hp(null, "Even Strength"))), "a team-less even strength play is still even")
        assertNull(HockeyStrength.powerPlaySide(emptyList()))
        assertNull(HockeyStrength.powerPlaySide(listOf(hp("home", null))))
    }

    @Test fun `strength - penalty plays and team-less power play plays are skipped because their tags are not the game state`() {
        // Live captures: a fighting major is tagged "Power Play" for one fighter although the game is at even strength.
        assertNull(HockeyStrength.powerPlaySide(listOf(hp("home", "Even Strength"), hp("away", "Power Play", pen = "Major", min = 5))))
        assertEquals("away", HockeyStrength.powerPlaySide(listOf(hp("away", "Power Play"), hp(null, "Shorthanded"))), "a stoppage with no team tells us nothing about who")
    }

    // ---- PowerPlayClock ----------------------------------------------------------------------------------------------------------------

    private fun pen(side: String, period: Int, el: Int, min: Int = 2, type: String = "Minor") = hp(side, "Even Strength", pen = type, min = min, period = period, el = el)
    private fun now(period: Int, elapsed: Int) = PowerPlayClock.REGULATION_PERIOD_SEC - elapsed   // the status clock counts DOWN

    @Test fun `clock - the play clock counts up and the status clock counts down`() {
        assertEquals(473, PowerPlayClock.elapsedInPeriod(3, 727), "727 s left of 1200 is 7:53 played")
        assertEquals(0, PowerPlayClock.elapsedInPeriod(1, 1200))
        assertNull(PowerPlayClock.elapsedInPeriod(4, 200), "overtime length differs by league and season")
        assertNull(PowerPlayClock.elapsedInPeriod(0, 200))
    }

    @Test fun `clock - the live capture agrees with that reading`() {
        val status = SportsPath.text(parse(GameScreenFixtures.nhlLiveEvent), "status.clock")!!.toInt()
        val period = SportsPath.text(parse(GameScreenFixtures.nhlLiveEvent), "status.period")!!.toInt()
        val lastPlay = SportsPath.read(parse(GameScreenFixtures.nhlLiveSummary), "plays[-1]").single()
        val (m, s) = SportsPath.text(lastPlay, "clock.displayValue")!!.split(":").map { it.toInt() }
        val played = PowerPlayClock.elapsedInPeriod(period, status)!!
        assertTrue(played - (m * 60 + s) in 0..180, "the status clock is at most a few minutes ahead of the newest play: $played vs ${m * 60 + s}")
    }

    @Test fun `clock - one minor against the away team leaves the rest of its two minutes`() {
        val plays = listOf(pen("away", 1, 300))
        assertEquals(20, PowerPlayClock.secondsLeft(plays, 1, now(1, 400), "home"))
        assertEquals(120, PowerPlayClock.secondsLeft(plays, 1, now(1, 300), "home"))
    }

    @Test fun `clock - an expired penalty or no penalty gives no number`() {
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300)), 1, now(1, 500), "home"))
        assertNull(PowerPlayClock.secondsLeft(emptyList(), 1, now(1, 500), "home"))
    }

    @Test fun `clock - a double minor and a major are counted whole`() {
        assertEquals(190, PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300, 4, "Double Minor")), 1, now(1, 350), "home"))
        assertEquals(250, PowerPlayClock.secondsLeft(listOf(pen("home", 1, 300, 5, "Major")), 1, now(1, 350), "away"))
        assertEquals(100, PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300, 2, "Bench")), 1, now(1, 320), "home"), "a bench minor is a real power play")
    }

    @Test fun `clock - a power play goal ends a minor but not a major`() {
        val ppg = hp("home", "Power Play", goal = true, el = 330)
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300), ppg), 1, now(1, 340), "home"), "the minor ended with the goal")
        assertEquals(260, PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300, 5, "Major"), ppg), 1, now(1, 340), "home"), "a major keeps running")
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300, 4, "Double Minor"), ppg), 1, now(1, 340), "home"), "a double minor after a goal is ambiguous")
    }

    @Test fun `clock - a goal by the penalised team does not end the power play`() {
        val shg = hp("away", "Shorthanded", goal = true, el = 330)
        assertEquals(80, PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300), shg), 1, now(1, 340), "home"))
    }

    @Test fun `clock - coincidental penalties and overlapping ones are ambiguous`() {
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("home", 1, 300, 5, "Major"), pen("away", 1, 300, 5, "Major")), 1, now(1, 310), null), "fighting majors")
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("home", 1, 300), pen("away", 1, 300)), 1, now(1, 310), null), "coincidental minors")
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300), pen("away", 1, 330)), 1, now(1, 340), "home"), "5-on-3")
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300), pen("home", 1, 330)), 1, now(1, 340), null), "4-on-4 at different times")
    }

    @Test fun `clock - a penalty carries across the period break`() {
        assertEquals(40, PowerPlayClock.secondsLeft(listOf(pen("away", 1, 1150)), 2, now(2, 30), "home"))
    }

    @Test fun `clock - overtime and an unreadable clock give no number`() {
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 3, 1190)), 4, 250, "home"))
        assertNull(PowerPlayClock.secondsLeft(listOf(HockeyPlay(1, null, "away", 2, "Minor")), 1, now(1, 400), "home"))
    }

    @Test fun `clock - an old odd penalty cannot null the clock once it is surely over`() {
        val odd = listOf(HockeyPlay(1, null, "away", 2, "Minor"), HockeyPlay(1, 100, null, 2, "Minor"), HockeyPlay(1, 200, "home", null, "Minor"))
        assertEquals(80, PowerPlayClock.secondsLeft(odd + pen("away", 3, 1150), 3, now(3, 1190), "home"), "period 1 oddities are over by period 3")
        // The same oddity in the CURRENT period could still be running: that stays ambiguous.
        assertNull(PowerPlayClock.secondsLeft(listOf(HockeyPlay(3, null, "away", 2, "Minor")) + pen("away", 3, 1150), 3, now(3, 1190), "home"))
        assertNull(PowerPlayClock.secondsLeft(listOf(HockeyPlay(3, 1100, null, 2, "Minor")) + pen("away", 3, 1150), 3, now(3, 1190), "home"), "a team-less penalty that could still be active")
    }

    @Test fun `clock - misconducts do not create a power play and a side that contradicts the feed gives no number`() {
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300, 10, "Misconduct")), 1, now(1, 320), "home"))
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300)), 1, now(1, 320), "away"), "the feed says away is on the power play but away is the penalised team")
        assertNull(PowerPlayClock.secondsLeft(listOf(pen("away", 1, 300)), 1, now(1, 320), null), "the feed's own strength says even: trust it over the arithmetic")
    }

    // ---- Hockey strip assembler --------------------------------------------------------------------------------------------------------

    private fun hockey(summary: String = GameScreenFixtures.nhlLiveSummary, event: String = GameScreenFixtures.nhlLiveEvent, m: com.nuvio.app.features.sportsdetail.internal.SportsMapping = bundledMapping()) =
        EspnAssembler(m).assemble(BlockType.HOCKEY_STRIP, "hockey", parse(event), parse(summary)) as? HockeyStripBlock

    private fun boxStat(teamId: String, label: String): String =
        SportsPath.read(parse(GameScreenFixtures.nhlLiveSummary), "boxscore.teams[*]").first { SportsPath.text(it, "team.id") == teamId }
            .let { t -> SportsPath.read(t, "statistics[label=$label].displayValue").first().let { SportsPath.display(it)!! } }

    @Test fun `hockey strip - shots power plays and faceoffs are the boxscore numbers by team`() {
        val b = assertNotNull(hockey())
        val home = "129764"; val away = "18"   // UTA at home, SJ away
        assertEquals(boxStat(home, "Shots").toInt(), b.homeShots); assertEquals(boxStat(away, "Shots").toInt(), b.awayShots)
        assertEquals("${boxStat(home, "Power Play Goals")}/${boxStat(home, "Power Play Opportunities")}", b.homePowerPlay)
        assertEquals("${boxStat(away, "Power Play Goals")}/${boxStat(away, "Power Play Opportunities")}", b.awayPowerPlay)
        assertEquals(boxStat(home, "Faceoffs Won").toInt(), b.homeFaceoffs); assertEquals(boxStat(away, "Faceoffs Won").toInt(), b.awayFaceoffs)
        assertEquals("espn", b.source)
    }

    @Test fun `hockey strip - the newest play at even strength is no power play and the clock is null`() {
        val b = assertNotNull(hockey())
        assertNull(b.powerPlaySide)
        assertNull(b.powerPlaySecondsLeft)
    }

    private fun ev(period: Int, remaining: Int) = """{"id":"1","status":{"clock":$remaining.0,"displayClock":"x","period":$period,"type":{"state":"in"}},"competitions":[{"competitors":[
        {"homeAway":"home","team":{"id":"129764"},"score":"1"},{"homeAway":"away","team":{"id":"18"},"score":"1"}]}]}"""
    private fun play(id: Int, team: String?, strength: String, el: String, extra: String = "", period: Int = 2) =
        """{"id":"$id","text":"t$id","period":{"number":$period,"displayValue":"2nd"},"clock":{"displayValue":"$el"},${team?.let { """"team":{"id":"$it"},""" } ?: ""}"strength":{"text":"$strength"},"scoringPlay":false,"type":{"text":"Shot"$extra}}"""
    private val boxMin = """"boxscore":{"teams":[{"team":{"id":"129764"},"statistics":[{"label":"Shots","displayValue":"20"}]},{"team":{"id":"18"},"statistics":[{"label":"Shots","displayValue":"14"}]}]}"""
    private fun hsum(vararg plays: String) = """{$boxMin,"plays":[${plays.joinToString(",")}]}"""

    @Test fun `hockey strip - a power play from the newest play and the clock only with its switch on`() {
        val s = hsum(
            play(1, "18", "Even Strength", "9:00", ",\"penaltyMinutes\":\"2\",\"penaltyType\":\"Minor\"", period = 2),   // SJ minor at 9:00
            play(2, "129764", "Power Play", "9:30"),
        )
        val event = ev(period = 2, remaining = 600)    // 10:00 played
        val off = assertNotNull(hockey(s, event))
        assertEquals("home", off.powerPlaySide, "UTA (home) has the power play: direct from the play")
        assertNull(off.powerPlaySecondsLeft, "derived value: null while the switch is off")
        val on = withSwitches("\"hockeyPowerPlayClock\":true,\"basketballRun\":false")
        assertEquals(60, hockey(s, event, on)?.powerPlaySecondsLeft, "9:00 + 2:00 = 11:00, now 10:00")
    }

    @Test fun `hockey strip - ambiguous penalties give a side but no time even with the switch on`() {
        val pen = ",\"penaltyMinutes\":\"2\",\"penaltyType\":\"Minor\""
        val s = hsum(play(1, "18", "Even Strength", "8:50", pen), play(2, "18", "Even Strength", "9:10", pen), play(3, "129764", "Power Play", "9:30"))
        val on = withSwitches("\"hockeyPowerPlayClock\":true,\"basketballRun\":false")
        val b = assertNotNull(hockey(s, ev(2, 600), on))
        assertEquals("home", b.powerPlaySide)
        assertNull(b.powerPlaySecondsLeft)
    }

    @Test fun `hockey strip - nothing in the summary means no block`() {
        assertNull(hockey("{}"))
        assertNull(EspnAssembler(bundledMapping()).assemble(BlockType.HOCKEY_STRIP, "basketball", parse(GameScreenFixtures.nhlLiveEvent), parse(GameScreenFixtures.nhlLiveSummary)))
    }

    @Test fun `hockey plays - a goal scored on the power play is tagged ppg and an even strength goal stays a goal`() {
        val wide = mappingOf(editedMappingJson("\"extra\": {\"last\": 60}", "\"extra\": {\"last\": 500}"))   // the fixture has more than 60 plays
        val plays = (EspnAssembler(wide).assemble(BlockType.PLAYS, "hockey", parse(GameScreenFixtures.nhlLiveEvent), parse(GameScreenFixtures.nhlLiveSummary)) as PlaysBlock).items
        assertEquals("ppg", plays.first { it.text.contains("Mikhail Sergachev Goal (6)") }.kind, "the feed tagged that goal Power Play")
        assertEquals("goal", plays.first { it.text.contains("Zac Jones Goal (1)") }.kind, "the feed tagged that one Even Strength")
    }

    // ---- Moments (Phase C) ----------------------------------------------------------------------------------------------------------------

    private fun hd(h: HockeyStripBlock) = detailOf(h, sport = "Ice Hockey")
    private fun bd(b: BasketballStripBlock) = detailOf(b)

    @Test fun `moments - a power play starting and ending`() {
        assertEquals(listOf<Moment>(Moment.PowerPlay("home", true)), GameMomentPolicy.diff(hd(HockeyStripBlock()), hd(HockeyStripBlock(powerPlaySide = "home"))))
        assertEquals(listOf<Moment>(Moment.PowerPlay(null, false)), GameMomentPolicy.diff(hd(HockeyStripBlock(powerPlaySide = "home")), hd(HockeyStripBlock())))
        assertEquals(listOf<Moment>(Moment.PowerPlay("away", true)), GameMomentPolicy.diff(hd(HockeyStripBlock(powerPlaySide = "home")), hd(HockeyStripBlock(powerPlaySide = "away"))))
        assertEquals(emptyList(), GameMomentPolicy.diff(hd(HockeyStripBlock(powerPlaySide = "home", powerPlaySecondsLeft = 60)), hd(HockeyStripBlock(powerPlaySide = "home", powerPlaySecondsLeft = 30))), "the bar steps; it is not a moment")
    }

    @Test fun `moments - fouls timeouts and the bonus`() {
        val a = BasketballStripBlock(homeFouls = 3, awayFouls = 4, homeBonus = "NONE", awayBonus = "NONE", homeTimeouts = 3, awayTimeouts = 3)
        val b = a.copy(homeFouls = 4, awayBonus = "DOUBLE", awayTimeouts = 2)
        val d = GameMomentPolicy.diff(bd(a), bd(b))
        assertTrue(Moment.FoulCount("home", 4) in d, d.toString())
        assertTrue(Moment.BonusReached("away") in d)
        assertTrue(Moment.TimeoutUsed("away") in d)
        assertEquals(3, d.size)
    }

    @Test fun `moments - a new period resetting the fouls and a bonus that was already on are not moments`() {
        val a = BasketballStripBlock(homeFouls = 5, awayFouls = 5, homeBonus = "DOUBLE", homeTimeouts = 2)
        assertEquals(emptyList(), GameMomentPolicy.diff(bd(a), bd(a.copy(homeFouls = 0, awayFouls = 0))))
        assertEquals(emptyList(), GameMomentPolicy.diff(bd(a), bd(a.copy(homeFouls = 5))))
        assertEquals(emptyList(), GameMomentPolicy.diff(bd(BasketballStripBlock()), bd(BasketballStripBlock(homeFouls = 1))).filterIsInstance<Moment.BonusReached>())
    }
}
