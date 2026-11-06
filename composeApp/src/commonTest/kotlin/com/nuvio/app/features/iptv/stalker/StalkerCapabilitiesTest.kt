package com.nuvio.app.features.iptv.stalker

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UX147: `get_modules` is a capability signal; unknown must keep today's behaviour (tab shown). */
class StalkerCapabilitiesTest {

    private fun modules(json: String) = StalkerCapabilities.seriesModuleDisabled(Json.parseToJsonElement(json))

    @Test
    fun `series listed under disabled modules is reported off`() {
        assertEquals(true, modules("""{"all_modules":["tv","vod","series"],"disabled_modules":["series","karaoke"]}"""))
        assertEquals(true, modules("""{"disabled_modules":[" Series "]}"""), "case and spaces are noise")
        assertEquals(true, modules("""{"restricted_modules":["series"],"disabled_modules":[]}"""))
        assertEquals(true, modules("""{"disabled_modules":{"series":1}}"""), "an object keyed by module name")
    }

    @Test
    fun `a payload that lists series as available is not disabled`() {
        assertEquals(false, modules("""{"all_modules":["tv","vod","series"],"disabled_modules":["karaoke"]}"""))
        assertEquals(false, modules("""{"all_modules":["tv","series"],"disabled_modules":[]}"""))
    }

    @Test
    fun `anything unrecognised is unknown so the tab stays`() {
        assertNull(modules("""{"all_modules":["tv","vod"]}"""), "series simply not mentioned says nothing")
        assertNull(modules("""{}"""))
        assertNull(modules("""[]"""))
        assertNull(modules("""null"""))
        assertNull(modules(""""disabled_modules""""))
        assertNull(modules("""{"disabled_modules":"series"}"""), "a bare string is not a list we decode")
        assertNull(StalkerCapabilities.seriesModuleDisabled(null))
    }

    @Test
    fun `the module flag alone never proves there are no series`() {
        // Ministra reports the module off yet serves series as is_series VOD: the vod probe decides.
        assertFalse(absent(modules = true, xcAnsweredEmpty = true, vodHasSeries = true))
        assertFalse(absent(modules = true, xcAnsweredEmpty = false), "probe not run")
    }

    @Test
    fun `portal's own nothing-for-section answer plus empty vod is absent`() {
        assertTrue(absent(modules = null, xcEmptySection = true))
        assertTrue(absent(modules = false, xcEmptySection = true))
        assertFalse(absent(modules = null, xcEmptySection = true, vodHasSeries = true))
    }

    @Test
    fun `an empty answer counts only when the portal also said the module is off`() {
        assertTrue(absent(modules = true, xcAnsweredEmpty = true))
        assertFalse(absent(modules = null, xcAnsweredEmpty = true))
        assertFalse(absent(modules = false, xcAnsweredEmpty = true))
    }

    @Test
    fun `a network or auth failure is never evidence`() {
        assertFalse(absent(modules = true, xcAnsweredEmpty = true, xcFailedOtherwise = true))
        assertFalse(absent(modules = true, xcEmptySection = true, xcFailedOtherwise = true))
    }

    private fun absent(
        modules: Boolean?,
        xcEmptySection: Boolean = false,
        xcAnsweredEmpty: Boolean = false,
        xcFailedOtherwise: Boolean = false,
        vodHasSeries: Boolean = false,
    ) = StalkerCapabilities.seriesAbsent(modules, xcEmptySection, xcAnsweredEmpty, xcFailedOtherwise, vodHasSeries)
}
