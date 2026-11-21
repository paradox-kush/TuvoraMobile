package com.nuvio.app.features.livetv

/**
 * When the live guide's "now" clock should tick next (TV parity: B274 follow-up).
 *
 * The screen reads the wall clock on every resume (so a guide that comes back from another app or the lock screen is
 * never stale), then ticks on wall-clock boundaries so the now-line stays in step with the system clock instead of
 * drifting by however long the screen took to compose. The tick itself makes no network request.
 */
internal object GuideClockPolicy {
    const val TICK_MS = 30_000L

    /** Milliseconds from [nowMs] to the next tick boundary; a full tick when exactly on one. */
    fun delayToNextTickMs(nowMs: Long): Long {
        val intoTick = ((nowMs % TICK_MS) + TICK_MS) % TICK_MS
        return if (intoTick == 0L) TICK_MS else TICK_MS - intoTick
    }
}
