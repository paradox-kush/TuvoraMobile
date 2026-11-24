package com.nuvio.app.core.contracts

import com.nuvio.app.features.common.lifecycle.Disposable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What is playing right now, as far as app chrome cares: nothing, a film/episode, or live TV (multiview counts as live). */
enum class PlaybackContext { None, Vod, LiveTv }

/**
 * Firewall port: the shell reports which playback surface is on screen (the player route → [PlaybackContext.Vod], the
 * Live TV route or a live launch → [PlaybackContext.LiveTv]); fork features (sports alerts) read it. Each surface
 * [enter]s while it is shown and disposes the handle when it leaves, so nothing has to remember to "turn it off".
 */
interface PlaybackContextPort {
    val current: StateFlow<PlaybackContext>

    /** The surface is on screen until the returned handle is disposed. The most recent surface still shown wins. */
    fun enter(context: PlaybackContext): Disposable
}

/**
 * The production port: a stack of shown surfaces. A player pushed over Live TV reads as Vod until it closes, then Live
 * TV again. Thread-safe; dispose is idempotent.
 */
class PlaybackContextStack : PlaybackContextPort {
    private val lock = SynchronizedObject()
    private val entries = ArrayList<Pair<Any, PlaybackContext>>()
    private val _current = MutableStateFlow(PlaybackContext.None)
    override val current: StateFlow<PlaybackContext> = _current.asStateFlow()

    override fun enter(context: PlaybackContext): Disposable {
        val token = Any()
        synchronized(lock) {
            entries += token to context
            _current.value = context
        }
        return Disposable {
            synchronized(lock) {
                if (entries.removeAll { it.first === token }) {
                    _current.value = entries.lastOrNull()?.second ?: PlaybackContext.None
                }
            }
        }
    }
}

object PlaybackContextAccess {
    private val noOp = object : PlaybackContextPort {
        override val current: StateFlow<PlaybackContext> = MutableStateFlow(PlaybackContext.None)
        override fun enter(context: PlaybackContext): Disposable = Disposable { }
    }
    private var port: PlaybackContextPort? = null

    fun register(p: PlaybackContextPort) {
        port = p
    }

    /** No-op until registered: a build without the port reads as "nothing playing". */
    fun current(): PlaybackContextPort = port ?: noOp

    fun resetForTest() {
        port = null
    }
}
