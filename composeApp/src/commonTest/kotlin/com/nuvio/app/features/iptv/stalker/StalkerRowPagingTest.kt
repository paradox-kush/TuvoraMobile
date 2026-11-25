package com.nuvio.app.features.iptv.stalker

import kotlin.test.Test
import kotlin.test.assertEquals

class StalkerRowPagingTest {

    @Test
    fun `offset zero is page one`() {
        assertEquals(1, StalkerRowPaging.pageFor(0, 14), "first window")
    }

    @Test
    fun `an offset on a page boundary is the next page`() {
        assertEquals(2, StalkerRowPaging.pageFor(14, 14), "offset = pageSize")
        assertEquals(3, StalkerRowPaging.pageFor(28, 14), "offset = 2 * pageSize")
        assertEquals(6, StalkerRowPaging.pageFor(70, 14), "the old cap boundary")
    }

    @Test
    fun `an offset inside a page stays on that page`() {
        assertEquals(1, StalkerRowPaging.pageFor(13, 14), "last row of page one")
        assertEquals(2, StalkerRowPaging.pageFor(27, 14), "last row of page two")
    }

    @Test
    fun `a non-positive page size reads page one`() {
        assertEquals(1, StalkerRowPaging.pageFor(40, 0), "zero size")
        assertEquals(1, StalkerRowPaging.pageFor(40, -5), "negative size")
        assertEquals(1, StalkerRowPaging.pageFor(-3, 14), "negative offset")
    }

    @Test
    fun `an empty page never has more`() {
        assertEquals(false, StalkerRowPaging.hasMore(1, 14, 0, 100), "total says more but the page was empty")
        assertEquals(false, StalkerRowPaging.hasMore(1, 14, 0, null), "no total, empty page")
    }

    @Test
    fun `total items decide when it is known`() {
        assertEquals(true, StalkerRowPaging.hasMore(1, 14, 14, 100), "14 of 100 read")
        assertEquals(true, StalkerRowPaging.hasMore(7, 14, 14, 100), "98 of 100 read")
        assertEquals(false, StalkerRowPaging.hasMore(8, 14, 2, 100), "112 >= 100")
        assertEquals(false, StalkerRowPaging.hasMore(2, 14, 14, 28), "exactly 28 of 28")
        assertEquals(false, StalkerRowPaging.hasMore(1, 14, 14, 14), "a single full page")
    }

    @Test
    fun `total items win over a short or full page`() {
        assertEquals(true, StalkerRowPaging.hasMore(1, 14, 10, 100), "a short page is not the end when the total says more")
        assertEquals(false, StalkerRowPaging.hasMore(1, 14, 14, 10), "a full page is the end when the total says so")
    }

    @Test
    fun `without a total a full page means more and a short page means done`() {
        assertEquals(true, StalkerRowPaging.hasMore(1, 14, 14, null), "full page")
        assertEquals(false, StalkerRowPaging.hasMore(1, 14, 13, null), "short page")
    }

    @Test
    fun `page size prefers max page items`() {
        assertEquals(14, StalkerRowPaging.pageSizeOf(14, 9), "portal says 14")
        assertEquals(10, StalkerRowPaging.pageSizeOf(10, 14), "portal says 10 even if the page held more")
    }

    @Test
    fun `page size falls back to the first page then the default`() {
        assertEquals(9, StalkerRowPaging.pageSizeOf(null, 9), "no max: rows on the page")
        assertEquals(9, StalkerRowPaging.pageSizeOf(0, 9), "zero max is not a size")
        assertEquals(14, StalkerRowPaging.pageSizeOf(null, 0), "nothing known")
        assertEquals(14, StalkerRowPaging.pageSizeOf(-1, 0), "garbage max and empty page")
    }
}
