package com.nuvio.app.features.notifications

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object EpisodeReleaseNotificationsStorage {
    private const val payloadKey = "episode_release_notifications_payload"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(
            payload,
            forKey = ProfileScopedKey.of(payloadKey),
        )
    }

    /** Device-local side files (viewing history, alert bookkeeping), kept per profile and never synced. */
    actual fun loadAux(name: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of("${payloadKey}_$name"))

    actual fun saveAux(name: String, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(
            payload,
            forKey = ProfileScopedKey.of("${payloadKey}_$name"),
        )
    }
}
