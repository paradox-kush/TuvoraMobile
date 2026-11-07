package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.api.FormBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameDetailCodec
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.LinescoreBlock
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GameDetailCodecTest {
    @Test fun `unknown block types and unknown fields are ignored`() {
        val json = """{"gameId":"tsdb:1","sport":"Basketball","leagueId":"4387","leagueName":"NBA","startsAt":"2026-11-07T00:00:00Z",
            "state":"live","futureField":{"x":1},
            "header":{"type":"teams","home":{"name":"A","score":"10","newThing":1},"away":{"name":"B"}},
            "blocks":[
              {"type":"hologram","source":"espn","beams":3},
              {"type":"winProbability","source":"espn","homePercent":61,"extra":true},
              {"type":"linescore","source":"espn","columns":["Q1","T"],"rows":[["1","1"],["2","2"]],"rowLabels":["A","B"]}
            ],
            "sources":{"winProbability":"espn"},"asOf":{"espn":5}}"""
        val d = assertNotNull(GameDetailCodec.decode(json))
        assertEquals(2, d.blocks.size)
        assertIs<WinProbabilityBlock>(d.blocks[0])
        assertEquals(61, (d.blocks[0] as WinProbabilityBlock).homePercent)
        assertIs<LinescoreBlock>(d.blocks[1])
        assertEquals("A", assertIs<TeamsHeader>(d.header).home.name)
        assertEquals(GameState.LIVE, d.state)
    }

    @Test fun `an unknown state falls back to upcoming and garbage decodes to null`() {
        val d = GameDetailCodec.decode("""{"gameId":"x","sport":"s","leagueId":"1","leagueName":"l","startsAt":"t","state":"weird","header":{"type":"event","name":"n"},"blocks":[]}""")
        assertEquals(GameState.UPCOMING, d?.state)
        assertNull(GameDetailCodec.decode("nope"))
        assertNull(GameDetailCodec.decode("{}"))
    }

    @Test fun `round trip`() {
        val d = GameDetail(
            gameId = "espn:9", sport = "Soccer", leagueId = "4328", leagueName = "EPL", startsAt = "2026-11-07T12:30:00Z",
            state = GameState.FINAL, header = TeamsHeader(Side("H", score = "1"), Side("A", score = "0")),
            blocks = listOf(FormBlock(source = "espn", home = listOf("W"), away = listOf("L"))),
            sources = mapOf("form" to "espn"), asOf = mapOf("espn" to 1L),
        )
        assertEquals(d, GameDetailCodec.decode(GameDetailCodec.encode(d)))
    }
}
