package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.internal.ChannelLabelPolicy

enum class TvLeagueFocus { FirstGame, Follow }

/** Where focus lands on a league page. Follow is a toggle, so it never takes first focus: a stray Select would unfollow. */
object TvLeagueFocusPolicy {
    fun initial(hasGames: Boolean): TvLeagueFocus = if (hasGames) TvLeagueFocus.FirstGame else TvLeagueFocus.Follow

    /** Up from the first row of cards goes to Follow explicitly; elsewhere the focus engine decides (null). */
    fun up(onFirstRow: Boolean): TvLeagueFocus? = if (onFirstRow) TvLeagueFocus.Follow else null
}

data class TvWatchLabel(val title: String, val subtitle: String?)

/** The one label for the game screen's primary watch action, whatever the sport; it depends only on channel state. */
object TvWatchActionPolicy {
    fun label(watching: String?, bestChannel: String?, matching: Boolean): TvWatchLabel = when {
        watching != null -> TvWatchLabel("Switch", "Now on ${ChannelLabelPolicy.clean(watching)}")
        bestChannel != null -> TvWatchLabel("Play", ChannelLabelPolicy.clean(bestChannel))
        matching -> TvWatchLabel("Finding channels…", null)
        else -> TvWatchLabel("Find channels", null)
    }
}
