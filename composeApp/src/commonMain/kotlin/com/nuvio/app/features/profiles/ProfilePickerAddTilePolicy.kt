package com.nuvio.app.features.profiles

/**
 * Decides whether the "Who's watching?" picker shows the "+" add-profile tile.
 *
 * An account can legitimately have zero profiles (e.g. created by a provider setup link). The picker
 * must never be a dead end there, so the tile is always offered when empty; otherwise it only shows
 * in manage mode, and never once the profile cap is reached.
 */
internal object ProfilePickerAddTilePolicy {
    fun showAddTile(profileCount: Int, isEditMode: Boolean): Boolean {
        if (profileCount >= MAX_PROFILES) return false
        return profileCount == 0 || isEditMode
    }
}
