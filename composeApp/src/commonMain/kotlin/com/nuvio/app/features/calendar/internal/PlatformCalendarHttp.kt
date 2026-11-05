package com.nuvio.app.features.calendar.internal

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.withTimeoutOrNull

/** Whole-call limit per request (repo rule: set per call, never client-wide). Calendar bodies are small JSON. */
private const val CALENDAR_CALL_LIMIT_MS = 60_000L

/** Calendar bodies are small JSON; anything bigger than this is not a calendar answer. */
private const val CALENDAR_MAX_BODY_BYTES = 2 * 1024 * 1024

internal object PlatformCalendarHttp : CalendarHttp {
    override suspend fun get(url: String, headers: Map<String, String>): CalendarHttpResponse {
        // withTimeoutOrNull, not withTimeout: a TimeoutCancellationException is a CancellationException and the
        // fetcher rethrows those, so a slow server would cancel the whole refresh instead of failing one title.
        val response = withTimeoutOrNull(CALENDAR_CALL_LIMIT_MS) {
            httpRequestRaw(
                method = "GET",
                url = url,
                headers = mapOf("Accept" to "application/json") + headers,
                body = "",
                followRedirects = true,
                maxResponseBodyBytes = CALENDAR_MAX_BODY_BYTES,
            )
        } ?: throw CalendarFetchException("timed out after ${CALENDAR_CALL_LIMIT_MS / 1000}s")
        return CalendarHttpResponse(response.status, response.body, response.headers)
    }
}
