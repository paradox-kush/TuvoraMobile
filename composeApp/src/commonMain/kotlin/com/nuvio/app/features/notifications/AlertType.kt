package com.nuvio.app.features.notifications

/**
 * What kind of notification a candidate would become. Closed on purpose: the gate decides about every OS notification
 * Tuvora schedules, so a new kind (in-app sports alerts have their own limits and are not here; a closed-app sports
 * alert would be `sports`) is added here, in one place, and enters through [NotificationGatePolicy.plan].
 */
internal enum class AlertType(val wire: String) {
    /** A new episode of a followed show aired. */
    Episode("episode"),

    /** "While you were out": one reminder after a stretch away. */
    Away("away");

    companion object {
        val All: Set<AlertType> = entries.toSet()

        /** Null for a string this build does not know (a newer app or the website wrote it): ignored, never thrown. */
        fun fromWire(value: String?): AlertType? = entries.firstOrNull { it.wire == value }

        /** The known types in [raw]; anything unknown, blank or null is dropped. */
        fun parseAll(raw: Iterable<String?>): Set<AlertType> = raw.mapNotNull(::fromWire).toSet()

        /** Which kinds may be planned right now. Pausing everything leaves none, whatever else is on. */
        fun enabled(episodeAlertsOn: Boolean, awayReminderOn: Boolean, allPaused: Boolean): Set<AlertType> {
            if (allPaused) return emptySet()
            return buildSet {
                if (episodeAlertsOn) add(Episode)
                if (awayReminderOn) add(Away)
            }
        }
    }
}
