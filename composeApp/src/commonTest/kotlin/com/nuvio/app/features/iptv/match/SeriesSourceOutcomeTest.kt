package com.nuvio.app.features.iptv.match

import com.nuvio.app.features.addons.HttpStatusException
import com.nuvio.app.features.iptv.IptvLoadTimeoutException
import com.nuvio.app.features.iptv.LoadOutcome
import com.nuvio.app.features.iptv.LoadSurface
import com.nuvio.app.features.iptv.XtreamClient
import com.nuvio.app.features.iptv.XtreamEpisode
import com.nuvio.app.features.iptv.XtreamParseException
import com.nuvio.app.features.iptv.XtreamSeriesDetail
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * B134: a failed get_series_info is InfoFailed, never an empty list. Red before the fix: the old code read
 * `getOrNull() ?: emptyList()`, so a 403 and a garbled body both came out as "no streams".
 */
class SeriesSourceOutcomeTest {

    private val show = IndexedItem(sid = 7, name = "Severance", year = null, tmdb = null, ext = null)

    private fun ep(season: Int, num: Int) =
        XtreamEpisode(episodeId = "e$season-$num", season = season, episodeNum = num, title = "EP", plot = null, still = null, containerExtension = "mkv")

    private fun detail(vararg eps: XtreamEpisode) = XtreamSeriesDetail(
        name = null, poster = null, tmdbId = null, plot = null, genres = emptyList(), rating = null, releaseDate = null,
        episodes = eps.toList(),
    )

    @Test
    fun aForbiddenSeriesInfoIsInfoFailedNotAnEmptyResult() = runBlocking {
        val outcome = SeriesSourcePolicy.resolve(listOf(show), 1, 1) { Result.failure(HttpStatusException(403, "HTTP 403")) }
        assertEquals(SeriesSourceOutcome.InfoFailed(SeriesInfoFailureKind.HTTP_4XX), outcome, "403 must not read as no streams")
    }

    @Test
    fun aServerErrorAndATimeoutAreTheirOwnKinds() {
        assertEquals(SeriesInfoFailureKind.HTTP_5XX, SeriesSourcePolicy.classify(HttpStatusException(502, "x")), "5xx")
        assertEquals(SeriesInfoFailureKind.TIMEOUT, SeriesSourcePolicy.classify(IptvLoadTimeoutException(LoadSurface.SERIES_INFO, 20_000L)), "stall deadline")
        assertEquals(SeriesInfoFailureKind.OTHER, SeriesSourcePolicy.classify(IllegalStateException("boom")), "anything else")
    }

    @Test
    fun anUnparseableBodyIsInfoFailedParse() = runBlocking {
        val thrown = assertFailsWith<XtreamParseException> { XtreamClient.parseSeriesInfoRoot("<html>blocked</html>") }
        val outcome = SeriesSourcePolicy.resolve(listOf(show), 1, 1) { Result.failure(thrown) }
        assertEquals(SeriesSourceOutcome.InfoFailed(SeriesInfoFailureKind.PARSE), outcome, "garbled body is a parse failure")
    }

    @Test
    fun aPanelSayingNoSuchSeriesIsNotAFailure() {
        assertNull(XtreamClient.parseSeriesInfoRoot("[]"), "empty array")
        assertNull(XtreamClient.parseSeriesInfoRoot("  "), "blank body")
        assertIs<kotlinx.serialization.json.JsonObject>(XtreamClient.parseSeriesInfoRoot("{\"episodes\":{}}"), "an object parses")
        assertFailsWith<XtreamParseException> { XtreamClient.parseSeriesInfoRoot("\"nope\"") }
        assertFailsWith<XtreamParseException> { XtreamClient.parseSeriesInfoRoot("{\"episodes\":") }
    }

    @Test
    fun successWithNoMatchingSeasonAndEpisodeIsNoEpisodeForSE() = runBlocking {
        val outcome = SeriesSourcePolicy.resolve(listOf(show), 3, 9) { Result.success(detail(ep(1, 1), ep(3, 1))) }
        assertEquals(SeriesSourceOutcome.NoEpisodeForSE, outcome, "info loaded but S3E9 absent")
    }

    @Test
    fun successWithTheEpisodeIsFound() = runBlocking {
        val outcome = SeriesSourcePolicy.resolve(listOf(show), 3, 1) { Result.success(detail(ep(1, 1), ep(3, 1))) }
        val found = assertIs<SeriesSourceOutcome.Found>(outcome)
        assertEquals(listOf("e3-1"), found.picks.map { it.episode.episodeId }, "the matching episode")
    }

    @Test
    fun noEditionsOrANullDetailIsNoMatch() = runBlocking {
        assertEquals(SeriesSourceOutcome.NoMatch, SeriesSourcePolicy.resolve(emptyList(), 1, 1) { error("never fetched") }, "no entries")
        assertEquals(SeriesSourceOutcome.NoMatch, SeriesSourcePolicy.resolve(listOf(show), 1, 1) { Result.success(null) }, "panel has no such series")
    }

    @Test
    fun aHitBeatsAFailedSiblingAndAFailureBeatsNoEpisode() = runBlocking {
        val other = IndexedItem(sid = 8, name = "Severance (Hindi)", year = null, tmdb = null, ext = null)
        val hitWins = SeriesSourcePolicy.resolve(listOf(show, other), 1, 1) { ed ->
            if (ed.sid == 7) Result.failure(HttpStatusException(500, "x")) else Result.success(detail(ep(1, 1)))
        }
        assertIs<SeriesSourceOutcome.Found>(hitWins, "a failed edition must not hide a hit")
        val failureWins = SeriesSourcePolicy.resolve(listOf(show, other), 1, 1) { ed ->
            if (ed.sid == 7) Result.failure(HttpStatusException(500, "x")) else Result.success(detail(ep(2, 2)))
        }
        assertEquals(SeriesSourceOutcome.InfoFailed(SeriesInfoFailureKind.HTTP_5XX), failureWins, "cannot claim absent when an edition failed")
    }

    @Test
    fun aFailedLoadOutcomeKeepsItsErrorAsAFailureResult() {
        val error = HttpStatusException(403, "x")
        assertEquals(error, LoadOutcome.Failed(error, timedOut = false).toResult().exceptionOrNull(), "the error survives")
    }

    @Test
    fun theFailureMessageNamesNoHostAndNoBody() {
        val message = XtreamSeriesInfoException(SeriesInfoFailureKind.HTTP_4XX).message.orEmpty()
        assertEquals(false, message.contains("http://") || message.contains("://"), "no URL in the message")
    }
}
