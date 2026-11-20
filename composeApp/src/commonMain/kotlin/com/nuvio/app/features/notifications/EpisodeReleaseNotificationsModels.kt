package com.nuvio.app.features.notifications

import com.nuvio.app.core.time.parseEpisodeReleaseLocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_episode_code_episode_only
import nuvio.composeapp.generated.resources.compose_player_episode_code_full
import nuvio.composeapp.generated.resources.notifications_episode_release_body_code
import nuvio.composeapp.generated.resources.notifications_episode_release_body_code_title
import nuvio.composeapp.generated.resources.notifications_episode_release_body_generic
import nuvio.composeapp.generated.resources.notifications_episode_release_body_title
import org.jetbrains.compose.resources.getString
import kotlin.math.abs

data class EpisodeReleaseNotificationsUiState(
    /** The master switch. Off for every new install; a stored opt-out is never turned back on by the app. */
    val isEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val permissionGranted: Boolean = false,
    /** The OS permission was refused or revoked: every alert control says so and offers the phone's settings. */
    val permissionDenied: Boolean = false,
    val scheduledCount: Int = 0,
    val testTargetTitle: String? = null,
    val isSendingTest: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val preferences: AlertPreferences = AlertPreferences(),
    /** Viewing sessions and distinct days seen so far, for the "learning" line. */
    val habitSessions: Int = 0,
    val habitDays: Int = 0,
    /** The usual start time for today's kind of day (weekday or weekend), or null while still learning. */
    val usualMinute: Int? = null,
    /** When an alert would go out today under the current timing choice and quiet hours. */
    val alertMinute: Int = AlertTimes.DefaultMinute,
    val quietConflict: QuietHoursConflict? = null,
    /** Alerts are being sent less often because several were not opened; Undo returns to the chosen cap. */
    val sendingLessOften: Boolean = false,
    val shows: List<ShowAlertRow> = emptyList(),
    /** The viewer reset the learned time and there is not yet enough history again. */
    val learningAgain: Boolean = false,
    /** The permission has been asked for on this device (any answer), so "not granted" means refused, not unasked. */
    val permissionAsked: Boolean = false,
) {
    val osPermission: NotificationPermission
        get() = when {
            permissionGranted -> NotificationPermission.Granted
            permissionAsked || permissionDenied -> NotificationPermission.Denied
            else -> NotificationPermission.NotAsked
        }
    val awayGroup: AwayGroupState get() = AwayGroupPolicy.state(osPermission, isEnabled)
    /** The away reminder in effect: what the viewer chose, else 7 days only while alerts can reach this phone. */
    val awayReminder: AwayReminder
        get() = AwayReminderPolicy.effective(preferences.awayReminder, permissionGranted, isEnabled)
    val isLearning: Boolean get() = usualMinute == null
    val showsOnCount: Int get() = shows.count { it.on }
}

/** One followed show in Settings › Notifications › Shows with alerts. */
data class ShowAlertRow(
    val contentType: String,
    val contentId: String,
    val title: String,
    val on: Boolean,
)

@Serializable
internal data class StoredEpisodeReleaseNotificationsPayload(
    val enabled: Boolean = false,
    val followedShows: List<TrackedFollowedShow> = emptyList(),
    val preferences: StoredAlertPreferences = StoredAlertPreferences(),
    /** Shows the end-of-episode prompt has already been shown for (once per show). */
    val promptedShowKeys: List<String> = emptyList(),
)

@Serializable
internal data class TrackedFollowedShow(
    val contentId: String,
    val contentType: String,
    val followedOnIsoDate: String,
    val title: String? = null,
    /** [FollowSource.wire]. Payloads written before alert controls existed decode as saved (library) shows. */
    val source: String = FollowSource.Library.wire,
    /** The viewer's own choice; null means the default for [source]. */
    val alertsOn: Boolean? = null,
)

/** Device-local bookkeeping that is never synced: what was scheduled, what was sent and what the viewer did about it. */
@Serializable
internal data class StoredAlertRuntime(
    val ignoredStreak: Int = 0,
    val backOffNoticeShown: Boolean = false,
    val sentDates: List<String> = emptyList(),
    val openedDates: List<String> = emptyList(),
    val scheduled: List<ScheduledAlertRecord> = emptyList(),
    val lastOpenedAtMs: Long = 0L,
    /** Set by Reset on the learned time; cleared once the habit is ready again. */
    val learningAgain: Boolean = false,
    /** The OS permission has been asked for on this device. */
    val permissionAsked: Boolean = false,
)

@Serializable
internal data class ScheduledAlertRecord(
    val fireAtMs: Long,
    val withNotice: Boolean = false,
)

@Serializable
internal data class StoredViewingHistory(
    val starts: List<String> = emptyList(),
)

internal data class EpisodeReleaseNotificationRequest(
    val requestId: String,
    val notificationTitle: String,
    val notificationBody: String,
    val releaseDateIso: String,
    val deepLinkUrl: String,
    val backdropUrl: String? = null,
    /** When to show it, in epoch milliseconds. Zero for a request that is shown at once (the test notification). */
    val fireAtEpochMs: Long = 0L,
    /** Quiet hours as chosen when this was scheduled, so a platform that runs code at fire time can re-check. */
    val quietHours: QuietHours = QuietHours(enabled = false),
    /** The one-per-absence reminder: a platform that runs code at fire time also skips it if another alert went out that day. */
    val isAwayReminder: Boolean = false,
)
internal const val MinReasonableSavedAtEpochMs = 946684800000L

internal fun buildTrackedShowKey(
    type: String,
    id: String,
): String = "${normalizeSeriesType(type)}:${id.trim()}"

internal fun normalizeSeriesType(type: String): String = when (type.trim().lowercase()) {
    "tv", "show", "series", "tvshow" -> "series"
    else -> type.trim().lowercase()
}

internal fun releaseDateIso(rawValue: String?): String? {
    return parseEpisodeReleaseLocalDate(rawValue)
}

internal fun buildEpisodeReleaseNotificationId(
    profileId: Int,
    contentType: String,
    contentId: String,
    episodeId: String,
    releaseDateIso: String,
): String {
    val contentHash = abs(buildTrackedShowKey(contentType, contentId).hashCode())
    val episodeHash = abs(episodeId.trim().ifBlank { releaseDateIso }.hashCode())
    return "episode-release-$profileId-$contentHash-$episodeHash-$releaseDateIso"
}

internal fun buildEpisodeCodeText(
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = runBlocking {
    when {
        seasonNumber != null && episodeNumber != null ->
            getString(Res.string.compose_player_episode_code_full, seasonNumber, episodeNumber)
        episodeNumber != null ->
            getString(Res.string.compose_player_episode_code_episode_only, episodeNumber)
        else -> ""
    }
}

internal fun buildEpisodeReleaseNotificationBody(
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
): String = runBlocking {
    val code = buildEpisodeCodeText(seasonNumber, episodeNumber)
    val title = episodeTitle?.trim().takeUnless { it.isNullOrBlank() }

    when {
        code.isNotBlank() && title != null ->
            getString(Res.string.notifications_episode_release_body_code_title, code, title)
        code.isNotBlank() ->
            getString(Res.string.notifications_episode_release_body_code, code)
        title != null ->
            getString(Res.string.notifications_episode_release_body_title, title)
        else ->
            getString(Res.string.notifications_episode_release_body_generic)
    }
}
