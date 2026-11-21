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

    // --- fuzzy-tier length prefilter (the 49k-channel episode fix) ---

    @Test
    fun lengthPrefilterOnlySkipsPairsTheGateWouldRejectAnyway() {
        // Levenshtein distance >= |Δlen|, so similarity <= 1 - Δlen/maxLen. Any pair the
        // prefilter drops must therefore score under the 0.87 gate — verify the bound holds
        // across a spread of shapes so the skip can never change a match result.
        val pairs = listOf(
            "sky sports main event" to "sky s",
            "sky sports main event" to "sky sports main event extra long name",
            "fox" to "fox sports racing",
            "bbc 1" to "bbc 1 scotland hd extra",
        )
        for ((a, b) in pairs) {
            val maxLen = maxOf(a.length, b.length)
            val bound = 1.0 - kotlin.math.abs(a.length - b.length).toDouble() / maxLen
            if (bound < 0.87) {
                val actual = EpgNorm.similarity(a, b)
                assertEquals(
                    true,
                    actual <= bound + 1e-9,
                    "similarity($a | $b) must sit under its length bound",
                )
            }
        }
    }

    // --- panel-noise stripping (+759 matches on the user's real 11,283-channel panel) ---

    @Test
    fun aPackagerSegmentBetweenPipesIsDropped() {
        // "PL | Canal+ | Golf Channel FHD" — Canal+ is the bouquet, not the channel.
        assertEquals("golf channel", EpgNorm.coreNorm(EpgNorm.stripPanelNoise("PL | Canal+ | Golf Channel FHD")))
    }

    @Test
    fun aTwoSegmentNameKeepsItsCountryPrefixHandling() {
        // Only 3+ segments are packager shapes; "UK | BBC One" must still work as before.
        assertEquals("bbc 1", EpgNorm.coreNorm(EpgNorm.stripPanelNoise("UK | BBC One")))
    }

    @Test
    fun operatorAndCodecTagsAreStripped() {
        assertEquals("trace urban", EpgNorm.coreNorm(EpgNorm.stripPanelNoise("FRA | Trace Urban HD (Local)")))
        assertEquals("cnbc", EpgNorm.coreNorm(EpgNorm.stripPanelNoise("|NO| CNBC (ALLENTE)")))
        assertEquals("warner tv series", EpgNorm.coreNorm(EpgNorm.stripPanelNoise("Warner TV Series -HD (Local) ''H.264''")))
    }

    @Test
    fun aCategoryPrefixIsDropped() {
        assertEquals("trace muzika", EpgNorm.coreNorm(EpgNorm.stripPanelNoise("Music: Trace Muzika")))
    }

    @Test
    fun anOperatorWordIsKeptWhenItIsTheChannelName() {
        // "Local News" / "Magenta Sport" are real names — stripping the first word would
        // turn them into a different channel entirely.
        assertEquals("local news", EpgNorm.coreNorm(EpgNorm.stripPanelNoise("Local News")))
        assertEquals("magenta sport", EpgNorm.coreNorm(EpgNorm.stripPanelNoise("Magenta Sport")))
    }

    // --- leading word-digit guard (the false positives the rules exposed) ---

    @Test
    fun aLeadingWordDigitIsBrandNotChannelNumber() {
        // Folding these made "One Sports" collide with "Sport 1" and "One TV" with "TV One"
        // through the order-insensitive token tiers — both seen on the user's panel.
        assertEquals("one sports", EpgNorm.coreNorm("ONE SPORTS"))
        assertEquals("one tv", EpgNorm.coreNorm("One TV"))
    }

    @Test
    fun aTrailingWordDigitIsStillFolded() {
        // The whole point of the table: "BBC One" is BBC 1.
        assertEquals("bbc 1", EpgNorm.coreNorm("BBC One"))
        assertEquals("sky sports 2", EpgNorm.coreNorm("Sky Sports Two"))
    }

    @Test
    fun oneSportsNoLongerMatchesSportOne() {
        val onePlusIndex = EpgChannelIndex.build(listOf("Sport1.pl" to listOf("Sport 1")))
        assertNull(onePlusIndex.match("PH ONE SPORTS (C)", null))
    }

    // --- timeshift parity (the worst false positive: right titles, wrong by one hour) ---

    @Test
    fun aTimeshiftChannelNeverMatchesItsBaseChannel() {
        val idx = EpgChannelIndex.build(
            listOf(
                "ITV1.uk" to listOf("ITV1"),
                "AnimalPlanet.uk" to listOf("Animal Planet"),
            )
        )
        assertNull(idx.match("UK: ITV +1", null), "ITV +1 is not ITV1")
        assertNull(idx.match("UK SD : Animal Planet +1", null), "the +1 feed runs an hour behind")
    }

    @Test
    fun aTimeshiftChannelStillMatchesItsOwnTimeshiftFeed() {
        val idx = EpgChannelIndex.build(listOf("ITV1plus1.uk" to listOf("ITV1 +1")))
        assertEquals("itv1plus1.uk", idx.match("UK: ITV1 +1", null)?.epgId)
    }

    @Test
    fun aBaseChannelIsNotBlockedByATimeshiftAlias() {
        // One-directional by design: an EPG id carrying a "+1" alias must not stop a plain
        // channel from matching (this was blocking a correct Tennis Channel hit).
        val idx = EpgChannelIndex.build(listOf("tennis.us" to listOf("Tennis Channel", "Tennis Channel +1")))
        assertEquals("tennis.us", idx.match("USA: Tennis Channel", null)?.epgId)
    }

    @Test
    fun fuzzyTierStillMatchesAfterThePrefilter() {
        // Same near-spelling case as above — the prefilter must not eat true fuzzy hits.
        assertEquals(EpgChannelIndex.TIER_FUZZY, index.match("SKY SPORTS MAIN EVENTT HD", null)?.tier)
        // And a short garbage prefix that shares the first token stays unmatched.
        assertNull(index.match("SKY S", null))
    }

    /** The Sports station tier pays for coreNorm only on channels this pre-check keeps: it must never drop the real first token. */
    @Test
    fun coreLeadCandidates_always_contains_the_first_token_of_coreNorm() {
        val names = listOf(
            "UK FHD : BBC One", "UK: BBC 1", "UKSD MTV HITS", "US: ESPN 2 HD", "ESPN2", "USA | ESPN HD 2", "HD Sky Sports",
            "us uk de fr sky", "|NO| CNBC (ALLENTE)", "AT&T SportsNet", "Canal+ Sport 1", "FR: beIN SPORTS 1 \u1D34\u1D30",
            "\uFF2E\uFF22\uFF21 \uFF34\uFF36", "ES: Movistar LaLiga", "Atl\u00E9tico TV", "4K", "HD", "uk hd fhd 4k sky", "DE: Sport1+", "ATANDT", "",
        )
        for (name in names) {
            val first = EpgNorm.coreNorm(name).substringBefore(' ')
            if (first.isEmpty()) continue
            val leads = EpgNorm.coreLeadCandidates(name)
            assertEquals(true, first in leads, "'$name' -> '$first' in $leads")
        }
    }

    @Test
    fun coreLeadCandidates_stops_at_the_first_token_that_cannot_be_a_stripped_prefix() {
        assertEquals(listOf("us", "sky"), EpgNorm.coreLeadCandidates("US: Sky Sports Main Event"))
        assertEquals(listOf("espn"), EpgNorm.coreLeadCandidates("ESPN 2"))
    }
}
