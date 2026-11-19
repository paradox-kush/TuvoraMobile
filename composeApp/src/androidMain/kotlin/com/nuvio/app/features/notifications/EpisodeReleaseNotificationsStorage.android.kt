package com.nuvio.app.features.notifications

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object EpisodeReleaseNotificationsStorage {
    private const val preferencesName = "nuvio_episode_release_notifications"
    private const val payloadKey = "episode_release_notifications_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(): String? =
        preferences?.getString(ProfileScopedKey.of(payloadKey), null)

    actual fun savePayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(payloadKey), payload)
            ?.apply()
    }

    /** Device-local side files (viewing history, alert bookkeeping), kept per profile and never synced. */
    actual fun loadAux(name: String): String? =
        preferences?.getString(ProfileScopedKey.of("${payloadKey}_$name"), null)

    actual fun saveAux(name: String, payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of("${payloadKey}_$name"), payload)
            ?.apply()
    }
}
