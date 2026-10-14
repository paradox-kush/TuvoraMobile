package com.nuvio.app.features.iptv.overlay

/**
 * UX36 + UX73: what a long-press on a live channel offers. One gesture, one meaning: the Live TV
 * guide and the browse hub show the same menu (favourite, then hide), where the guide used to hide
 * silently and the hub used to favourite. Pure, so it tests without the overlay store or the UI.
 */
internal object IptvChannelQuickActionsPolicy {

    enum class Action { ADD_FAVORITE, REMOVE_FAVORITE, HIDE }

    /** The channel a Hide, and the Undo after it, act on: its canon-v1 identity and its playlist. */
    data class HideTarget(val entityId: String, val playlistId: String?)

    /** Null when the channel has no durable identity here, so there is nothing a hide could key on. */
    fun hideTarget(entityId: String?, playlistId: String?): HideTarget? =
        entityId?.takeIf { it.isNotBlank() }?.let { HideTarget(it, playlistId) }

    /**
     * The hub's Favorites and Recent Channels rails are the viewer's own lists, which a hide does
     * not filter (UX37 keeps that open), so hiding from one would look like it did nothing: Hide is
     * offered only on provider and custom-group rows.
     */
    fun hubHideTarget(target: HideTarget?, inPersonalRail: Boolean): HideTarget? =
        if (inPersonalRail) null else target

    fun menu(isFavorite: Boolean, hideTarget: HideTarget?): List<Action> = buildList {
        add(if (isFavorite) Action.REMOVE_FAVORITE else Action.ADD_FAVORITE)
        if (hideTarget != null) add(Action.HIDE)
    }

    /**
     * B108: what a provider row of the hub shows, worked out every time the row is SHOWN from the
     * raw provider window the hub caches: hidden channels dropped, renames applied, pinned channels
     * marked ([withPinned]) and floated to the top of the row (stable, provider order otherwise).
     *
     * The hub used to bake renames and pins into the window when it was fetched, so an un-pin or an
     * un-rename (from the guide, Settings, or the website) left the loaded row stale until a
     * re-fetch; only hides were applied at display time (UX73/K9). Applying all of it here makes
     * every overlay edit, and its undo, show at once. Position/reorder stays deferred on this paged
     * surface, and the cache keeps the RAW provider order, so the next window's offset is still the
     * raw provider count the paging indexes.
     *
     * A card whose identity is unknown ([entityOf] null) is kept untouched. Returns [items] itself
     * when no card in it carries an overlay edit, so an untouched row keeps its identity.
     */
    fun <T> hubRow(
        items: List<T>,
        overlay: Map<String, ChannelOverlay>,
        entityOf: (T) -> String?,
        withName: (T, newName: String) -> T = { r, _ -> r },
        withPinned: (T) -> T = { it },
    ): List<T> {
        if (items.isEmpty() || overlay.isEmpty()) return items
        if (items.none { item -> entityOf(item)?.let { overlay[it] }?.isNoop == false }) return items
        // Identity is taken from the card BEFORE any rename, then: hidden dropped, pinned floated
        // (sortedBy is stable, so provider order holds within each group), rename + pin marker applied.
        return items
            .map { item -> item to entityOf(item)?.let { overlay[it] } }
            .filter { (_, edit) -> edit?.hidden != true }
            // F03: pinned channels float in their synced order (position; provider order breaks ties).
            .sortedWith(compareBy({ (_, edit) -> if (edit?.pinned == true) 0 else 1 }, { (_, edit) -> if (edit?.pinned == true) edit.position ?: Int.MAX_VALUE else 0 }))
            .map { (item, edit) ->
                val renamed = edit?.rename?.takeIf { it.isNotBlank() }?.let { withName(item, it) } ?: item
                if (edit?.pinned == true) withPinned(renamed) else renamed
            }
    }

    /**
     * Custom-group rows share channel hides and names with provider rows, but retain the group's
     * explicit member order: pins and provider positions must not reorder it. Apply to raw cached
     * cards at display time so clearing a rename or hide restores the provider value immediately.
     * Unknown identities stay untouched; an unchanged row is returned as is.
     */
    fun <T> visibleInHub(
        items: List<T>,
        overlay: Map<String, ChannelOverlay>,
        withName: (T, String) -> T = { row, _ -> row },
        entityOf: (T) -> String?,
    ): List<T> {
        if (items.isEmpty() || overlay.isEmpty()) return items
        var changed = false
        val shown = buildList {
            items.forEach { item ->
                val edit = entityOf(item)?.let { overlay[it] }
                if (edit?.hidden == true) {
                    changed = true
                } else {
                    val rename = edit?.rename?.takeIf { it.isNotBlank() }
                    if (rename != null) {
                        changed = true
                        add(withName(item, rename))
                    } else {
                        add(item)
                    }
                }
            }
        }
        return if (changed) shown else items
    }
}

/**
 * The overlay writes behind the long-press Hide and its Undo — explicit sets, never a toggle (the
 * guide used to toggle, so a hide that had already synced in would have un-hidden the channel).
 */
internal object IptvChannelQuickActions {
    fun hide(target: IptvChannelQuickActionsPolicy.HideTarget) =
        IptvOverlayRepository.setChannelHidden(target.entityId, target.playlistId, hidden = true)

    fun undoHide(target: IptvChannelQuickActionsPolicy.HideTarget) =
        IptvOverlayRepository.setChannelHidden(target.entityId, target.playlistId, hidden = false)
}
