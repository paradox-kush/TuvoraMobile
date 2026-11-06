package com.nuvio.app.features.calendar.internal

/** Shared fixtures for the calendar tests (ported from the Arc fork's tests, ysosrs123/NuvioTV-Fork, GPL-3.0). */
internal fun at(iso: String): Long = checkNotNull(parseCalendarInstantMs(iso)) { "bad instant $iso" }

internal const val HOUR = CALENDAR_HOUR_MS
internal const val DAY = CALENDAR_DAY_MS
internal const val MINUTE = CALENDAR_MINUTE_MS

internal fun showRecord(
    key: String,
    vararg entries: CalendarEntry,
    source: CalendarSource = CalendarSource.TVMAZE,
) = CalendarTitleRecord(
    key = key,
    type = CalendarTitleType.SERIES,
    ids = CalendarIds(imdb = key.substringAfterLast(':')),
    name = key,
    sources = mapOf(source to entries.toList()),
)
