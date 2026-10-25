package com.tuvora.tvos.screens

import com.nuvio.app.features.details.MetaExternalRating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression for the Apple TV tester report (Discord 2026-10-01): MDBList ratings never appeared on
 * the details screen although the shared repository fetched them into MetaDetails.externalRatings.
 */
class TvRatingsPolicyTest {
    private val fetched = listOf(
        MetaExternalRating(source = "metacritic", value = 74.0),
        MetaExternalRating(source = "imdb", value = 7.84),
        MetaExternalRating(source = "tomatoes", value = 91.0, isCertified = true),
        MetaExternalRating(source = "tmdb", value = 78.4),
        MetaExternalRating(source = "audience", value = 55.0),
        MetaExternalRating(source = "trakt", value = 80.0),
        MetaExternalRating(source = "letterboxd", value = 3.9),
    )

    @Test
    fun `fetched MDBList ratings become hero chips in NuvioTV order`() {
        val chips = TvRatingsPolicy.chips(fetched, mdbListActive = true)
        assertEquals(listOf("trakt", "imdb", "tmdb", "letterboxd", "tomatoes", "audience", "metacritic"), chips.map { it.source })
        assertEquals(listOf("80", "7.8", "78", "3.9", "91%", "55%", "74"), chips.map { it.text })
    }

    @Test
    fun `Rotten Tomatoes and audience logos follow their status`() {
        val logos = TvRatingsPolicy.chips(fetched, mdbListActive = true).associate { it.source to it.logo }
        assertEquals("rating_rotten_tomatoes_certified", logos["tomatoes"])
        assertEquals("rating_audience_stale", logos["audience"])
        assertEquals("rating_imdb", logos["imdb"])
    }

    @Test
    fun `accessible rating source names are human readable`() {
        val names = TvRatingsPolicy.chips(fetched, mdbListActive = true).map { it.sourceName }
        assertEquals(listOf("Trakt", "IMDb", "TMDB", "Letterboxd", "Rotten Tomatoes", "Audience Score", "Metacritic"), names)
    }

    @Test
    fun `no chips while MDBList ratings are off`() {
        assertTrue(TvRatingsPolicy.chips(fetched, mdbListActive = false).isEmpty())
    }

    @Test
    fun `the plain IMDb line hides only when MDBList shows an IMDb score`() {
        assertFalse(TvRatingsPolicy.showsImdbLine("7.8", fetched, mdbListActive = true))
        assertTrue(TvRatingsPolicy.showsImdbLine("7.8", fetched.filter { it.source != "imdb" }, mdbListActive = true))
        assertTrue(TvRatingsPolicy.showsImdbLine("7.8", fetched, mdbListActive = false))
        assertFalse(TvRatingsPolicy.showsImdbLine(null, emptyList(), mdbListActive = false))
    }
}
