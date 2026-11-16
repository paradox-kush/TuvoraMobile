package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UX389 (ported from the TV redesign): the hub restored a remembered tab without asking whether it had anything, so
 * a playlist with no series reopened on "Nothing here". Entry now lands on the first tab with content, Live first.
 */
class HubEntrySectionPolicyTest {
    private val all = setOf(XtreamHubSection.LIVE, XtreamHubSection.MOVIES, XtreamHubSection.SERIES)

    @Test
    fun `nothing remembered opens on Live TV`() {
        assertEquals(XtreamHubSection.LIVE, HubEntrySectionPolicy.initial(null, all, emptySet()), "Live TV is the first tab")
    }

    @Test
    fun `a remembered tab with content is kept`() {
        assertEquals(XtreamHubSection.MOVIES, HubEntrySectionPolicy.initial(XtreamHubSection.MOVIES, all, emptySet()), "sticky tab")
    }

    @Test
    fun `a remembered tab known to be empty falls to the first tab with content`() {
        assertEquals(
            XtreamHubSection.LIVE,
            HubEntrySectionPolicy.initial(XtreamHubSection.SERIES, all, setOf(XtreamHubSection.SERIES)),
            "a playlist with no series must not reopen on Series",
        )
    }

    @Test
    fun `a disabled remembered tab is skipped`() {
        assertEquals(
            XtreamHubSection.MOVIES,
            HubEntrySectionPolicy.initial(XtreamHubSection.LIVE, setOf(XtreamHubSection.MOVIES, XtreamHubSection.SERIES), emptySet()),
            "Live is disabled, Movies is the first with content",
        )
    }

    @Test
    fun `when every tab is empty the first enabled tab is used`() {
        assertEquals(XtreamHubSection.LIVE, HubEntrySectionPolicy.initial(null, all, all), "nothing better exists")
    }

    @Test
    fun `an entry tab that proves empty advances to the next untried tab with Live first`() {
        assertEquals(XtreamHubSection.LIVE, HubEntrySectionPolicy.advanceFrom(XtreamHubSection.MOVIES, all, setOf(XtreamHubSection.MOVIES)))
        assertEquals(
            XtreamHubSection.SERIES,
            HubEntrySectionPolicy.advanceFrom(
                XtreamHubSection.MOVIES,
                setOf(XtreamHubSection.MOVIES, XtreamHubSection.SERIES),
                setOf(XtreamHubSection.MOVIES),
            ),
        )
    }

    @Test
    fun `advancing stops when every enabled tab was tried`() {
        assertNull(HubEntrySectionPolicy.advanceFrom(XtreamHubSection.SERIES, all, all), "no loop")
    }

    @Test
    fun `a fresh hub state starts on Live TV not Movies`() {
        assertEquals(XtreamHubSection.LIVE, XtreamHubUiState().section, "the default section was MOVIES on TV (UX389)")
    }

    // UX5 (sim pass 2026-11-16): the phone hub reopened on a bare search prompt after the viewer left it from
    // there. Like an empty tab, a search with nothing to show is not where the hub reopens.
    @Test
    fun `a bare search is not kept for the next visit`() {
        assertFalse(HubEntrySectionPolicy.keepsSearchOnLeave(""), "an empty search box closes on leave")
        assertFalse(HubEntrySectionPolicy.keepsSearchOnLeave("  "), "blank is bare")
        assertFalse(HubEntrySectionPolicy.keepsSearchOnLeave(","), "one character never searched anything (p3-05)")
    }

    @Test
    fun `a search with a real query is kept for the next visit`() {
        assertTrue(HubEntrySectionPolicy.keepsSearchOnLeave("news"), "the viewer's results survive a tab switch")
    }
}
