package com.nuvio.app.features.radar.alerts

import com.nuvio.app.features.radar.RadarFollow
import com.nuvio.app.features.radar.RadarLocalState
import com.nuvio.app.features.radar.RadarPrefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `alerts` object: tolerant decode (missing / unknown → defaults), round-trip, and per-game overrides.
 * kotlin.test → assertEquals(expected, actual, message).
 */
class SportsAlertSettingsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun parse(text: String) = SportsAlertSettings.fromJson(json.parseToJsonElement(text))

    @Test
    fun `the owner-approved defaults`() {
        val d = SportsAlertSettings()
        assertEquals(true, d.enabled, "on")
        assertEquals(AlertLevel.All, d.teamLevel, "teams: everything")
        assertEquals(AlertLevel.FinalOnly, d.leagueLevel, "leagues: full time only")
        assertEquals(Triple(true, 22 * 60, 8 * 60), Triple(d.quietOn, d.quietStartMin, d.quietEndMin), "quiet 22:00 to 08:00")
        assertEquals(6 to 60, d.maxPerHour to d.batchSeconds, "6 an hour and 60 s batching")
        assertEquals(DuringPlayback.LiveTvOnly, d.duringPlayback, "live TV only")
    }

    @Test
    fun `a local blob without the alerts key decodes to the defaults`() {
        val old = """{"follows":[{"league_id":"4328","sport":"Soccer"}],"prefs":{"scores_shown":false},"teams":[]}"""
        val state = json.decodeFromString<RadarLocalState>(old)
        assertEquals(SportsAlertSettings(), state.prefs.alerts, "defaults")
        assertEquals(false, state.prefs.alertsDirty, "nothing pending")
        assertEquals(listOf("4328"), state.follows.map { it.leagueId }, "follows intact")
    }

    @Test
    fun `a malformed alerts value never costs the follows`() {
        listOf("\"loud\"", "null", "[1]", "{\"enabled\":\"yes\"}").forEach { bad ->
            val text = """{"follows":[{"league_id":"4328"}],"prefs":{"alerts":$bad},"teams":[]}"""
            val state = json.decodeFromString<RadarLocalState>(text)
            assertEquals(SportsAlertSettings(), state.prefs.alerts, "defaults for $bad")
            assertEquals(1, state.follows.size, "follows kept for $bad")
        }
    }

    @Test
    fun `unknown values read as that key's default`() {
        val s = parse(
            """{"enabled":false,"team_level":"loud","league_level":"start_final","quiet_start_min":2000,"quiet_end_min":-5,
               "max_per_hour":7,"batch_s":45,"during_playback":"sometimes","muted_teams":["1",2]}""",
        )
        assertEquals(false, s.enabled, "a valid value is kept")
        assertEquals(AlertLevel.All, s.teamLevel, "unknown level")
        assertEquals(AlertLevel.StartAndFinal, s.leagueLevel, "valid level kept")
        assertEquals(22 * 60 to 8 * 60, s.quietStartMin to s.quietEndMin, "out-of-range minutes")
        assertEquals(6 to 60, s.maxPerHour to s.batchSeconds, "values outside the choices")
        assertEquals(DuringPlayback.LiveTvOnly, s.duringPlayback, "unknown playback choice")
        assertEquals(setOf("1", "2"), s.mutedTeams, "numeric ids read as strings")
    }

    @Test
    fun `settings round-trip through their JSON`() {
        val s = SportsAlertSettings(
            enabled = false, teamLevel = AlertLevel.StartAndFinal, leagueLevel = AlertLevel.Off, quietOn = false,
            quietStartMin = 60, quietEndMin = 90, maxPerHour = 12, batchSeconds = 300, duringPlayback = DuringPlayback.Never,
            mutedLeagues = setOf("4328"), mutedTeams = setOf("133604"),
            games = listOf(GameAlertOverride("e1", AlertLevel.Off, 123L)),
        )
        assertEquals(s, SportsAlertSettings.fromJson(SportsAlertSettings.toJson(s)), "lossless")
        val prefs = RadarPrefs(alerts = s, alertsDirty = true)
        val text = json.encodeToString(RadarPrefs.serializer(), prefs)
        assertEquals(prefs, json.decodeFromString(RadarPrefs.serializer(), text), "through the local blob")
        assertTrue("\"alerts_dirty\":true" in text, "the flag is persisted locally: $text")
    }

    @Test
    fun `the JSON uses the spec's keys`() {
        val keys = SportsAlertSettings.toJson(SportsAlertSettings()).keys
        assertEquals(
            setOf("enabled", "team_level", "league_level", "quiet_on", "quiet_start_min", "quiet_end_min", "max_per_hour", "batch_s", "during_playback", "muted_leagues", "muted_teams", "games"),
            keys,
            "contract with the backend",
        )
        val o = SportsAlertSettings.toJson(SportsAlertSettings())
        assertEquals(JsonPrimitive("final"), o["league_level"], "wire value")
        assertEquals(JsonPrimitive("live"), o["during_playback"], "wire value")
    }

    @Test
    fun `a game override is all or off with an expiry`() {
        val s = parse(
            """{"games":[{"id":"e1","level":"off","exp":100},{"id":"e2","level":"start_final","exp":100},{"id":"e3","level":"all"},{"id":"e4","level":"all","exp":200}]}""",
        )
        assertEquals(listOf("e1", "e4"), s.games.map { it.id }, "other levels and a missing expiry are dropped")
        assertEquals(AlertLevel.Off, s.gameOverride("e1", nowMs = 50), "before expiry")
        assertNull(s.gameOverride("e1", nowMs = 100), "at expiry")
    }

    @Test
    fun `overrides keep the newest 50`() {
        val many = (1..60).fold(SportsAlertSettings()) { acc, i -> acc.withGame("e$i", AlertLevel.Off, expMs = 1_000, nowMs = 0) }
        assertEquals(50, many.games.size, "capped")
        assertEquals("e11", many.games.first().id, "the oldest went first")
        val text = """{"games":[${(1..60).joinToString(",") { "{\"id\":\"e$it\",\"level\":\"off\",\"exp\":9}" }}]}"""
        assertEquals("e60", parse(text).games.last().id, "decode keeps the newest too")
    }

    @Test
    fun `the serialized object stays under 8 KB at its limits`() {
        val longId = { p: String, i: Int -> (p + i).padEnd(64, 'x') }
        val worst = SportsAlertSettings(
            mutedLeagues = (1..500).map { longId("l", it) }.toSet(),
            mutedTeams = (1..500).map { longId("t", it) }.toSet(),
            games = (1..50).map { GameAlertOverride(longId("g", it), AlertLevel.All, Long.MAX_VALUE) },
        )
        val bytes = SportsAlertSettings.toJson(worst).toString().encodeToByteArray().size
        assertTrue(bytes < 8 * 1024, "the backend stores it only under 8 KB: $bytes bytes")
        val back = SportsAlertSettings.fromJson(SportsAlertSettings.toJson(worst))
        assertTrue(back.games.isNotEmpty() && back.mutedTeams.isNotEmpty(), "trimmed, not emptied")
    }

    @Test
    fun `setting a game override prunes expired ones`() {
        val s = SportsAlertSettings(games = listOf(GameAlertOverride("old", AlertLevel.Off, 10)))
            .withGame("e1", AlertLevel.All, expMs = 1_000, nowMs = 20)
        assertEquals(listOf("e1"), s.games.map { it.id }, "the expired one is gone")
        assertEquals(emptyList(), s.withGame("e1", null, 1_000, 20).games, "null clears")
    }

    @Test
    fun `cycles walk the fixed choices`() {
        assertEquals(listOf(12, 0, 3, 6), generateSequence(6) { SportsAlertSettings.nextPerHour(it) }.drop(1).take(4).toList(), "per hour")
        assertEquals(listOf(300, 0, 60), generateSequence(60) { SportsAlertSettings.nextBatch(it) }.drop(1).take(3).toList(), "batch")
        assertEquals(AlertLevel.StartAndFinal, SportsAlertLabels.nextLevel(AlertLevel.All), "level")
        assertEquals(AlertLevel.All, SportsAlertLabels.nextLevel(AlertLevel.Off), "wraps")
    }

    @Test
    fun `the payload parses and ignores other versions`() {
        val payload = json.parseToJsonElement(
            """{"v":1,"key":"e1:score:1-0:1","eventId":"e1","kind":"score","sport":"ice_hockey","leagueId":4380,"league":"NHL",
               "homeId":"1","awayId":"2","home":"Rangers","away":"Bruins","homeScore":"1","awayScore":0,"progress":"P2","corrected":false,"at":1800000000000}""",
        ).jsonObject
        val alert = SportsAlert.parse(payload)!!
        assertEquals("ice hockey", alert.sport, "normalised sport")
        assertEquals("4380", alert.leagueId, "numeric id read as text")
        assertEquals(1 to 0, alert.homeScore to alert.awayScore, "string or number scores")
        assertEquals(AlertKind.Score, alert.kind, "kind")
        assertNull(SportsAlert.parse(JsonObject(payload + ("v" to JsonPrimitive(2)))), "another version is ignored")
        assertNull(SportsAlert.parse(JsonObject(payload - "eventId")), "no event id")
        assertNull(SportsAlert.parse(JsonObject(payload - "at")), "at is required")
        assertNull(SportsAlert.parse(JsonObject(payload - "v")), "v is required")
        assertEquals("", SportsAlert.parse(JsonObject(payload - "away"))?.away, "away is optional")
    }

    @Test
    fun `labels for the chips`() {
        val s = SportsAlertSettings()
        assertEquals(AlertChip("Alerts on", selected = true, enabled = true), SportsAlertLabels.hubChip(s, signedIn = true), "on")
        assertEquals(AlertChip("Alerts off", selected = false, enabled = true), SportsAlertLabels.hubChip(s.copy(enabled = false), signedIn = true), "off")
        assertEquals(AlertChip(SportsAlertLabels.SIGN_IN, selected = false, enabled = false), SportsAlertLabels.hubChip(s, signedIn = false), "signed out")
        assertNull(SportsAlertLabels.gameChip(s, "e1", followed = false, signedIn = true, nowMs = 0), "not a followed game")
        assertEquals("Alerts: Default", SportsAlertLabels.gameChip(s, "e1", followed = true, signedIn = true, nowMs = 0)?.label, "default")
        val all = s.withGame("e1", AlertLevel.All, expMs = 100, nowMs = 0)
        assertEquals("Alerts: All", SportsAlertLabels.gameChip(all, "e1", followed = true, signedIn = true, nowMs = 0)?.label, "all")
        assertEquals(AlertLevel.Off, SportsAlertLabels.nextGameLevel(AlertLevel.All), "all then off")
        assertNull(SportsAlertLabels.nextGameLevel(AlertLevel.Off), "off then default")
        assertEquals("22:00", SportsAlertLabels.clock(22 * 60), "clock")
        assertEquals(23 * 60 + 30, SportsAlertLabels.stepClock(0, -1), "wraps back past midnight")
    }
}
