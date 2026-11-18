package com.nuvio.app.core.telemetry.api

import com.nuvio.app.features.addons.HttpStatusException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException

/**
 * Names a failure for telemetry (`error_type`, `error_class`) with a token that survives minification.
 *
 * Android release builds run R8, which renames app and library classes: `it::class.simpleName` shipped
 * `error_class=fb4` in phone 1.21.0. So nothing here reads a class NAME: every branch is an `is` check
 * against a known type, most specific first, and the result is a fixed string from this file. A renamed
 * subclass therefore reports its nearest known ancestor (an app `IOException` subclass → `IOException`).
 * A failure that carries an HTTP status reports `http_<status>`. Anything else is `other`.
 *
 * Never reads the message: messages carry hosts, URLs and account names.
 * Same intent as NuvioTV's `ErrorClassPolicy` (d9ce644da).
 */
object TelemetryErrorName {
    const val OTHER = "other"
    const val NONE = "none"

    fun of(error: Throwable?): String {
        if (error == null) return NONE
        statusOf(error)?.let { return http(it) }
        // Before the platform pass: Ktor's own timeout types are IOExceptions on every platform.
        when (error) {
            is HttpRequestTimeoutException -> return "HttpRequestTimeoutException"
            is ConnectTimeoutException -> return "ConnectTimeoutException"
            is SocketTimeoutException -> return "SocketTimeoutException"
        }
        platformErrorName(error)?.let { return it }
        return when (error) {
            // TimeoutCancellation before Cancellation; Cancellation before IllegalState (its JVM parent).
            is TimeoutCancellationException -> "TimeoutCancellationException"
            is CancellationException -> "CancellationException"
            is SerializationException -> "SerializationException"
            is kotlinx.io.EOFException -> "EOFException"
            is kotlinx.io.IOException -> "IOException"
            is NumberFormatException -> "NumberFormatException"
            is IllegalArgumentException -> "IllegalArgumentException"
            is IllegalStateException -> "IllegalStateException"
            is NoSuchElementException -> "NoSuchElementException"
            is IndexOutOfBoundsException -> "IndexOutOfBoundsException"
            is UnsupportedOperationException -> "UnsupportedOperationException"
            is ConcurrentModificationException -> "ConcurrentModificationException"
            is ClassCastException -> "ClassCastException"
            is NullPointerException -> "NullPointerException"
            is ArithmeticException -> "ArithmeticException"
            is RuntimeException -> "RuntimeException"
            is Exception -> "Exception"
            is Error -> "Error"
            else -> OTHER
        }
    }

    /** `http_403`; a value outside the HTTP range is `http_other` (the set stays closed). */
    fun http(status: Int): String = if (status in 100..599) "http_$status" else "http_other"

    private fun statusOf(error: Throwable): Int? = when (error) {
        is HttpStatusException -> error.status
        is ResponseException -> error.response.status.value
        is RestException -> error.statusCode
        else -> null
    }
}

/**
 * The platform's own exception types (java.net / javax.net.ssl on Android, NSURLError on iOS), as fixed
 * strings chosen by `is` checks. Null when the platform has nothing more specific than the common pass.
 */
internal expect fun platformErrorName(error: Throwable): String?
