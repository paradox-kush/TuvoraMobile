package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContinueWatchingOrderPreferencesTest {
    @Test
    fun `a brand new install gets pick up where I left off`() {
        assertEquals(true, ContinueWatchingOrderDefaultPolicy.initialResumeFirst(null, false) { false })
    }

    @Test
    fun `an existing user keeps their order`() {
        assertNull(ContinueWatchingOrderDefaultPolicy.initialResumeFirst(null, false) { true })
        assertNull(ContinueWatchingOrderDefaultPolicy.initialResumeFirst(null, true) { false })
    }

    @Test
    fun `an explicit choice always wins`() {
        assertNull(ContinueWatchingOrderDefaultPolicy.initialResumeFirst(false, false) { false })
        assertNull(ContinueWatchingOrderDefaultPolicy.initialResumeFirst(true, false) { false })
    }

    @Test
    fun `the install probe is not asked when stored state already settles it`() {
        var asked = false
        ContinueWatchingOrderDefaultPolicy.initialResumeFirst(false, false) { asked = true; false }
        ContinueWatchingOrderDefaultPolicy.initialResumeFirst(null, true) { asked = true; false }
        assertFalse(asked)
    }

    @Test
    fun `an unknown sort mode from a newer build decodes tolerantly and keeps the other preferences`() {
        val prefs = decodeContinueWatchingPreferences(
            """{"isVisible":false,"sort_mode":"SOME_FUTURE_MODE","split_continue_watching_by_type":true,"brand_new_key":1}""",
        )
        assertNotNull(prefs)
        assertEquals(ContinueWatchingSortMode.DEFAULT, prefs.sortMode)
        assertFalse(prefs.isVisible)
        assertTrue(prefs.splitByType)
    }

    @Test
    fun `resume first is stored under its own key and leaves sort mode alone`() {
        val prefs = decodeContinueWatchingPreferences(
            """{"sort_mode":"STREAMING_STYLE","resume_first_order":true}""",
        )
        assertNotNull(prefs)
        assertEquals(ContinueWatchingSortMode.STREAMING_STYLE, prefs.sortMode)
        assertTrue(prefs.isResumeFirst)
        assertEquals(ContinueWatchingSortMode.DEFAULT, prefs.effectiveSortMode)
    }

    @Test
    fun `a payload from an older build has no resume first choice`() {
        val prefs = decodeContinueWatchingPreferences("""{"sort_mode":"SPLIT_UPCOMING"}""")
        assertNotNull(prefs)
        assertNull(prefs.resumeFirst)
        assertFalse(prefs.isResumeFirst)
        assertEquals(ContinueWatchingSortMode.SPLIT_UPCOMING, prefs.effectiveSortMode)
    }

    @Test
    fun `a payload that is not json decodes to null`() {
        assertNull(decodeContinueWatchingPreferences("not json"))
    }
}
