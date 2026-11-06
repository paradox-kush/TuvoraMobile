package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals

/** UX147: the hub's section chips — Series disappears only on the portal's positive "no series" evidence. */
class XtreamHubSectionsTest {

    private val all = listOf(XtreamHubSection.LIVE, XtreamHubSection.MOVIES, XtreamHubSection.SERIES)

    @Test
    fun `unknown keeps every enabled tab`() {
        assertEquals(all, XtreamHubSections.visible({ true }, seriesAbsent = false))
    }

    @Test
    fun `positive no-series evidence hides only the series tab`() {
        assertEquals(listOf(XtreamHubSection.LIVE, XtreamHubSection.MOVIES), XtreamHubSections.visible({ true }, seriesAbsent = true))
    }

    @Test
    fun `the series chip stays while Series is the current section and goes once the viewer leaves`() {
        assertEquals(all, XtreamHubSections.visible({ true }, seriesAbsent = true, current = XtreamHubSection.SERIES))
        assertEquals(
            listOf(XtreamHubSection.LIVE, XtreamHubSection.MOVIES),
            XtreamHubSections.visible({ true }, seriesAbsent = true, current = XtreamHubSection.MOVIES),
        )
        assertEquals(all, XtreamHubSections.visible({ true }, seriesAbsent = false, current = XtreamHubSection.MOVIES))
    }

    @Test
    fun `playlist content toggles still apply`() {
        assertEquals(listOf(XtreamHubSection.LIVE), XtreamHubSections.visible({ it == XtreamHubSection.LIVE }, seriesAbsent = false))
    }

    @Test
    fun `absence is tracked per playlist and can be taken back`() {
        val one = XtreamHubSections.withSeriesAbsent(emptySet(), "a", true)
        assertEquals(setOf("a"), one)
        assertEquals(setOf("a", "b"), XtreamHubSections.withSeriesAbsent(one, "b", true))
        assertEquals(setOf("b"), XtreamHubSections.withSeriesAbsent(setOf("a", "b"), "a", false))
    }
}
