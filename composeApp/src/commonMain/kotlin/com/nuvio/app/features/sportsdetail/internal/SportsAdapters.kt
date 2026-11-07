package com.nuvio.app.features.sportsdetail.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

private val adapterJson = Json { ignoreUnknownKeys = true }

/**
 * One GET under a hard per-call deadline, mapped to [SourceResult] with BoundedLoad semantics: the call ENDS
 * (Ok / Empty / Failed) within [deadlineMs] even if the transport ignores cancellation, 404 is Empty, and
 * every other failure — HTTP 5xx, bad JSON, empty body, transport error, timeout — is Failed, never "empty".
 */
private suspend fun getJson(http: SportsHttp, url: String, deadlineMs: Long, headers: Map<String, String> = emptyMap()): SourceResult<JsonElement> {
    val reply: HttpReply? = try {
        withTimeoutOrNull(deadlineMs) { http.get(url, headers, deadlineMs) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        return SourceResult.Failed(timedOut = false)
    }
    if (reply == null) return SourceResult.Failed(timedOut = true)
    return when {
        reply.status == 404 || reply.status == 204 -> SourceResult.Empty
        reply.status !in 200..299 -> SourceResult.Failed(timedOut = false)
        reply.body.isNullOrBlank() -> SourceResult.Failed(timedOut = false)
        else -> try {
            adapterJson.parseToJsonElement(reply.body).let { if (it is JsonObject || it is JsonArray) SourceResult.Ok(it) else SourceResult.Failed(timedOut = false) }
        } catch (_: Throwable) {
            SourceResult.Failed(timedOut = false)
        }
    }
}

/** ESPN site API; URL templates and the deadline come from the (remotely patchable) mapping. */
internal class EspnHttpSource(
    private val http: SportsHttp,
    private val deadlineOverrideMs: Long? = null,
    private val mapping: () -> SportsMapping,
) : EspnSource {
    override suspend fun scoreboard(espnPath: String, yyyymmdd: String): SourceResult<JsonElement> {
        val m = mapping()
        val url = m.espn.scoreboardTemplate.replace("{path}", espnPath).replace("{yyyymmdd}", yyyymmdd)
        return getJson(http, url, deadlineOverrideMs ?: m.espn.deadlineMs)
    }

    override suspend fun summary(espnPath: String, eventId: String): SourceResult<JsonElement> {
        val m = mapping()
        val url = m.espn.summaryTemplate.replace("{path}", espnPath).replace("{id}", eventId)
        return getJson(http, url, deadlineOverrideMs ?: m.espn.deadlineMs)
    }
}

/** TheSportsDB via our backend. */
internal class TheSportsDbHttpSource(
    private val http: SportsHttp,
    private val baseUrl: String = SportsDetailEndpoints.TSDB_BASE,
    private val deadlineMs: Long = 8_000L,
) : TsdbSource {
    override suspend fun event(idEvent: String): SourceResult<JsonObject> =
        firstRow(getJson(http, "$baseUrl/lookup/event/$idEvent", deadlineMs))

    override suspend fun lookup(idEvent: String, kind: TsdbLookup): SourceResult<JsonObject> =
        wrapRows(getJson(http, "$baseUrl/lookup/${kind.path}/$idEvent", deadlineMs))

    /** `{"lookup":[row,...]}` (or `events`/`schedule`) -> the first row. */
    private fun firstRow(r: SourceResult<JsonElement>): SourceResult<JsonObject> = when (r) {
        is SourceResult.Ok -> rows(r.value).firstOrNull()?.let { SourceResult.Ok(it) } ?: SourceResult.Empty
        is SourceResult.Empty -> SourceResult.Empty
        is SourceResult.Failed -> r
    }

    /** Keeps the envelope but turns "no rows" into Empty. */
    private fun wrapRows(r: SourceResult<JsonElement>): SourceResult<JsonObject> = when (r) {
        is SourceResult.Ok -> if (rows(r.value).isEmpty()) SourceResult.Empty else SourceResult.Ok(r.value as JsonObject)
        is SourceResult.Empty -> SourceResult.Empty
        is SourceResult.Failed -> r
    }

    private fun rows(e: JsonElement): List<JsonObject> {
        val obj = e as? JsonObject ?: return emptyList()
        val arr = listOf("lookup", "events", "schedule", "livescore").firstNotNullOfOrNull { k -> (obj[k] as? JsonArray) } ?: return emptyList()
        return arr.filter { it !is JsonNull }.mapNotNull { it as? JsonObject }
    }

    companion object {
        /** Rows of a lookup envelope, for the assemblers. */
        fun rowsOf(e: JsonObject): List<JsonObject> {
            val arr = listOf("lookup", "events", "schedule", "livescore").firstNotNullOfOrNull { k -> (e[k] as? JsonArray) } ?: return emptyList()
            return arr.mapNotNull { it as? JsonObject }
        }
    }
}
