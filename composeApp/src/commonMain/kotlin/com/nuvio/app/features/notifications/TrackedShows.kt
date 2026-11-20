package com.nuvio.app.features.notifications

import com.nuvio.app.core.contracts.isEpisodicShowType
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryUiState
import com.nuvio.app.features.watchprogress.WatchProgressUiState

/**
 * Which titles the alert store tracks. Only episodic shows (see [isEpisodicShowType]): the Library also holds live
 * channels (type `tv`) and movies, and neither has an episode release date. Enrolment, the per-show list, the
 * "N of M" count, the test-notification picker and the load-time migration all go through here.
 */
internal object TrackedShows {
    fun isTrackable(type: String, contentId: String): Boolean = isEpisodicShowType(type, contentId)

    /** Drops what is not an episodic show, keeping everything about the shows that stay (their switches, dates). */
    fun prune(shows: List<TrackedFollowedShow>): List<TrackedFollowedShow> =
        shows.filter { isTrackable(it.contentType, it.contentId) }

    /**
     * The next set of tracked shows from the Library and Continue Watching, or null while the Library has not loaded.
     * [existing] supplies each show's follow date, title and switch.
     */
    fun reconcile(
        existing: Map<String, TrackedFollowedShow>,
        library: LibraryUiState,
        progress: WatchProgressUiState,
        today: String,
        followedOn: (LibraryItem) -> String = { today },
    ): Map<String, TrackedFollowedShow>? {
        if (!library.isLoaded) return null

        val next = linkedMapOf<String, TrackedFollowedShow>()

        library.items.filter { isTrackable(it.type, it.id) }.forEach { item ->
            val key = buildTrackedShowKey(item.type, item.id)
            val old = existing[key]
            next[key] = (old ?: TrackedFollowedShow(
                contentId = item.id,
                contentType = item.type,
                followedOnIsoDate = followedOn(item),
            )).copy(title = item.name.ifBlank { old?.title }, source = FollowSource.Library.wire)
        }

        // Series being watched, whether or not they are saved. They are listed with their switch off until the
        // viewer turns one on (see ShowAlertDefaults).
        progress.entries
            .filter { isTrackable(it.parentMetaType, it.parentMetaId) && it.parentMetaId !in progress.hiddenContentIds }
            .forEach { entry ->
                val key = buildTrackedShowKey(entry.parentMetaType, entry.parentMetaId)
                if (key in next) return@forEach
                val old = existing[key]
                next[key] = (old ?: TrackedFollowedShow(
                    contentId = entry.parentMetaId,
                    contentType = entry.parentMetaType,
                    followedOnIsoDate = today,
                )).copy(title = entry.title.ifBlank { old?.title }, source = FollowSource.ContinueWatching.wire)
            }

        // A show the viewer switched on stays, even if it has left both lists: dropping it would silently end alerts.
        existing.forEach { (key, show) ->
            if (key !in next && show.alertsOn == true && isTrackable(show.contentType, show.contentId)) next[key] = show
        }
        return next
    }

    /** The rows of Settings > Notifications > Shows with alerts; their count is the "M" of "N of M". */
    fun rows(shows: Collection<TrackedFollowedShow>, isOn: (TrackedFollowedShow) -> Boolean): List<ShowAlertRow> =
        shows.filter { isTrackable(it.contentType, it.contentId) }
            .map { show ->
                ShowAlertRow(
                    contentType = show.contentType,
                    contentId = show.contentId,
                    title = show.title?.takeIf { it.isNotBlank() } ?: show.contentId,
                    on = isOn(show),
                )
            }
            .sortedBy { it.title.lowercase() }

    /** What the Send-test notification is about: the first saved show. A channel or movie is never the pick. */
    fun testTarget(items: List<LibraryItem>): LibraryItem? = items.firstOrNull { isTrackable(it.type, it.id) }
}
