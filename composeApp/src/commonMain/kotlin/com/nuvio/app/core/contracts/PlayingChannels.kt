package com.nuvio.app.core.contracts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Every live channel on screen right now (one player, or every multiview tile). Each player [set]s its channel when it
 * starts showing it and [clear]s exactly that id when it stops, so one player can never clear another's channel.
 * Read by sports alerts ("is the viewer already watching this game?"). Thread-safe.
 */
class PlayingChannelHolder {
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    fun set(channelId: String) = _ids.update { it + channelId }

    fun clear(channelId: String) = _ids.update { it - channelId }
}

object PlayingChannelsAccess {
    private var holder = PlayingChannelHolder()

    fun register(h: PlayingChannelHolder) {
        holder = h
    }

    fun current(): PlayingChannelHolder = holder

    fun resetForTest() {
        holder = PlayingChannelHolder()
    }
}
