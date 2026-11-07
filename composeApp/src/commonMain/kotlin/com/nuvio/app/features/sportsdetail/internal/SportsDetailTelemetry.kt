package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.core.analytics.AnalyticsSink

/**
 * `sports_detail` — one event per (sport, block, source, outcome) transition, with the fetch duration. Never a URL,
 * a host, a team, a player or an error message (repo CLAUDE.md "outcomes are reported"). Outcomes: loaded | empty |
 * failed | timeout. A regression in a provider or a mapping edit shows up in PostHog as a block flipping to
 * empty/failed for a sport within a day.
 */
internal object SportsDetailTelemetry {
    const val EVENT = "sports_detail"
    private const val MAX_NON_LOADED_PER_KEY = 10
    private val sent = mutableMapOf<String, Int>()

    fun properties(sport: String, block: String, source: String, outcome: String, durationMs: Long): Map<String, Any> = mapOf(
        "sport" to sport,
        "block" to block,
        "source" to source,
        "outcome" to outcome,
        "duration_ms" to durationMs,
    )

    /** Production sink. Failure/empty outcomes are capped per key per process so a dead provider cannot flood. */
    fun send(props: Map<String, Any>) {
        if (props["outcome"] != "loaded") {
            val key = "${props["sport"]}|${props["block"]}|${props["source"]}|${props["outcome"]}"
            val n = sent[key] ?: 0
            if (n >= MAX_NON_LOADED_PER_KEY) return
            sent[key] = n + 1
        }
        runCatching { AnalyticsSink.capture(EVENT, props) }
    }
}
