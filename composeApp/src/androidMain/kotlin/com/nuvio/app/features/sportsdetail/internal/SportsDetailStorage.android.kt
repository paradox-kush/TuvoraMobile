package com.nuvio.app.features.sportsdetail.internal

import android.content.Context
import android.content.SharedPreferences

internal actual object SportsDetailStorage {
    private var preferences: SharedPreferences? = null

    /** Called once per process from AndroidFeatureWiring (via SportsDetailAndroid). */
    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("tuvora_sportsdetail", Context.MODE_PRIVATE)
    }

    actual fun loadString(key: String): String? = preferences?.getString(key, null)

    actual fun saveString(key: String, value: String) {
        preferences?.edit()?.putString(key, value)?.apply()
    }
}
