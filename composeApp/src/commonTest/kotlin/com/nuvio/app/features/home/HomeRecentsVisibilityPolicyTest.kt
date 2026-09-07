package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeRecentsVisibilityPolicyTest {

    private fun preview(id: String, type: String) = MetaPreview(id = id, type = type, name = id)

    private val liveRecents = listOf(
        preview("bbc-one", "tv"),
        preview("espn", "tv"),
    )

    // VOD/movie/series recents live in a separate list (Continue Watching). They must never be
    // touched by this toggle; kept here to assert the policy never leaks or drops them.
    private val vodRecents = listOf(
        preview("tt0111161", "movie"),
        preview("tt0903747", "series"),
    )

    @Test
    fun `default true preserves current behaviour and shows live recents`() {
        assertEquals(
            liveRecents,
            HomeRecentsVisibilityPolicy.visibleLiveRecents(showLiveOnHome = true, liveRecents = liveRecents),
            "showLiveOnHome=true must return the live recents unchanged (current behaviour)",
        )
    }

    @Test
    fun `showLiveOnHome false excludes live recents`() {
        assertTrue(
            HomeRecentsVisibilityPolicy.visibleLiveRecents(showLiveOnHome = false, liveRecents = liveRecents).isEmpty(),
            "showLiveOnHome=false must hide every live-channel recent from the home screen",
        )
    }

    @Test
    fun `toggle only gates live recents and never affects vod recents`() {
        // The policy is scoped to the live list: whatever it returns is always a subset of the live
        // recents and can never contain a VOD entry, so Movies/Series recents survive either state.
        val vodIds = vodRecents.map { it.id }.toSet()
        listOf(true, false).forEach { show ->
            val result = HomeRecentsVisibilityPolicy.visibleLiveRecents(showLiveOnHome = show, liveRecents = liveRecents)
            assertTrue(
                result.all { it in liveRecents },
                "result must only ever contain live recents (show=$show)",
            )
            assertFalse(
                result.any { it.id in vodIds },
                "no VOD recent may leak into the live row (show=$show)",
            )
        }
        // The VOD list itself is a separate reference the policy never receives, hence unchanged.
        assertEquals(
            listOf("tt0111161", "tt0903747"),
            vodRecents.map { it.id },
            "VOD recents remain intact regardless of the live toggle",
        )
    }

    @Test
    fun `empty live recents stay empty in both states`() {
        assertTrue(
            HomeRecentsVisibilityPolicy.visibleLiveRecents(showLiveOnHome = true, liveRecents = emptyList<MetaPreview>()).isEmpty(),
            "no live recents to show when the source list is empty",
        )
        assertTrue(
            HomeRecentsVisibilityPolicy.visibleLiveRecents(showLiveOnHome = false, liveRecents = emptyList<MetaPreview>()).isEmpty(),
            "no live recents to show when hidden and the source list is empty",
        )
    }
}
