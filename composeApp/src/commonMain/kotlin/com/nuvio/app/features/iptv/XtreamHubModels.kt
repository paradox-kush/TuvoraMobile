package com.nuvio.app.features.iptv

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape

/** Top-level IPTV hub state. Live is handled by its own guide (P5); this covers VOD + Series browse. */
data class XtreamHubUiState(
    val accounts: List<XtreamAccount> = emptyList(),
    val selectedAccountId: String? = null,
    val section: XtreamHubSection = XtreamHubSection.LIVE,
    val categories: List<XtreamHubCategory> = emptyList(),
    val loadingCategories: Boolean = false,
)

enum class XtreamHubSection { LIVE, MOVIES, SERIES }

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
    val loaded: Boolean = false,
    val loading: Boolean = false,
)

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
