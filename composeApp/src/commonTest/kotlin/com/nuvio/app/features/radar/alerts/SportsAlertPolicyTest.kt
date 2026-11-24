package com.nuvio.app.features.radar.alerts

import com.nuvio.app.core.contracts.PlaybackContext
import com.nuvio.app.features.radar.RevealedGames
import com.nuvio.app.features.radar.ScoreSpoilerSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The show / hold / drop decision every app runs (research/sports-alerts-spec-2026-11-24.md section 3).
 * kotlin.test → assertEquals(expected, actual, message). Native: no commas in test names; JVM: no colons or dots.
 */
class SportsAlertPolicyTest {

    private val t0 = 1_800_000_000_000L
    private val arsenal = "133604"
    private val chelsea = "133610"
    private val epl = "4328"
    private val afternoon = 15 * 60

    private fun alert(
        kind: AlertKind = AlertKind.Score,
        eventId: String = "e1",
        key: String = "$eventId:${kind.wire}",
        at: Long = t0,
        h: Int? = 1,
        a: Int? = 0,
        home: String = "Arsenal",
        away: String = "Chelsea",
        homeId: String? = arsenal,
        awayId: String? = chelsea,
        leagueId: String? = epl,
        sport: String = "soccer",
        corrected: Boolean = false,
    ) = SportsAlert(
        key = key, eventId = eventId, kind = kind, sport = sport, leagueId = leagueId, league = "Premier League",
        homeId = homeId, awayId = awayId, home = home, away = away, homeScore = h, awayScore = a,
        progress = "63'", corrected = corrected, atMs = at,
    )

    private fun ctx(
        now: Long = t0,
        minute: Int = afternoon,
        playback: PlaybackContext = PlaybackContext.None,
        openGame: String? = null,
        playing: Set<String> = emptySet(),
        watch: Map<String, WatchState> = emptyMap(),
        leagues: Set<String> = emptySet(),
        teams: Set<String> = setOf(arsenal),
        spoiler: ScoreSpoilerSettings = ScoreSpoilerSettings(),
        revealed: RevealedGames = RevealedGames(),
    ) = AlertContext(now, minute, playback, openGame, playing, watch, leagues, teams, spoiler, revealed)

    private val noBatch = SportsAlertSettings(batchSeconds = 0)

    private fun decide(
        a: SportsAlert,
        settings: SportsAlertSettings = SportsAlertSettings(),
        c: AlertContext = ctx(),
        ledger: AlertLedger = AlertLedger(),
    ) = SportsAlertPolicy.onAlert(a, settings, c, ledger)

    private fun dropReason(d: AlertDecision): DropReason? = (d as? AlertDecision.Drop)?.reason

    private fun title(d: AlertDecision): String? = (d as? AlertDecision.ShowNow)?.banner?.title

    // --- follows and levels ---------------------------------------------------------------------------------------

    @Test
    fun `defaults show a followed team's goal`() {
        val (d, _) = decide(alert())
        assertEquals("Goal · Arsenal 1–0 Chelsea", title(d), "a followed club's goal shows at once")
        val banner = (d as AlertDecision.ShowNow).banner
        assertEquals("Premier League · 63'", banner.subtitle, "league and progress underneath")
        assertEquals(BannerAction.OpenGame("e1"), banner.action, "Open game at once")
    }

    @Test
    fun `an unfollowed game is dropped`() {
        val (d, _) = decide(alert(), c = ctx(teams = emptySet()))
        assertEquals(DropReason.NotFollowed, dropReason(d), "neither the league nor a team is followed")
    }

    @Test
    fun `a league follower at the defaults gets full time only`() {
        val c = ctx(leagues = setOf(epl), teams = emptySet())
        assertEquals(DropReason.KindOff, dropReason(decide(alert(AlertKind.Score), c = c).first), "no goals")
        assertEquals(DropReason.KindOff, dropReason(decide(alert(AlertKind.Start), c = c).first), "no kick-off")
        assertEquals("Full time · Arsenal 1–0 Chelsea", title(decide(alert(AlertKind.Final), c = c).first), "full time shows")
    }

    @Test
    fun `the team level beats the league level`() {
        val both = ctx(leagues = setOf(epl), teams = setOf(arsenal))
        assertIs<AlertDecision.ShowNow>(decide(alert(), c = both).first, "team All beats league Final only")
        val narrowTeam = SportsAlertSettings(teamLevel = AlertLevel.FinalOnly, leagueLevel = AlertLevel.All)
        assertEquals(DropReason.KindOff, dropReason(decide(alert(), narrowTeam, both).first), "the team level applies even when the league's is wider")
    }

    @Test
    fun `a per-game off mutes and a per-game all widens`() {
        val off = SportsAlertSettings().withGame("e1", AlertLevel.Off, expMs = t0 + 1_000_000, nowMs = t0)
        assertEquals(DropReason.Muted, dropReason(decide(alert(), off).first), "this game off")
        val all = SportsAlertSettings().withGame("e1", AlertLevel.All, expMs = t0 + 1_000_000, nowMs = t0)
        val leagueOnly = ctx(leagues = setOf(epl), teams = emptySet())
        assertIs<AlertDecision.ShowNow>(decide(alert(), all, leagueOnly).first, "this game all beats league Final only")
        val expired = SportsAlertSettings().withGame("e1", AlertLevel.Off, expMs = t0 - 1, nowMs = t0 - 10)
        assertIs<AlertDecision.ShowNow>(decide(alert(), expired).first, "an expired override no longer applies")
    }

    @Test
    fun `a mute wins over a per-game all`() {
        val s = SportsAlertSettings(mutedTeams = setOf(arsenal)).withGame("e1", AlertLevel.All, expMs = t0 + 1_000_000, nowMs = t0)
        assertEquals(DropReason.Muted, dropReason(decide(alert(), s).first), "the club is muted: this game all does not undo it")
        val leagueMuted = SportsAlertSettings(mutedLeagues = setOf(epl)).withGame("e1", AlertLevel.All, expMs = t0 + 1_000_000, nowMs = t0)
        assertEquals(DropReason.Muted, dropReason(decide(alert(), leagueMuted, ctx(leagues = setOf(epl), teams = emptySet())).first), "a muted league too")
    }

    @Test
    fun `muting every followed route mutes the game`() {
        val muted = SportsAlertSettings(mutedTeams = setOf(arsenal))
        assertEquals(DropReason.Muted, dropReason(decide(alert(), muted).first), "the only followed team is muted")
        val leagueToo = ctx(leagues = setOf(epl), teams = setOf(arsenal))
        assertEquals(DropReason.KindOff, dropReason(decide(alert(), muted, leagueToo).first), "falls back to the league level")
    }

    @Test
    fun `alerts switched off drop everything`() {
        assertEquals(DropReason.Disabled, dropReason(decide(alert(), SportsAlertSettings(enabled = false)).first), "off is off")
    }

    // --- quiet hours ----------------------------------------------------------------------------------------------

    @Test
    fun `quiet hours 22 to 08 drop at 23h30 and 07h59 and show at 08h00 and 21h59`() {
        val s = SportsAlertSettings()
        assertEquals(DropReason.QuietHours, dropReason(decide(alert(), s, ctx(minute = 23 * 60 + 30)).first), "23:30 inside")
        assertEquals(DropReason.QuietHours, dropReason(decide(alert(), s, ctx(minute = 7 * 60 + 59)).first), "07:59 inside")
        assertIs<AlertDecision.ShowNow>(decide(alert(), s, ctx(minute = 8 * 60)).first, "08:00 is the end")
        assertIs<AlertDecision.ShowNow>(decide(alert(), s, ctx(minute = 21 * 60 + 59)).first, "21:59 is before the start")
    }

    @Test
    fun `a quiet window that does not cross midnight`() {
        assertTrue(SportsAlertPolicy.inQuietHours(14 * 60, 13 * 60, 15 * 60), "14:00 in 13:00-15:00")
        assertFalse(SportsAlertPolicy.inQuietHours(15 * 60, 13 * 60, 15 * 60), "15:00 is the end")
        assertFalse(SportsAlertPolicy.inQuietHours(12 * 60 + 59, 13 * 60, 15 * 60), "12:59 before")
    }

    @Test
    fun `a quiet window whose start equals its end is off`() {
        val s = SportsAlertSettings(quietStartMin = 600, quietEndMin = 600)
        assertIs<AlertDecision.ShowNow>(decide(alert(), s, ctx(minute = 600)).first, "start == end means no quiet hours")
        assertIs<AlertDecision.ShowNow>(decide(alert(), SportsAlertSettings(quietOn = false), ctx(minute = 23 * 60)).first, "switched off")
    }

    // --- cap ------------------------------------------------------------------------------------------------------

    @Test
    fun `the hourly cap drops the extra alert and frees after 60 minutes`() {
        val s = noBatch.copy(maxPerHour = 3)
        var ledger = AlertLedger()
        repeat(3) { i ->
            val (d, next) = decide(alert(key = "k$i", eventId = "e$i", at = t0 + i * 60_000L), s, ctx(now = t0 + i * 60_000L), ledger)
            assertIs<AlertDecision.ShowNow>(d, "banner ${i + 1} of 3")
            ledger = next
        }
        val (fourth, afterFourth) = decide(alert(key = "k3", eventId = "e3", at = t0 + 3 * 60_000L), s, ctx(now = t0 + 3 * 60_000L), ledger)
        assertEquals(DropReason.HourlyCap, dropReason(fourth), "the 4th in the hour is dropped")
        val later = t0 + 60 * 60_000L + 1
        val (fifth, _) = decide(alert(key = "k4", eventId = "e4", at = later), s, ctx(now = later), afterFourth)
        assertIs<AlertDecision.ShowNow>(fifth, "the first banner left the hour")
    }

    @Test
    fun `no limit means no cap`() {
        val s = noBatch.copy(maxPerHour = 0)
        var ledger = AlertLedger()
        repeat(20) { i ->
            val (d, next) = decide(alert(key = "k$i", eventId = "e$i"), s, ctx(), ledger)
            assertIs<AlertDecision.ShowNow>(d, "banner $i")
            ledger = next
        }
    }

    // --- batching -------------------------------------------------------------------------------------------------

    /** One banner at t0 (leading edge), so the window is open for the next minute. */
    private fun afterFirstBanner(): AlertLedger {
        val (d, ledger) = decide(alert(key = "first", eventId = "e0"))
        assertIs<AlertDecision.ShowNow>(d, "leading edge shows")
        return ledger
    }

    @Test
    fun `a second alert inside the batch window is held then flushed`() {
        val l1 = afterFirstBanner()
        val (d, l2) = decide(alert(eventId = "e2", at = t0 + 10_000), c = ctx(now = t0 + 10_000), ledger = l1)
        assertEquals(AlertDecision.HoldUntil(t0 + 60_000, HoldReason.Batch), d, "held to the end of the window")
        assertEquals(t0 + 60_000, SportsAlertPolicy.nextTimerAtMs(l2), "one local timer")
        assertNull(SportsAlertPolicy.onTimer(SportsAlertSettings(), ctx(now = t0 + 59_000), l2).banner, "not before")
        val flush = SportsAlertPolicy.onTimer(SportsAlertSettings(), ctx(now = t0 + 60_000), l2)
        assertEquals("Goal · Arsenal 1–0 Chelsea", flush.banner?.title, "flushed as its own banner")
        assertNull(SportsAlertPolicy.nextTimerAtMs(flush.ledger), "nothing left to time")
    }

    @Test
    fun `a batch keeps only the newest moment per game`() {
        val l1 = afterFirstBanner()
        val (_, l2) = decide(alert(eventId = "e2", key = "e2:1-0", at = t0 + 10_000, h = 1, a = 0), c = ctx(now = t0 + 10_000), ledger = l1)
        val (_, l3) = decide(alert(eventId = "e2", key = "e2:2-0", at = t0 + 20_000, h = 2, a = 0), c = ctx(now = t0 + 20_000), ledger = l2)
        val flush = SportsAlertPolicy.onTimer(SportsAlertSettings(), ctx(now = t0 + 60_000), l3)
        assertEquals("Goal · Arsenal 2–0 Chelsea", flush.banner?.title, "the newer score supersedes")
        assertEquals(listOf("e2:1-0" to DropReason.Superseded), flush.dropped.map { it.first.key to it.second }, "the older one is reported")
    }

    @Test
    fun `a full time beats a later score of the same game in a batch`() {
        val l1 = afterFirstBanner()
        val (_, l2) = decide(alert(AlertKind.Final, eventId = "e2", key = "e2:final", at = t0 + 10_000, h = 2, a = 1), c = ctx(now = t0 + 10_000), ledger = l1)
        val (_, l3) = decide(alert(eventId = "e2", key = "e2:late", at = t0 + 20_000, h = 2, a = 1), c = ctx(now = t0 + 20_000), ledger = l2)
        val flush = SportsAlertPolicy.onTimer(SportsAlertSettings(), ctx(now = t0 + 60_000), l3)
        assertEquals("Full time · Arsenal 2–1 Chelsea", flush.banner?.title, "Final wins")
    }

    @Test
    fun `several games flush as one summary that counts once for the cap`() {
        val l1 = afterFirstBanner()
        val (_, l2) = decide(alert(eventId = "e2", at = t0 + 10_000), c = ctx(now = t0 + 10_000), ledger = l1)
        val (_, l3) = decide(
            alert(eventId = "e3", at = t0 + 20_000, home = "Spurs", away = "Leeds", homeId = "spurs", awayId = arsenal),
            c = ctx(now = t0 + 20_000), ledger = l2,
        )
        val flush = SportsAlertPolicy.onTimer(SportsAlertSettings(), ctx(now = t0 + 60_000), l3)
        val banner = flush.banner!!
        assertEquals("2 sports updates", banner.title, "summary title")
        assertEquals(listOf("Goal · Arsenal 1–0 Chelsea", "Goal · Spurs 1–0 Leeds"), banner.lines, "one line per game")
        assertEquals(BannerAction.OpenSports, banner.action, "a summary opens Sports")
        assertEquals(2, flush.ledger.shownAtMs.size, "first banner + ONE for the summary")
    }

    @Test
    fun `batching off shows every alert at once`() {
        val (_, l1) = decide(alert(key = "a", eventId = "e1"), noBatch)
        val (d, _) = decide(alert(key = "b", eventId = "e2", at = t0 + 1_000), noBatch, ctx(now = t0 + 1_000), l1)
        assertIs<AlertDecision.ShowNow>(d, "no window")
    }

    // --- during playback ------------------------------------------------------------------------------------------

    @Test
    fun `live tv only shows with nothing playing and over live tv but not over a film`() {
        val s = SportsAlertSettings(duringPlayback = DuringPlayback.LiveTvOnly)
        assertIs<AlertDecision.ShowNow>(decide(alert(), s, ctx(playback = PlaybackContext.None)).first, "nothing playing")
        assertEquals(DropReason.Playback, dropReason(decide(alert(), s, ctx(playback = PlaybackContext.Vod)).first), "never over a film")
        assertIs<AlertDecision.ShowNow>(decide(alert(), s, ctx(playback = PlaybackContext.LiveTv)).first, "over live TV")
    }

    @Test
    fun `never and always during playback`() {
        val never = SportsAlertSettings(duringPlayback = DuringPlayback.Never)
        assertEquals(DropReason.Playback, dropReason(decide(alert(), never, ctx(playback = PlaybackContext.LiveTv)).first), "never over live TV")
        val always = SportsAlertSettings(duringPlayback = DuringPlayback.Always)
        assertIs<AlertDecision.ShowNow>(decide(alert(), always, ctx(playback = PlaybackContext.Vod)).first, "always over a film")
    }

    // --- spoilers -------------------------------------------------------------------------------------------------

    private val hidden = ScoreSpoilerSettings(shown = false)

    @Test
    fun `hidden scores drop a goal`() {
        assertEquals(DropReason.ScoresHidden, dropReason(decide(alert(), c = ctx(spoiler = hidden)).first), "a goal alert is a spoiler")
    }

    @Test
    fun `hidden scores show full time without numbers`() {
        val (d, _) = decide(alert(AlertKind.Final, h = 2, a = 1), c = ctx(spoiler = hidden))
        assertEquals("Full time · Arsenal v Chelsea", title(d), "no numbers")
    }

    @Test
    fun `hidden scores still show the kick-off`() {
        assertEquals("Arsenal v Chelsea has started", title(decide(alert(AlertKind.Start), c = ctx(spoiler = hidden)).first), "spoiler-free")
    }

    @Test
    fun `a game revealed on its own shows its numbers`() {
        val c = ctx(spoiler = hidden, revealed = RevealedGames(setOf("e1")))
        assertEquals("Goal · Arsenal 1–0 Chelsea", title(decide(alert(), c = c).first), "revealed counts as shown")
        assertEquals("Full time · Arsenal 1–0 Chelsea", title(decide(alert(AlertKind.Final, key = "f"), c = c).first), "with numbers")
    }

    private val delayed = ScoreSpoilerSettings(shown = true, delaySeconds = 30)

    @Test
    fun `a delay holds a score until received plus the delay`() {
        val now = t0 + 5_000
        val (d, ledger) = decide(alert(at = t0), c = ctx(now = now, spoiler = delayed))
        assertEquals(AlertDecision.HoldUntil(now + 30_000, HoldReason.Delay), d, "held from when it was received")
        val flush = SportsAlertPolicy.onTimer(SportsAlertSettings(), ctx(now = now + 30_000, spoiler = delayed), ledger)
        assertEquals("Goal · Arsenal 1–0 Chelsea", flush.banner?.title, "shown when due")
    }

    @Test
    fun `a delay does not hold a kick-off`() {
        assertIs<AlertDecision.ShowNow>(decide(alert(AlertKind.Start), c = ctx(spoiler = delayed)).first, "start is spoiler-free")
    }

    @Test
    fun `a delayed alert is dropped if the game page opened meanwhile`() {
        val (_, ledger) = decide(alert(), c = ctx(spoiler = delayed))
        val flush = SportsAlertPolicy.onTimer(SportsAlertSettings(), ctx(now = t0 + 30_000, spoiler = delayed, openGame = "e1"), ledger)
        assertNull(flush.banner, "the viewer is looking at it")
        assertEquals(listOf(DropReason.WatchingThisGame), flush.dropped.map { it.second }, "reported")
        assertTrue(flush.ledger.pending.isEmpty(), "and forgotten")
    }

    @Test
    fun `a delayed alert released inside the batch window waits for the window`() {
        val l1 = afterFirstBanner()
        val (_, l2) = decide(alert(eventId = "e2", at = t0 + 1_000), c = ctx(now = t0 + 1_000, spoiler = delayed), ledger = l1)
        val release = SportsAlertPolicy.onTimer(SportsAlertSettings(), ctx(now = t0 + 31_000, spoiler = delayed), l2)
        assertNull(release.banner, "the window is still open")
        assertEquals(t0 + 60_000, SportsAlertPolicy.nextTimerAtMs(release.ledger), "re-held to the window's end")
    }

    // --- stale / duplicate / watching -----------------------------------------------------------------------------

    @Test
    fun `stale and duplicate alerts are dropped`() {
        val old = alert(at = t0 - SportsAlertPolicy.MAX_ALERT_AGE_MS - 1)
        assertEquals(DropReason.Stale, dropReason(decide(old).first), "older than 5 minutes")
        val (_, ledger) = decide(alert(key = "same"), noBatch)
        assertEquals(DropReason.Duplicate, dropReason(decide(alert(key = "same"), noBatch, ledger = ledger).first), "the same key twice")
    }

    @Test
    fun `seen keys stay bounded`() {
        var ledger = AlertLedger()
        repeat(SportsAlertPolicy.SEEN_KEYS_MAX + 10) { i ->
            ledger = decide(alert(key = "k$i", eventId = "e$i"), noBatch.copy(maxPerHour = 0), ctx(), ledger).second
        }
        assertEquals(SportsAlertPolicy.SEEN_KEYS_MAX, ledger.seenKeys.size, "LRU of 200")
        assertFalse("k0" in ledger.seenKeys, "the oldest went first")
    }

    @Test
    fun `an alert for the open game is dropped`() {
        assertEquals(DropReason.WatchingThisGame, dropReason(decide(alert(), c = ctx(openGame = "e1")).first), "page open")
        assertIs<AlertDecision.ShowNow>(decide(alert(), c = ctx(openGame = "other")).first, "another game's page")
    }

    @Test
    fun `a future timestamp beyond the skew allowance is judged by receive time`() {
        val (d, _) = decide(alert(at = t0 + 10 * 60_000L))
        assertIs<AlertDecision.ShowNow>(d, "a slow device clock never drops a fresh alert")
        val (near, _) = decide(alert(key = "n", eventId = "e9", at = t0 + 30_000L), noBatch)
        assertIs<AlertDecision.ShowNow>(near, "inside the allowance")
    }

    // --- gate 3: watching this game -------------------------------------------------------------------------------

    private val liveCtx = ctx(playback = PlaybackContext.LiveTv, playing = setOf("live:42"))

    @Test
    fun `the game's known channel playing drops the alert`() {
        val c = liveCtx.copy(watch = mapOf("e1" to WatchState.Channel("live:42")))
        assertEquals(DropReason.WatchingThisGame, dropReason(decide(alert(), c = c).first), "its channel is on screen")
    }

    @Test
    fun `another channel or no channel lets the alert through`() {
        val other = liveCtx.copy(watch = mapOf("e1" to WatchState.Channel("live:7")))
        assertIs<AlertDecision.ShowNow>(decide(alert(), c = other).first, "the game is on another channel")
        val none = liveCtx.copy(watch = mapOf("e1" to WatchState.NoChannel))
        assertIs<AlertDecision.ShowNow>(decide(alert(), c = none).first, "no playlist channel carries it")
    }

    @Test
    fun `an unknown channel over live tv holds the alert for the watch check`() {
        val (d, ledger) = decide(alert(), c = liveCtx)
        assertEquals(AlertDecision.HoldUntil(t0 + SportsAlertPolicy.WATCH_CHECK_MS, HoldReason.WatchCheck), d, "about 6 s to learn the channel")
        assertEquals(t0 + SportsAlertPolicy.WATCH_CHECK_MS, SportsAlertPolicy.nextTimerAtMs(ledger), "the deadline is the timer")
        assertIs<AlertDecision.ShowNow>(decide(alert(AlertKind.Start), c = liveCtx).first, "a kick-off is never held")
        assertIs<AlertDecision.ShowNow>(decide(alert(), c = ctx(playback = PlaybackContext.LiveTv)).first, "no channel known to be playing")
    }

    @Test
    fun `the watch check released with a result re-runs gate 3`() {
        val (_, ledger) = decide(alert(), c = liveCtx)
        val resolved = SportsAlertPolicy.onWatchResolved("e1", ledger, t0 + 1_000)
        val watching = SportsAlertPolicy.onTimer(SportsAlertSettings(), liveCtx.copy(now = t0 + 1_000, watch = mapOf("e1" to WatchState.Channel("live:42"))), resolved)
        assertEquals(listOf(DropReason.WatchingThisGame), watching.dropped.map { it.second }, "it was this game")
        val elsewhere = SportsAlertPolicy.onTimer(SportsAlertSettings(), liveCtx.copy(now = t0 + 1_000, watch = mapOf("e1" to WatchState.NoChannel)), resolved)
        assertEquals("Goal · Arsenal 1–0 Chelsea", elsewhere.banner?.title, "shown as soon as the answer is in")
    }

    @Test
    fun `still unknown at the deadline drops a score and shows a full time without numbers`() {
        val deadline = t0 + SportsAlertPolicy.WATCH_CHECK_MS
        val (_, l1) = decide(alert(), c = liveCtx)
        val score = SportsAlertPolicy.onTimer(SportsAlertSettings(), liveCtx.copy(now = deadline), l1)
        assertEquals(listOf(DropReason.WatchUnknown), score.dropped.map { it.second }, "a score could spoil the stream")
        val (_, l2) = decide(alert(AlertKind.Final, h = 2, a = 1), c = liveCtx)
        val final = SportsAlertPolicy.onTimer(SportsAlertSettings(), liveCtx.copy(now = deadline), l2)
        assertEquals("Full time · Arsenal v Chelsea", final.banner?.title, "full time without numbers")
    }

    // --- text -----------------------------------------------------------------------------------------------------

    @Test
    fun `score words follow the sport and corrections`() {
        assertEquals("Score · Yankees 3–2 Mets", SportsAlertPolicy.headline(alert(sport = "baseball", home = "Yankees", away = "Mets", h = 3, a = 2), true), "baseball scores")
        assertEquals("Goal · Rangers 1–0 Bruins", SportsAlertPolicy.headline(alert(sport = "ice hockey", home = "Rangers", away = "Bruins"), true), "hockey goals")
        assertEquals("Score corrected · Arsenal 0–0 Chelsea", SportsAlertPolicy.headline(alert(h = 0, a = 0, corrected = true), true), "a disallowed goal")
    }
}
