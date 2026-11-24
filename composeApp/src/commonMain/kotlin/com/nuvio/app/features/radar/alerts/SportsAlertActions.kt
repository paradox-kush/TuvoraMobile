package com.nuvio.app.features.radar.alerts

import com.nuvio.app.features.sportsdetail.api.GameRef
import com.nuvio.app.features.sportsdetail.api.GameWatchPort
import com.nuvio.app.features.sportsdetail.api.WatchLookup
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The Watch lookup behind the alerts, on the game screen's own [GameWatchPort] (single-flight, deadline-bound, shared
 * with the game page). Results are cached per game for [CACHE_TTL_MS], no match and no playlist included, so gate 3
 * ([WatchState]) and the banner's Watch button never ask twice in that window. A failed lookup is not cached (Unknown).
 *
 * Lookups run only for a Score or Start banner (the button reads **Open game** at once and becomes **Watch** on Found)
 * and for a watch-check hold. Never for a full-time banner.
 */
internal class SportsAlertActions(
    private val watch: GameWatchPort,
    private val scope: CoroutineScope,
    /** The game screen's reference for an event id, from fixtures already held; null = no lookup possible. */
    private val gameRef: (eventId: String) -> GameRef?,
) {
    private data class Cached(val state: WatchState, val channelName: String?, val atMs: Long)

    private val lock = SynchronizedObject()
    private val cache = HashMap<String, Cached>()

    /** What is known about [eventId]'s channel at [nowMs]; Unknown when never asked, expired or failed. Never asks. */
    fun watchState(eventId: String, nowMs: Long): WatchState = synchronized(lock) {
        cache[eventId]?.takeIf { nowMs - it.atMs < CACHE_TTL_MS }?.state ?: WatchState.Unknown
    }

    /** [watchState] for several games at once (the policy's context). */
    fun watchStates(eventIds: Collection<String>, nowMs: Long): Map<String, WatchState> =
        eventIds.associateWith { watchState(it, nowMs) }.filterValues { it != WatchState.Unknown }

    /**
     * Makes sure [eventId]'s channel is known: a fresh cached answer is returned through [onKnown] at once; otherwise
     * one lookup starts (unless no fixture is held). [onKnown] gets the state and the channel's name. Returns the job.
     */
    fun ensureKnown(eventId: String, nowMs: () -> Long, onKnown: (WatchState, String?) -> Unit): Job? {
        synchronized(lock) { cache[eventId]?.takeIf { nowMs() - it.atMs < CACHE_TTL_MS } }?.let {
            onKnown(it.state, it.channelName)
            return null
        }
        val ref = gameRef(eventId) ?: return null
        return scope.launch {
            val result = try {
                watch.lookup(ref)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                WatchLookup.Failed
            }
            val known = when (result) {
                is WatchLookup.Found -> Cached(WatchState.Channel(result.offer.channelId), result.offer.channelName, nowMs())
                WatchLookup.NoMatch, WatchLookup.NoPlaylist -> Cached(WatchState.NoChannel, null, nowMs())
                WatchLookup.Failed -> null
            }
            if (known != null) synchronized(lock) { cache[eventId] = known }
            onKnown(known?.state ?: WatchState.Unknown, known?.channelName)
        }
    }

    /** Starts (or reuses) the lookup for a single-game Score / Start banner; [onWatch] runs only when a channel is known. */
    fun resolve(banner: AlertBanner, nowMs: () -> Long, onWatch: (BannerAction.Watch) -> Unit): Job? {
        val open = banner.action as? BannerAction.OpenGame ?: return null
        val kind = banner.alerts.singleOrNull()?.kind ?: return null
        if (kind == AlertKind.Final) return null
        return ensureKnown(open.eventId, nowMs) { state, name ->
            if (state is WatchState.Channel) onWatch(BannerAction.Watch(open.eventId, state.channelId, name.orEmpty()))
        }
    }

    fun resetForProfile() = synchronized(lock) { cache.clear() }

    companion object {
        const val CACHE_TTL_MS: Long = 15 * 60_000L
    }
}
