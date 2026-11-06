package com.nuvio.app.features.recordings.api

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.nuvio.app.features.livetv.LiveTvData
import com.nuvio.app.features.livetv.pause.LiveCaptureEnv
import com.nuvio.app.features.player.PlayerPlaybackNetworking
import com.nuvio.app.features.recordings.internal.AndroidRecordingAlarms
import com.nuvio.app.features.recordings.internal.AndroidRecordingLiveCapturer
import com.nuvio.app.features.recordings.internal.AndroidRecordingFiles
import com.nuvio.app.features.recordings.internal.AndroidRecordingSaver
import com.nuvio.app.features.recordings.internal.ExactAlarmSettingsLaunch
import com.nuvio.app.features.recordings.internal.RecordingNotifications
import com.nuvio.app.features.recordings.internal.RecordingService
import com.nuvio.app.features.recordings.internal.RecordingsRepository
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Android capabilities. One constant per platform (iOS and Desktop keep their own, false there). */
private object AndroidRecordingCapabilities {
    /** Live capture runs on live-pause's shared capture loop inside the recording foreground service. */
    const val CAN_RECORD_LIVE = true
    val value = RecordingCapabilities(canRecordLive = CAN_RECORD_LIVE, canSaveCatchUp = true)
}

/**
 * The Android composition of the recordings feature. [install] builds the one repository for the
 * process and publishes it (alarm receivers and the service have no Compose tree); it is cheap (no
 * I/O) and safe to call from every process start, including a headless alarm start.
 */
object RecordingsAndroid {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var appContext: Context? = null

    @Synchronized
    fun install(context: Context) {
        val app = context.applicationContext as Application
        appContext = app
        trackForegroundActivity(app)
        if (RecordingsAccess.current() != null) return
        val files = AndroidRecordingFiles(
            stateDir = File(app.filesDir, "recordings-state"),
            recordingsDir = File(app.filesDir, "recordings"),
        )
        val saver = AndroidRecordingSaver(isOnline = { isOnline(app) })
        val liveCapturer = AndroidRecordingLiveCapturer(
            // The same HTTP stack and header policy live pause builds its own capture with (AndroidLivePauseWiring).
            env = LiveCaptureEnv(
                callFactoryFor = { singleUse, link ->
                    PlayerPlaybackNetworking.captureCallFactory(link.url, link.headers, retryConnectionFailures = !singleUse)
                },
                headerPolicy = PlayerPlaybackNetworking::captureHeaders,
            ),
            // The guide's own live-resolve path (registry, DoH, the playlist's User-Agent); a Stalker link is minted per call.
            resolver = { channel, mint ->
                LiveTvData.resolveForRecording(channel.key, forceMint = mint)?.let { RecordingLiveLink(it.url, it.headers) }
            },
            isOnline = { isOnline(app) },
        )
        RecordingsAccess.register(
            RecordingsRepository(
                files = files,
                alarms = AndroidRecordingAlarms(app),
                saver = saver,
                capabilities = AndroidRecordingCapabilities.value,
                lowSpacePolicy = { LowSpacePolicy.STOP }, // TODO(wave 3): read the user's setting
                liveCapturer = liveCapturer,
            ),
        )
    }

    /**
     * App start (an Activity is visible, so a foreground service may start): load, recover what a dead
     * process left running, then hand each overdue schedule to the service. Off the main thread.
     */
    fun onAppStart(context: Context) {
        install(context)
        val app = context.applicationContext
        val port = RecordingsAccess.current() ?: return
        scope.launch {
            val now = System.currentTimeMillis()
            port.start(now)
            for (id in port.overdueIds(now)) {
                try {
                    ContextCompat.startForegroundService(app, RecordingService.fireIntent(app, id))
                } catch (e: IllegalStateException) {
                    RecordingNotifications.couldNotStart(app)
                    break
                }
            }
        }
    }

    /**
     * True the first time it is asked on this install (and only then), so the screen offers "Allow exact alarms"
     * once and never nags. Called only when an alarm actually came back inexact.
     */
    fun takeExactAlarmPromptTurn(): Boolean {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return false
        if (prefs.getBoolean(KEY_EXACT_PROMPT_TAKEN, false)) return false
        prefs.edit().putBoolean(KEY_EXACT_PROMPT_TAKEN, true).apply()
        return true
    }

    /** Opens Settings > Alarms & reminders for this app. False when this device has no such screen (or below Android 12). */
    fun openExactAlarmSettings(): Boolean {
        val context = appContext ?: return false
        val intent = AndroidRecordingAlarms.exactAlarmSettingsIntent(context) ?: return false
        if (intent.resolveActivity(context.packageManager) == null) return false
        val activity = resumedActivity?.get()?.takeUnless { it.isFinishing }
        if (ExactAlarmSettingsLaunch.needsNewTask(hasForegroundActivity = activity != null)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            (activity ?: context).startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    @Volatile
    private var resumedActivity: WeakReference<Activity>? = null
    private var tracking = false

    @Synchronized
    private fun trackForegroundActivity(app: Application) {
        if (tracking) return
        tracking = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity?.get() === activity) resumedActivity = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private const val PREFS = "recordings_prefs"
    private const val KEY_EXACT_PROMPT_TAKEN = "exact_alarm_prompt_taken"

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
