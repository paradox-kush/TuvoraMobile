package com.nuvio.app.features.notifications

import com.nuvio.app.core.contracts.OwnSourcePolicy
import com.nuvio.app.core.contracts.isEpisodicShowType
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryUiState
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressUiState
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackedShowsTest {
    @BeforeTest
    fun registerOwnSource() {
        OwnSourcePolicy.resetForTest()
        OwnSourcePolicy.registerContentIdPredicate("test-iptv") { it.startsWith("xtream:") || it.startsWith("m3u:") }
    }

    @AfterTest
    fun clearOwnSource() = OwnSourcePolicy.resetForTest()

    private val today = "2026-11-19"
    private val show = LibraryItem(id = "tt100", type = "series", name = "Severance", savedAtEpochMs = 0L)
    private val anime = LibraryItem(id = "kitsu:1", type = "anime", name = "Frieren", savedAtEpochMs = 0L)
    private val movie = LibraryItem(id = "tt200", type = "movie", name = "Heat", savedAtEpochMs = 0L)
    private val sky = LibraryItem(id = "xtream:acc:live:1", type = "tv", name = "Sky News 1 HD", savedAtEpochMs = 0L)
    private val espn = LibraryItem(id = "m3u:acc:live:9", type = "tv", name = "ESPN News 1 HD", savedAtEpochMs = 0L)

    private fun library(vararg items: LibraryItem) = LibraryUiState(items = items.toList(), isLoaded = true)

    private fun reconcile(
        existing: Map<String, TrackedFollowedShow> = emptyMap(),
        lib: LibraryUiState,
        progress: WatchProgressUiState = WatchProgressUiState(),
    ) = TrackedShows.reconcile(existing, lib, progress, today)!!

    private fun progressOf(type: String, id: String) = WatchProgressUiState(
        entries = listOf(
            WatchProgressEntry(
                contentType = type, parentMetaId = id, parentMetaType = type, videoId = "$id:1:1",
                title = "Title $id", lastPositionMs = 1, durationMs = 10, lastUpdatedEpochMs = 1,
            ),
        ),
    )

    @Test
    fun `only episodic shows are trackable`() {
        assertTrue(isEpisodicShowType("series", "tt100"))
        assertTrue(isEpisodicShowType(" Series ", "tt100"))
        assertTrue(isEpisodicShowType("anime", "kitsu:1"))
        assertTrue(isEpisodicShowType("series", "xtream:acc:series:5"), "a series through a provider id is still a show")
        assertFalse(isEpisodicShowType("movie", "tt200"))
        assertFalse(isEpisodicShowType("movie", "xtream:acc:movie:3"))
        assertFalse(isEpisodicShowType("channel", "tt1"))
        assertFalse(isEpisodicShowType("", "tt1"))
        assertFalse(isEpisodicShowType(null, null))
    }

    @Test
    fun `a tv item with an own-provider id is a live channel and not a show`() {
        assertFalse(isEpisodicShowType("tv", "xtream:acc:live:42"))
        assertFalse(isEpisodicShowType("TV", "m3u:acc:live:9"))
    }

    @Test
    fun `a tv item with a TMDB or Cinemeta style id is a show`() {
        assertTrue(isEpisodicShowType("tv", "tt0903747"))
        assertTrue(isEpisodicShowType("tv", "tmdb:1396"))
    }

    @Test
    fun `a saved tv show with a plain id is enrolled while a channel is not`() {
        val tmdbShow = LibraryItem(id = "tmdb:1396", type = "tv", name = "Breaking Bad", savedAtEpochMs = 0L)
        val tracked = reconcile(lib = library(tmdbShow, sky))
        assertEquals(setOf("series:tmdb:1396"), tracked.keys)
    }

    @Test
    fun `enrolment from a library of shows and channels yields only the shows`() {
        val tracked = reconcile(lib = library(sky, show, movie, espn, anime))
        assertEquals(setOf("series:tt100", "anime:kitsu:1"), tracked.keys)
        assertTrue(tracked.values.none { it.contentType == "tv" })
    }

    @Test
    fun `a live channel in Continue Watching is not enrolled`() {
        val tracked = reconcile(lib = library(show), progress = progressOf("tv", "xtream:acc:live:1"))
        assertEquals(setOf("series:tt100"), tracked.keys)
    }

    @Test
    fun `an unloaded library enrols nothing`() {
        assertNull(TrackedShows.reconcile(emptyMap(), LibraryUiState(isLoaded = false), WatchProgressUiState(), today))
    }

    @Test
    fun `a stored channel switched on is not kept alive by the keep-switched-on rule`() {
        val stale = TrackedFollowedShow("xtream:acc:live:1", "tv", "2026-01-01", alertsOn = true)
        val tracked = reconcile(existing = mapOf("series:xtream:acc:live:1" to stale), lib = library(show))
        assertEquals(setOf("series:tt100"), tracked.keys)
    }

    @Test
    fun `a payload with channel entries loads with them pruned and the real show switches kept`() {
        val json = Json { ignoreUnknownKeys = true }
        val payload = json.decodeFromString<StoredEpisodeReleaseNotificationsPayload>(
            """{"enabled":true,"followedShows":[
              {"contentId":"tt100","contentType":"series","followedOnIsoDate":"2026-01-01","alertsOn":false},
              {"contentId":"xtream:acc:live:1","contentType":"tv","followedOnIsoDate":"2026-01-01"},
              {"contentId":"m3u:acc:live:9","contentType":"tv","followedOnIsoDate":"2026-01-01","alertsOn":true},
              {"contentId":"tmdb:1396","contentType":"tv","followedOnIsoDate":"2026-01-01","alertsOn":true},
              {"contentId":"tt200","contentType":"movie","followedOnIsoDate":"2026-01-01"}]}""",
        )
        val kept = TrackedShows.prune(payload.followedShows)
        assertEquals(listOf("tt100", "tmdb:1396"), kept.map { it.contentId }, "tv shows with plain ids stay")
        assertEquals(false, kept.first().alertsOn, "the user's switch for a real show survives")
        assertEquals(true, kept.last().alertsOn)
        assertTrue(payload.enabled, "the master switch is untouched")
    }

    @Test
    fun `an old payload without any followed shows prunes to nothing`() {
        val json = Json { ignoreUnknownKeys = true }
        val payload = json.decodeFromString<StoredEpisodeReleaseNotificationsPayload>("""{"enabled":false}""")
        assertEquals(emptyList(), TrackedShows.prune(payload.followedShows))
    }

    @Test
    fun `the N of M count and the list ignore channels`() {
        val tracked = reconcile(lib = library(sky, show, espn, anime))
        val rows = TrackedShows.rows(tracked.values + TrackedFollowedShow("xtream:acc:live:1", "tv", "2026-01-01")) { true }
        assertEquals(listOf("Frieren", "Severance"), rows.map { it.title })
        val state = EpisodeReleaseNotificationsUiState(shows = rows)
        assertEquals(2, state.showsOnCount)
        assertEquals(2, state.shows.size)
    }

    @Test
    fun `the test notification picks a show and never a channel`() {
        assertEquals(show, TrackedShows.testTarget(listOf(sky, movie, show, espn)))
        assertNull(TrackedShows.testTarget(listOf(sky, espn, movie)), "only channels and movies saved")
    }

    @Test
    fun `pinning live channels does not change the show list`() {
        val before = reconcile(lib = library(show, anime))
        val afterPinAll = reconcile(existing = before, lib = library(sky, espn, show, anime, movie))
        assertEquals(before, afterPinAll)
        val rowsBefore = TrackedShows.rows(before.values) { true }
        val rowsAfter = TrackedShows.rows(afterPinAll.values) { true }
        assertEquals(rowsBefore, rowsAfter)
    }
}
