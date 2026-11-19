package com.nuvio.app.features.home

import kotlinx.serialization.Serializable

/**
 * Ids of the Home sections a person can reorder and hide. The same ids are written by every Tuvora app
 * (phone, iPhone, Apple TV, Android TV, Desktop) under `home_sections` in the shared Home settings blob,
 * so they are a wire format: never rename one. [CATALOGS] is the anchor where the ordered catalog and
 * collection rows render; their own order stays in the Home catalog settings `items` list.
 */
object HomeSectionIds {
    const val HERO = "hero"
    const val CONTINUE_WATCHING = "continue_watching"
    const val LIVE_RECENTS = "live_recents"
    const val NEW_EPISODES = "new_episodes"
    const val SPORTS = "sports"
    const val UPCOMING = "upcoming"
    const val CATALOGS = "catalogs"

    /** Today's order: what a person who never customised Home and has no watch history sees. */
    val DEFAULT_ORDER: List<String> =
        listOf(HERO, NEW_EPISODES, SPORTS, LIVE_RECENTS, CONTINUE_WATCHING, UPCOMING, CATALOGS)

    /** Shown first for a returning person (anyone with Continue Watching items or recent live channels). */
    val HISTORY_FIRST: List<String> = listOf(CONTINUE_WATCHING, LIVE_RECENTS)

    /** Every id this phone, iPhone and Desktop build can render. */
    val ALL: Set<String> = DEFAULT_ORDER.toSet()
}

@Serializable
data class HomeSectionEntry(
    val id: String,
    val enabled: Boolean = true,
)

/**
 * The stored `home_sections` value. [customized] false (or no value at all) means the person never
 * arranged Home: the device default applies and [sections] is ignored. When true, [sections] is the full
 * list, and its [HomeSectionEntry.enabled] flags are the source of truth for visibility.
 */
@Serializable
data class HomeSectionsConfig(
    val customized: Boolean = false,
    val sections: List<HomeSectionEntry> = emptyList(),
)

/** The standalone switches that existed before `home_sections` and still have to agree with it. */
data class HomeSectionLegacyToggles(
    val heroEnabled: Boolean = true,
    val continueWatchingVisible: Boolean = true,
    val showLiveOnHome: Boolean = true,
)

/** What this device and profile bring to the decision. */
data class HomeSectionContext(
    val hasWatchHistory: Boolean,
    /** Ids this device can render; any other id is skipped silently on render and kept on write. */
    val supportedIds: Set<String> = HomeSectionIds.ALL,
    /** Apple TV and Android TV keep the hero at the top (it is the Home backdrop): on/off only. */
    val heroPinnedFirst: Boolean = false,
)

/**
 * Decides which Home sections render, in which order, and how a change is written back. Pure: no I/O,
 * no Compose, so it tests without a device.
 *
 * Wire rules (shared with every Tuvora app, see [HomeSectionIds]):
 *  - no value or `customized: false` -> device default order; with watch history `continue_watching` and
 *    `live_recents` come first, the rest in today's order;
 *  - `customized: true` -> the stored order and flags, never reinterpreted;
 *  - unknown ids stay where they are on write and are skipped on render; so are ids this device cannot render.
 */
object HomeSectionOrderPolicy {

    /**
     * Sections this device can render, in render order, each with its visibility. Hidden ones are
     * included (the settings list shows them switched off); [visibleIds] is what Home draws.
     */
    fun resolve(
        stored: HomeSectionsConfig?,
        context: HomeSectionContext,
        legacy: HomeSectionLegacyToggles = HomeSectionLegacyToggles(),
    ): List<HomeSectionEntry> {
        val ordered = if (stored?.customized == true) {
            customisedOrder(stored, context, legacy)
        } else {
            defaultOrder(context.hasWatchHistory)
                .filter { it in context.supportedIds }
                .map { HomeSectionEntry(it, defaultEnabled(it, legacy)) }
        }
        return pinHero(ordered, context)
    }

    fun visibleIds(
        stored: HomeSectionsConfig?,
        context: HomeSectionContext,
        legacy: HomeSectionLegacyToggles = HomeSectionLegacyToggles(),
    ): List<String> = resolve(stored, context, legacy).filter { it.enabled }.map { it.id }

    fun defaultOrder(hasWatchHistory: Boolean): List<String> =
        if (hasWatchHistory) {
            HomeSectionIds.HISTORY_FIRST + HomeSectionIds.DEFAULT_ORDER.filterNot { it in HomeSectionIds.HISTORY_FIRST }
        } else {
            HomeSectionIds.DEFAULT_ORDER
        }

    /** Moves [id] to [toIndex] within the sections this device renders. Returns a customised config. */
    fun move(
        stored: HomeSectionsConfig?,
        context: HomeSectionContext,
        legacy: HomeSectionLegacyToggles,
        id: String,
        toIndex: Int,
    ): HomeSectionsConfig {
        val full = materialize(stored, context, legacy)
        val shown = resolve(full, context, legacy).map { it.id }
        val from = shown.indexOf(id)
        if (from == -1) return full
        if (context.heroPinnedFirst && id == HomeSectionIds.HERO) return full
        val reordered = shown.toMutableList().apply { add(toIndex.coerceIn(0, lastIndex), removeAt(from)) }
        return full.copy(sections = placeInSlots(full.sections, shown.toSet(), reordered))
    }

    fun setEnabled(
        stored: HomeSectionsConfig?,
        context: HomeSectionContext,
        legacy: HomeSectionLegacyToggles,
        id: String,
        enabled: Boolean,
    ): HomeSectionsConfig {
        val full = materialize(stored, context, legacy)
        if (full.sections.none { it.id == id }) return full
        return full.copy(sections = full.sections.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /** "Reset to default": `customized: false` is written explicitly because a merge on push cannot delete a key. */
    fun reset(): HomeSectionsConfig = HomeSectionsConfig(customized = false, sections = emptyList())

    /**
     * The standalone switches a customised list implies, or null when nothing is customised. Applied to
     * the local toggles whenever such a list arrives, so two switches never disagree.
     */
    fun togglesFrom(stored: HomeSectionsConfig?, fallback: HomeSectionLegacyToggles): HomeSectionLegacyToggles? {
        if (stored?.customized != true) return null
        fun flag(id: String, current: Boolean) =
            stored.sections.firstOrNull { it.id == id }?.enabled ?: current
        return HomeSectionLegacyToggles(
            heroEnabled = flag(HomeSectionIds.HERO, fallback.heroEnabled),
            continueWatchingVisible = flag(HomeSectionIds.CONTINUE_WATCHING, fallback.continueWatchingVisible),
            showLiveOnHome = flag(HomeSectionIds.LIVE_RECENTS, fallback.showLiveOnHome),
        )
    }

    /**
     * A standalone switch changed. When the list is customised its flag follows (a full list is written),
     * and when it is not, nothing is written: the derived default already reads the switch.
     * Returns null when no write is needed.
     */
    fun mirrorToggle(
        stored: HomeSectionsConfig?,
        context: HomeSectionContext,
        legacy: HomeSectionLegacyToggles,
        id: String,
        enabled: Boolean,
    ): HomeSectionsConfig? {
        if (stored?.customized != true) return null
        val entry = stored.sections.firstOrNull { it.id == id } ?: return null
        if (entry.enabled == enabled) return null
        return setEnabled(stored, context, legacy, id, enabled)
    }

    /**
     * The full list a write starts from: the stored list when customised (duplicates dropped, unknown ids
     * kept, sections this build can render but the list predates inserted), else today's effective order.
     */
    private fun materialize(
        stored: HomeSectionsConfig?,
        context: HomeSectionContext,
        legacy: HomeSectionLegacyToggles,
    ): HomeSectionsConfig {
        if (stored?.customized != true) {
            return HomeSectionsConfig(customized = true, sections = resolve(null, context, legacy))
        }
        val seen = HashSet<String>()
        return stored.copy(sections = withMissing(stored.sections.filter { seen.add(it.id) }, context, legacy))
    }

    private fun customisedOrder(
        stored: HomeSectionsConfig,
        context: HomeSectionContext,
        legacy: HomeSectionLegacyToggles,
    ): List<HomeSectionEntry> {
        val seen = HashSet<String>()
        val unique = stored.sections.filter { seen.add(it.id) }
        return withMissing(unique, context, legacy).filter { it.id in context.supportedIds }
    }

    /** A section this build renders but [sections] predates: placed before the catalogs, with its default visibility. */
    private fun withMissing(
        sections: List<HomeSectionEntry>,
        context: HomeSectionContext,
        legacy: HomeSectionLegacyToggles,
    ): List<HomeSectionEntry> {
        val present = sections.mapTo(HashSet()) { it.id }
        val missing = defaultOrder(context.hasWatchHistory)
            .filter { it in context.supportedIds && it !in present }
            .map { HomeSectionEntry(it, defaultEnabled(it, legacy)) }
        if (missing.isEmpty()) return sections
        val anchor = sections.indexOfFirst { it.id == HomeSectionIds.CATALOGS }
        return if (anchor == -1) sections + missing else sections.take(anchor) + missing + sections.drop(anchor)
    }

    /** Visibility of a section nobody has switched yet: the standalone switches, or shown. */
    private fun defaultEnabled(id: String, legacy: HomeSectionLegacyToggles): Boolean = when (id) {
        HomeSectionIds.HERO -> legacy.heroEnabled
        HomeSectionIds.CONTINUE_WATCHING, HomeSectionIds.UPCOMING -> legacy.continueWatchingVisible
        // Live recents have always lived inside Continue Watching: off there means off here too.
        HomeSectionIds.LIVE_RECENTS -> legacy.continueWatchingVisible && legacy.showLiveOnHome
        else -> true
    }

    private fun pinHero(list: List<HomeSectionEntry>, context: HomeSectionContext): List<HomeSectionEntry> {
        if (!context.heroPinnedFirst) return list
        val hero = list.firstOrNull { it.id == HomeSectionIds.HERO } ?: return list
        return listOf(hero) + list.filterNot { it.id == HomeSectionIds.HERO }
    }

    /**
     * Puts [reordered] (the renderable ids in their new order) back into the slots the renderable ids held
     * in [slots], so unknown and unsupported ids keep their place in the list.
     */
    private fun placeInSlots(
        slots: List<HomeSectionEntry>,
        renderable: Set<String>,
        reordered: List<String>,
    ): List<HomeSectionEntry> {
        val byId = slots.associateBy { it.id }
        val queue = reordered.iterator()
        return slots.map { entry -> if (entry.id in renderable && queue.hasNext()) byId.getValue(queue.next()) else entry }
    }
}
