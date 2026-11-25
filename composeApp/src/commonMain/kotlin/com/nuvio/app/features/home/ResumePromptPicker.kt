package com.nuvio.app.features.home

import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Chooses the title the "Continue where you left off" launch prompt offers.
 *
 * The prompt follows the Continue Watching row: it offers the first item of the very list Home displays
 * ([row], already ordered by the viewer's choice, "Pick up where I left off" or most recent first, and already
 * filtered by the days cap, dismissed next-ups and hidden items), never a separately computed "last watched".
 *
 * Eligibility: an item the row shows but the prompt cannot honestly offer is skipped, namely a next-up episode
 * that has not aired yet ([isUpcoming]); the prompt used to offer only released next-ups. The first remaining
 * item wins. An empty row (or one holding only unaired items) yields null, so no prompt is shown.
 */
object ResumePromptPicker {
    fun pick(row: List<ContinueWatchingItem>, nowEpochMs: Long): ContinueWatchingItem? =
        row.firstOrNull { item -> !item.isUpcoming(nowEpochMs) }
}

/**
 * The ordered main Continue Watching row as Home last built it, published so the launch prompt reads the same
 * list instead of recomputing it. `null` means Home has not built a row yet (not "empty").
 */
object ContinueWatchingRowSnapshot {
    private val _items = MutableStateFlow<List<ContinueWatchingItem>?>(null)
    val items: StateFlow<List<ContinueWatchingItem>?> = _items.asStateFlow()

    fun publish(items: List<ContinueWatchingItem>) {
        _items.value = items
    }

    fun clear() {
        _items.value = null
    }
}
