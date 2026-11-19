package com.nuvio.app.features.notifications

import com.nuvio.app.core.storage.ProfileScopedKey
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNNotificationAttachment
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
internal actual object EpisodeReleaseNotificationPlatform {
    /** Seconds between the Unix epoch and Apple's reference date (2001-01-01). */
    private const val AppleReferenceDateOffsetSeconds = 978307200.0
    private const val scheduledIdsKey = "episode_release_notification_scheduled_ids"
    private const val attachmentDirectoryName = "episode_release_notification_attachments"
    private val httpClient = HttpClient(Darwin) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }

    actual suspend fun notificationsAuthorized(): Boolean = suspendCancellableCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            continuation.resume(
                status == UNAuthorizationStatusAuthorized || status == UNAuthorizationStatusProvisional,
            )
        }
    }

    actual suspend fun requestAuthorization(): Boolean = suspendCancellableCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
            options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { granted, _ ->
            continuation.resume(granted)
        }
    }

    actual suspend fun scheduleEpisodeReleaseNotifications(requests: List<EpisodeReleaseNotificationRequest>) {
        clearScheduledEpisodeReleaseNotifications()

        val center = UNUserNotificationCenter.currentNotificationCenter()
        val scheduledIds = mutableListOf<String>()

        requests.forEach { request ->
            if (request.fireAtEpochMs <= 0L) return@forEach
            val scheduledDate = NSDate(timeIntervalSinceReferenceDate = request.fireAtEpochMs / 1000.0 - AppleReferenceDateOffsetSeconds)
            if (scheduledDate.timeIntervalSince1970 <= NSDate().timeIntervalSince1970) return@forEach
            val dateComponents = buildDateComponents(scheduledDate)

            val content = buildNotificationContent(request)
            val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(
                dateComponents = dateComponents,
                repeats = false,
            )
            val notificationRequest = UNNotificationRequest.requestWithIdentifier(
                identifier = request.requestId,
                content = content,
                trigger = trigger,
            )
            center.addNotificationRequest(notificationRequest) { _ -> }
            scheduledIds += request.requestId
        }

        NSUserDefaults.standardUserDefaults.setObject(
            scheduledIds.joinToString(separator = "|"),
            forKey = ProfileScopedKey.of(scheduledIdsKey),
        )
    }

    actual suspend fun clearScheduledEpisodeReleaseNotifications() {
        val identifiers = trackedScheduledIds()
        if (identifiers.isNotEmpty()) {
            UNUserNotificationCenter.currentNotificationCenter()
                .removePendingNotificationRequestsWithIdentifiers(identifiers)
        }
        NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(scheduledIdsKey))
    }

    /** iOS cannot run code when an alert is due; the repository reschedules on every app open instead. */
    actual suspend fun recordAppOpened(epochMs: Long) = Unit

    actual fun openNotificationSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any?>(), null)
    }

    actual suspend fun showTestNotification(request: EpisodeReleaseNotificationRequest) {
        val content = buildNotificationContent(request)
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = 1.0,
            repeats = false,
        )
        val notificationRequest = UNNotificationRequest.requestWithIdentifier(
            identifier = request.requestId,
            content = content,
            trigger = trigger,
        )
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(notificationRequest) { _ -> }
    }

    private fun trackedScheduledIds(): List<String> =
        NSUserDefaults.standardUserDefaults
            .stringForKey(ProfileScopedKey.of(scheduledIdsKey))
            ?.split('|')
            ?.filter { value -> value.isNotBlank() }
            .orEmpty()

    private suspend fun buildNotificationContent(request: EpisodeReleaseNotificationRequest): UNMutableNotificationContent =
        UNMutableNotificationContent().apply {
            setTitle(request.notificationTitle)
            setBody(request.notificationBody)
            setUserInfo(mapOf("deeplink" to request.deepLinkUrl))
            attachmentFor(request)?.let { attachment ->
                setAttachments(listOf(attachment))
            }
        }

    private suspend fun attachmentFor(request: EpisodeReleaseNotificationRequest): UNNotificationAttachment? {
        val imageUrl = request.backdropUrl?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        val localUrl = downloadBackdropToTemporaryFile(
            requestId = request.requestId,
            imageUrl = imageUrl,
        ) ?: return null

        return UNNotificationAttachment.attachmentWithIdentifier(
            request.requestId,
            localUrl,
            null as Map<Any?, *>?,
            null,
        )
    }

    private suspend fun downloadBackdropToTemporaryFile(
        requestId: String,
        imageUrl: String,
    ): NSURL? {
        val bytes: ByteArray = runCatching {
            httpClient.get(imageUrl).body<ByteArray>()
        }.getOrNull() ?: return null

        val directoryPath = NSTemporaryDirectory().trimEnd('/') + "/" + attachmentDirectoryName
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = directoryPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )

        val fileExtension = imageUrl.substringAfterLast('.', "jpg")
            .substringBefore('?')
            .takeIf { extension -> extension.length in 2..5 }
            ?: "jpg"
        val filePath = "$directoryPath/$requestId.$fileExtension"
        val fileUrl = NSURL.fileURLWithPath(filePath)
        val wrote = bytes.writeToFile(filePath)
        if (!wrote) return null
        return fileUrl
    }

    private fun ByteArray.writeToFile(path: String): Boolean =
        usePinned { pinned ->
            val file = fopen(path, "wb") ?: return false
            try {
                val written = fwrite(
                    pinned.addressOf(0),
                    1.convert(),
                    size.convert(),
                    file,
                )
                written.toLong() == size.toLong()
            } finally {
                fclose(file)
            }
        }

    private fun buildDateComponents(date: NSDate): NSDateComponents {
        val calendar = NSCalendar.currentCalendar
        val units = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
            NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond
        return calendar.components(units, fromDate = date).apply {
            this.calendar = calendar
            setTimeZone(calendar.timeZone)
        }
    }
}
