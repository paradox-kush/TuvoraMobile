package com.nuvio.app.features.sportsdetail.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameDetailCodec
import kotlinx.serialization.json.JsonObject

private val adapterJson = Json { ignoreUnknownKeys = true }

/**
 * One GET under a hard per-call deadline, mapped to [SourceResult] with BoundedLoad semantics: the call ENDS
 * (Ok / Empty / Failed) within [deadlineMs] even if the transport ignores cancellation, 404 is Empty, and
 * every other failure — HTTP 5xx, bad JSON, empty body, transport error, timeout — is Failed, never "empty".
 */
internal suspend fun getJson(http: SportsHttp, url: String, deadlineMs: Long, headers: Map<String, String> = emptyMap()): SourceResult<JsonElement> {
    val reply: HttpReply? = try {
        withTimeoutOrNull(deadlineMs) { http.get(url, headers, deadlineMs) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        return SourceResult.Failed(timedOut = false)
    }
    if (reply == null) return SourceResult.Failed(timedOut = true)
    return when {
        reply.status == 404 || reply.status == 400 || reply.status == 204 -> SourceResult.Empty
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

/** TheSportsDB via our backend's `game-detail` function. */
internal class GameDetailBackendSource(
    private val http: SportsHttp,
    private val gameDetailUrl: String,
    private val deadlineMs: Long = 8_000L,
) : TsdbSource {
    override suspend fun detail(eventId: String, leagueId: String?, blocks: Set<String>?): SourceResult<GameDetail> {
        val url = buildString {
            append(gameDetailUrl).append("?event_id=").append(eventId)
            if (!leagueId.isNullOrBlank()) append("&league_id=").append(leagueId)
            if (!blocks.isNullOrEmpty()) append("&blocks=").append(blocks.joinToString(","))
        }
        return when (val r = getJson(http, url, deadlineMs)) {
            is SourceResult.Empty -> SourceResult.Empty
            is SourceResult.Disabled -> SourceResult.Disabled
            is SourceResult.Failed -> r
            is SourceResult.Ok -> {
                val body = r.value as? JsonObject ?: return SourceResult.Failed(timedOut = false)
                if ((body["disabled"] as? JsonPrimitive)?.booleanOrNull == true) SourceResult.Disabled
                else GameDetailCodec.decodeElement(body)?.let { SourceResult.Ok(it) } ?: SourceResult.Failed(timedOut = false)
            }
        }
    }
}
