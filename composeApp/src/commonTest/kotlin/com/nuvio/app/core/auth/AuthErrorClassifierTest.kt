package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the session-invalidation contract that fixes the "signed out after every app update" bug:
 * a 401/bad-JWT from an API call is a TOKEN failure (refreshable — expiry while closed, clock
 * skew, backend JWT-key rotation), never by itself an account-death verdict. Only a definitive
 * user-gone error, or a definitive rejection of the REFRESH call, may wipe local auth.
 */
class AuthErrorClassifierTest {

    // --- isUserGoneError: the only immediate wipe verdict --------------------------------------

    @Test
    fun userGoneMatchesDeletedAccountShapes() {
        assertTrue(AuthRepository.isUserGoneError(Exception("User from sub claim in JWT does not exist")))
        assertTrue(AuthRepository.isUserGoneError(Exception("user not found")))
        assertTrue(AuthRepository.isUserGoneError(Exception("insert violates foreign key constraint on auth.users")))
    }

    @Test
    fun userGoneDoesNotMatchTokenProblems() {
        assertFalse(AuthRepository.isUserGoneError(Exception("bad_jwt: invalid JWT: unable to parse or verify signature")))
        assertFalse(AuthRepository.isUserGoneError(Exception("jwt expired")))
        assertFalse(AuthRepository.isUserGoneError(Exception("connection reset")))
    }

    // --- isAuthTokenError: refreshable token-shaped failures ------------------------------------

    @Test
    fun tokenErrorMatchesJwtComplaints() {
        // The exact shape the wipe bug was reproduced with (GoTrue bad_jwt on /auth/v1/user).
        assertTrue(AuthRepository.isAuthTokenError(Exception("bad_jwt invalid JWT: unable to parse or verify signature, token is malformed")))
        assertTrue(AuthRepository.isAuthTokenError(Exception("jwt expired")))
        assertTrue(AuthRepository.isAuthTokenError(Exception("JWT is invalid")))
    }

    @Test
    fun tokenErrorDoesNotMatchNetworkOrPlainErrors() {
        assertFalse(AuthRepository.isAuthTokenError(Exception("Unable to resolve host")))
        assertFalse(AuthRepository.isAuthTokenError(Exception("timeout")))
        assertFalse(AuthRepository.isAuthTokenError(Exception("row level security policy violation")))
    }

    // --- isRefreshDefinitelyInvalid: the refresh token is the arbiter ---------------------------

    @Test
    fun refreshInvalidMatchesDeadRefreshTokenShapes() {
        assertTrue(AuthRepository.isRefreshDefinitelyInvalid(Exception("invalid_grant")))
        assertTrue(AuthRepository.isRefreshDefinitelyInvalid(Exception("Invalid Refresh Token: Already Used")))
        assertTrue(AuthRepository.isRefreshDefinitelyInvalid(Exception("refresh_token_not_found")))
        assertTrue(AuthRepository.isRefreshDefinitelyInvalid(Exception("session_not_found")))
    }

    @Test
    fun refreshFailingOfflineIsNotInvalid() {
        // Offline / flaky refresh must NEVER sign the user out.
        assertFalse(AuthRepository.isRefreshDefinitelyInvalid(Exception("Unable to resolve host qsonncwknzdixurjyqap.supabase.co")))
        assertFalse(AuthRepository.isRefreshDefinitelyInvalid(Exception("Connect timeout has expired")))
        assertFalse(AuthRepository.isRefreshDefinitelyInvalid(Exception("HTTP 503 Service Unavailable")))
    }
}
