package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthRepositorySessionErrorTest {

    @Test
    fun authLookingErrorsGateTheRefreshProbe() {
        assertTrue(AuthRepository.couldBeInvalidSessionError(401, "jwt expired"))
        assertTrue(AuthRepository.couldBeInvalidSessionError(403, ""))
        assertTrue(AuthRepository.couldBeInvalidSessionError(null, "jwt malformed"))
        assertTrue(AuthRepository.couldBeInvalidSessionError(null, "user does not exist"))
        assertTrue(AuthRepository.couldBeInvalidSessionError(409, "foreign key violation on user_id"))
    }

    @Test
    fun nonAuthErrorsNeverGateTheRefreshProbe() {
        assertFalse(AuthRepository.couldBeInvalidSessionError(500, "internal server error"))
        assertFalse(AuthRepository.couldBeInvalidSessionError(null, "timeout"))
        assertFalse(AuthRepository.couldBeInvalidSessionError(429, "rate limited"))
    }

    @Test
    fun rejectedRefreshMeansInvalidSession() {
        assertTrue(AuthRepository.isInvalidRefreshError(400, "invalid refresh token"))
        assertTrue(AuthRepository.isInvalidRefreshError(401, ""))
        assertTrue(AuthRepository.isInvalidRefreshError(403, ""))
        assertTrue(AuthRepository.isInvalidRefreshError(null, "refresh_token_not_found"))
        assertTrue(AuthRepository.isInvalidRefreshError(null, "invalid_grant"))
    }

    @Test
    fun transientRefreshFailuresKeepTheSession() {
        assertFalse(AuthRepository.isInvalidRefreshError(null, "unable to resolve host"))
        assertFalse(AuthRepository.isInvalidRefreshError(503, "service unavailable"))
        assertFalse(AuthRepository.isInvalidRefreshError(null, "connection reset by peer"))
        assertFalse(AuthRepository.isInvalidRefreshError(408, "request timeout"))
    }
}
