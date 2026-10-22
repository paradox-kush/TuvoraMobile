package com.nuvio.app.core.sync

import android.content.Context
import android.content.SharedPreferences

actual object SyncClientIdentityStorage {
    private const val preferencesName = "nuvio_sync_client_identity"
    private const val clientIdKey = "client_instance_id"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        AndroidDeviceName.initialize(context)
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadClientId(): String? =
        preferences?.getString(clientIdKey, null)

    actual fun saveClientId(clientId: String) {
        preferences
            ?.edit()
            ?.putString(clientIdKey, clientId)
            ?.apply()
    }

    actual fun loadValue(key: String): String? = preferences?.getString(key, null)

    actual fun saveValue(key: String, value: String) {
        preferences?.edit()?.putString(key, value)?.apply()
    }
}
