package com.nuvio.app.features.radar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The name pass runs over every channel of every playlist (55k on the owner's Onn) on each game open. Almost no
 * channel names either opponent or the competition, so [SportsEventMatchEngine.nameMayMatch] rejects those with
 * a few substring checks and only the survivors pay for the full [SportsEventMatchEngine.name] evaluation.
 * The gate must be SOUND: it may let a non-match through, it may never drop a name [SportsEventMatchEngine.name]
 * would have scored. Twin of NuvioTV's SportsNameGateTest.
 */
class SportsNameGateTest {
    private val nba = RadarFixture(home = "Atlanta Hawks", away = "Charlotte Hornets", sport = "Basketball",
        event = "Atlanta Hawks vs Charlotte Hornets", league = "NBA", ts = "2026-11-20T23:00:00")
    private val ufc = RadarFixture(home = "", away = "", sport = "Fighting", event = "UFC 333 Volkanovski vs Evloev", ts = "2026-11-20T23:00:00")
    private val f1 = RadarFixture(sport = "Motorsport", event = "Las Vegas Grand Prix", ts = "2026-11-20T23:00:00")
    private val soccer = RadarFixture(home = "Atl\u00E9tico Madrid", away = "Real Sociedad", sport = "Soccer",
        event = "Atl\u00E9tico Madrid vs Real Sociedad", league = "La Liga", ts = "2026-11-20T20:00:00")

    private val corpus = listOf(
        "US: NBA TV", "NBA League Pass 07", "Hawks vs Hornets", "Hornets @ Hawks", "USA | Hawks-Hornets 19:00",
        "Atlanta Hawks vs Charlotte Hornets (11/20)", "\uFF2E\uFF22\uFF21 \uFF34\uFF36", "Live | Hawks vs Hornets Highlights",
        "UFC 333 Volkanovski vs Evloev", "UFC 333 Prelims", "UFC 322", "F1: Las Vegas Grand Prix", "Las Vegas Grand Prix Qualifying",
        "Atletico Madrid vs Real Sociedad", "ATL\u00C9TICO MADRID - REAL SOCIEDAD", "LaLiga TV", "La Liga 2", "DAZN LaLiga 1",
        "US: ESPN 2", "UK: Sky Sports Main Event", "FR: TF1 HD", "DE: Sky Sport Bundesliga 3", "Bally Sports Southeast",
        "WNBA League Pass", "NFL Network", "Hawks Nest Radio", "Charlotte Local News", "ES: Movistar LaLiga",
    )

    private fun prepared(f: RadarFixture, keywords: List<String>) = SportsEventMatchEngine.prepare(f, keywords)

    @Test
    fun the_gate_never_drops_a_name_the_full_evaluation_would_score() {
        for ((fixture, keywords) in listOf(nba to listOf("nba"), ufc to listOf("ufc"), f1 to listOf("f1", "formula 1"), soccer to listOf("la liga", "laliga"))) {
            val p = prepared(fixture, keywords)
            for (raw in corpus) {
                val scored = SportsEventMatchEngine.name(p, raw).strength > 0
                if (scored) assertTrue(SportsEventMatchEngine.nameMayMatch(p, raw), "gate dropped '$raw' for ${fixture.event}")
            }
        }
    }

    @Test
    fun channels_naming_neither_opponent_nor_the_competition_are_rejected_without_a_full_evaluation() {
        val p = prepared(nba, listOf("nba"))
        for (raw in listOf("US: ESPN 2", "UK: Sky Sports Main Event", "FR: TF1 HD", "Hawks Nest Radio", "Charlotte Local News", "NFL Network")) {
            assertFalse(SportsEventMatchEngine.nameMayMatch(p, raw), "gate let '$raw' through")
        }
        assertTrue(SportsEventMatchEngine.nameMayMatch(p, "Atlanta Hawks vs Charlotte Hornets"), "the game's own channel")
        assertTrue(SportsEventMatchEngine.nameMayMatch(p, "US: NBA TV"), "the competition's channel")
    }

    @Test
    fun a_55k_channel_lineup_leaves_only_a_handful_of_names_for_the_full_evaluation() {
        val p = prepared(nba, listOf("nba"))
        val survivors = syntheticLineup(55_000).count { SportsEventMatchEngine.nameMayMatch(p, it) }
        assertEquals(1_100, survivors, "only the NBA-branded and the game's own channels survive")
    }

    private fun syntheticLineup(size: Int): List<String> {
        val brands = listOf("ESPN", "Sky Sports", "beIN Sports", "DAZN", "TSN", "Fox Sports", "Canal+ Sport", "Eurosport",
            "BBC One", "TF1", "Movistar", "Sport TV", "SuperSport", "Star Sports", "Willow", "TNT Sports", "CBS", "NBC",
            "Hulu", "Peacock", "Paramount+", "Prime Video", "Telemundo", "Univision", "RTL", "ZDF", "RAI", "Mediaset")
        val countries = listOf("US", "UK", "CA", "FR", "DE", "ES", "IT", "PT", "AR", "MX", "BR", "IN", "AU", "NL", "TR", "PL")
        val tags = listOf("HD", "FHD", "4K", "SD", "HEVC", "(backup)", "\u1D34\u1D30", "")
        return List(size) { i ->
            if (i % 50 == 0) "US: NBA League Pass ${i / 50}"
            else "${countries[i % countries.size]}: ${brands[(i / 7) % brands.size]} ${i % 97} ${tags[i % tags.size]}".trim()
        }
    }
}
