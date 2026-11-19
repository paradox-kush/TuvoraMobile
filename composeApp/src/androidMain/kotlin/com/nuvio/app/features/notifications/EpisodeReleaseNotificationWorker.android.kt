package com.nuvio.app.features.notifications

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlin.math.abs
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

@OptIn(ExperimentalTime::class)
class EpisodeReleaseNotificationWorker(
    appContext: android.content.Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {
        // WorkManager can start this in a process with no Activity, where nothing has initialised the platform yet;
        // without a context the permission check below reads "not authorized" and the alert is silently dropped.
        EpisodeReleaseNotificationPlatform.initialize(applicationContext)
        if (!EpisodeReleaseNotificationPlatform.notificationsAuthorized()) {
            return Result.success()
        }

        val requestId = inputData.getString(EpisodeReleaseNotificationPlatform.workerRequestIdKey)
            ?: return Result.failure()
        val title = inputData.getString(EpisodeReleaseNotificationPlatform.workerTitleKey)
            ?: return Result.failure()
        val body = inputData.getString(EpisodeReleaseNotificationPlatform.workerBodyKey)
            ?: return Result.failure()
        val deepLink = inputData.getString(EpisodeReleaseNotificationPlatform.workerDeepLinkKey)
            ?: return Result.failure()
        val backdropUrl = inputData.getString(EpisodeReleaseNotificationPlatform.workerBackdropUrlKey)

        // Re-check at fire time: the viewer may have opened Tuvora since this was scheduled, and the OS may have
        // delivered it late into quiet hours.
        val quietHours = QuietHours(
            enabled = inputData.getBoolean(EpisodeReleaseNotificationPlatform.workerQuietEnabledKey, false),
            startMinute = inputData.getInt(EpisodeReleaseNotificationPlatform.workerQuietStartKey, 22 * 60),
            endMinute = inputData.getInt(EpisodeReleaseNotificationPlatform.workerQuietEndKey, 8 * 60),
        )
        val lastOpened = EpisodeReleaseNotificationPlatform.lastAppOpenedEpochMs(applicationContext)
            ?.let { Instant.fromEpochMilliseconds(it) }
        when (
            val decision = FireTimeGate.decide(
                now = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                zone = TimeZone.currentSystemDefault(),
                quietHours = quietHours,
                lastOpenedAt = lastOpened,
            )
        ) {
            FireDecision.Skip -> return Result.success()
            is FireDecision.DelayUntil -> {
                EpisodeReleaseNotificationPlatform.enqueueLate(
                    context = applicationContext,
                    requestId = requestId,
                    inputData = inputData,
                    delayMs = decision.at.toEpochMilliseconds() - System.currentTimeMillis(),
                )
                return Result.success()
            }
            FireDecision.Send -> Unit
        }

        val request = EpisodeReleaseNotificationRequest(
            requestId = requestId,
            notificationTitle = title,
            notificationBody = body,
            releaseDateIso = "",
            deepLinkUrl = deepLink,
            backdropUrl = backdropUrl,
        )

        val notification = EpisodeReleaseNotificationPlatform.buildNotification(
            context = applicationContext,
            request = request,
        )

        NotificationManagerCompat.from(applicationContext)
            .notify(abs(requestId.hashCode()), notification)

        return Result.success()
    }
}
