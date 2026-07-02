package com.nuvio.app.features.radar

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/**
 * Calls the radar-fixtures Supabase edge function (TheSportsDB proxy + cache). The paid API
 * key never ships in the app — this is the only fixtures endpoint the client knows.
 * verify_jwt=false server-side, so this works in the local-anonymous (signed-out) state too.
 */
internal object RadarFixturesClient {
    private val log = Logger.withTag("RadarFixturesClient")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(leagueIds: Collection<String>, livescoreSports: Collection<String>): RadarFixturesResponse? {
        if (leagueIds.isEmpty() && livescoreSports.isEmpty()) return null
        return runCatching {
            val body = buildJsonObject {
                put("league_ids", buildJsonArray { leagueIds.forEach { add(it) } })
                put("livescore_sports", buildJsonArray { livescoreSports.forEach { add(it) } })
            }
            val response = SupabaseProvider.client.functions.invoke(
                function = "radar-fixtures",
                body = body,
            )
            json.decodeFromString<RadarFixturesResponse>(response.bodyAsText())
        }.onFailure { e -> log.e(e) { "fetch — FAILED" } }.getOrNull()
    }
}
