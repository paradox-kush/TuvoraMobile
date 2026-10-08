package com.tuvora.tvos.player

import com.nuvio.app.features.livetv.LiveTvErrorFramePolicy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/** A Siri Remote press the Apple TV player reacts to (the clickpad's edges are the arrows). */
enum class TvRemoteInput { PlayPause, Select, Left, Right, Up, Down, Back }

/** What the player does with a press. [PassThrough] leaves it to the focused control / the focus engine. */
enum class TvRemoteAction {
    PassThrough,
    TogglePlayPause,
    CommitScrub,
    CancelScrub,
    Retry,
    SkipBack,
    SkipForward,
    ShowControls,
    HideControls,
    ZapPrevious,
    ZapNext,
    OpenTracks,
    ClosePanel,
    CloseOverlay,
    /** Leave the player: the session must be closed (engine destroyed), never just hidden (B112). */
    Leave,
}

/** The player screen at the moment of a press. */
data class TvPlayerRemoteContext(
    val controlsVisible: Boolean,
    val panelOpen: Boolean,
    val overlayOpen: Boolean,
    val isLive: Boolean,
    val isPlaying: Boolean,
    val hasError: Boolean,
    val scrubbing: Boolean,
    /** Live only: a channel list to zap through exists. */
    val canZap: Boolean = false,
    /** A Skip / Start over button is up over the hidden controls and holds focus (it takes Select). */
    val actionButtonUp: Boolean = false,
)

/**
 * The Apple TV player's remote handling, as AVPlayerViewController behaves (tvOS HIG › Playing video):
 * Play/Pause always toggles; over bare video the clickpad's left/right edges skip 10 s, a deliberate
 * swipe scrubs (click commits, Back cancels), up shows the controls (live: zaps), down opens the
 * audio/subtitle panel; Back closes the innermost layer, then hides the controls, then leaves.
 *
 * Beta report B112 (2026-10-01): Play/Pause did nothing while the controls showed, a resting thumb or
 * a swipe across the control buttons started a paused scrub, and Back could leave without stopping
 * the engine. Swift feeds every press through here; it owns no remote decisions itself.
 */
object TvPlayerRemotePolicy {
    fun coverFailedFrame(hasError: Boolean): Boolean = LiveTvErrorFramePolicy.coverVideo(false, hasError)

    const val SKIP_MS = 10_000L

    /** Pan distance (remote points, ~1920 across the surface) before a swipe counts as a scrub. */
    const val SCRUB_DEAD_ZONE = 60.0

    fun decide(input: TvRemoteInput, ctx: TvPlayerRemoteContext): TvRemoteAction = when (input) {
        TvRemoteInput.Back -> when {
            ctx.panelOpen -> TvRemoteAction.ClosePanel
            ctx.overlayOpen -> TvRemoteAction.CloseOverlay
            ctx.scrubbing -> TvRemoteAction.CancelScrub
            ctx.controlsVisible && ctx.isPlaying -> TvRemoteAction.HideControls
            else -> TvRemoteAction.Leave
        }
        TvRemoteInput.PlayPause -> when {
            ctx.scrubbing -> TvRemoteAction.CommitScrub
            ctx.hasError -> TvRemoteAction.Retry
            else -> TvRemoteAction.TogglePlayPause
        }
        TvRemoteInput.Select -> when {
            ctx.panelOpen || ctx.overlayOpen -> TvRemoteAction.PassThrough
            ctx.scrubbing -> TvRemoteAction.CommitScrub
            ctx.controlsVisible || ctx.actionButtonUp -> TvRemoteAction.PassThrough
            ctx.hasError -> TvRemoteAction.Retry
            else -> TvRemoteAction.TogglePlayPause
        }
        TvRemoteInput.Left, TvRemoteInput.Right -> when {
            !ownsArrows(ctx) -> TvRemoteAction.PassThrough
            ctx.isLive -> TvRemoteAction.ShowControls
            input == TvRemoteInput.Left -> TvRemoteAction.SkipBack
            else -> TvRemoteAction.SkipForward
        }
        TvRemoteInput.Up -> when {
            !ownsArrows(ctx) -> TvRemoteAction.PassThrough
            ctx.isLive && ctx.canZap -> TvRemoteAction.ZapPrevious
            else -> TvRemoteAction.ShowControls
        }
        TvRemoteInput.Down -> when {
            !ownsArrows(ctx) -> TvRemoteAction.PassThrough
            ctx.isLive && ctx.canZap -> TvRemoteAction.ZapNext
            else -> TvRemoteAction.OpenTracks
        }
    }

    /**
     * Whether the player itself takes the arrows (bare video). Otherwise they belong to the focus
     * engine: moving between control buttons, inside a panel or a dialog, or the seek bar's own skip.
     */
    fun ownsArrows(ctx: TvPlayerRemoteContext): Boolean =
        !ctx.controlsVisible && !ctx.panelOpen && !ctx.overlayOpen && !ctx.scrubbing

    /** Whether the player takes Select itself (no control or Skip button holds focus). */
    fun ownsSelect(ctx: TvPlayerRemoteContext): Boolean = ownsArrows(ctx) && !ctx.actionButtonUp

    /**
     * Whether a touch-surface swipe may scrub: VOD with nothing open over the video, and either bare
     * video or the seek bar focused. A swipe that moves focus across the control buttons never scrubs.
     */
    fun scrubAllowed(ctx: TvPlayerRemoteContext, seekBarFocused: Boolean): Boolean =
        !ctx.isLive && !ctx.panelOpen && !ctx.overlayOpen && !ctx.hasError &&
            (!ctx.controlsVisible || seekBarFocused || ctx.scrubbing)

    /**
     * The scrub position for a pan of ([dx], [dy]) remote points from [originMs], or null while the
     * pan is not (yet) a scrub: under the dead zone, mostly vertical, or nothing to seek in. One full
     * swipe past the dead zone moves about a quarter of the title (at least 0.05 s per point).
     */
    fun scrubTarget(originMs: Long, dx: Double, dy: Double, durationMs: Long, scrubbing: Boolean): Long? {
        if (durationMs <= 0L) return null
        if (!scrubbing && (abs(dx) < SCRUB_DEAD_ZONE || abs(dy) > abs(dx))) return null
        val effective = if (abs(dx) <= SCRUB_DEAD_ZONE) 0.0 else dx - sign(dx) * SCRUB_DEAD_ZONE
        val msPerPoint = max(50.0, durationMs.toDouble() / (1920.0 * 4))
        return (originMs + (effective * msPerPoint).toLong()).coerceIn(0L, durationMs)
    }
}

/**
 * "Latest request wins" for a session that opens asynchronously (a guide preview, a live zap): a
 * session arriving for a request the viewer has since replaced or abandoned must be closed, never
 * attached — otherwise it plays on with no screen (B112: sound after leaving the player).
 */
class TvPlaybackRequestGate {
    private var current = 0L

    /** Starts a request; any earlier one is stale from now on. */
    fun begin(): Long = ++current

    /** The viewer left: every outstanding request is stale. */
    fun cancel() {
        current++
    }

    fun isCurrent(token: Long): Boolean = token == current
}
