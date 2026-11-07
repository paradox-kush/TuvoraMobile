package com.tuvora.tvos.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvPlayerRemotePolicyTest {
    private val bare = TvPlayerRemoteContext(
        controlsVisible = false, panelOpen = false, overlayOpen = false,
        isLive = false, isPlaying = true, hasError = false, scrubbing = false,
    )
    private val controls = bare.copy(controlsVisible = true)

    private fun decide(input: TvRemoteInput, ctx: TvPlayerRemoteContext) = TvPlayerRemotePolicy.decide(input, ctx)

    // B112: Play/Pause was swallowed while the controls showed (it only re-armed the hide timer).
    @Test
    fun `play pause toggles even while the controls show`() {
        assertEquals(TvRemoteAction.TogglePlayPause, decide(TvRemoteInput.PlayPause, controls))
        assertEquals(TvRemoteAction.TogglePlayPause, decide(TvRemoteInput.PlayPause, bare))
        assertEquals(TvRemoteAction.TogglePlayPause, decide(TvRemoteInput.PlayPause, controls.copy(panelOpen = true)))
    }

    @Test
    fun `play pause commits a scrub and retries a failure`() {
        assertEquals(TvRemoteAction.CommitScrub, decide(TvRemoteInput.PlayPause, controls.copy(scrubbing = true)))
        assertEquals(TvRemoteAction.Retry, decide(TvRemoteInput.PlayPause, controls.copy(hasError = true)))
    }

    // B112: Back must end in Leave (session closed) once nothing is left to close.
    @Test
    fun `back closes the innermost layer first then hides the controls then leaves`() {
        assertEquals(TvRemoteAction.ClosePanel, decide(TvRemoteInput.Back, controls.copy(panelOpen = true)))
        assertEquals(TvRemoteAction.CloseOverlay, decide(TvRemoteInput.Back, controls.copy(overlayOpen = true)))
        assertEquals(TvRemoteAction.CancelScrub, decide(TvRemoteInput.Back, controls.copy(scrubbing = true)))
        assertEquals(TvRemoteAction.HideControls, decide(TvRemoteInput.Back, controls))
        assertEquals(TvRemoteAction.Leave, decide(TvRemoteInput.Back, bare))
    }

    @Test
    fun `back leaves a paused or failed or loading player even with the controls showing`() {
        assertEquals(TvRemoteAction.Leave, decide(TvRemoteInput.Back, controls.copy(isPlaying = false)))
        assertEquals(TvRemoteAction.Leave, decide(TvRemoteInput.Back, controls.copy(isPlaying = false, hasError = true)))
    }

    @Test
    fun `clickpad edges skip ten seconds over bare video only`() {
        assertEquals(TvRemoteAction.SkipBack, decide(TvRemoteInput.Left, bare))
        assertEquals(TvRemoteAction.SkipForward, decide(TvRemoteInput.Right, bare))
        // With the controls up the arrows move focus between buttons (the seek bar skips by itself).
        assertEquals(TvRemoteAction.PassThrough, decide(TvRemoteInput.Left, controls))
        assertEquals(TvRemoteAction.PassThrough, decide(TvRemoteInput.Right, bare.copy(panelOpen = true)))
        assertEquals(TvRemoteAction.PassThrough, decide(TvRemoteInput.Right, bare.copy(overlayOpen = true)))
    }

    @Test
    fun `a skip button over hidden controls still lets the edges skip but takes select`() {
        val skipUp = bare.copy(actionButtonUp = true)
        assertEquals(TvRemoteAction.SkipForward, decide(TvRemoteInput.Right, skipUp))
        assertEquals(TvRemoteAction.PassThrough, decide(TvRemoteInput.Select, skipUp))
        assertFalse(TvPlayerRemotePolicy.ownsSelect(skipUp))
        assertTrue(TvPlayerRemotePolicy.ownsSelect(bare))
    }

    // Telemetry v2 review: the error card's buttons (Retry, Send report) were unreachable over hidden controls,
    // because the policy still saw bare video and took Select (as Retry) and the edges (as skip).
    @Test
    fun `an error card over hidden controls leaves select and the edges to the focused button`() {
        val card = bare.copy(hasError = true, isPlaying = false, errorCardUp = true)
        assertEquals(TvRemoteAction.PassThrough, decide(TvRemoteInput.Select, card))
        assertEquals(TvRemoteAction.PassThrough, decide(TvRemoteInput.Left, card))
        assertEquals(TvRemoteAction.PassThrough, decide(TvRemoteInput.Right, card))
        assertFalse(TvPlayerRemotePolicy.ownsSelect(card))
        // The old behaviour (no card flag) is what swallowed them:
        assertEquals(TvRemoteAction.Retry, decide(TvRemoteInput.Select, card.copy(errorCardUp = false)))
        assertEquals(TvRemoteAction.SkipForward, decide(TvRemoteInput.Right, card.copy(errorCardUp = false)))
    }

    @Test
    fun `an error card still retries on play pause leaves on back and zaps live on up down`() {
        val card = bare.copy(hasError = true, isPlaying = false, errorCardUp = true)
        assertEquals(TvRemoteAction.Retry, decide(TvRemoteInput.PlayPause, card))
        assertEquals(TvRemoteAction.Leave, decide(TvRemoteInput.Back, card))
        assertEquals(TvRemoteAction.ZapNext, decide(TvRemoteInput.Down, card.copy(isLive = true, canZap = true)))
    }

    @Test
    fun `live has no seeking - edges show the controls and up down zap when a list exists`() {
        val live = bare.copy(isLive = true)
        assertEquals(TvRemoteAction.ShowControls, decide(TvRemoteInput.Left, live))
        assertEquals(TvRemoteAction.ShowControls, decide(TvRemoteInput.Up, live))
        assertEquals(TvRemoteAction.OpenTracks, decide(TvRemoteInput.Down, live))
        assertEquals(TvRemoteAction.ZapPrevious, decide(TvRemoteInput.Up, live.copy(canZap = true)))
        assertEquals(TvRemoteAction.ZapNext, decide(TvRemoteInput.Down, live.copy(canZap = true)))
    }

    @Test
    fun `select toggles over bare video and is left to the focused button otherwise`() {
        assertEquals(TvRemoteAction.TogglePlayPause, decide(TvRemoteInput.Select, bare))
        assertEquals(TvRemoteAction.PassThrough, decide(TvRemoteInput.Select, controls))
        assertEquals(TvRemoteAction.CommitScrub, decide(TvRemoteInput.Select, controls.copy(scrubbing = true)))
        assertEquals(TvRemoteAction.Retry, decide(TvRemoteInput.Select, bare.copy(hasError = true)))
    }

    // B112: a swipe across the control buttons started a paused scrub.
    @Test
    fun `swipes scrub over bare video or a focused seek bar but not across the buttons`() {
        assertTrue(TvPlayerRemotePolicy.scrubAllowed(bare, seekBarFocused = false))
        assertTrue(TvPlayerRemotePolicy.scrubAllowed(controls, seekBarFocused = true))
        assertFalse(TvPlayerRemotePolicy.scrubAllowed(controls, seekBarFocused = false))
        assertFalse(TvPlayerRemotePolicy.scrubAllowed(bare.copy(overlayOpen = true), seekBarFocused = false))
        assertFalse(TvPlayerRemotePolicy.scrubAllowed(bare.copy(panelOpen = true), seekBarFocused = false))
        assertFalse(TvPlayerRemotePolicy.scrubAllowed(bare.copy(isLive = true), seekBarFocused = false))
    }

    // B112: a thumb resting on the clickpad (a few points of drift) paused playback into a scrub.
    @Test
    fun `a small or vertical pan is not a scrub`() {
        assertNull(TvPlayerRemotePolicy.scrubTarget(600_000, dx = 20.0, dy = 3.0, durationMs = 7_200_000, scrubbing = false))
        assertNull(TvPlayerRemotePolicy.scrubTarget(600_000, dx = 80.0, dy = 200.0, durationMs = 7_200_000, scrubbing = false))
        assertNull(TvPlayerRemotePolicy.scrubTarget(600_000, dx = 500.0, dy = 0.0, durationMs = 0, scrubbing = false))
    }

    @Test
    fun `a swipe past the dead zone scrubs from where it began without a jump`() {
        val duration = 7_680_000L                       // 1 s per remote point
        val dz = TvPlayerRemotePolicy.SCRUB_DEAD_ZONE
        assertEquals(600_000L, TvPlayerRemotePolicy.scrubTarget(600_000, dz + 0.0, 0.0, duration, scrubbing = false))
        assertEquals(700_000L, TvPlayerRemotePolicy.scrubTarget(600_000, dz + 100.0, 0.0, duration, scrubbing = false))
        assertEquals(500_000L, TvPlayerRemotePolicy.scrubTarget(600_000, -(dz + 100.0), 0.0, duration, scrubbing = true))
        // Once scrubbing, coming back inside the dead zone returns to the origin rather than dropping out.
        assertEquals(600_000L, TvPlayerRemotePolicy.scrubTarget(600_000, 10.0, 0.0, duration, scrubbing = true))
        assertEquals(duration, TvPlayerRemotePolicy.scrubTarget(7_600_000, 5_000.0, 0.0, duration, scrubbing = true))
        assertEquals(0L, TvPlayerRemotePolicy.scrubTarget(10_000, -5_000.0, 0.0, duration, scrubbing = true))
    }

    @Test
    fun `a short title still moves at least fifty ms per point`() {
        assertEquals(10_000L + 100 * 50, TvPlayerRemotePolicy.scrubTarget(10_000, TvPlayerRemotePolicy.SCRUB_DEAD_ZONE + 100, 0.0, 60_000, scrubbing = false))
    }
}

/** Live pause / rewind on the Siri Remote: the clickpad edges step through the window instead of doing nothing. */
class TvPlayerRemoteLivePauseTest {
    private val live = TvPlayerRemoteContext(
        controlsVisible = false, panelOpen = false, overlayOpen = false,
        isLive = true, isPlaying = true, hasError = false, scrubbing = false,
        livePauseAvailable = true,
    )

    private fun decide(input: TvRemoteInput, ctx: TvPlayerRemoteContext) = TvPlayerRemotePolicy.decide(input, ctx)

    @Test
    fun `live edges step back and forward through the window over bare video`() {
        assertEquals(TvRemoteAction.LiveStepBack, decide(TvRemoteInput.Left, live))
        assertEquals(TvRemoteAction.LiveStepForward, decide(TvRemoteInput.Right, live))
    }

    @Test
    fun `live edges only show the controls where no pause is available`() {
        val plain = live.copy(livePauseAvailable = false)
        assertEquals(TvRemoteAction.ShowControls, decide(TvRemoteInput.Left, plain))
        assertEquals(TvRemoteAction.ShowControls, decide(TvRemoteInput.Right, plain))
    }

    @Test
    fun `with the controls up the arrows still belong to the focus engine`() {
        assertEquals(TvRemoteAction.PassThrough, decide(TvRemoteInput.Left, live.copy(controlsVisible = true)))
        assertEquals(TvRemoteAction.PassThrough, decide(TvRemoteInput.Right, live.copy(panelOpen = true)))
    }

    @Test
    fun `a video on demand never steps through a live window`() {
        val vod = live.copy(isLive = false)
        assertEquals(TvRemoteAction.SkipBack, decide(TvRemoteInput.Left, vod))
        assertEquals(TvRemoteAction.SkipForward, decide(TvRemoteInput.Right, vod))
    }

    @Test
    fun `play pause and select pause a live channel and a failed pause retries`() {
        assertEquals(TvRemoteAction.TogglePlayPause, decide(TvRemoteInput.PlayPause, live))
        assertEquals(TvRemoteAction.TogglePlayPause, decide(TvRemoteInput.Select, live))
        assertEquals(TvRemoteAction.Retry, decide(TvRemoteInput.PlayPause, live.copy(controlsVisible = true, livePauseFailed = true)))
    }

    @Test
    fun `back from a paused live channel leaves even with the controls showing`() {
        assertEquals(TvRemoteAction.Leave, decide(TvRemoteInput.Back, live.copy(controlsVisible = true, isPlaying = false)))
    }

    @Test
    fun `up and down still zap a live channel`() {
        val zapping = live.copy(canZap = true)
        assertEquals(TvRemoteAction.ZapPrevious, decide(TvRemoteInput.Up, zapping))
        assertEquals(TvRemoteAction.ZapNext, decide(TvRemoteInput.Down, zapping))
    }
}

class TvPlaybackRequestGateTest {
    // B112: a guide preview / zap that resolved after the viewer moved on kept playing with no screen.
    @Test
    fun `a newer request makes the earlier one stale`() {
        val gate = TvPlaybackRequestGate()
        val first = gate.begin()
        val second = gate.begin()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `leaving makes every outstanding request stale`() {
        val gate = TvPlaybackRequestGate()
        val token = gate.begin()
        gate.cancel()
        assertFalse(gate.isCurrent(token))
        assertTrue(gate.isCurrent(gate.begin()))
    }
}
