package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonEventPolicyTest {
    private val tracker = "https://tracker.example/token/manifest.json"

    @Test
    fun `no consent means nothing is delivered`() {
        assertFalse(AddonEventPolicy.mayDeliver(tracker, emptySet()))
    }

    @Test
    fun `consent to one add-on never covers another`() {
        val consented = setOf("https://other.example/manifest.json")
        assertFalse(AddonEventPolicy.mayDeliver(tracker, consented))
        assertTrue(AddonEventPolicy.mayDeliver(tracker, consented + tracker))
    }

    @Test
    fun `a blank transport url is never delivered to`() {
        assertFalse(AddonEventPolicy.mayDeliver("", setOf("")))
    }

    @Test
    fun `own source and server items are never described to a tracker`() {
        val none: (String) -> Boolean = { false }
        assertFalse(AddonEventPolicy.mayDescribe("srv:abc", isServerId = { it.startsWith("srv:") }, isOwnContentId = none, isTrackingExcluded = none))
        assertFalse(AddonEventPolicy.mayDescribe("xtream:12", isServerId = none, isOwnContentId = { it.startsWith("xtream:") }, isTrackingExcluded = none))
        assertFalse(AddonEventPolicy.mayDescribe("tt1", isServerId = none, isOwnContentId = none, isTrackingExcluded = { it == "tt1" }))
        assertFalse(AddonEventPolicy.mayDescribe("", isServerId = none, isOwnContentId = none, isTrackingExcluded = none))
        assertTrue(AddonEventPolicy.mayDescribe("tt0898266", isServerId = none, isOwnContentId = none, isTrackingExcluded = none))
    }
}
