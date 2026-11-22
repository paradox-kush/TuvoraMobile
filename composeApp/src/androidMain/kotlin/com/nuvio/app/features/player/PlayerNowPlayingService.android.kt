package com.nuvio.app.features.player

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.IntentCompat
import com.nuvio.app.R
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.concurrent.ConflatedTaskDispatcher
import java.util.concurrent.Executors

internal const val NOW_PLAYING_TAG = "NuvioNowPlaying"
internal const val NOW_PLAYING_CHANNEL_ID = "nuvio_playback"
internal const val NOW_PLAYING_NOTIFICATION_ID = 0x4E55
private const val ACTION_START_FOREGROUND = "com.nuvio.app.nowplaying.START_FOREGROUND"
private const val EXTRA_START_NOTIFICATION = "com.nuvio.app.nowplaying.START_NOTIFICATION"
private const val EXTRA_REQUEST_ID = "com.nuvio.app.nowplaying.REQUEST_ID"
private const val NO_REQUEST_ID = -1L

/**
 * Keeps playback alive in the background behind the now-playing notification.
 *
 * Contract (see [NowPlayingServicePolicy]): every `onStartCommand` calls `startForeground()`
 * before anything may stop the service, using the parcelled start notification, the current
 * one, or a minimal fallback. The service never stops itself after a successful promotion;
 * the controller decides. The only self-stop is after `startForeground()` throws, which is
 * safe: AOSP clears the foreground-required flag before every throw inside `startForeground()`.
 */
class PlayerNowPlayingService : Service() {
    private var lastRequestId: Long? = null
    private var promoted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = intent
            ?.takeIf { it.action == ACTION_START_FOREGROUND }
            ?.getLongExtra(EXTRA_REQUEST_ID, NO_REQUEST_ID)
            ?.takeIf { it != NO_REQUEST_ID }
        if (requestId != null) lastRequestId = requestId

        val current = PlayerNowPlayingServiceState.notification
        val startNotification = intent
            ?.takeIf { it.action == ACTION_START_FOREGROUND }
            ?.let { IntentCompat.getParcelableExtra(it, EXTRA_START_NOTIFICATION, Notification::class.java) }
            ?: current
            ?: fallbackNotification()

        if (!promoted) {
            promoted = runCatching {
                startForeground(NOW_PLAYING_NOTIFICATION_ID, startNotification)
            }.onFailure { error ->
                Log.w(NOW_PLAYING_TAG, "Unable to promote playback service", error)
            }.isSuccess
        }
        if (!promoted) {
            stopSelf(startId)
            if (requestId != null) PlayerNowPlayingServiceController.onPromoteFailed(requestId)
            return START_NOT_STICKY
        }

        if (current != null && current !== startNotification) {
            runCatching {
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOW_PLAYING_NOTIFICATION_ID, current)
            }
        }
        if (requestId != null) {
            PlayerNowPlayingServiceController.onPromoted(requestId)
        } else if (current == null) {
            // Not a start the controller sent, and nothing to show. stopSelf(startId) never
            // cuts off a newer start: it only stops when this is the latest one.
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        PlayerNowPlayingServiceController.onServiceDestroyed(applicationContext, lastRequestId)
        super.onDestroy()
    }

    private fun fallbackNotification(): Notification {
        ensureNowPlayingChannel(this)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOW_PLAYING_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(applicationInfo.loadLabel(packageManager))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        internal fun publish(context: Context, notification: Notification) {
            if (!AppFeaturePolicy.mediaPlaybackForegroundServiceEnabled) return
            PlayerNowPlayingServiceController.publish(context.applicationContext, notification)
        }

        internal fun hide(context: Context) {
            if (!AppFeaturePolicy.mediaPlaybackForegroundServiceEnabled) return
            PlayerNowPlayingServiceController.hide(context.applicationContext)
        }

        /** Test-only: drop any tracked start so each test begins idle. */
        internal fun resetForTest() {
            PlayerNowPlayingServiceController.resetForTest()
        }
    }
}

private sealed interface PlayerNowPlayingServiceCommand {
    data class Publish(
        val context: Context,
        val notification: Notification,
    ) : PlayerNowPlayingServiceCommand

    data class Hide(val context: Context) : PlayerNowPlayingServiceCommand
}

/**
 * Runs every start/stop decision on one thread, so [NowPlayingServicePolicy] sees events in
 * order. Publish/Hide are conflated (only the latest wish matters); the service's own reports
 * go straight to the executor and are never conflated away.
 */
private object PlayerNowPlayingServiceController {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NuvioNowPlayingService").apply { isDaemon = true }
    }
    private val policy = NowPlayingServicePolicy()

    // The context the latest publish/hide came from (the application in production).
    @Volatile
    private var lastContext: Context? = null
    private val commands = ConflatedTaskDispatcher<PlayerNowPlayingServiceCommand>(
        schedule = { task -> executor.execute(task) },
        consume = ::execute,
    )

    fun publish(context: Context, notification: Notification) {
        lastContext = context
        PlayerNowPlayingServiceState.notification = notification
        commands.dispatch(PlayerNowPlayingServiceCommand.Publish(context, notification))
    }

    fun hide(context: Context) {
        lastContext = context
        PlayerNowPlayingServiceState.notification = null
        commands.dispatch(PlayerNowPlayingServiceCommand.Hide(context))
    }

    fun onPromoted(requestId: Long) {
        executor.execute {
            val stillWanted = PlayerNowPlayingServiceState.notification != null
            if (policy.onPromoted(requestId, stillWanted)) {
                lastContext?.let(::stopNow)
            }
        }
    }

    fun onPromoteFailed(requestId: Long) {
        executor.execute { policy.onPromoteFailed(requestId) }
    }

    fun onServiceDestroyed(context: Context, lastRequestId: Long?) {
        executor.execute {
            val notification = PlayerNowPlayingServiceState.notification
            if (policy.onServiceDestroyed(lastRequestId, stillWanted = notification != null) && notification != null) {
                publishNow(PlayerNowPlayingServiceCommand.Publish(lastContext ?: context, notification))
            }
        }
    }

    fun resetForTest() {
        executor.submit { policy.reset() }.get()
    }

    private fun execute(command: PlayerNowPlayingServiceCommand) {
        when (command) {
            is PlayerNowPlayingServiceCommand.Publish -> publishNow(command)
            is PlayerNowPlayingServiceCommand.Hide -> hideNow(command)
        }
    }

    private fun publishNow(command: PlayerNowPlayingServiceCommand.Publish) {
        if (PlayerNowPlayingServiceState.notification !== command.notification) return
        val requestId = policy.onPublish()
        if (requestId != null) {
            val intent = Intent(command.context, PlayerNowPlayingService::class.java)
                .setAction(ACTION_START_FOREGROUND)
                .putExtra(EXTRA_START_NOTIFICATION, command.notification)
                .putExtra(EXTRA_REQUEST_ID, requestId)
            val started = runCatching {
                startPlaybackService(command.context, intent)
            }.onFailure { error ->
                Log.w(NOW_PLAYING_TAG, "Unable to start playback service", error)
            }.isSuccess
            if (!started) {
                policy.onStartFailed(requestId)
                return
            }
        }
        if (PlayerNowPlayingServiceState.notification !== command.notification) return
        runCatching {
            command.context.getSystemService(NotificationManager::class.java)
                ?.notify(NOW_PLAYING_NOTIFICATION_ID, command.notification)
        }.onFailure { error ->
            Log.w(NOW_PLAYING_TAG, "Unable to publish playback notification", error)
        }
    }

    private fun startPlaybackService(context: Context, intent: Intent) {
        try {
            context.startService(intent)
        } catch (error: IllegalStateException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) throw error
            context.startForegroundService(intent)
        }
    }

    private fun hideNow(command: PlayerNowPlayingServiceCommand.Hide) {
        if (PlayerNowPlayingServiceState.notification != null) return
        // While a start is unanswered the policy defers the stop: stopping a
        // startForegroundService() start before it is promoted crashes the app.
        if (policy.onHide()) stopNow(command.context)
        cancelNotification(command.context)
    }

    private fun stopNow(context: Context) {
        runCatching {
            context.stopService(Intent(context, PlayerNowPlayingService::class.java))
        }.onFailure { error ->
            Log.w(NOW_PLAYING_TAG, "Unable to stop playback service", error)
        }
        cancelNotification(context)
    }

    private fun cancelNotification(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(NOW_PLAYING_NOTIFICATION_ID)
        }.onFailure { error ->
            Log.w(NOW_PLAYING_TAG, "Unable to hide playback notification", error)
        }
    }
}

private object PlayerNowPlayingServiceState {
    @Volatile
    var notification: Notification? = null
}
