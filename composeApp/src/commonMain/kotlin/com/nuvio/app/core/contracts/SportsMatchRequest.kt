package com.nuvio.app.core.contracts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Open the channel-matching sheet for this game" — a neutral request from the shell (the Calendar's Watch now) to the
 * Sports hub. The shell switches to the Sports tab; the hub, which owns the match sheet and the fixtures, consumes the
 * request when it is composed. The shell names no sports type, only the opaque game id.
 */
object SportsMatchRequest {
    private val _gameId = MutableStateFlow<String?>(null)
    val gameId: StateFlow<String?> = _gameId.asStateFlow()

    fun request(gameId: String) {
        _gameId.value = gameId
    }

    fun consume() {
        _gameId.value = null
    }
}
