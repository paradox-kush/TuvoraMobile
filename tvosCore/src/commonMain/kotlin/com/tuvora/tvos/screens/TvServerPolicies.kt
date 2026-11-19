package com.tuvora.tvos.screens

import com.nuvio.app.features.servers.ServerFailure

enum class TvServerStatus { SIGNED_IN, SIGN_IN_AGAIN, NEEDS_SIGN_IN, OFFLINE, DISABLED }

enum class TvAddError {
    INVALID_ADDRESS, NOT_A_MEDIA_SERVER, UNREACHABLE, WRONG_CREDENTIALS, QUICK_CONNECT_FAILED, QUICK_CONNECT_EXPIRED,
    QUICK_CONNECT_OFF, SIGN_IN_FAILED, DIFFERENT_SERVER, NOT_SAVED, CERTIFICATE_REFUSED,
}

/** The status badge of a connected server (pure; the add/details screens and the Settings list all use it). */
object TvServerStatusPolicy {
    fun of(enabled: Boolean, hasLocalToken: Boolean, failure: ServerFailure?): TvServerStatus = when {
        !enabled -> TvServerStatus.DISABLED
        !hasLocalToken -> TvServerStatus.NEEDS_SIGN_IN
        failure == ServerFailure.AUTH_REQUIRED ||
            failure == ServerFailure.CERTIFICATE_CHANGED ||
            failure == ServerFailure.CERTIFICATE_UNTRUSTED -> TvServerStatus.SIGN_IN_AGAIN
        failure == ServerFailure.UNREACHABLE -> TvServerStatus.OFFLINE
        else -> TvServerStatus.SIGNED_IN
    }

    /** "Jellyfin · viewer · nas.local:8096". */
    fun subtitle(product: String, userName: String, address: String): String {
        val host = address.substringAfter("://").substringBefore('/').takeIf { it.isNotBlank() }
        return listOfNotNull(product.takeIf { it.isNotBlank() }, userName.takeIf { it.isNotBlank() }, host).joinToString(" · ")
    }
}

/** What a failed step of the add-a-server flow tells the viewer. */
object TvServerAddErrorPolicy {
    /** [duringSignIn]: the failure came from the password / Quick Connect exchange, not from reaching the server. */
    fun of(failure: ServerFailure, duringSignIn: Boolean): TvAddError = when (failure) {
        ServerFailure.NOT_FOUND -> if (duringSignIn) TvAddError.SIGN_IN_FAILED else TvAddError.INVALID_ADDRESS
        ServerFailure.UNSUPPORTED -> TvAddError.NOT_A_MEDIA_SERVER
        ServerFailure.UNREACHABLE -> TvAddError.UNREACHABLE
        ServerFailure.AUTH_REQUIRED, ServerFailure.FORBIDDEN -> if (duringSignIn) TvAddError.WRONG_CREDENTIALS else TvAddError.SIGN_IN_FAILED
        ServerFailure.CERTIFICATE_UNTRUSTED, ServerFailure.CERTIFICATE_CHANGED -> TvAddError.CERTIFICATE_REFUSED
        // A server that refuses remote playback is a playback matter (Plex remote streaming off), not an add-flow step.
        ServerFailure.INCOMPLETE, ServerFailure.FAILED, ServerFailure.REMOTE_PLAYBACK_BLOCKED -> TvAddError.SIGN_IN_FAILED
    }
}
