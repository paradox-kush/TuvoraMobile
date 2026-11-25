package com.nuvio.app.core.telemetry.internal

/**
 * The one event an iPhone/iPad MetricKit payload becomes (`metrickit_payload`). Pure: Swift hands over counts under
 * the closed keys below, this builds the properties. Nothing from the payload except those counts travels: no call
 * stacks, no termination text, no app-version strings, no signposts. An all-routine payload (only normal exits)
 * returns null: routine successes are not events.
 */
internal object MetricKitEvent {
    const val EVENT = "metrickit_payload"
    const val MAX_COUNT = 100_000
    const val MAX_WINDOW_HOURS = 168

    /** `diagnostics` = MXDiagnosticPayload (crash, hang, CPU, disk write); `metrics` = MXMetricPayload exit counts. */
    val SOURCES: Set<String> = setOf("diagnostics", "metrics")

    /** Suffixes of the `mk_*` properties. `fg_*` / `bg_*` are MXForegroundExitData / MXBackgroundExitData counters. */
    val COUNT_KEYS: List<String> = listOf(
        "crash", "hang", "cpu", "disk_write",
        "fg_normal", "fg_memory_limit", "fg_bad_access", "fg_abnormal", "fg_illegal", "fg_watchdog",
        "bg_normal", "bg_memory_limit", "bg_cpu_limit", "bg_memory_pressure", "bg_bad_access",
        "bg_abnormal", "bg_illegal", "bg_watchdog", "bg_suspended_locked", "bg_task_timeout",
    )

    private val routine = setOf("fg_normal", "bg_normal")

    fun build(source: String, counts: Map<String, Int>, windowHours: Int): Map<String, Any>? {
        if (source !in SOURCES) return null
        val out = LinkedHashMap<String, Any>()
        out["metrickit_source"] = source
        var notable = 0
        for (key in COUNT_KEYS) {
            val n = (counts[key] ?: 0).coerceIn(0, MAX_COUNT)
            if (n == 0) continue
            out["mk_$key"] = n
            if (key !in routine) notable += n
        }
        if (notable == 0) return null
        out["mk_window_h"] = windowHours.coerceIn(0, MAX_WINDOW_HOURS)
        return out
    }
}
