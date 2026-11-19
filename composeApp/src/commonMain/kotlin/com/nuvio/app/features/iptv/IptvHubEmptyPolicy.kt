package com.nuvio.app.features.iptv

/**
 * What the hub's "Nothing here yet" state offers. A slow, half-loaded or wrongly parsed playlist looks
 * identical to a truly empty one, so the state must never be a dead end: Retry is always there (it
 * re-asks the same bounded load, a user action, never a loop or timer), and after a Retry that came
 * back empty the screen says so ("Checked just now, still empty") so the tap does not look broken.
 *
 * Pure and string-free so it tests without Compose or a portal; shared by the phone screen and the
 * Apple TV screen (through tvosCore).
 */
object IptvHubEmptyPolicy {
    data class View(
        /** Retry is the first (primary) action; "add playlist" stays as the second. */
        val offersRetry: Boolean,
        /** True once the viewer has retried and the answer is still empty. */
        val showsStillEmptyNote: Boolean,
    )

    /** What happened since the empty state was last decided. */
    enum class Event {
        /** The viewer tapped Retry on the empty state. */
        USER_RETRY,

        /** Anything else that (re)loads the list: a section or playlist switch, a sync, a refresh. */
        RELOAD,
    }

    fun view(userRetried: Boolean): View = View(offersRetry = true, showsStillEmptyNote = userRetried)

    /** Only the viewer's own Retry arms the note; any other reload starts from a plain empty state again. */
    fun retriedAfter(event: Event): Boolean = event == Event.USER_RETRY

    /**
     * Whether a hub with nothing to show is an EMPTY state (Retry + "add playlist") rather than a wait or a
     * failure: the load ended (Loaded or Empty, never Loading/Failed/Idle), no failure card is up, and no
     * row is left to render (a row that loaded with no items collapses, as on the phone). The phone's
     * `when` reaches the same answer by branch order; the Apple TV screen asks this directly.
     */
    fun showsEmptyState(state: XtreamHubUiState): Boolean =
        state.loadError == null &&
            (state.categoriesLoad is LoadStatus.Loaded || state.categoriesLoad is LoadStatus.Empty) &&
            state.categories.none { !(it.loaded && it.items.isEmpty()) }
}
