package com.nuvio.app.features.player.keepawake

/**
 * B279: when the screen must be held on while a player is up.
 *
 * Keyed on WATCH INTENT, never on the engine's `isPlaying`. A live channel stalls, buffers and
 * reconnects with `isPlaying == false` and no spinner for tens of seconds; an OS idle timer that
 * follows `isPlaying` then fires mid-channel (the Apple TV screensaver, the iPhone dimming).
 * libmpv on a CAMetalLayer also gets no automatic hold from the OS, so every engine needs this.
 *
 * Pure shared logic: each platform feeds its own signals in and applies the answer with its own
 * mechanism (`UIApplication.isIdleTimerDisabled` on Apple platforms).
 */
object PlaybackKeepAwakePolicy {
    /**
     * @param playIntent the viewer wants to watch: a source is open or being opened.
     * @param userPaused the viewer paused on purpose (remote, button, live pause).
     * @param ended playback finished.
     * @param error playback failed and nothing is left to try.
     * @param inBackground the app is not the active scene.
     */
    fun shouldHold(
        playIntent: Boolean,
        userPaused: Boolean,
        ended: Boolean,
        error: Boolean,
        inBackground: Boolean,
    ): Boolean = playIntent && !userPaused && !ended && !error && !inBackground
}
