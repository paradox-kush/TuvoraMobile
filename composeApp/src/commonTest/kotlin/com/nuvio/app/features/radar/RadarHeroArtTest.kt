package com.nuvio.app.features.radar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** B234: TheSportsDB banners are composed away-left; the hero row is home-left, so team art contradicts it. */
class RadarHeroArtTest {
    @Test
    fun twoSidedFixtureDoesNotUseTeamComposedArtwork() {
        val f = RadarFixture(home = "Washington Capitals", away = "Florida Panthers", banner = "b.jpg", thumb = "t.jpg")
        assertNull(f.heroImage)
    }

    @Test
    fun singleEventFixtureKeepsBannerThenThumb() {
        assertEquals("b.jpg", RadarFixture(event = "Monaco GP", banner = "b.jpg", thumb = "t.jpg").heroImage)
        assertEquals("t.jpg", RadarFixture(event = "Monaco GP", banner = " ", thumb = "t.jpg").heroImage)
    }

    @Test
    fun blankSideCountsAsSingleEvent() {
        assertEquals("b.jpg", RadarFixture(home = "Someone", away = "", banner = "b.jpg").heroImage)
    }
}
