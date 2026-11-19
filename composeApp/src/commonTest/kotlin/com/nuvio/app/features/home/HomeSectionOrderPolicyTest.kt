package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeSectionOrderPolicyTest {
    private val noHistory = HomeSectionContext(hasWatchHistory = false)
    private val history = HomeSectionContext(hasWatchHistory = true)
    private val legacy = HomeSectionLegacyToggles()

    private fun ids(entries: List<HomeSectionEntry>) = entries.map { it.id }

    private fun custom(vararg entries: Pair<String, Boolean>) =
        HomeSectionsConfig(customized = true, sections = entries.map { HomeSectionEntry(it.first, it.second) })

    @Test
    fun `default order without history is today's order`() {
        val expected = listOf("hero", "new_episodes", "sports", "live_recents", "continue_watching", "upcoming", "catalogs")
        assertEquals(expected, HomeSectionOrderPolicy.visibleIds(null, noHistory, legacy), "no stored value")
        assertEquals(
            expected,
            HomeSectionOrderPolicy.visibleIds(HomeSectionsConfig(customized = false), noHistory, legacy),
            "customized false",
        )
    }

    @Test
    fun `default order with history puts continue watching and live first`() {
        assertEquals(
            listOf("continue_watching", "live_recents", "hero", "new_episodes", "sports", "upcoming", "catalogs"),
            HomeSectionOrderPolicy.visibleIds(null, history, legacy),
        )
    }

    @Test
    fun `customized false ignores a stale stored list`() {
        val stale = HomeSectionsConfig(customized = false, sections = listOf(HomeSectionEntry("catalogs"), HomeSectionEntry("hero")))
        assertEquals(
            HomeSectionOrderPolicy.visibleIds(null, history, legacy),
            HomeSectionOrderPolicy.visibleIds(stale, history, legacy),
        )
    }

    @Test
    fun `customised order is used as stored even with history`() {
        val stored = custom("catalogs" to true, "hero" to true, "continue_watching" to true)
        assertEquals(
            listOf("live_recents", "new_episodes", "sports", "upcoming", "catalogs", "hero", "continue_watching"),
            ids(HomeSectionOrderPolicy.resolve(stored, history, legacy)),
            "stored order kept; sections the list predates are placed before the catalogs anchor",
        )
    }

    @Test
    fun `sections missing from a customised list land before the catalogs and are shown`() {
        val stored = custom("sports" to true, "catalogs" to true, "hero" to true)
        val resolved = HomeSectionOrderPolicy.resolve(stored, noHistory, legacy)
        assertEquals(
            listOf("sports", "new_episodes", "live_recents", "continue_watching", "upcoming", "catalogs", "hero"),
            ids(resolved),
        )
        assertTrue(resolved.all { it.enabled }, "a new section defaults to shown")
    }

    @Test
    fun `hidden sections are listed but not visible`() {
        val stored = custom(
            "hero" to false, "new_episodes" to true, "sports" to false, "live_recents" to true,
            "continue_watching" to true, "upcoming" to false, "catalogs" to true,
        )
        assertEquals(7, HomeSectionOrderPolicy.resolve(stored, noHistory, legacy).size, "the settings list shows all")
        assertEquals(
            listOf("new_episodes", "live_recents", "continue_watching", "catalogs"),
            HomeSectionOrderPolicy.visibleIds(stored, noHistory, legacy),
        )
    }

    @Test
    fun `unknown ids are skipped on render and kept in place on write`() {
        val stored = custom(
            "hero" to true, "future_row" to true, "sports" to true, "mystery" to false, "catalogs" to true,
            "new_episodes" to true, "live_recents" to true, "continue_watching" to true, "upcoming" to true,
        )
        assertTrue("future_row" !in ids(HomeSectionOrderPolicy.resolve(stored, noHistory, legacy)), "skipped on render")

        val moved = HomeSectionOrderPolicy.move(stored, noHistory, legacy, id = "catalogs", toIndex = 0)
        assertEquals(
            listOf("catalogs", "future_row", "hero", "mystery", "sports", "new_episodes", "live_recents", "continue_watching", "upcoming"),
            moved.sections.map { it.id },
            "unknown ids keep their slots, the rest reorder around them",
        )
        assertEquals(false, moved.sections.first { it.id == "mystery" }.enabled, "unknown flags are preserved too")
        assertEquals(
            listOf("catalogs", "hero", "sports", "new_episodes", "live_recents", "continue_watching", "upcoming"),
            ids(HomeSectionOrderPolicy.resolve(moved, noHistory, legacy)),
        )
    }

    @Test
    fun `setting enabled keeps unknown ids and the rest of the list`() {
        val stored = custom("hero" to true, "future_row" to true, "catalogs" to true)
        val updated = HomeSectionOrderPolicy.setEnabled(stored, noHistory, legacy, "hero", false)
        assertEquals(false, updated.sections.first { it.id == "hero" }.enabled)
        assertEquals(true, updated.sections.any { it.id == "future_row" })
        assertEquals(true, updated.customized)
    }

    @Test
    fun `ids this device cannot render are skipped and kept`() {
        val tv = HomeSectionContext(
            hasWatchHistory = false,
            supportedIds = setOf("hero", "continue_watching", "live_recents", "new_episodes", "catalogs"),
        )
        val stored = custom(
            "sports" to true, "hero" to true, "upcoming" to true, "catalogs" to true,
            "new_episodes" to true, "live_recents" to true, "continue_watching" to true,
        )
        assertEquals(
            listOf("hero", "catalogs", "new_episodes", "live_recents", "continue_watching"),
            HomeSectionOrderPolicy.visibleIds(stored, tv, legacy),
        )
        val moved = HomeSectionOrderPolicy.move(stored, tv, legacy, id = "continue_watching", toIndex = 0)
        assertEquals(
            listOf("sports", "continue_watching", "upcoming", "hero", "catalogs", "new_episodes", "live_recents"),
            moved.sections.map { it.id },
            "unsupported sports and upcoming keep their slots",
        )
    }

    @Test
    fun `an unsupported id is not part of the default either`() {
        val tv = HomeSectionContext(hasWatchHistory = true, supportedIds = setOf("hero", "continue_watching", "catalogs"))
        assertEquals(listOf("continue_watching", "hero", "catalogs"), HomeSectionOrderPolicy.visibleIds(null, tv, legacy))
    }

    @Test
    fun `reset writes customized false explicitly`() {
        val reset = HomeSectionOrderPolicy.reset()
        assertEquals(false, reset.customized)
        assertEquals(emptyList(), reset.sections)
        val stored = custom("catalogs" to true, "hero" to false)
        assertEquals(
            HomeSectionOrderPolicy.visibleIds(null, history, legacy),
            HomeSectionOrderPolicy.visibleIds(reset, history, legacy),
            "after a reset the device default applies again",
        )
        assertEquals(true, stored.customized, "reset builds a new value, it does not edit the old one")
    }

    @Test
    fun `hero stays first and cannot be moved on a device that pins it`() {
        val tv = HomeSectionContext(hasWatchHistory = true, heroPinnedFirst = true)
        assertEquals(
            listOf("hero", "continue_watching", "live_recents", "new_episodes", "sports", "upcoming", "catalogs"),
            HomeSectionOrderPolicy.visibleIds(null, tv, legacy),
            "even the returning-user default keeps the hero on top",
        )
        val stored = custom("catalogs" to true, "hero" to true, "continue_watching" to true)
        assertEquals("hero", HomeSectionOrderPolicy.visibleIds(stored, tv, legacy).first(), "a list written elsewhere is pinned on render")

        val moveHero = HomeSectionOrderPolicy.move(stored, tv, legacy, id = "hero", toIndex = 2)
        assertEquals(
            ids(HomeSectionOrderPolicy.resolve(stored, tv, legacy)),
            ids(HomeSectionOrderPolicy.resolve(moveHero, tv, legacy)),
            "hero move is ignored",
        )

        val moveOther = HomeSectionOrderPolicy.move(stored, tv, legacy, id = "continue_watching", toIndex = 0)
        assertEquals("hero", HomeSectionOrderPolicy.visibleIds(moveOther, tv, legacy).first(), "nothing goes above the hero")
    }

    @Test
    fun `hero can be hidden on a device that pins it`() {
        val tv = HomeSectionContext(hasWatchHistory = false, heroPinnedFirst = true)
        val hidden = HomeSectionOrderPolicy.setEnabled(null, tv, legacy, "hero", false)
        assertTrue("hero" !in HomeSectionOrderPolicy.visibleIds(hidden, tv, legacy))
    }

    @Test
    fun `an uncustomised list derives visibility from the standalone switches`() {
        val off = HomeSectionLegacyToggles(heroEnabled = false, continueWatchingVisible = false, showLiveOnHome = true)
        assertEquals(
            listOf("new_episodes", "sports", "catalogs"),
            HomeSectionOrderPolicy.visibleIds(null, noHistory, off),
            "hero, continue watching, upcoming and live (it lives inside Continue Watching) are off",
        )
        val liveOff = HomeSectionLegacyToggles(showLiveOnHome = false)
        assertTrue("live_recents" !in HomeSectionOrderPolicy.visibleIds(null, noHistory, liveOff))
        assertTrue("continue_watching" in HomeSectionOrderPolicy.visibleIds(null, noHistory, liveOff))
    }

    @Test
    fun `moving from the default writes the full effective order as customised`() {
        val written = HomeSectionOrderPolicy.move(null, history, legacy, id = "catalogs", toIndex = 0)
        assertTrue(written.customized)
        assertEquals(
            listOf("catalogs", "continue_watching", "live_recents", "hero", "new_episodes", "sports", "upcoming"),
            written.sections.map { it.id },
        )
        assertTrue(written.sections.all { it.enabled })
    }

    @Test
    fun `move clamps the target index and ignores an unknown id`() {
        val stored = custom("hero" to true, "catalogs" to true)
        val ordered = HomeSectionOrderPolicy.resolve(stored, noHistory, legacy).map { it.id }
        val clamped = HomeSectionOrderPolicy.move(stored, noHistory, legacy, id = "hero", toIndex = 99)
        assertEquals(ordered.drop(1) + "hero", ids(HomeSectionOrderPolicy.resolve(clamped, noHistory, legacy)))
        val untouched = HomeSectionOrderPolicy.move(stored, noHistory, legacy, id = "nope", toIndex = 0)
        assertEquals(ordered, ids(HomeSectionOrderPolicy.resolve(untouched, noHistory, legacy)))
    }

    @Test
    fun `a duplicate id is read once`() {
        val stored = custom("hero" to true, "catalogs" to true, "hero" to false)
        assertEquals(1, HomeSectionOrderPolicy.resolve(stored, noHistory, legacy).count { it.id == "hero" })
        assertEquals(true, HomeSectionOrderPolicy.resolve(stored, noHistory, legacy).first { it.id == "hero" }.enabled, "first wins")
        val moved = HomeSectionOrderPolicy.move(stored, noHistory, legacy, id = "catalogs", toIndex = 0)
        assertEquals(1, moved.sections.count { it.id == "hero" })
    }

    // ---- the standalone toggles stay in step with the list -------------------------------------------

    @Test
    fun `a customised list drives the standalone switches`() {
        val stored = custom("hero" to false, "continue_watching" to true, "live_recents" to false, "catalogs" to true)
        val toggles = assertNotNull(HomeSectionOrderPolicy.togglesFrom(stored, legacy))
        assertEquals(false, toggles.heroEnabled)
        assertEquals(true, toggles.continueWatchingVisible)
        assertEquals(false, toggles.showLiveOnHome)
    }

    @Test
    fun `an uncustomised list leaves the standalone switches alone`() {
        assertNull(HomeSectionOrderPolicy.togglesFrom(null, legacy))
        assertNull(HomeSectionOrderPolicy.togglesFrom(HomeSectionOrderPolicy.reset(), legacy))
    }

    @Test
    fun `a missing section keeps the current standalone switch`() {
        val stored = custom("catalogs" to true)
        val current = HomeSectionLegacyToggles(heroEnabled = false)
        assertEquals(false, HomeSectionOrderPolicy.togglesFrom(stored, current)?.heroEnabled)
    }

    @Test
    fun `flipping a standalone switch updates a customised list`() {
        val stored = custom("hero" to true, "continue_watching" to true, "catalogs" to true)
        val updated = assertNotNull(
            HomeSectionOrderPolicy.mirrorToggle(stored, noHistory, legacy, "continue_watching", false),
        )
        assertEquals(false, updated.sections.first { it.id == "continue_watching" }.enabled)
        assertEquals(true, updated.sections.first { it.id == "hero" }.enabled)
        assertEquals(
            false,
            HomeSectionOrderPolicy.togglesFrom(updated, legacy)?.continueWatchingVisible,
            "and the switch reads back the same value: they never disagree",
        )
    }

    @Test
    fun `flipping a standalone switch writes nothing when nothing is customised or it already agrees`() {
        assertNull(HomeSectionOrderPolicy.mirrorToggle(null, noHistory, legacy, "hero", false), "uncustomised derives instead")
        val stored = custom("hero" to false, "catalogs" to true)
        assertNull(HomeSectionOrderPolicy.mirrorToggle(stored, noHistory, legacy, "hero", false), "already false")
        assertNull(HomeSectionOrderPolicy.mirrorToggle(stored, noHistory, legacy, "continue_watching", false), "id not in list")
    }

    @Test
    fun `hiding a section in the list then reading the switches agree for every switch`() {
        var config: HomeSectionsConfig? = null
        for ((id, read) in listOf<Pair<String, (HomeSectionLegacyToggles) -> Boolean>>(
            "hero" to { it.heroEnabled },
            "continue_watching" to { it.continueWatchingVisible },
            "live_recents" to { it.showLiveOnHome },
        )) {
            config = HomeSectionOrderPolicy.setEnabled(config, noHistory, legacy, id, false)
            assertEquals(false, read(assertNotNull(HomeSectionOrderPolicy.togglesFrom(config, legacy))), "$id hidden")
            config = HomeSectionOrderPolicy.setEnabled(config, noHistory, legacy, id, true)
            assertEquals(true, read(assertNotNull(HomeSectionOrderPolicy.togglesFrom(config, legacy))), "$id shown")
        }
    }

    @Test
    fun `a remembered history answer gives the returning-user order on the first frame`() {
        // The repository persists the last known answer per profile; before Continue Watching loads it is
        // still true here, so Home never starts in the first-run order and then reorders.
        val firstFrame = HomeSectionContext(hasWatchHistory = true)
        assertEquals("continue_watching", HomeSectionOrderPolicy.visibleIds(null, firstFrame, legacy).first())
    }

    @Test
    fun `live recents follow continue watching only when nothing is customised`() {
        val cwOff = HomeSectionLegacyToggles(continueWatchingVisible = false, showLiveOnHome = true)
        assertTrue("live_recents" !in HomeSectionOrderPolicy.visibleIds(null, noHistory, cwOff), "uncustomised: coupled")
        val stored = custom("continue_watching" to false, "live_recents" to true, "catalogs" to true)
        val visible = HomeSectionOrderPolicy.visibleIds(stored, noHistory, cwOff)
        assertTrue("live_recents" in visible, "customised: own flag")
        assertTrue("continue_watching" !in visible, "customised: own flag")
        val liveOffOnly = custom("continue_watching" to true, "live_recents" to false, "catalogs" to true)
        assertTrue("continue_watching" in HomeSectionOrderPolicy.visibleIds(liveOffOnly, noHistory, legacy))
    }
}
