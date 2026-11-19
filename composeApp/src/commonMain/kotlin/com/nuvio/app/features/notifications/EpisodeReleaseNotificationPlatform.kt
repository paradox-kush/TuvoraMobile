package com.nuvio.app.features.notifications

internal expect object EpisodeReleaseNotificationPlatform {
    suspend fun notificationsAuthorized(): Boolean
    suspend fun requestAuthorization(): Boolean
    suspend fun scheduleEpisodeReleaseNotifications(requests: List<EpisodeReleaseNotificationRequest>)
    suspend fun clearScheduledEpisodeReleaseNotifications()
    suspend fun showTestNotification(request: EpisodeReleaseNotificationRequest)

    /** The app was opened at [epochMs]. Android keeps it where a cold-started alert worker can read it; others ignore it. */
    suspend fun recordAppOpened(epochMs: Long)

    /** Opens the phone's own notification settings for this app (the way back from a refused permission). */
    fun openNotificationSettings()
}
