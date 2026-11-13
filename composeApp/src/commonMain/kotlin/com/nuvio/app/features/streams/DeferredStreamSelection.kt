package com.nuvio.app.features.streams

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** One screen-owned selection; cancelled or superseded resolution can never navigate. */
internal class DeferredStreamSelection(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var generation = 0
    /** [repoint] re-points [stream] at the minted url (with the headers that url needs, B259). */
    fun select(stream: StreamItem, resolve: suspend (String) -> String?,
               onReady: (StreamItem) -> Unit, onFailure: () -> Unit,
               repoint: (StreamItem, String) -> StreamItem = { s, u -> s.copy(url = u) }) {
        val expected = ++generation
        job?.cancel()
        job = scope.launch {
            val url = try { resolve(stream.playableDirectUrl.orEmpty()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            ensureActive()
            if (generation != expected) return@launch
            if (url.isNullOrBlank()) onFailure() else onReady(repoint(stream, url))
        }
    }
    fun cancel() { generation++; job?.cancel(); job = null }
}
