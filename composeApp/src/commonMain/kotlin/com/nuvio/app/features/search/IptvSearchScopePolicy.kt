package com.nuvio.app.features.search

/** F85: the IPTV content types (lives in search, not iptv: Search is non-fork and may not import the fork feature) the search result rows can be narrowed to. */
internal enum class IptvSearchScope(val sectionKey: String) {
    LIVE("xtream_channels"),
    MOVIES("xtream_movies"),
    SERIES("xtream_series"),
}

/**
 * F85: scope chips (Live / Movies / Series, multi-select, default all) over the IPTV rows of a search. Pure
 * filter over rows already fetched — no new request. Only IPTV rows are narrowed; add-on rows are never touched.
 * The selection is never empty: un-picking the last chip goes back to all, so a viewer can't filter themselves
 * into a blank screen with no obvious way out.
 */
internal object IptvSearchScopePolicy {
    val ALL: Set<IptvSearchScope> = IptvSearchScope.entries.toSet()

    private val byKey = IptvSearchScope.entries.associateBy { it.sectionKey }

    /** Saved-state form: scope names. */
    fun encode(selected: Set<IptvSearchScope>): List<String> = selected.map { it.name }

    /** Unknown or empty saved state reads as all, never a throw or a blank screen. */
    fun decode(saved: List<String>): Set<IptvSearchScope> =
        saved.mapNotNull { name -> IptvSearchScope.entries.firstOrNull { it.name == name } }.toSet()
            .ifEmpty { ALL }

    fun scopeOf(sectionKey: String): IptvSearchScope? = byKey[sectionKey]

    /** What the results area of Search is showing; mirrors the `when` in SearchScreen (same order). */
    enum class Body { SKELETON, ERROR, NO_RESULTS, RESULTS }

    fun bodyOf(loadingOrPending: Boolean, noRows: Boolean, errorWithNoRows: Boolean): Body = when {
        loadingOrPending && noRows -> Body.SKELETON
        errorWithNoRows -> Body.ERROR
        !loadingOrPending && noRows -> Body.NO_RESULTS
        else -> Body.RESULTS
    }

    /**
     * The chips are part of the results area only (rows, or the "No Results" state): never over skeletons or an
     * error. There they stay up whenever the IPTV lane is relevant to this query: IPTV rows are on screen, or the
     * lane already answered for the shown search (even with zero hits, or with every row filtered out) so the
     * viewer never loses the filters they just set. A lane that FAILED is not an answer.
     */
    fun chipsVisible(body: Body, hasIptvRows: Boolean, iptvAnswered: Boolean): Boolean =
        (body == Body.RESULTS || body == Body.NO_RESULTS) && (hasIptvRows || iptvAnswered)

    /**
     * True when the selection hides the IPTV rows that DID come back, after loading finished: [allRows] are the
     * unfiltered rows. When the search found no IPTV hits at all the filter is not to blame, so there is no note.
     */
    fun <T> showEmptyNote(
        selected: Set<IptvSearchScope>,
        allRows: List<T>,
        iptvAnswered: Boolean,
        isLoading: Boolean,
        keyOf: (T) -> String,
    ): Boolean = iptvAnswered && !isLoading && selected.size != ALL.size &&
        hasIptvRows(allRows, keyOf) && filter(allRows, selected, keyOf).none { scopeOf(keyOf(it)) != null }

    /** True when [rows] carry at least one IPTV row — the only time the chips are worth showing. */
    fun <T> hasIptvRows(rows: List<T>, keyOf: (T) -> String): Boolean = rows.any { scopeOf(keyOf(it)) != null }

    fun toggle(selected: Set<IptvSearchScope>, scope: IptvSearchScope): Set<IptvSearchScope> {
        val next = if (scope in selected) selected - scope else selected + scope
        return if (next.isEmpty()) ALL else next
    }

    /** Keeps every non-IPTV row, and an IPTV row only when its type is selected. Order is preserved. */
    fun <T> filter(rows: List<T>, selected: Set<IptvSearchScope>, keyOf: (T) -> String): List<T> {
        if (selected.isEmpty() || selected.size == ALL.size) return rows
        return rows.filter { row ->
            val scope = scopeOf(keyOf(row))
            scope == null || scope in selected
        }
    }
}
