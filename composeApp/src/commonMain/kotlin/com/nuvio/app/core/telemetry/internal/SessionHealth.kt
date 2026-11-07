package com.nuvio.app.core.telemetry.internal

/**
 * Per-session counters that replace per-event streams: successes are counted here and shipped as one
 * `session_health` event on background/exit; failures are still sent individually. Counts are derived from
 * closed vocabularies (event names, outcomes, surfaces, engines), so the key space stays small.
 */
internal class SessionHealth(private val startedAtMs: Long, private val maxKeys: Int = 80) {
    private val lock = TelemetryLock()
    private val counts = LinkedHashMap<String, Int>()
    private var peakJavaMb = 0L
    private var peakPssMb = 0L

    fun count(key: String, by: Int = 1) {
        val safe = key.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(48)
        if (safe.isEmpty()) return
        lock.withLock {
            if (safe !in counts && counts.size >= maxKeys) return
            counts[safe] = (counts[safe] ?: 0) + by
        }
    }

    /** Folds a counted routine event into keys such as `iptv_load_hub_categories_loaded` or `playback_started_exo`. */
    fun countEvent(event: String, props: Map<String, Any>) {
        val parts = buildList {
            add(event)
            when (event) {
                "iptv_load" -> { (props["surface"] as? String)?.let(::add); (props["outcome"] as? String)?.let(::add) }
                "epg_ingest" -> { (props["source"] as? String)?.let(::add); (props["outcome"] as? String)?.let(::add) }
                "playback_started" -> { (props["engine"] as? String)?.let(::add); (props["kind"] as? String)?.let(::add) }
                "app_exit" -> (props["reason"] as? String)?.let(::add)
            }
        }
        count(parts.joinToString("_"))
    }

    fun noteMemory(javaMb: Long?, pssMb: Long?) = lock.withLock {
        if (javaMb != null && javaMb > peakJavaMb) peakJavaMb = javaMb
        if (pssMb != null && pssMb > peakPssMb) peakPssMb = pssMb
    }

    /** The `session_health` payload; null when nothing happened (an idle session sends nothing). */
    fun summary(nowMs: Long): Map<String, Any>? = lock.withLock {
        // Memory peaks alone do not make a session worth a summary: an idle session sends nothing.
        if (counts.isEmpty()) return@withLock null
        buildMap {
            put("schema_version", 1)
            put("session_ms", (nowMs - startedAtMs).coerceAtLeast(0L))
            for ((k, v) in counts) put("n_$k", v)
            if (peakJavaMb > 0) put("peak_java_mb", peakJavaMb)
            if (peakPssMb > 0) put("peak_pss_mb", peakPssMb)
        }
    }
}
