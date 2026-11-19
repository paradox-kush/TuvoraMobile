package com.nuvio.app.features.notifications

// tvOS user notifications can only badge the app icon; they cannot show an alert or play a sound, so a
// "new episode is out" banner has no Apple TV equivalent. Named gap: authorization is reported as
// unavailable, which makes the shared repository skip scheduling, and the settings toggle stays off.
internal actual object EpisodeReleaseNotificationPlatform {
    actual suspend fun notificationsAuthorized(): Boolean = false
    actual suspend fun requestAuthorization(): Boolean = false
    actual suspend fun scheduleEpisodeReleaseNotifications(requests: List<EpisodeReleaseNotificationRequest>) = Unit
    actual suspend fun clearScheduledEpisodeReleaseNotifications() = Unit
    actual suspend fun showTestNotification(request: EpisodeReleaseNotificationRequest) = Unit
    actual suspend fun recordAppOpened(epochMs: Long) = Unit
    actual fun openNotificationSettings() = Unit
}
