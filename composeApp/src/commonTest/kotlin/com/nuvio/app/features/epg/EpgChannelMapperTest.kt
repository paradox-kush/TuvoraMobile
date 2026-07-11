package com.nuvio.app.features.epg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Port of research/epg-matching/epg_match.py's selftest + tier behavior, pinned against the
 * same real-world shapes the study measured (94-97% eligible-UK / 99% US on B1G). Twin of
 * NuvioTV's EpgChannelMapperTest.
 */
class EpgChannelMapperTest {

    // --- normalizer (the python selftest cases verbatim) ---

    @Test
    fun coreNormStripsRegionPrefixesAndQualityTokens() {
        val cases = mapOf(
            "UK FHD : BBC One" to "bbc 1",
            "UK: BBC 1" to "bbc 1",
            "UKSD MTV HITS" to "mtv hits",
            "UK SD : TNT Sport 2" to "tnt sport 2",
            "UK || SKY SPORTS FOOTBALL" to "sky sports football",
            "SKY SPORTS PREMIER LEAGUE HD " to "sky sports premier league",
            "IRE : Virgin Two FHD" to "virgin 2",
            "US| FOX SPORTS UHD" to "fox sports",
        )
        for ((raw, want) in cases) {
            assertEquals(want, EpgNorm.coreNorm(raw), raw)
        }
    }

    @Test
    fun idStemReadsAnXmltvIdAsAName() {
        assertEquals("bbc 1", EpgNorm.idStem("BBC.One.HD.uk"))
        assertEquals("tnt sports 2", EpgNorm.idStem("TNT.Sports.2.HD.uk"))
    }

    @Test
    fun plusIsIdentityNotQuality() {
        assertEquals("sky sports plus", EpgNorm.coreNorm("UK: SKY SPORTS PLUS FHD"))
    }

    // --- index tiers ---

    private val index = EpgChannelIndex.build(
        listOf(
            "BBC.One.HD.uk" to listOf("BBC One", "BBC 1"),
            "SkySportsMainEvent.uk" to listOf("Sky Sports Main Event"),
            "TSN1.ca" to listOf("TSN 1"),
            "dave.uk" to listOf("U&Dave"),
            "fox.us" to listOf("FOX"),
        )
    )

    @Test
    fun tvgIdMatchesWhenPlausible() {
        val hit = index.match("UK: BBC ONE FHD", "bbc.one.hd.uk")
        assertEquals(EpgChannelIndex.TIER_TVG, hit?.tier)
        assertEquals("bbc.one.hd.uk", hit?.epgId)
    }

    @Test
    fun garbageTvgIdIsRejectedAndNameStillMatches() {
        // Operator pasted the wrong tvg-id; the name is authoritative.
        val hit = index.match("UK: SKY SPORTS MAIN EVENT", "bbc.one.hd.uk")
        assertEquals(EpgChannelIndex.TIER_EXACT, hit?.tier)
        assertEquals("skysportsmainevent.uk", hit?.epgId)
    }

    @Test
    fun exactViaRegionAndQualityStrip() {
        assertEquals("bbc.one.hd.uk", index.match("UK FHD : BBC One", null)?.epgId)
    }

    @Test
    fun uAndRebrandVariant() {
        assertEquals("dave.uk", index.match("UK: DAVE", null)?.epgId)
    }

    @Test
    fun tokenOrderInsensitive() {
        assertEquals(EpgChannelIndex.TIER_TOKENS, index.match("MAIN EVENT SKY SPORTS", null)?.tier)
    }

    @Test
    fun squashJoinsSpacedAndUnspacedSpellings() {
        assertEquals(EpgChannelIndex.TIER_SQUASH, index.match("SKYSPORTS MAIN EVENT", null)?.tier)
    }

    @Test
    fun pluralInsensitive() {
        assertEquals(EpgChannelIndex.TIER_PLURAL, index.match("SKY SPORT MAIN EVENT", null)?.tier)
    }

    @Test
    fun fuzzyCatchesNearSpellingsWithSameFirstToken() {
        assertEquals(EpgChannelIndex.TIER_FUZZY, index.match("SKY SPORTS MAIN EVENTT HD", null)?.tier)
    }

    @Test
    fun unrelatedNameDoesNotMatch() {
        assertNull(index.match("AR: MBC DRAMA", null))
    }

    @Test
    fun wordDigitsFold() {
        assertEquals("bbc.one.hd.uk", index.match("BBC ONE", null)?.epgId)
    }
}
