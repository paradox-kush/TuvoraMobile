package com.nuvio.app.core.sources

import kotlin.test.Test
import kotlin.test.assertEquals

class UnknownHostException(m: String) : Exception(m)
class ConnectException(m: String) : Exception(m)
class SocketTimeoutException(m: String) : Exception(m)
class IOException(m: String, c: Throwable? = null) : Exception(m, c)

class AccountFailurePolicyTest {
    private fun k(e: Throwable, online: Boolean? = null, configured: Boolean = true) =
        AccountFailurePolicy.classify(e, online, configured)

    @Test fun `unresolved host on an online device is not called offline`() =
        assertEquals(AccountFailureKind.SERVER_UNREACHABLE, k(UnknownHostException("Unable to resolve host \"x.supabase.co\""), online = true))

    @Test fun `unresolved host with no network is offline`() =
        assertEquals(AccountFailureKind.OFFLINE, k(UnknownHostException("Unable to resolve host"), online = false))

    @Test fun `unknown connectivity does not claim offline`() =
        assertEquals(AccountFailureKind.SERVER_UNREACHABLE, k(ConnectException("Connection refused"), online = null))

    @Test fun `missing config wins over any exception`() =
        assertEquals(AccountFailureKind.MISSING_CONFIG, k(UnknownHostException("Unable to resolve host \"\""), true, configured = false))

    @Test fun `timeout`() = assertEquals(AccountFailureKind.TIMEOUT, k(SocketTimeoutException("timeout")))

    @Test fun `wrapped cause is read`() =
        assertEquals(AccountFailureKind.SERVER_UNREACHABLE, k(RuntimeException("x", IOException("io", UnknownHostException("h"))), true))

    @Test fun `expired jwt and 401 mean sign in again`() {
        assertEquals(AccountFailureKind.SESSION_EXPIRED, k(RuntimeException("JWT expired")))
        assertEquals(AccountFailureKind.SESSION_EXPIRED, k(RuntimeException("HTTP 401 Unauthorized")))
        assertEquals(AccountFailureKind.SESSION_EXPIRED, k(RuntimeException("code 42501 permission denied")))
    }

    @Test fun `profile not found is its own class`() =
        assertEquals(AccountFailureKind.PROFILE_MISSING, k(RuntimeException("profile_not_found (22023) HTTP 400")))

    @Test fun `owner required`() = assertEquals(AccountFailureKind.NOT_OWNER, k(RuntimeException("owner_required")))

    @Test fun `rate limit`() {
        assertEquals(AccountFailureKind.RATE_LIMITED, k(RuntimeException("email rate limit exceeded")))
        assertEquals(AccountFailureKind.RATE_LIMITED, k(RuntimeException("HTTP 429")))
    }

    @Test fun `missing rpc is outdated server`() {
        assertEquals(AccountFailureKind.SERVER_OUTDATED, k(RuntimeException("PGRST202 Could not find the function")))
        assertEquals(AccountFailureKind.SERVER_OUTDATED, k(RuntimeException("HTTP 404")))
    }

    @Test fun `5xx is a server error`() = assertEquals(AccountFailureKind.SERVER_ERROR, k(RuntimeException("HTTP 503 Service Unavailable")))

    @Test fun `anonymous disabled`() =
        assertEquals(AccountFailureKind.ANONYMOUS_DISABLED, k(RuntimeException("Anonymous sign-ins are disabled")))

    @Test fun `unrecognised stays unknown`() = assertEquals(AccountFailureKind.UNKNOWN, k(IllegalStateException("boom")))
}
