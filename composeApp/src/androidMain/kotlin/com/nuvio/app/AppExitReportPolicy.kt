package com.nuvio.app

/**
 * Decides whether an [android.app.ApplicationExitInfo] record is worth reporting to analytics, or is
 * normal OS lifecycle that would skew crash metrics.
 *
 * On low-RAM devices the OS SIGKILLs a *cached* (backgrounded) process to reclaim memory when the
 * user switches apps; Android surfaces that as `REASON_SIGNALED` + `SIGKILL` (status 9) with a
 * background importance (and on devices that don't report `REASON_LOW_MEMORY`, it's the only way a
 * reclaim shows up). Per Android's docs that is normal resource management, not a crash.
 *
 * Rule (mirrors Google Play vitals' "user-perceived crash" concept):
 *  - App faults — crash / native_crash / anr / initialization_failure — ALWAYS report.
 *  - OS-initiated reclaim — signaled / low_memory_kill / excessive_resource_usage — reports only when
 *    the process was still USER-VISIBLE when it died; a cached/background kill is dropped.
 *
 * Pure + unit-tested: no Android types, just the reason string + the raw importance int.
 */
object AppExitReportPolicy {
    /**
     * Max `ActivityManager.RunningAppProcessInfo` importance that still counts as user-visible.
     * Foreground=100 .. cant-save-state=270 are user-visible; service=300, cached=400, gone=1000 are
     * background. Lower = more foreground. A literal so it compiles on every API level.
     */
    const val USER_VISIBLE_IMPORTANCE_MAX = 270

    private val APP_FAULT = setOf("crash", "native_crash", "anr", "initialization_failure")
    private val OS_RECLAIM = setOf("signaled", "low_memory_kill", "excessive_resource_usage")

    /** @param importance the raw `ApplicationExitInfo.getImportance()` value (0/unknown when unset). */
    fun shouldReport(reason: String, importance: Int): Boolean = when (reason) {
        in APP_FAULT -> true
        in OS_RECLAIM -> importance in 1..USER_VISIBLE_IMPORTANCE_MAX
        else -> false
    }
}
