package com.nuvio.app.features.radar

/** Where the Sports tab currently is. Booleans, not objects, so the decision tests without the UI. */
internal data class SportsNavState(
    val browsing: Boolean = false,
    val browseCategoryOpen: Boolean = false,
    val leaguePageOpen: Boolean = false,
    val fixturesPageOpen: Boolean = false,
    val gameOpen: Boolean = false,
) {
    val atRoot: Boolean get() = !browsing && !browseCategoryOpen && !leaguePageOpen && !fixturesPageOpen && !gameOpen
}

/**
 * Navigation decisions of the phone Sports tab (UX ledger 5): re-selecting the tab always lands on the Sports root,
 * and Back never reaches the Exit-app dialog while a sub-page is open.
 */
internal object SportsNavPolicy {
    val root = SportsNavState()

    /** The user taps the Sports tab while already on it: back to the root, whatever page is open. */
    fun onReselect(state: SportsNavState): SportsNavState = root

    /**
     * Back inside the tab. Null = already at the root, so Back is not ours (the shell decides).
     * A league or fixtures page goes straight to the root (not to whichever list opened it); the browse
     * drill-down steps back one level at a time.
     */
    fun onBack(state: SportsNavState): SportsNavState? = when {
        state.atRoot -> null
        state.gameOpen -> state.copy(gameOpen = false)
        state.leaguePageOpen || state.fixturesPageOpen -> root
        state.browseCategoryOpen -> state.copy(browseCategoryOpen = false)
        else -> root
    }
}
