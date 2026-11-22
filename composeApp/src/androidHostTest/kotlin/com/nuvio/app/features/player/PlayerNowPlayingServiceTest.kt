package com.nuvio.app.features.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Parcel
import com.nuvio.app.core.build.AppFeaturePolicy
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class PlayerNowPlayingServiceTest {
    private lateinit var context: RecordingServiceContext

    @Before
    fun prepare() {
        assumeTrue(AppFeaturePolicy.mediaPlaybackForegroundServiceEnabled)
        context = RecordingServiceContext(RuntimeEnvironment.getApplication())
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(NOW_PLAYING_CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW),
        )
        PlayerNowPlayingService.hide(context)
        PlayerNowPlayingService.resetForTest()
        context.drain()
    }

    @Test
    fun hideWhileForegroundStartPendingDefersStopUntilPromoted() {
        // Crash order on 1.22.0: the background fallback start is foreground-required, and a hide
        // that stops the service before onStartCommand promotes it crashes the app with
        // ForegroundServiceDidNotStartInTimeException (AOSP ActiveServices.bringDownServiceLocked).
        // The old test blockedBackgroundStartFallsBackToForegroundStart asserted exactly that
        // order (foreground-start, then stop) and is replaced by this one.
        context.blockBackgroundStart = true
        PlayerNowPlayingService.publish(context, notification("Background"))
        val request = context.takeCall("foreground-start")
        context.blockBackgroundStart = false
        PlayerNowPlayingService.hide(context)
        context.assertNoCall()

        val controller = Robolectric.buildService(PlayerNowPlayingService::class.java).create()
        try {
            controller.get().onStartCommand(request, 0, 1)
            assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
            context.takeCall("stop")
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun startCommandWithoutNotificationStillPromotesBeforeStopping() {
        val bare = Intent(context, PlayerNowPlayingService::class.java)
            .setAction("com.nuvio.app.nowplaying.START_FOREGROUND")
        val controller = Robolectric.buildService(PlayerNowPlayingService::class.java).create()
        try {
            controller.get().onStartCommand(bare, 0, 1)
            assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun rapidPublishHideCyclesKeepOneStartInFlightAndStopOnlyAfterPromotion() {
        PlayerNowPlayingService.publish(context, notification("First"))
        val request = context.takeCall("start")
        PlayerNowPlayingService.hide(context)
        PlayerNowPlayingService.publish(context, notification("Second"))
        PlayerNowPlayingService.hide(context)
        context.assertNoCall()

        val controller = Robolectric.buildService(PlayerNowPlayingService::class.java).create()
        try {
            controller.get().onStartCommand(request, 0, 1)
            assertEquals("First", shadowOf(controller.get()).lastForegroundNotification?.extras?.getString(Notification.EXTRA_TITLE))
            context.takeCall("stop")
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun publishWhileStartPendingCancelsTheDeferredStop() {
        PlayerNowPlayingService.publish(context, notification("Paused"))
        val request = context.takeCall("start")
        PlayerNowPlayingService.hide(context)
        PlayerNowPlayingService.publish(context, notification("Resumed"))
        val controller = Robolectric.buildService(PlayerNowPlayingService::class.java).create()
        try {
            controller.get().onStartCommand(request, 0, 1)
            context.assertNoCall()
            val manager = context.getSystemService(NotificationManager::class.java)
            val current = assertNotNull(shadowOf(manager).getNotification(NOW_PLAYING_NOTIFICATION_ID))
            assertEquals("Resumed", current.extras.getString(Notification.EXTRA_TITLE))
        } finally {
            PlayerNowPlayingService.hide(context)
            context.takeCall("stop")
            controller.destroy()
        }
    }

    @Test
    fun startupNotificationSurvivesIntentParcelingAndServiceRecreation() {
        val request = startThenHide("Restarted")
        val first = Robolectric.buildService(PlayerNowPlayingService::class.java).create()
        first.get().onStartCommand(request, 0, 1)
        context.takeCall("stop")
        first.destroy()

        val parcel = Parcel.obtain()
        val restored = try {
            request.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Intent.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
        // A late copy of an answered start reaches a new instance: it still promotes first,
        // and the controller stops the untracked service instead of letting it run forever.
        val recreated = Robolectric.buildService(PlayerNowPlayingService::class.java).create()
        try {
            recreated.get().onStartCommand(restored, 0, 1)
            assertEquals("Restarted", shadowOf(recreated.get()).lastForegroundNotification?.extras?.getString(Notification.EXTRA_TITLE))
            context.takeCall("stop")
        } finally {
            recreated.destroy()
        }
    }

    @Test
    fun activePlaybackKeepsTheLatestNotificationWhenAnOlderStartArrives() {
        PlayerNowPlayingService.publish(context, notification("Initial"))
        val request = context.takeCall("start")
        PlayerNowPlayingService.publish(context, notification("Updated"))
        val controller = Robolectric.buildService(PlayerNowPlayingService::class.java).create()
        try {
            controller.get().onStartCommand(request, 0, 1)
            val manager = context.getSystemService(NotificationManager::class.java)
            val current = assertNotNull(shadowOf(manager).getNotification(NOW_PLAYING_NOTIFICATION_ID))
            assertEquals("Updated", current.extras.getString(Notification.EXTRA_TITLE))
        } finally {
            PlayerNowPlayingService.hide(context)
            context.takeCall("stop")
            controller.destroy()
        }
    }

    private fun startThenHide(title: String): Intent {
        PlayerNowPlayingService.publish(context, notification(title))
        val request = context.takeCall("start")
        PlayerNowPlayingService.hide(context)
        context.assertNoCall()
        return request
    }

    private fun notification(title: String): Notification =
        Notification.Builder(context, NOW_PLAYING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .build()

    private class RecordingServiceContext(base: Context) : ContextWrapper(base) {
        private val calls = LinkedBlockingQueue<Pair<String, Intent>>()

        @Volatile
        var blockBackgroundStart = false

        override fun getApplicationContext(): Context = this

        override fun startService(service: Intent): ComponentName? {
            if (blockBackgroundStart) throw IllegalStateException("Not allowed to start service")
            calls.put("start" to service)
            return service.component
        }

        override fun startForegroundService(service: Intent): ComponentName? {
            calls.put("foreground-start" to service)
            return service.component
        }

        override fun stopService(service: Intent): Boolean {
            calls.put("stop" to service)
            return true
        }

        fun drain() {
            while (calls.poll(200, TimeUnit.MILLISECONDS) != null) Unit
        }

        fun assertNoCall(waitMs: Long = 500) {
            val call = calls.poll(waitMs, TimeUnit.MILLISECONDS)
            assertNull(call, "unexpected service call ${call?.first}")
        }

        fun takeCall(expected: String): Intent {
            val call = assertNotNull(calls.poll(5, TimeUnit.SECONDS))
            assertEquals(expected, call.first)
            return call.second
        }
    }
}
