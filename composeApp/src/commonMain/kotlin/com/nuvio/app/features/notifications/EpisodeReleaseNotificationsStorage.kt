package com.nuvio.app.features.notifications

internal expect object EpisodeReleaseNotificationsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)

    /** Named side files next to the payload, for device-local state that must never travel with it. */
    fun loadAux(name: String): String?
    fun saveAux(name: String, payload: String)
}
