package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.internal.SportsPath
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of research/sports-mapping/validate.py `read`: same syntax, same "missing -> nothing, never throws". */
class SportsPathTest {
    private val doc = parse(
        """{"a":{"b":[{"c":[{"d":1},{"d":2},{"d":null}]},{"c":[]}]},
            "k":[{"id":"X","on":true,"n":1},{"id":"y","on":false,"n":2}],
            "nul":null,"s":"str","e":[]}""",
    )

    private fun texts(path: String) = SportsPath.read(doc, path).map { it.jsonPrimitive.content }

    @Test fun `dotted keys and index`() {
        assertEquals(listOf("2"), texts("a.b[0].c[1].d"))
    }

    @Test fun `star fans out and null leaves are dropped`() {
        assertEquals(listOf("1", "2"), texts("a.b[0].c[*].d"))
    }

    @Test fun `negative index counts from the end and is bounds checked like python`() {
        assertEquals(listOf("2"), texts("k[-1].n"))
        assertEquals(listOf("1"), texts("k[-2].n"))
        assertEquals(emptyList(), texts("k[-3].n"))
        assertEquals(emptyList(), texts("k[2].n"))
    }

    @Test fun `filter matches case-insensitively on the stringified value`() {
        assertEquals(listOf("1"), texts("k[id=x].n"))
        assertEquals(listOf("1"), texts("k[on=true].n"))
        assertEquals(listOf("2"), texts("k[on=FALSE].n"))
        assertEquals(listOf("2"), texts("k[n=2].id").map { if (it == "y") "2" else it })
    }

    @Test fun `missing steps yield nothing and never throw`() {
        val weird = listOf(
            "", "[", "]", "a..b", "a.b.c.d.e", "nope", "nope[0]", "a[0]", "s.x", "s[*]", "e[0]", "e[-1]",
            "nul.x", "k[id=", "k[=x]", "[999999999999999999999]", "a.b[0].c[*].d[*]", "...", "[*][*][*]",
        )
        for (p in weird) SportsPath.read(doc, p) // must not throw
        assertEquals(emptyList(), SportsPath.read(doc, "nope.x"))
        assertEquals(emptyList(), SportsPath.read(doc, "nul"))
        assertEquals(emptyList(), SportsPath.read(null, "a.b"))
    }

    @Test fun `text and first helpers`() {
        assertEquals("str", SportsPath.text(doc, "s"))
        assertNull(SportsPath.text(doc, "nope"))
        assertNull(SportsPath.text(doc, "a.b")) // a list is not a display value
        assertEquals(JsonPrimitive("X"), SportsPath.first(doc, "k[0].id"))
    }

    @Test fun `numbers render without a spurious decimal`() {
        val n = parse("""{"v":6.0,"w":7,"x":0.5,"y":"187/4"}""")
        assertEquals("6", SportsPath.text(n, "v"))
        assertEquals("7", SportsPath.text(n, "w"))
        assertEquals("0.5", SportsPath.text(n, "x"))
        assertEquals("187/4", SportsPath.text(n, "y"))
    }

    @Test fun `reads recorded NBA summary exactly like validate py`() {
        val sm = parse(SportsFixtures.espn_nba_summary)
        assertEquals("1", SportsPath.text(sm, "winprobability[-1].homeWinPercentage"))
        val ev = parse(SportsFixtures.espn_nba_event)
        assertEquals(
            listOf("Atlanta Hawks"),
            SportsPath.read(ev, "competitions[0].competitors[homeAway=home].team.displayName").map { it.jsonPrimitive.content },
        )
        assertTrue(SportsPath.read(sm, "plays[scoringPlay=true]").size > 5)
    }
}
