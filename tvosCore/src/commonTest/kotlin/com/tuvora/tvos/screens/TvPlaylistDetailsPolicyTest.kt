package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.BoundedLoad
import com.nuvio.app.features.iptv.ConnectionsDisplay
import com.nuvio.app.features.iptv.LoadStatus
import com.nuvio.app.features.iptv.LoadSurface
import com.nuvio.app.features.iptv.ManualRefreshResult
import com.nuvio.app.features.iptv.ManualRefreshUiState
import com.nuvio.app.features.iptv.XtreamAccountInfo
import com.nuvio.app.features.iptv.DetailsCounts
import com.nuvio.app.features.iptv.ExpiryDisplay
import com.nuvio.app.features.iptv.DetailsGroupKind
import com.nuvio.app.features.iptv.ManagedDetailsModel
import com.nuvio.app.features.iptv.ManagedInfo
import com.nuvio.app.features.iptv.ProviderSupport
import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_FILE
import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_URL
import com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER
import com.nuvio.app.features.iptv.XtreamAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Apple TV playlist details page: which labelled shelves exist and what cards sit on them (build plan 3.6). */
class TvPlaylistDetailsPolicyTest {

    private val xtream = XtreamAccount(id = "k1", name = "Acme Live", baseUrl = "http://h:80", username = "u", password = "p")
    private val acme = ManagedInfo("k1", "Starshare", support = ProviderSupport(telegram = "acme_help", email = "help@acme.example.com"))

    private fun shelves(account: XtreamAccount, info: ManagedInfo?) = TvPlaylistDetailsPolicy.shelves(
        ManagedDetailsModel.build(account, info, null, DetailsCounts(), nowEpochSec = 0, allowEdit = false), account,
    )

    @Test
    fun `a managed playlist has three labelled shelves in the agreed order`() {
        val s = shelves(xtream, acme)
        assertEquals(listOf(DetailsGroupKind.PROVIDER, DetailsGroupKind.LIBRARY, DetailsGroupKind.REMOVE), s.map { it.kind })
        assertEquals(listOf("STARSHARE", "YOUR LIBRARY", "REMOVE"), s.map { it.title })
        assertEquals(listOf(TvDetailCard.CONTACT), s[0].cards)
        assertEquals(listOf(TvDetailCard.DETACH, TvDetailCard.REMOVE), s[2].cards)
    }

    @Test
    fun `the library shelf leads with content hidden and disable`() {
        val lib = shelves(xtream, acme)[1].cards
        assertEquals(listOf(TvDetailCard.CONTENT_CATEGORIES, TvDetailCard.HIDDEN, TvDetailCard.TOGGLE_ENABLED), lib.take(3))
    }

    @Test
    fun `a managed playlist never offers server or login editing`() {
        assertEquals(false, TvDetailCard.EDIT_SERVER in shelves(xtream, acme).flatMap { it.cards })
    }

    @Test
    fun `an unmanaged playlist has no provider shelf no detach and keeps edit url and credentials`() {
        val s = shelves(xtream, null)
        assertEquals(listOf(DetailsGroupKind.LIBRARY, DetailsGroupKind.REMOVE), s.map { it.kind })
        assertEquals(listOf(TvDetailCard.REMOVE), s[1].cards)
        assertEquals(true, TvDetailCard.EDIT_SERVER in s[0].cards)
    }

    @Test
    fun `a managed playlist whose provider set no contacts has no contact shelf`() {
        val s = shelves(xtream, ManagedInfo("k1", "Starshare"))
        assertEquals(listOf(DetailsGroupKind.LIBRARY, DetailsGroupKind.REMOVE), s.map { it.kind })
        assertEquals(listOf(TvDetailCard.DETACH, TvDetailCard.REMOVE), s[1].cards)
    }

    @Test
    fun `xtream keeps its rematch and catch up cards others do not`() {
        val x = shelves(xtream, null)[0].cards
        assertEquals(
            listOf(TvDetailCard.REMATCH, TvDetailCard.CATCHUP_CONTAINER, TvDetailCard.CATCHUP_TIME, TvDetailCard.GUIDE_OFFSET),
            x.drop(5),
        )
        assertEquals(TvDetailCard.REFRESH, x[3], "Refresh now sits right after the three agreed cards")
        val m3u = xtream.copy(sourceType = SOURCE_TYPE_M3U_URL)
        assertEquals(listOf(TvDetailCard.CONTENT_CATEGORIES, TvDetailCard.HIDDEN, TvDetailCard.TOGGLE_ENABLED, TvDetailCard.REFRESH, TvDetailCard.EDIT_SERVER), shelves(m3u, null)[0].cards)
        val stalker = xtream.copy(sourceType = SOURCE_TYPE_STALKER)
        assertEquals(false, TvDetailCard.REMATCH in shelves(stalker, null)[0].cards)
        assertEquals(false, TvDetailCard.CATCHUP_TIME in shelves(stalker, null)[0].cards)
    }

    @Test
    fun `a file playlist has no edit card because the Apple TV has no file picker`() {
        val file = xtream.copy(sourceType = SOURCE_TYPE_M3U_FILE)
        assertEquals(false, TvDetailCard.EDIT_SERVER in shelves(file, null).flatMap { it.cards })
    }

    // --- focus memory -----------------------------------------------------------------------------

    @Test
    fun `focus starts on the first card of the first shelf`() {
        val f = TvShelfFocus(listOf(1, 5, 2))
        assertEquals(TvShelfPosition(0, 0), f.start())
    }

    @Test
    fun `down from a shelf lands on the remembered card of the next shelf`() {
        val f = TvShelfFocus(listOf(1, 5, 2))
        assertEquals(TvShelfPosition(1, 0), f.vertical(from = 0, down = true))
        f.onFocused(1, 3)
        f.onFocused(2, 1)
        assertEquals(TvShelfPosition(1, 3), f.vertical(from = 2, down = false))
        assertEquals(TvShelfPosition(2, 1), f.vertical(from = 1, down = true))
    }

    @Test
    fun `there is nowhere to go above the first shelf or below the last`() {
        val f = TvShelfFocus(listOf(1, 5, 2))
        assertNull(f.vertical(from = 0, down = false))
        assertNull(f.vertical(from = 2, down = true))
    }

    @Test
    fun `a remembered card that disappeared clamps to the last one`() {
        val f = TvShelfFocus(listOf(1, 5, 2))
        f.onFocused(1, 4)
        f.resize(listOf(1, 3, 2))
        assertEquals(TvShelfPosition(1, 2), f.vertical(from = 0, down = true))
    }

    @Test
    fun `a shelf that vanished does not strand the memory`() {
        val f = TvShelfFocus(listOf(1, 5, 2))
        f.onFocused(2, 1)
        f.resize(listOf(5, 2))
        assertEquals(TvShelfPosition(1, 0), f.vertical(from = 0, down = true))
    }

    @Test
    fun `moving along a shelf stops at its ends`() {
        assertEquals(2, TvShelfFocus.along(index = 1, count = 4, forward = true))
        assertNull(TvShelfFocus.along(index = 3, count = 4, forward = true))
        assertNull(TvShelfFocus.along(index = 0, count = 4, forward = false))
        assertEquals(0, TvShelfFocus.along(index = 1, count = 4, forward = false))
    }

    // --- the words on the page --------------------------------------------------------------------

    @Test
    fun `expiry reads as days left with a bar or says plainly that none was reported`() {
        assertEquals("12 days left", TvPlaylistDetailsPolicy.expiryLine(ExpiryDisplay.DaysLeft(12, 0.4f)))
        assertEquals("1 day left", TvPlaylistDetailsPolicy.expiryLine(ExpiryDisplay.DaysLeft(1, 0.03f)))
        assertEquals("Expired", TvPlaylistDetailsPolicy.expiryLine(ExpiryDisplay.Expired))
        assertEquals("Never expires", TvPlaylistDetailsPolicy.expiryLine(ExpiryDisplay.NeverExpires))
        assertEquals("February 20, 2027", TvPlaylistDetailsPolicy.expiryLine(ExpiryDisplay.Text("February 20, 2027")))
        assertEquals("Expiry not reported by this provider", TvPlaylistDetailsPolicy.expiryLine(ExpiryDisplay.NotReported))
    }

    @Test
    fun `the thin bar exists only when there is a count of days to show`() {
        assertEquals(0.4f, TvPlaylistDetailsPolicy.expiryBar(ExpiryDisplay.DaysLeft(12, 0.4f)))
        assertNull(TvPlaylistDetailsPolicy.expiryBar(ExpiryDisplay.Expired))
        assertNull(TvPlaylistDetailsPolicy.expiryBar(ExpiryDisplay.NeverExpires))
        assertNull(TvPlaylistDetailsPolicy.expiryBar(ExpiryDisplay.Text("x")))
        assertNull(TvPlaylistDetailsPolicy.expiryBar(ExpiryDisplay.NotReported))
    }

    @Test
    fun `connections read as n of m only when the panel reports a maximum`() {
        assertEquals("1 of 3 connections", TvPlaylistDetailsPolicy.connectionsLine(ConnectionsDisplay(1, 3)))
        assertNull(TvPlaylistDetailsPolicy.connectionsLine(null))
    }

    @Test
    fun `the settings row names the provider and the days left once the panel has answered`() {
        assertEquals("Managed by Starshare", TvPlaylistDetailsPolicy.managedRowLine("Starshare", null))
        assertEquals("Managed by Starshare", TvPlaylistDetailsPolicy.managedRowLine("Starshare", ExpiryDisplay.NotReported))
        assertEquals("Managed by Starshare \u00B7 20 days left", TvPlaylistDetailsPolicy.managedRowLine("Starshare", ExpiryDisplay.DaysLeft(20, 0.6f)))
        assertEquals("Managed by Starshare \u00B7 1 day left", TvPlaylistDetailsPolicy.managedRowLine("Starshare", ExpiryDisplay.DaysLeft(1, 0.03f)))
        assertEquals("Managed by Starshare \u00B7 Expired", TvPlaylistDetailsPolicy.managedRowLine("Starshare", ExpiryDisplay.Expired))
    }

    @Test
    fun `the ribbon names the provider and when it last updated the service`() {
        assertNull(TvPlaylistDetailsPolicy.ribbon(null, "2026-10-01"))
        assertEquals("Managed by Starshare", TvPlaylistDetailsPolicy.ribbon("Managed by Starshare", null))
        assertEquals("Managed by Starshare \u00B7 updated 2026-10-01", TvPlaylistDetailsPolicy.ribbon("Managed by Starshare", "2026-10-01"))
    }

    @Test
    fun `the status line says what is known without inventing anything`() {
        assertEquals("Active", TvPlaylistDetailsPolicy.statusLine("Active", loading = false, hasPanel = true))
        assertEquals("Checking the account\u2026", TvPlaylistDetailsPolicy.statusLine(null, loading = true, hasPanel = true))
        assertEquals("Couldn't reach the provider for account details.", TvPlaylistDetailsPolicy.statusLine(null, loading = false, hasPanel = true))
        assertEquals("This playlist is a plain list, so there is no account to check.", TvPlaylistDetailsPolicy.statusLine(null, loading = false, hasPanel = false))
    }

    @Test
    fun `a count of zero is not shown because the match index may simply not be built yet`() {
        assertEquals(emptyList(), TvPlaylistDetailsPolicy.countLines(DetailsCounts(movies = 0, series = 0)))
        assertEquals(
            listOf(TvDetailsCount("Movies", "120")),
            TvPlaylistDetailsPolicy.countLines(DetailsCounts(channels = 0, movies = 120, series = 0)),
        )
    }

    @Test
    fun `counts list only what the catalog knows`() {
        assertEquals(emptyList(), TvPlaylistDetailsPolicy.countLines(DetailsCounts()))
        assertEquals(
            listOf(TvDetailsCount("Movies", "120"), TvDetailsCount("Series", "8")),
            TvPlaylistDetailsPolicy.countLines(DetailsCounts(movies = 120, series = 8)),
        )
    }

    // --- Mobile's final semantics, through the shared model's own tables ----------------------------

    private fun panel(daysFromNow: Long) = XtreamAccountInfo(
        status = "Active", isTrial = false, expiresAtEpochSec = 1_000_000L + daysFromNow * 86_400L,
        maxConnections = 2, activeConnections = 1,
    )

    private fun expiryFor(days: Long) = ManagedDetailsModel.expiryOf(panel(days), nowEpochSec = 1_000_000L)

    @Test
    fun `the bar is days over 30 inside the last 30 days and absent beyond that`() {
        assertEquals(1f / 30f, TvPlaylistDetailsPolicy.expiryBar(expiryFor(1)))
        assertEquals(29f / 30f, TvPlaylistDetailsPolicy.expiryBar(expiryFor(29)))
        assertEquals(1f, TvPlaylistDetailsPolicy.expiryBar(expiryFor(30)))
        assertNull(TvPlaylistDetailsPolicy.expiryBar(expiryFor(31)))
        assertNull(TvPlaylistDetailsPolicy.expiryBar(expiryFor(365)))
    }

    @Test
    fun `beyond thirty days the line says only how many days are left`() {
        assertEquals("45 days left", TvPlaylistDetailsPolicy.expiryLine(expiryFor(45)))
        assertEquals("1 day left", TvPlaylistDetailsPolicy.expiryLine(expiryFor(1)))
    }

    @Test
    fun `a panel that could not be asked says it could not check never that none was reported`() {
        val failed = ManagedDetailsModel.expiryOf(null, 1_000_000L, panelCheckFailed = true)
        assertEquals(ExpiryDisplay.CheckFailed, failed)
        assertEquals("Couldn't check expiry", TvPlaylistDetailsPolicy.expiryLine(failed))
        assertNull(TvPlaylistDetailsPolicy.expiryBar(failed))
    }

    @Test
    fun `while the panel is still being asked nothing is said about expiry`() {
        assertNull(TvPlaylistDetailsPolicy.expiryLine(ExpiryDisplay.NotReported, loading = true))
        assertEquals("Expiry not reported by this provider", TvPlaylistDetailsPolicy.expiryLine(ExpiryDisplay.NotReported, loading = false))
    }

    @Test
    fun `negative and zero counts hide like unknown ones`() {
        assertEquals(emptyList(), TvPlaylistDetailsPolicy.countLines(DetailsCounts(channels = -1, movies = 0, series = null)))
    }

    // ---- UX179: the Refresh now card's words ----

    @Test
    fun `the refresh card always says what happened`() {
        val now = 10_000_000_000L
        fun hint(state: ManualRefreshUiState) = TvPlaylistDetailsPolicy.refreshHint(state)
        assertEquals("Re-download channels, movies and series", hint(ManualRefreshUiState()), "nothing known yet")
        assertEquals("Refreshing\u2026", hint(ManualRefreshUiState(load = BoundedLoad.begin(LoadSurface.PLAYLIST_REFRESH))), "running")
        assertEquals(
            "Updated 5 min ago",
            hint(ManualRefreshUiState(load = LoadStatus.Loaded, result = ManualRefreshResult.REFRESHED, lastRefreshAtMs = now - 5 * 60_000L - 1_000L, publishedAtMs = now)),
            "after a refresh",
        )
        assertEquals(
            "Couldn\u2019t refresh. Press to try again.",
            hint(ManualRefreshUiState(load = LoadStatus.Failed(timedOut = false), result = ManualRefreshResult.FAILED)),
            "failed",
        )
        assertEquals(
            "Only part updated. Press to try again.",
            hint(ManualRefreshUiState(load = LoadStatus.Failed(timedOut = false), result = ManualRefreshResult.PARTIAL)),
            "partial",
        )
        assertEquals(
            "Still working in the background. Check back in a few minutes.",
            hint(ManualRefreshUiState(load = LoadStatus.Failed(timedOut = true), result = ManualRefreshResult.FAILED)),
            "a stall deadline is outcome unknown, not failed",
        )
        assertEquals("Please wait a minute before refreshing again.", hint(ManualRefreshUiState(load = LoadStatus.Loaded, result = ManualRefreshResult.COOLDOWN)), "cooldown")
        assertEquals("Reloads each time you open it in the IPTV tab.", hint(ManualRefreshUiState(load = LoadStatus.Loaded, result = ManualRefreshResult.RELOAD_ON_OPEN)), "stalker")
        assertEquals("No copy of the file here.", hint(ManualRefreshUiState(load = LoadStatus.Loaded, result = ManualRefreshResult.NEEDS_LOCAL_FILE)), "file with no copy")
    }

    @Test fun aSkippedInspectionSaysTurnItOnInsteadOfNotReported() {
        assertEquals("Turn this playlist on to check its account",
            TvPlaylistDetailsPolicy.expiryLine(ExpiryDisplay.NotReported, inspectionSkipped = true))
        assertEquals("Turn this playlist on to check its account", TvPlaylistDetailsPolicy.statusLine(null, false, true, inspectionSkipped = true))
        assertEquals("Expiry not reported by this provider", TvPlaylistDetailsPolicy.expiryLine(ExpiryDisplay.NotReported))
    }
}
