package com.nuvio.app.features.player

import android.util.Log

/**
 * B287: libmpv is used only where its native libraries load. On a 32-bit Android 8.1 tablet (Galaxy Tab A
 * SM-T580) the bundled armeabi-v7a libmpv fails to load, and the UnsatisfiedLinkError from mpv's
 * constructor crashed the app every time the player opened. Such a device plays on ExoPlayer instead.
 */
internal object LibmpvAvailabilityPolicy {
    /** [wanted]: the resolved engine is libmpv. [loadable] is asked only then (it loads native code). */
    fun usable(wanted: Boolean, loadable: () -> Boolean): Boolean = wanted && loadable()
}

/** Loads the same libraries, in the same order, as `is.xyz.mpv.MPV`'s constructor; once per process. */
internal object LibmpvNativeProbe {
    val loadable: Boolean by lazy {
        try {
            System.loadLibrary("mpv")
            System.loadLibrary("player")
            true
        } catch (e: UnsatisfiedLinkError) {
            // Message text is not logged to telemetry (it can name device paths); the class is enough.
            Log.w("LibmpvNativeProbe", "libmpv native libraries failed to load; using ExoPlayer")
            false
        }
    }
}
