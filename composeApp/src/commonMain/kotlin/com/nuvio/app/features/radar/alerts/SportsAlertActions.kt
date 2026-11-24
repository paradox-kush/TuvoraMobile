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
 * The banner's button. It reads **Open game** at once; when the banner appears this starts the game screen's own Watch
 * lookup ([GameWatchPort.lookup]: single-flight, deadline-bound, shared with the game page) and, if that returns
 * Found while the banner is still up, the button becomes **Watch**. The banner never spins and never waits.
 *
 * Found channels are remembered for the session so gate 3 can tell "the live channel playing is this game's channel"
 * without ever starting a lookup to decide it.
 */
internal class SportsAlertActions(
    private val watch: GameWatchPort,
    private val scope: CoroutineScope,
    /** The game screen's reference for an event id, from fixtures already held; null = no lookup (Open game stays). */
    private val gameRef: (eventId: String) -> GameRef?,
) {
    private val lock = SynchronizedObject()
    private val channelByEvent = HashMap<String, String>()

    /** The channel a past lookup matched to [eventId], or null. Never starts a lookup. */
    fun cachedChannelId(eventId: String): String? = synchronized(lock) { channelByEvent[eventId] }

    /** The game whose cached Watch channel is [channelId] (what is playing), or null. */
    fun eventForChannel(channelId: String?): String? {
        if (channelId == null) return null
        return synchronized(lock) { channelByEvent.entries.firstOrNull { it.value == channelId }?.key }
    }

    /** Starts the Watch lookup for a single-game banner; [onWatch] runs only on Found. Returns the job to cancel on dismiss. */
    fun resolve(banner: AlertBanner, onWatch: (BannerAction.Watch) -> Unit): Job? {
        val open = banner.action as? BannerAction.OpenGame ?: return null
        val ref = gameRef(open.eventId) ?: return null
        return scope.launch {
            val result = try {
                watch.lookup(ref)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                WatchLookup.Failed
            }
            if (result is WatchLookup.Found) {
                synchronized(lock) { channelByEvent[open.eventId] = result.offer.channelId }
                onWatch(BannerAction.Watch(open.eventId, result.offer.channelId, result.offer.channelName))
            }
        }
    }

    fun resetForProfile() = synchronized(lock) { channelByEvent.clear() }
}
