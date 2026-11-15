package com.nuvio.app.features.sportsdetail.internal

import co.touchlab.kermit.Logger
import com.nuvio.app.core.analytics.AnalyticsSink
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * `sports_detail` — one event per (sport, block, source, outcome) transition, with the fetch duration. Never a URL,
 * a host, a team, a player or an error message (repo CLAUDE.md "outcomes are reported"). Outcomes: loaded | empty |
 * failed | timeout. A regression in a provider or a mapping edit shows up in PostHog as a block flipping to
 * empty/failed for a sport within a day.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object SportsDetailTelemetry {
    const val EVENT = "sports_detail"
    private val log = Logger.withTag("SportsDetail")
    private const val MAX_NON_LOADED_PER_KEY = 10
    /** Copy-on-write under a CAS loop: sends can come from several screens' coroutines at once. */
    private val sent = AtomicReference<Map<String, Int>>(emptyMap())

    fun properties(sport: String, block: String, source: String, outcome: String, durationMs: Long): Map<String, Any> = mapOf(
        "sport" to sport,
        "block" to block,
        "source" to source,
        "outcome" to outcome,
        "duration_ms" to durationMs,
    )

    /** Production sink. Failure/empty outcomes are capped per key per process so a dead provider cannot flood. */
    fun send(props: Map<String, Any>) {
        // Local breadcrumb for device passes (logcat / Xcode console, tag SportsDetail). Same payload as PostHog: no URLs, hosts or teams.
        runCatching { log.i { "$EVENT $props" } }
        if (props["outcome"] != "loaded") {
            val key = "${props["sport"]}|${props["block"]}|${props["source"]}|${props["outcome"]}"
            while (true) {
                val cur = sent.load()
                val n = cur[key] ?: 0
                if (n >= MAX_NON_LOADED_PER_KEY) return
                if (sent.compareAndSet(cur, cur + (key to n + 1))) break
            }
        }
        runCatching { AnalyticsSink.capture(EVENT, props) }
    }

    /** A remote mapping was refused. Reason is a short CODE (never the free-text reason, a URL or the body). */
    fun mappingRejected(code: String, durationMs: Long): Map<String, Any> = mapOf(
        "sport" to "mapping", "block" to "mapping", "source" to "remote", "outcome" to "rejected", "reason" to code, "duration_ms" to durationMs,
    )
}
