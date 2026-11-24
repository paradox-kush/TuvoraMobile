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

/**
 * "Open this game's page" — a neutral request from the shell (a sports alert banner's Open game) to the Sports hub,
 * the same shape as [SportsMatchRequest]: the shell switches to the Sports tab, the hub opens the game screen when the
 * fixture is held and consumes the request. [Request.atMs] is when it was asked: the hub honours it only briefly, so a
 * game never opens late and unprompted. The shell names no sports type, only the opaque game id.
 */
object SportsGameRequest {
    data class Request(val gameId: String, val atMs: Long)

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    fun request(gameId: String, atMs: Long) {
        _request.value = Request(gameId, atMs)
    }

    /** Clears [request] only if it is still the current one (a newer tap must survive an older one's drop). */
    fun consume(request: Request) {
        _request.compareAndSet(request, null)
    }
}
