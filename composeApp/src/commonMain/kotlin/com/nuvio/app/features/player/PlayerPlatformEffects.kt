package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize

interface PlayerGestureController {
    fun currentBrightness(): Float?
    fun setBrightness(level: Float): Float?
    fun currentVolume(): PlayerAudioLevel?
    fun setVolume(level: Float): PlayerAudioLevel?
}

data class PlayerAudioLevel(
    val fraction: Float,
    val isMuted: Boolean,
)

@Composable
expect fun LockPlayerToLandscape()

@Composable
expect fun FullscreenPlayerDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
)

@Composable
expect fun HidePlayerSystemBars()

@Composable
expect fun EnterImmersivePlayerMode(keepScreenAwake: Boolean)

@Composable
expect fun ManagePlayerPictureInPicture(
    enabled: Boolean,
    isPlaying: Boolean,
    videoSize: IntSize,
)

/**
 * Whether this platform can do Picture-in-Picture at all, so the settings row can be hidden rather
 * than offering a switch that does nothing. Android: yes (API 26+ and the system feature present).
 * iOS: not yet — the engine renders through libmpv into a CAMetalLayer, which
 * AVPictureInPictureController cannot accept. Desktop: no OS equivalent.
 */
expect fun platformSupportsPictureInPicture(): Boolean

/**
 * Whether the OS is refusing PiP for this app despite the app asking for it — on Android the
 * per-app "Picture-in-picture" special app access can be revoked, after which entering PiP silently
 * no-ops: audio keeps playing from the media session and no window ever appears. Surfacing this is
 * the difference between a confusing "PiP is broken" and an actionable setting.
 */
expect fun isPictureInPictureBlockedBySystem(): Boolean

/** Opens the OS screen where the user can re-grant PiP. No-op where not applicable. */
expect fun openPictureInPictureSystemSettings()

@Composable
expect fun rememberIsInPictureInPicture(): Boolean

/**
 * Key of the output media audio plays on (speaker, wired or Bluetooth headphones…), updated when
 * the output changes. Null while [enabled] is false or when the platform cannot tell.
 */
@Composable
internal expect fun rememberAudioOutputKey(enabled: Boolean): String?

@Composable
fun rememberPlayerGestureController(restoreBrightness: Boolean): PlayerGestureController? {
    val controller = rememberPlatformPlayerGestureController() ?: return null
    DisposableEffect(controller, restoreBrightness) {
        if (restoreBrightness) {
            PlayerSettingsStorage.loadPlaybackBrightness()
                ?.takeIf { it in 0f..1f }
                ?.let(controller::setBrightness)
        }
        onDispose {}
    }
    return remember(controller) {
        object : PlayerGestureController by controller {
            override fun setBrightness(level: Float): Float? {
                if (!level.isFinite()) return null
                return controller.setBrightness(level.coerceIn(0f, 1f))
                    ?.also(PlayerSettingsStorage::savePlaybackBrightness)
            }
        }
    }
}

@Composable
internal expect fun rememberPlatformPlayerGestureController(): PlayerGestureController?
