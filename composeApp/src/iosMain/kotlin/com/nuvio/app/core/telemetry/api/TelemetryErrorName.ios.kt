package com.nuvio.app.core.telemetry.api

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSURLErrorDomain

/**
 * Ktor's Darwin engine wraps the NSURLSession failure: name it by its NSURLError code (`nsurl_1001` is a
 * timeout, `nsurl_1003` an unknown host, `nsurl_1009` offline). A bounded number, never the description.
 */
internal actual fun platformErrorName(error: Throwable): String? = when (error) {
    is DarwinHttpRequestException -> {
        val ns = error.origin
        val code = -ns.code
        if (ns.domain == NSURLErrorDomain && code in 1L..9999L) "nsurl_$code" else "DarwinHttpRequestException"
    }
    else -> null
}
