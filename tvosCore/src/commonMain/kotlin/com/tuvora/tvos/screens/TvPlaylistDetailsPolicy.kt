package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.ConnectionsDisplay
import com.nuvio.app.features.iptv.DetailsAction
import com.nuvio.app.features.iptv.DetailsCounts
import com.nuvio.app.features.iptv.DetailsGroupKind
import com.nuvio.app.features.iptv.ExpiryDisplay
import com.nuvio.app.features.iptv.IptvManualRefreshPolicy
import com.nuvio.app.features.iptv.LoadStatus
import com.nuvio.app.features.iptv.ManualRefreshResult
import com.nuvio.app.features.iptv.ManualRefreshUiState
import com.nuvio.app.features.iptv.ManagedDetailsModel
import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_FILE
import com.nuvio.app.features.iptv.SOURCE_TYPE_XTREAM
import com.nuvio.app.features.iptv.XtreamAccount

/** Everything a card on the Apple TV playlist details page can be. */
enum class TvDetailCard {
    CONTACT, CONTENT_CATEGORIES, HIDDEN, TOGGLE_ENABLED, REFRESH, EDIT_SERVER, REMATCH,
    CATCHUP_CONTAINER, CATCHUP_TIME, GUIDE_OFFSET, DETACH, REMOVE,
}

/** One labelled horizontal shelf of cards. [title] is shown as written (the provider's name in capitals, YOUR LIBRARY, REMOVE). */
data class TvDetailShelf(val kind: DetailsGroupKind, val title: String, val cards: List<TvDetailCard>)

/** A "label: value" line for the read-only facts on the left. */
data class TvDetailsCount(val label: String, val value: String)

/**
 * The Apple TV playlist details page (build plan 3.6): which labelled shelves exist for a playlist and
 * what sits on them, plus the words for the read-only facts. The shape comes from the shared
 * [ManagedDetailsModel] (so phone, desktop, TV and Apple TV agree on what exists); this adds the Apple
 * TV's own cards and order. Pure: no UI, no I/O.
 */
object TvPlaylistDetailsPolicy {

    /**
     * UX179: what the "Refresh now" card says. Always what happened: running, updated N ago, or why nothing happened — and
     * a failure says so. A stall deadline is "outcome unknown", not "failed": the rebuild keeps going behind the page.
     */
    internal fun refreshHint(state: ManualRefreshUiState): String {
        val load = state.load
        return when {
            state.refreshing -> "Refreshing\u2026"
            load is LoadStatus.Failed && load.timedOut -> "Still working in the background. Check back in a few minutes."
            load is LoadStatus.Failed ->
                if (state.result == ManualRefreshResult.PARTIAL) "Only part updated. Press to try again." else "Couldn\u2019t refresh. Press to try again."
            state.result == ManualRefreshResult.COOLDOWN -> "Please wait a minute before refreshing again."
            state.result == ManualRefreshResult.RELOAD_ON_OPEN -> "Reloads each time you open it in the IPTV tab."
            state.result == ManualRefreshResult.NEEDS_LOCAL_FILE -> "No copy of the file here."
            else -> when (val age = state.age) {
                null -> "Re-download channels, movies and series"
                else -> when (age.unit) {
                    IptvManualRefreshPolicy.AgeUnit.JUST_NOW -> "Updated just now"
                    IptvManualRefreshPolicy.AgeUnit.MINUTES -> "Updated ${age.count} min ago"
                    IptvManualRefreshPolicy.AgeUnit.HOURS -> "Updated ${age.count} h ago"
                    IptvManualRefreshPolicy.AgeUnit.DAYS -> "Updated ${age.count} d ago"
                }
            }
        }
    }

    /**
     * Shelves for [account]. [model] must be built with `allowEdit = false` (rename is dropped on Apple TV:
     * typing is costly). The library shelf leads with the three agreed cards and then keeps every other
     * action this playlist already had on Apple TV (edit URL / credentials for a playlist that is the
     * viewer's own, re-match, catch-up and guide offsets for an Xtream panel), so redesigning the page
     * removes nothing.
     */
    fun shelves(model: ManagedDetailsModel, account: XtreamAccount): List<TvDetailShelf> {
        val managed = model.isManaged
        return model.groups.mapNotNull { group ->
            val cards = when (group.kind) {
                DetailsGroupKind.PROVIDER -> group.actions.mapNotNull { if (it == DetailsAction.CONTACT) TvDetailCard.CONTACT else null }
                DetailsGroupKind.LIBRARY -> buildList {
                    add(TvDetailCard.CONTENT_CATEGORIES)
                    add(TvDetailCard.HIDDEN)
                    add(TvDetailCard.TOGGLE_ENABLED)
                    // UX179: Refresh now, right after the three agreed cards (what it does differs per source; the card says so).
                    add(TvDetailCard.REFRESH)
                    // A managed playlist's server and login are the provider's: no way in (a lock note stands in).
                    // Apple TV has no file picker, so a file playlist cannot be re-picked either.
                    if (!managed && account.sourceType != SOURCE_TYPE_M3U_FILE) add(TvDetailCard.EDIT_SERVER)
                    if (DetailsAction.REMATCH in group.actions) add(TvDetailCard.REMATCH)
                    if (account.sourceType == SOURCE_TYPE_XTREAM) {
                        add(TvDetailCard.CATCHUP_CONTAINER)
                        add(TvDetailCard.CATCHUP_TIME)
                        add(TvDetailCard.GUIDE_OFFSET)
                    }
                }
                DetailsGroupKind.REMOVE -> group.actions.mapNotNull {
                    when (it) {
                        DetailsAction.DETACH -> TvDetailCard.DETACH
                        DetailsAction.REMOVE -> TvDetailCard.REMOVE
                        else -> null
                    }
                }
            }
            if (cards.isEmpty()) null else TvDetailShelf(group.kind, group.title, cards)
        }
    }

    /**
     * "12 days left", "Couldn't check expiry" and the other readings of the header; null says nothing at all.
     * "Expiry not reported by this provider" is the provider's own answer and only ever follows a successful
     * call, so while the panel is still being asked ([loading]) nothing is said, and when it could not be asked
     * the line is [ExpiryDisplay.CheckFailed]'s "Couldn't check expiry" (the shared model decides which).
     */
    fun expiryLine(expiry: ExpiryDisplay, loading: Boolean = false, inspectionSkipped: Boolean = false): String? = when (expiry) {
        is ExpiryDisplay.DaysLeft -> if (expiry.days == 1) "1 day left" else "${expiry.days} days left"
        ExpiryDisplay.Expired -> "Expired"
        ExpiryDisplay.NeverExpires -> "Never expires"
        is ExpiryDisplay.Text -> expiry.text
        ExpiryDisplay.NotReported -> if (loading) null else if (inspectionSkipped) "Turn this playlist on to check its account" else "Expiry not reported by this provider"
        ExpiryDisplay.CheckFailed -> "Couldn't check expiry"
    }

    /**
     * The thin bar (0..1): only in the last 30 days, filled days/30 (the shared model's [ExpiryDisplay.DaysLeft.fraction],
     * null beyond 30 days); every other reading has no bar.
     */
    fun expiryBar(expiry: ExpiryDisplay): Float? = (expiry as? ExpiryDisplay.DaysLeft)?.fraction

    /** "1 of 3 connections", only when the panel reports a maximum. */
    fun connectionsLine(connections: ConnectionsDisplay?): String? =
        connections?.let { "${it.active} of ${it.max} connections" }

    /** The line under a playlist on the settings list: "Managed by X" and, once the panel answered, "· N days left". */
    fun managedRowLine(providerName: String, expiry: ExpiryDisplay?): String {
        val base = "Managed by $providerName"
        return when (expiry) {
            is ExpiryDisplay.DaysLeft -> "$base · ${expiryLine(expiry)}"
            ExpiryDisplay.Expired -> "$base · Expired"
            else -> base
        }
    }

    /** "Managed by X", with "\u00B7 updated <date>" when the server reported when the provider last changed it. */
    fun ribbon(managedBy: String?, serviceUpdatedDate: String?): String? =
        managedBy?.let { if (serviceUpdatedDate != null) "$it \u00B7 updated $serviceUpdatedDate" else it }

    /** What the status line under the facts says. [statusText] is the panel's own word ("Active", "Active · Trial"). */
    fun statusLine(statusText: String?, loading: Boolean, hasPanel: Boolean, inspectionSkipped: Boolean = false): String? = when {
        !hasPanel -> "This playlist is a plain list, so there is no account to check."
        statusText != null -> statusText
        inspectionSkipped -> "Turn this playlist on to check its account"
        loading -> "Checking the account…"
        else -> "Couldn't reach the provider for account details."
    }

    /**
     * Channels / Movies / Series lines, only for the counts the local catalog knows. A count of zero is left
     * out: the match index reports 0 until it has been built, and "Movies 0" would read as a fact.
     */
    fun countLines(counts: DetailsCounts): List<TvDetailsCount> = buildList {
        val shown = counts.hidingZeros()   // the shared rule: only a count above zero is shown
        shown.channels?.let { add(TvDetailsCount("Channels", it.toString())) }
        shown.movies?.let { add(TvDetailsCount("Movies", it.toString())) }
        shown.series?.let { add(TvDetailsCount("Series", it.toString())) }
    }
}

/** Where focus is on the page: which shelf and which card along it. */
data class TvShelfPosition(val shelf: Int, val index: Int)

/**
 * Focus on the shelves: Up/Down changes shelf and lands on the card that shelf last had (not the one that
 * happens to sit nearest), Left/Right walks along a shelf and stops at its ends. The page starts on the
 * first card of the first shelf. Pure bookkeeping the SwiftUI page drives; it never touches focus itself.
 */
class TvShelfFocus(sizes: List<Int>) {
    private var sizes: List<Int> = sizes.toList()
    private val remembered = mutableMapOf<Int, Int>()

    fun start(): TvShelfPosition = TvShelfPosition(0, 0)

    /** The viewer's focus landed on [index] of [shelf]. */
    fun onFocused(shelf: Int, index: Int) {
        remembered[shelf] = index
    }

    /** Where Up ([down] = false) or Down from [from] should land, or null at the first / last shelf. */
    fun vertical(from: Int, down: Boolean): TvShelfPosition? {
        val target = if (down) from + 1 else from - 1
        if (target !in sizes.indices) return null
        val last = (sizes[target] - 1).coerceAtLeast(0)
        return TvShelfPosition(target, (remembered[target] ?: 0).coerceIn(0, last))
    }

    /** The shelves changed shape (a card or a whole shelf appeared or went): keep what still exists. */
    fun resize(newSizes: List<Int>) {
        // A shelf coming or going shifts every index after it, so what was remembered no longer means the same.
        if (newSizes.size != sizes.size) remembered.clear()
        sizes = newSizes.toList()
        remembered.keys.removeAll { it !in sizes.indices }
    }

    companion object {
        /** The next card along a shelf of [count] cards, or null at an end (no wrap). */
        fun along(index: Int, count: Int, forward: Boolean): Int? {
            val next = if (forward) index + 1 else index - 1
            return next.takeIf { it in 0 until count }
        }
    }
}
