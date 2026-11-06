package com.nuvio.app.features.iptv

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape

/** Top-level IPTV hub state. Live is handled by its own guide (P5); this covers VOD + Series browse. */
data class XtreamHubUiState(
    val accounts: List<XtreamAccount> = emptyList(),
    val hasConfiguredAccounts: Boolean = accounts.isNotEmpty(),
    /**
     * False until ensureLoaded has actually read the stored accounts. The first composition runs
     * BEFORE the screen's LaunchedEffect, so an empty [accounts] alone cannot mean "no playlists" —
     * gating the add-a-playlist empty state on this stops it flashing over a populated hub
     * (field-reported: visible for a second when the process was busy).
     */
    val accountsLoaded: Boolean = false,
    val selectedAccountId: String? = null,
    val section: XtreamHubSection = XtreamHubSection.LIVE,
    val categories: List<XtreamHubCategory> = emptyList(),
    val catalogueGeneration: Long = 0L,
    /** The category-list load. Entered only through [BoundedLoad], so it always ends (see that file). */
    val categoriesLoad: LoadStatus = LoadStatus.Idle,
    // Non-null when the category-list fetch failed and there was no cache to fall back on — the UI
    // shows this instead of spinning forever (dead portal / Cloudflare block / timeout). Carries
    // WHICH of those it was, so the card can stop telling every viewer the portal is down.
    val loadError: IptvLoadFailurePolicy.Failure? = null,
    /** UX147: playlists whose portal positively has no series section this session; their Series tab is hidden. */
    val seriesUnavailableIds: Set<String> = emptySet(),
) {
    val loadingCategories: Boolean get() = categoriesLoad is LoadStatus.Loading

    /** UX147: the SELECTED playlist's portal positively has no series (read by the Apple TV screen's chip row). */
    val seriesUnavailable: Boolean get() = selectedAccountId != null && selectedAccountId in seriesUnavailableIds
}

enum class XtreamHubSection { LIVE, MOVIES, SERIES }

/** Which section chips the hub offers (pure, so Compose and the Apple TV screen share one answer). */
object XtreamHubSections {
    /**
     * [typeEnabled] is the playlist-manager content toggle per section; [seriesAbsent] is the portal's
     * own positive "no series" evidence (UX147). Unknown evidence is false, so the tab stays. The chip
     * only goes once the viewer has LEFT Series ([current]): removing the selected chip would leave the
     * "no series" card without its tab (and TV focus on a detached node).
     */
    fun visible(typeEnabled: (XtreamHubSection) -> Boolean, seriesAbsent: Boolean, current: XtreamHubSection? = null): List<XtreamHubSection> =
        XtreamHubSection.entries.filter {
            typeEnabled(it) && !(it == XtreamHubSection.SERIES && seriesAbsent && current != XtreamHubSection.SERIES)
        }

    fun withSeriesAbsent(ids: Set<String>, accountId: String, absent: Boolean): Set<String> =
        if (absent) ids + accountId else ids - accountId
}

/** The account.contentTypes / categorySelections key this hub section corresponds to. */
val XtreamHubSection.contentKey: String
    get() = when (this) {
        XtreamHubSection.LIVE -> CONTENT_TYPE_LIVE
        XtreamHubSection.MOVIES -> CONTENT_TYPE_MOVIES
        XtreamHubSection.SERIES -> CONTENT_TYPE_SERIES
    }

/** Now/next program titles for a live channel (from get_short_epg). */
data class ChannelEpg(val now: String?, val next: String?)

data class XtreamHubCategory(
    val id: String,
    val name: String,
    val items: List<MetaPreview> = emptyList(),
    /** Fetched (Loaded or Empty): never fetched again until evicted. A failed row is NOT loaded. */
    val loaded: Boolean = false,
    /** This row's load. Entered only through [BoundedLoad]; [LoadStatus.Failed] shows the row's Retry. */
    val load: LoadStatus = LoadStatus.Idle,
    /** More rows exist past [items] (item 5): the row's end-trigger calls loadMore. */
    val hasMore: Boolean = false,
    /** Raw provider rows the windows have covered so far (the paging offset). 0 = unknown: fall back to [items].size. */
    val rawOffset: Int = 0,
) {
    val loading: Boolean get() = load is LoadStatus.Loading
    val failed: Boolean get() = load is LoadStatus.Failed
}

fun XtreamMovie.toMetaPreview(accountId: String): MetaPreview = MetaPreview(
    id = XtreamItemRegistry.vodId(accountId, streamId),
    type = "movie",
    name = name,
    poster = poster,
    posterShape = PosterShape.Poster,
)

fun XtreamSeriesItem.toMetaPreview(accountId: String): MetaPreview = MetaPreview(
    id = XtreamItemRegistry.seriesId(accountId, seriesId),
    type = "series",
    name = name,
    poster = poster,
    posterShape = PosterShape.Poster,
)

fun XtreamChannel.toMetaPreview(accountId: String): MetaPreview = MetaPreview(
    id = XtreamItemRegistry.liveId(accountId, streamId),
    type = "tv",
    name = name,
    poster = logo,
    logo = logo,
    posterShape = PosterShape.Landscape,
)
