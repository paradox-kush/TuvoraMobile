package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.outputVolume
import platform.Foundation.NSNotificationCenter
import platform.MediaPlayer.MPVolumeView
import platform.UIKit.UIApplication
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UIScreen
import platform.UIKit.UISlider

private const val lockPlayerToLandscapeNotification = "NuvioPlayerLockLandscape"
private const val unlockPlayerOrientationNotification = "NuvioPlayerUnlockOrientation"
private var playerLandscapeLockCount = 0

@Composable
actual fun LockPlayerToLandscape() {
    DisposableEffect(Unit) {
        playerLandscapeLockCount += 1
        if (playerLandscapeLockCount == 1) {
            NSNotificationCenter.defaultCenter.postNotificationName(
                lockPlayerToLandscapeNotification,
                null,
            )
        }

        onDispose {
            playerLandscapeLockCount -= 1
            if (playerLandscapeLockCount == 0) {
                NSNotificationCenter.defaultCenter.postNotificationName(
                    unlockPlayerOrientationNotification,
                    null,
                )
            }
        }
    }
}

@Composable
actual fun FullscreenPlayerDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            usePlatformInsets = false,
            useSoftwareKeyboardInset = false,
        ),
        content = content,
    )
}

@Composable
actual fun HidePlayerSystemBars() = Unit

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) {
    SideEffect {
        UIApplication.sharedApplication.setIdleTimerDisabled(keepScreenAwake)
    }

    DisposableEffect(Unit) {
        onDispose {
            UIApplication.sharedApplication.setIdleTimerDisabled(false)
        }
    }
}

@Composable
actual fun ManagePlayerPictureInPicture(
    enabled: Boolean,
    isPlaying: Boolean,
    videoSize: IntSize,
) {
    // iOS PiP runs entirely in the Swift bridge: AVPictureInPictureController is bound to an
    // AVSampleBufferDisplayLayer that receives frames blitted out of mpv's Metal drawable, so all
    // this side has to do is tell the bridge whether the user wants it. The OS drives the rest via
    // canStartPictureInPictureAutomaticallyFromInline when the app is backgrounded.
    //
    // Deliberately keyed on `enabled` only: isPlaying/videoSize are Android's concern (they feed
    // PictureInPictureParams there) and re-sending them here would thrash the capture install.
    DisposableEffect(enabled) {
        IosPictureInPictureBridgeHolder.setEnabled(enabled)
        onDispose {
            if (enabled) IosPictureInPictureBridgeHolder.setEnabled(false)
        }
    }
}

// PiP exists on iOS 14+ for iPhone and iPadOS; the device-level answer comes from
// AVPictureInPictureController.isPictureInPictureSupported() inside the bridge, which needs a live
// player. Report true here so the settings row is offered, and let the bridge no-op if the device
// or the moment says otherwise — the same shape as Android, where the row shows and the OS may
// still refuse.
actual fun platformSupportsPictureInPicture(): Boolean = true

// iOS has no equivalent of Android's per-app PiP app-op: the capability is not user-revocable.
actual fun isPictureInPictureBlockedBySystem(): Boolean = false

actual fun openPictureInPictureSystemSettings() = Unit

@Composable
actual fun rememberIsInPictureInPicture(): Boolean {
    var inPip by remember { mutableStateOf(false) }
    // AVKit gives the app delegate callbacks, not an observable the Kotlin side can subscribe to,
    // so this polls. 250ms matches the existing player snapshot cadence and is only used to hide
    // chrome, which never needs to be frame-accurate.
    LaunchedEffect(Unit) {
        while (true) {
            inPip = IosPictureInPictureBridgeHolder.isActive()
            delay(250)
        }
    }
    return inPip
}

@Composable
internal actual fun rememberPlatformPlayerGestureController(): PlayerGestureController? {
    val controller = remember { IOSPlayerGestureController() }

    DisposableEffect(controller) {
        onDispose {
            controller.restoreBrightness()
        }
    }

    return controller
}

private class IOSPlayerGestureController : PlayerGestureController {
    private val volumeView = MPVolumeView().apply {
        hidden = true
        alpha = 0.01
    }
    private val originalBrightness = UIScreen.mainScreen.brightness
    private var brightnessRestored = false

    override fun currentBrightness(): Float =
        UIScreen.mainScreen.brightness.toFloat().coerceIn(0f, 1f)

    override fun setBrightness(level: Float): Float {
        val target = level.coerceIn(0f, 1f)
        UIScreen.mainScreen.brightness = target.toDouble()
        return target
    }

    override fun currentVolume(): PlayerAudioLevel {
        val current = AVAudioSession.sharedInstance().outputVolume.coerceIn(0f, 1f)
        return PlayerAudioLevel(
            fraction = current,
            isMuted = current <= 0.001f,
        )
    }

    override fun setVolume(level: Float): PlayerAudioLevel {
        val target = level.coerceIn(0f, 1f)
        val slider = volumeView.subviews.filterIsInstance<UISlider>().firstOrNull()
            ?: return currentVolume()
        slider.value = target
        slider.sendActionsForControlEvents(UIControlEventValueChanged)
        return PlayerAudioLevel(
            fraction = target,
            isMuted = target <= 0.001f,
        )
    }

    fun restoreBrightness() {
        if (brightnessRestored) return
        brightnessRestored = true
        UIScreen.mainScreen.brightness = originalBrightness
    }
}
