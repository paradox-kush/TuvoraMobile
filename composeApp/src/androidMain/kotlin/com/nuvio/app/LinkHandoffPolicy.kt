package com.nuvio.app

/**
 * Which activity class a link handed over by [LinkActivity] goes to. Pure (class names in, class name out).
 */
internal object LinkHandoffPolicy {
    /**
     * The activity already on top when the app runs (it gets the link through onNewIntent), else the launcher icon
     * (cold start). Starting the launcher icon while ANOTHER MainActivity class roots the task (started directly,
     * or after the icon changed) stacked a second instance whose hidden twin consumed the link.
     */
    fun target(liveActivities: List<String>, launcherClass: String): String = liveActivities.lastOrNull() ?: launcherClass
}

/** The MainActivity instances alive in this process, oldest first (the last one is on top). */
internal object LiveMainActivities {
    private val live = mutableListOf<String>()

    @Synchronized fun created(className: String) { live += className }

    @Synchronized fun destroyed(className: String) {
        val index = live.lastIndexOf(className)
        if (index >= 0) live.removeAt(index)
    }

    @Synchronized fun snapshot(): List<String> = live.toList()
}
