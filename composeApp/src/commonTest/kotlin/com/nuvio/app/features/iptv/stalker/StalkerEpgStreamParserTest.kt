package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamProgram
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StalkerEpgStreamParserTest {

    private val body = """{"js":{"data":{"12":[
        {"id":"98765","ch_id":"12","name":"News","descr":"d","start_timestamp":"1000","stop_timestamp":"2000"},
        {"ch_id":"12","name":"No id","start_timestamp":"2000","stop_timestamp":"3000"},
        {"id":"  ","name":"Blank id","start_timestamp":"3000","stop_timestamp":"4000"}
    ]}}}"""

    private fun parse(chunk: Int): List<Pair<Int, XtreamProgram>> {
        val out = mutableListOf<Pair<Int, XtreamProgram>>()
        val parser = StalkerEpgStreamParser(Json { ignoreUnknownKeys = true }) { ch, p -> out += ch to p }
        body.chunked(chunk).forEach(parser::feed)
        return out
    }

    @Test
    fun `the portal's programme id is kept on the programme`() {
        val rows = parse(chunk = body.length)
        assertEquals(listOf("98765", null, null), rows.map { it.second.programmeId })
        assertEquals("News", rows.first().second.title)
        assertEquals(1_000_000L, rows.first().second.startMs)
    }

    @Test
    fun `the id survives any chunk boundary`() {
        assertEquals(parse(body.length), parse(7))
        assertNull(parse(1)[1].second.programmeId)
    }
}
