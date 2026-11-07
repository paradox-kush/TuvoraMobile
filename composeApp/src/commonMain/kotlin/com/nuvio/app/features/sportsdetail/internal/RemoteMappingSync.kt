package com.nuvio.app.features.sportsdetail.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Polls the remote mapping with `If-None-Match`; adopts it only through [SportsMappingStore.offer] (validated). */
internal class RemoteMappingSync(
    private val store: SportsMappingStore,
    private val http: SportsHttp,
    private val url: String,
    private val deadlineMs: Long = 8_000L,
) {
    sealed interface Outcome {
        data class Updated(val version: String) : Outcome
        data object NotModified : Outcome
        data class Rejected(val reason: String) : Outcome
        data object Failed : Outcome
    }

    suspend fun sync(): Outcome {
        val headers = store.etag?.let { mapOf("If-None-Match" to it) } ?: emptyMap()
        val reply = try {
            withTimeoutOrNull(deadlineMs) { http.get(url, headers, deadlineMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: return Outcome.Failed
        return when {
            reply.status == 304 -> Outcome.NotModified
            reply.status in 200..299 && !reply.body.isNullOrBlank() -> when (val r = store.offer(reply.body, reply.etag)) {
                is AdoptResult.Adopted -> Outcome.Updated(r.version)
                is AdoptResult.Rejected -> Outcome.Rejected(r.reason)
            }
            else -> Outcome.Failed
        }
    }
}
