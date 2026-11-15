package com.nuvio.app.features.sportsdetail

import com.nuvio.app.features.sportsdetail.internal.SportsMappingParser
import com.nuvio.app.features.sportsdetail.internal.SportsMappingValidator
import com.nuvio.app.features.sportsdetail.internal.BlockType
import com.nuvio.app.features.sportsdetail.internal.BundledSportsMapping
import com.nuvio.app.features.sportsdetail.internal.TeamPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameScreenMappingTest {
    private val m = bundledMapping()

    @Test fun `the bundled mapping carries the single-event template`() {
        assertEquals("https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard/{id}", m.espn.eventTemplate)
    }

    @Test fun `a mapping without the event key parses with a null template and still validates`() {
        val legacy = mappingOf(editedMappingJson("\"event\": \"https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard/{id}\",", ""))
        assertNull(legacy.espn.eventTemplate)
        assertNull(SportsMappingValidator.validate(legacy))
    }

    @Test fun `an event template off the ESPN host or without its placeholders is refused`() {
        val good = "https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard/{id}"
        val offHost = mappingOf(editedMappingJson(good, "https://evil.example.com/{path}/{id}"))
        assertTrue(SportsMappingValidator.validate(offHost)!!.startsWith("espn template"))
        val noId = mappingOf(editedMappingJson(good, "https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard"))
        assertTrue(SportsMappingValidator.validate(noId)!!.startsWith("espn template"))
        assertNull(SportsMappingValidator.validate(m))
    }

    @Test fun `team colour and rank default to ESPN's own keys when the mapping has none`() {
        assertEquals("team.color", m.common.team.color)
        assertEquals("team.alternateColor", m.common.team.altColor)
        assertEquals("curatedRank.current", m.common.team.rank)
        assertEquals(TeamPaths.DEFAULT_COLOR_PATH, "team.color")
    }

    @Test fun `a mapping may re-point colour and rank`() {
        val json = editedMappingJson("\"form\": \"form\"\n  },", "\"form\": \"form\", \"color\": \"team.primary\", \"altColor\": \"team.secondary\", \"rank\": \"rank.now\"\n  },")
        val p = mappingOf(json).common.team
        assertEquals("team.primary", p.color); assertEquals("team.secondary", p.altColor); assertEquals("rank.now", p.rank)
    }

    @Test fun `plays and standingsTable are listed for the four team-sport profiles and read from the summary`() {
        for (p in listOf("football", "basketball", "hockey", "soccer")) {
            val profile = assertNotNull(m.profiles[p], p)
            assertTrue("plays" in profile.blocks, "$p lists plays")
            assertTrue("standingsTable" in profile.blocks, "$p lists standingsTable")
            assertEquals("summary", profile.spec(BlockType.PLAYS)?.from, p)
            assertEquals("summary", profile.spec(BlockType.STANDINGS_TABLE)?.from, p)
            assertEquals("standings.groups[*]", profile.spec(BlockType.STANDINGS_TABLE)?.path, p)
        }
        for (p in listOf("baseball", "tennis", "motorsport", "fighting", "cricket")) {
            assertTrue("plays" !in m.profiles[p]!!.blocks && "standingsTable" !in m.profiles[p]!!.blocks, "$p keeps today's blocks")
        }
        assertEquals("plays[*]", m.profiles["basketball"]!!.spec(BlockType.PLAYS)?.path)
        assertEquals("drives.previous[*].plays[*]", m.profiles["football"]!!.spec(BlockType.PLAYS)?.path)
        assertEquals("commentary[*]", m.profiles["soccer"]!!.spec(BlockType.PLAYS)?.path)
    }

    @Test fun `existing blocks keep their order and the new ones are appended`() {
        val b = m.profiles["basketball"]!!.blocks
        assertEquals(listOf("header", "situation", "watch", "linescore", "stats", "leaders", "timeline", "highlights", "next"), b.take(9))
        assertEquals(listOf("plays", "standingsTable"), b.drop(9).take(2), "Phase A's blocks directly follow today's nine")
    }

    @Test fun `the bundled mapping validates and is still schema 1`() {
        assertNull(SportsMappingValidator.validate(m))
        assertTrue(BundledSportsMapping.JSON.contains("\"schemaVersion\": 1"))
        val reparsed = SportsMappingParser.parse(BundledSportsMapping.JSON)
        assertTrue(reparsed is SportsMappingParser.ParseResult.Ok)
    }
}
