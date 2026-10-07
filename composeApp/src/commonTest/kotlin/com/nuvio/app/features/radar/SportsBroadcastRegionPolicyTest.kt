package com.nuvio.app.features.radar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The broadcaster-listing lane surfaced wrong channels: TheSportsDB lists an NFL game's broadcasters
 * as ~14 international feeds (NFL Network US, DAZN Australia, DAZN Brasil, … ESPN 2 in various
 * countries). Because the matcher drops the country tail ("DAZN Australia" -> "dazn"), one regional
 * station matched EVERY same-brand channel the user owned and stamped them all with the station's
 * country — so "USA: ESPN 2" showed up labelled "The Netherlands" (device-confirmed). These pin the
 * region gate: a brand-level listing match only counts when the channel is region-neutral or shares
 * the station's region.
 */
class SportsBroadcastRegionPolicyTest {

    @Test
    fun `country names map to region codes`() {
        assertEquals("us", SportsBroadcastRegionPolicy.regionOfCountry("United States"))
        assertEquals("nl", SportsBroadcastRegionPolicy.regionOfCountry("The Netherlands"))
        assertEquals("au", SportsBroadcastRegionPolicy.regionOfCountry("Australia"))
        assertEquals("br", SportsBroadcastRegionPolicy.regionOfCountry("Brazil"))
        assertNull(SportsBroadcastRegionPolicy.regionOfCountry("Neverland"))
        assertNull(SportsBroadcastRegionPolicy.regionOfCountry(null))
    }

    @Test
    fun `channel names expose their region prefix or suffix`() {
        assertEquals("us", SportsBroadcastRegionPolicy.regionOfChannel("USA: ESPN 2 HD"))
        assertEquals("nl", SportsBroadcastRegionPolicy.regionOfChannel("NL: ESPN 2"))
        assertEquals("au", SportsBroadcastRegionPolicy.regionOfChannel("|AU| DAZN 1"))
        assertEquals("au", SportsBroadcastRegionPolicy.regionOfChannel("DAZN Australia"))
        assertNull(SportsBroadcastRegionPolicy.regionOfChannel("NFL Network"))
        assertNull(SportsBroadcastRegionPolicy.regionOfChannel("beIN Sports 1"))
    }

    @Test
    fun `a Netherlands station must not surface a USA channel`() {
        // The reported bug: ESPN2-Netherlands stamping "USA: ESPN 2 HD" as The Netherlands.
        assertFalse(SportsBroadcastRegionPolicy.listingAccepts("nl", "USA: ESPN 2 HD"))
        assertFalse(SportsBroadcastRegionPolicy.listingAccepts("au", "BR: ESPN 2 HD"))
    }

    @Test
    fun `a region-aligned channel still matches`() {
        assertTrue(SportsBroadcastRegionPolicy.listingAccepts("nl", "NL: ESPN 2"))
        assertTrue(SportsBroadcastRegionPolicy.listingAccepts("au", "DAZN Australia"))
    }

    @Test
    fun `region-neutral channels and unknown station regions stay permissive`() {
        assertTrue(SportsBroadcastRegionPolicy.listingAccepts("us", "NFL Network"), "neutral channel is allowed")
        assertTrue(SportsBroadcastRegionPolicy.listingAccepts(null, "USA: ESPN 2 HD"), "unknown station region is permissive")
    }

    @Test
    fun `only a known matching feed territory confirms a listing`() {
        assertEquals(MatchConfidence.CONFIRMED, SportsBroadcastRegionPolicy.listingConfidence("us", "us"), "US station for a US game confirms")
        assertEquals(MatchConfidence.POSSIBLE, SportsBroadcastRegionPolicy.listingConfidence("nl", "us"), "a mismatched national feed is unresolved")
        assertEquals(MatchConfidence.POSSIBLE, SportsBroadcastRegionPolicy.listingConfidence("nl", null), "unknown channel territory remains possible")
        assertEquals(MatchConfidence.POSSIBLE, SportsBroadcastRegionPolicy.listingConfidence(null, "us"), "unknown station territory remains possible")
    }

    @Test
    fun `the home broadcaster ranks up and out-of-country sinks`() {
        assertEquals(10, SportsBroadcastRegionPolicy.listingScoreDelta("us", "us"), "home broadcaster gets a boost")
        assertEquals(-60, SportsBroadcastRegionPolicy.listingScoreDelta("nl", "us"), "out-of-country feed is penalised below home channels")
        assertEquals(0, SportsBroadcastRegionPolicy.listingScoreDelta("nl", null), "no home country -> no nudge")
    }

    @Test
    fun `a home-country event feed is nudged up while out-of-country feeds stay put`() {
        // "US (ESPN+ 08) | Bills vs Steelers" for a US game leads; "CA-DAZN | Bills vs Steelers" does not.
        assertEquals(10, SportsBroadcastRegionPolicy.homeRegionBoost("US (ESPN+ 08) | Bills vs Steelers", "us"), "US feed of a US game leads")
        assertEquals(0, SportsBroadcastRegionPolicy.homeRegionBoost("CA-DAZN 10 | Bills vs Steelers", "us"), "a Canadian feed of the same game is not nudged")
        assertEquals(0, SportsBroadcastRegionPolicy.homeRegionBoost("ESPN+ 08 | Bills vs Steelers", null), "unknown home country -> no nudge")
    }
}
