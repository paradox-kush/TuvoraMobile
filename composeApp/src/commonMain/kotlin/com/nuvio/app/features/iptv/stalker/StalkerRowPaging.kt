package com.nuvio.app.features.iptv.stalker

/**
 * B294: the paging arithmetic for a hub Movies/Series row on a Stalker portal - pure, so it is pinned
 * by tests without a portal.
 *
 * A hub row reads ONE `get_ordered_list` page per window (the portal serves ~14 rows a page); the row asks
 * for the next page as it scrolls. The old row read five pages in a row before showing anything, which on
 * a session with two request slots behind EPG and poster traffic outlasted the row's deadline.
 */
internal object StalkerRowPaging {

    /** The page size portals serve when they do not say (14 rows a page on stock Ministra / Xtream panels). */
    const val DEFAULT_PAGE_SIZE = 14

    /** The 1-based portal page that holds raw row [offset]. Offset 0 is page 1; a non-positive size reads page 1. */
    fun pageFor(offset: Int, pageSize: Int): Int =
        if (pageSize <= 0 || offset <= 0) 1 else offset / pageSize + 1

    /**
     * Whether another page exists after [page]. An empty page is the end. With the portal's
     * `total_items` the answer is exact; without it a full page is the only evidence of more.
     */
    fun hasMore(page: Int, pageSize: Int, rowsOnPage: Int, totalItems: Int?): Boolean = when {
        rowsOnPage <= 0 -> false
        totalItems != null -> page.toLong() * pageSize < totalItems
        else -> rowsOnPage >= pageSize
    }

    /** The portal's page size: its own `max_page_items`, else what the first page actually held, else the default. */
    fun pageSizeOf(maxPageItems: Int?, rowsOnFirstPage: Int): Int = when {
        maxPageItems != null && maxPageItems > 0 -> maxPageItems
        rowsOnFirstPage > 0 -> rowsOnFirstPage
        else -> DEFAULT_PAGE_SIZE
    }
}
