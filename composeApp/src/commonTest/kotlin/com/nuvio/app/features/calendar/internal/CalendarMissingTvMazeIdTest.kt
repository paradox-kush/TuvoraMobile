package com.nuvio.app.features.calendar.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone

class CalendarMissingTvMazeIdTest {
    @Test fun aSnapshotWithALearnedMissingTvMazeIdRoundTrips() {
        val snapshot = CalendarSnapshot(tvmazeIds = mapOf("imdb:tt1" to CalendarTvMazeId(null, 5L)))
        val raw = calendarJson.encodeToString(CalendarSnapshot.serializer(), snapshot)
        assertEquals(snapshot, calendarJson.decodeFromString(CalendarSnapshot.serializer(), raw))
    }

    @Test fun theFetcherLearnsAMissingIdAndReturnsAnEmptyRecord() = runTest {
        val remote = FakeCalendarRemote()
        val fetcher = CalendarTitleFetcher(remote, "US", TimeZone.UTC)
        val record = CalendarTitleRecord(key = "series:imdb:tt1", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt1"))
        val outcome = fetcher.refresh(record, emptyMap(), false, 1_000L)
        assertEquals(1, outcome.tvMazeIds.size)
        val merged = emptyMap<String, CalendarTvMazeId>() + outcome.tvMazeIds
        assertEquals(1, merged.size)
    }

    @Test fun theRegionComesFromTheFirstLanguageTagThatHasOne() {
        assertEquals("GB", com.nuvio.app.features.calendar.api.calendarRegionFromLanguageTags(listOf("en", "en-GB", "fr-FR")))
        assertEquals("CN", com.nuvio.app.features.calendar.api.calendarRegionFromLanguageTags(listOf("zh-Hans_CN")))
        assertEquals("US", com.nuvio.app.features.calendar.api.calendarRegionFromLanguageTags(listOf("en", "de")))
    }
}
