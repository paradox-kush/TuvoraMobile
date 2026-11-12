package com.nuvio.app.features.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.media.AudioManager
import androidx.activity.ComponentActivity
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt

@Composable
actual fun LockPlayerToLandscape() {
    val activity = LocalContext.current.findActivity() ?: return
    if (!activity.shouldForceLandscapePlayer()) return

    DisposableEffect(activity) {
        val previousOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        onDispose {
            activity.requestedOrientation = previousOrientation
        }
    }
}

@Composable
actual fun FullscreenPlayerDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        HidePlayerSystemBars()
        content()
    }
}

@Composable
actual fun HidePlayerSystemBars() {
    val activity = LocalContext.current.findActivity() ?: return
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: activity.window

    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousBehavior = controller.systemBarsBehavior

        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
        }
    }
}

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) = Unit

@Composable
actual fun ManagePlayerPictureInPicture(
    enabled: Boolean,
    isPlaying: Boolean,
    videoSize: IntSize,
) {
    val activity = LocalContext.current.findActivity() ?: return

    DisposableEffect(activity) {
        onDispose {
            PlayerPictureInPictureManager.clearSession(activity)
        }
    }

    SideEffect {
        // isActive=false clears auto-enter, so a disabled setting means the OS never pulls us into
        // PiP rather than us trying to back out of it after the fact.
        PlayerPictureInPictureManager.updateSession(
            activity = activity,
            isActive = enabled,
            isPlaying = isPlaying,
            videoSize = videoSize,
        )
    }
}

actual fun platformSupportsPictureInPicture(): Boolean {
    val context = AndroidPictureInPictureContext.appContext ?: return false
    return pictureInPictureSupported(
        Build.VERSION.SDK_INT,
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE),
    )
}

actual fun isPictureInPictureBlockedBySystem(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val context = AndroidPictureInPictureContext.appContext ?: return false
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
        return true
    }
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    val mode = runCatching {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
            android.os.Process.myUid(),
            context.packageName,
        )
    }.getOrElse { return false }
    return mode != AppOpsManager.MODE_ALLOWED
}

actual fun openPictureInPictureSystemSettings() {
    val context = AndroidPictureInPictureContext.appContext ?: return
    // Deliberately the raw action string: Settings.ACTION_PICTURE_IN_PICTURE_SETTINGS is NOT public
    // API (absent from android.jar as of API 37 — checked, not assumed), even though the screen it
    // opens is a normal Settings activity. Verified to resolve on an API 36 emulator, but resolution
    // is re-checked at runtime because an OEM build may not ship the screen at all.
    val pipSettings = Intent(ACTION_PICTURE_IN_PICTURE_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val appDetails = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val target = if (pipSettings.resolveActivity(context.packageManager) != null) {
        pipSettings
    } else {
        appDetails
    }
    runCatching { context.startActivity(target) }
        .onFailure { runCatching { context.startActivity(appDetails) } }
}

private const val ACTION_PICTURE_IN_PICTURE_SETTINGS = "android.settings.PICTURE_IN_PICTURE_SETTINGS"

/** Application context for the non-composable PiP capability probes above. */
object AndroidPictureInPictureContext {
    @Volatile
    var appContext: Context? = null
        private set

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }
}

@Composable
actual fun rememberIsInPictureInPicture(): Boolean {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
    val componentActivity = activity as? ComponentActivity ?: return false
    var pipState by remember(activity) { mutableStateOf(componentActivity.isInPictureInPictureMode) }
    DisposableEffect(componentActivity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
            pipState = info.isInPictureInPictureMode
        }
        componentActivity.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            componentActivity.removeOnPictureInPictureModeChangedListener(listener)
        }
    }
    return pipState
}

@Composable
internal actual fun rememberPlatformPlayerGestureController(): PlayerGestureController? {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return null
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null

    val controller = remember(activity, audioManager) {
        AndroidPlayerGestureController(
            activity = activity,
            audioManager = audioManager,
        )
    }

    DisposableEffect(controller) {
        onDispose {
            controller.restoreBrightness()
        }
    }

    return controller
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun Activity.shouldForceLandscapePlayer(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) return false
    return true
}

private class AndroidPlayerGestureController(
    private val activity: Activity,
    private val audioManager: AudioManager,
) : PlayerGestureController {
    private val originalBrightness = activity.window.attributes.screenBrightness
    private var brightnessRestored = false

    override fun currentBrightness(): Float {
        val windowValue = activity.window.attributes.screenBrightness
        return if (windowValue in 0f..1f) {
            windowValue.coerceIn(0f, 1f)
        } else {
            readSystemBrightness()
        }
    }

    override fun setBrightness(level: Float): Float {
        val target = level.coerceIn(0f, 1f)
        val attributes = activity.window.attributes
        attributes.screenBrightness = target
        activity.window.attributes = attributes
        return target
    }

    override fun currentVolume(): PlayerAudioLevel {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVolume)
        val fraction = currentVolume.toFloat() / maxVolume.toFloat()
        return PlayerAudioLevel(
            fraction = fraction,
            isMuted = currentVolume == 0,
        )
    }

    override fun setVolume(level: Float): PlayerAudioLevel {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetVolume = (level.coerceIn(0f, 1f) * maxVolume.toFloat())
            .roundToInt()
            .coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        val fraction = targetVolume.toFloat() / maxVolume.toFloat()
        return PlayerAudioLevel(
            fraction = fraction,
            isMuted = targetVolume == 0,
        )
    }

    fun restoreBrightness() {
        if (brightnessRestored) return
        brightnessRestored = true

        val attributes = activity.window.attributes
        attributes.screenBrightness = when {
            originalBrightness < 0f -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else -> originalBrightness.coerceIn(0f, 1f)
        }
        activity.window.attributes = attributes
    }

    private fun readSystemBrightness(): Float =
        runCatching {
            Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
            )
        }.getOrDefault(127)
            .coerceIn(0, 255)
            .toFloat() / 255f
}
