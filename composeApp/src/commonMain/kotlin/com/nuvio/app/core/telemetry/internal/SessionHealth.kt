package com.nuvio.app.core.telemetry.internal

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Counters that replace per-event streams: successes are counted here and shipped as one `session_health` event when
 * the app goes to the background; failures are still sent individually. Counts are derived from closed vocabularies
 * (event names, outcomes, surfaces, engines), so the key space stays small.
 *
 * Counts are never dropped on a lifecycle change. A summary is [snapshot]ted, sent, and only a SUCCESSFUL send
 * [commit]s it (subtracting exactly what was sent, so increments that landed meanwhile survive).
 */
internal class SessionHealth(private var windowStartMs: Long, private val maxKeys: Int = 80) {
    private val lock = SynchronizedObject()
    private val counts = LinkedHashMap<String, Int>()
    private var peakJavaMb = 0L
    private var peakPssMb = 0L

    /** What a [snapshot] captured: the payload plus the exact counts it contains. */
    class Pending(val payload: Map<String, Any>, internal val sent: Map<String, Int>)

    fun count(key: String, by: Int = 1) {
        val safe = key.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(48)
        if (safe.isEmpty()) return
        synchronized(lock) {
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
                "epg_mapping" -> (props["outcome"] as? String)?.let(::add)
                "playback_started" -> { (props["engine"] as? String)?.let(::add); (props["kind"] as? String)?.let(::add) }
                "app_exit" -> (props["reason"] as? String)?.let(::add)
            }
        }
        count(parts.joinToString("_"))
    }

    fun noteMemory(javaMb: Long?, pssMb: Long?) = synchronized(lock) {
        if (javaMb != null && javaMb > peakJavaMb) peakJavaMb = javaMb
        if (pssMb != null && pssMb > peakPssMb) peakPssMb = pssMb
    }

    /** The `session_health` payload; null when nothing was counted (an idle session sends nothing, never an empty summary). */
    fun snapshot(nowMs: Long): Pending? = synchronized(lock) {
        if (counts.isEmpty()) return@synchronized null
        val sent = LinkedHashMap(counts)
        val payload = buildMap<String, Any> {
            put("schema_version", 1)
            put("window_ms", (nowMs - windowStartMs).coerceAtLeast(0L))
            for ((k, v) in sent) put("n_$k", v)
            if (peakJavaMb > 0) put("peak_java_mb", peakJavaMb)
            if (peakPssMb > 0) put("peak_pss_mb", peakPssMb)
        }
        Pending(payload, sent)
    }

    /** Call only after the summary was handed to the SDK without error. */
    fun commit(pending: Pending, nowMs: Long) = synchronized(lock) {
        for ((k, v) in pending.sent) {
            val left = (counts[k] ?: 0) - v
            if (left > 0) counts[k] = left else counts.remove(k)
        }
        peakJavaMb = 0L
        peakPssMb = 0L
        windowStartMs = nowMs
    }

    /** Consent is off: nothing will be sent, so do not hoard the counts. */
    fun discard(nowMs: Long) = synchronized(lock) {
        counts.clear(); peakJavaMb = 0L; peakPssMb = 0L; windowStartMs = nowMs
    }
}
