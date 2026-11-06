@file:OptIn(ExperimentalTime::class)

package com.nuvio.app.features.calendar.internal

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/*
 * Time helpers for the Calendar. Every instant is epoch milliseconds (a Long), every calendar date is a
 * kotlinx-datetime LocalDate, and every date <-> instant conversion names its zone: there is no
 * "system default" anywhere in the calendar logic (Arc's sortInstant defaulted to it - fix 4).
 */

const val CALENDAR_MINUTE_MS = 60_000L
const val CALENDAR_HOUR_MS = 3_600_000L
const val CALENDAR_DAY_MS = 86_400_000L

fun parseCalendarDate(value: String?): LocalDate? =
    value?.trim()?.takeIf { it.length == 10 }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** An ISO-8601 instant with `Z` or an offset (`2026-10-10T01:00:00-04:00`, `…T05:00:00.000Z`). */
fun parseCalendarInstantMs(value: String?): Long? =
    value?.trim()?.takeIf(String::isNotEmpty)?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

fun calendarDateAt(epochMs: Long, zone: TimeZone): LocalDate =
    Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date

fun calendarTimeAt(epochMs: Long, zone: TimeZone): LocalTime =
    Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).time

fun LocalDate.startOfDayMs(zone: TimeZone): Long = atStartOfDayIn(zone).toEpochMilliseconds()

fun LocalDate.atTimeMs(time: LocalTime, zone: TimeZone): Long =
    LocalDateTime(this, time).toInstant(zone).toEpochMilliseconds()

fun LocalDate.plusDays(days: Int): LocalDate = plus(days, DateTimeUnit.DAY)

fun LocalDate.plusMonths(months: Int): LocalDate = plus(months, DateTimeUnit.MONTH)

/** A valid IANA zone id, or null. */
fun calendarZoneOf(id: String?): TimeZone? =
    id?.trim()?.takeIf(String::isNotEmpty)?.let { runCatching { TimeZone.of(it) }.getOrNull() }

/** The zone the Calendar counts days in: the viewer's override when it is a real zone, else the device's. */
fun resolveCalendarZone(override: String?, deviceZone: TimeZone): TimeZone = calendarZoneOf(override) ?: deviceZone

fun normalizeCalendarZoneOverride(override: String?): String? = calendarZoneOf(override)?.id
