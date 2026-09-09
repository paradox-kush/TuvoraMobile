package com.nuvio.app.features.settings

import com.nuvio.app.features.simkl.SimklConnectionMode
import com.nuvio.app.features.trakt.MoreLikeThisSourcePreference
import com.nuvio.app.features.trakt.TraktConnectionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingSettingsPresentationTest {
    @Test
    fun `provider connection modes map to matching card modes`() {
        assertEquals(
            TrackingConnectionCardMode.DISCONNECTED,
            TraktConnectionMode.DISCONNECTED.toTrackingConnectionCardMode(),
        )
        assertEquals(
            TrackingConnectionCardMode.AWAITING_APPROVAL,
            TraktConnectionMode.AWAITING_APPROVAL.toTrackingConnectionCardMode(),
        )
        assertEquals(
            TrackingConnectionCardMode.CONNECTED,
            TraktConnectionMode.CONNECTED.toTrackingConnectionCardMode(),
        )
        assertEquals(
            TrackingConnectionCardMode.DISCONNECTED,
            SimklConnectionMode.DISCONNECTED.toTrackingConnectionCardMode(),
        )
        assertEquals(
            TrackingConnectionCardMode.AWAITING_APPROVAL,
            SimklConnectionMode.AWAITING_APPROVAL.toTrackingConnectionCardMode(),
        )
        assertEquals(
            TrackingConnectionCardMode.CONNECTED,
            SimklConnectionMode.CONNECTED.toTrackingConnectionCardMode(),
        )
    }

    @Test
    fun `remote brands are available only while their provider is connected`() {
        assertTrue(isTrackingBrandAvailable(TrackingBrand.NUVIO, false, false))
        assertTrue(isTrackingBrandAvailable(TrackingBrand.TMDB, false, false))
        assertFalse(isTrackingBrandAvailable(TrackingBrand.TRAKT, false, true))
        assertTrue(isTrackingBrandAvailable(TrackingBrand.TRAKT, true, false))
        assertFalse(isTrackingBrandAvailable(TrackingBrand.SIMKL, true, false))
        assertTrue(isTrackingBrandAvailable(TrackingBrand.SIMKL, false, true))
    }

    @Test
    fun `Trakt recommendations fall back visually without rewriting preference`() {
        val stored = MoreLikeThisSourcePreference.TRAKT

        assertEquals(
            MoreLikeThisSourcePreference.TMDB,
            effectiveTrackingRecommendationsSource(stored, traktConnected = false),
        )
        assertEquals(
            MoreLikeThisSourcePreference.TRAKT,
            effectiveTrackingRecommendationsSource(stored, traktConnected = true),
        )
        assertEquals(MoreLikeThisSourcePreference.TRAKT, stored)
    }

    @Test
    fun `Trakt integration search rows hide when this build has no Trakt credentials`() {
        val traktRows = listOf(
            "trakt-authentication",
            "trakt-library-source",
            "trakt-watch-progress",
            "trakt-continue-watching-window",
            "trakt-comments",
            "trakt-more-like-this-source",
        )
        traktRows.forEach { key ->
            assertFalse(
                isTraktSearchEntryVisible(key, traktCredentialsConfigured = false),
                "$key should be hidden without Trakt credentials",
            )
            assertTrue(
                isTraktSearchEntryVisible(key, traktCredentialsConfigured = true),
                "$key should be shown when Trakt credentials exist",
            )
        }
    }

    @Test
    fun `non-Trakt-integration and Simkl search rows stay regardless of Trakt credentials`() {
        // The combined Tracking page, Simkl, the licenses attribution and MDBList rating rows are not
        // the Trakt integration card and must never be hidden by the credential gate.
        val alwaysVisible = listOf(
            "tracking",
            "simkl-authentication",
            "trakt-attribution",
            "mdb-trakt",
        )
        alwaysVisible.forEach { key ->
            assertTrue(
                isTraktSearchEntryVisible(key, traktCredentialsConfigured = false),
                "$key must stay visible even without Trakt credentials",
            )
        }
    }
}
