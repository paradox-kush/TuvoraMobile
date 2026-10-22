package com.nuvio.app.core.sync

import android.content.Context
import android.os.Build
import android.provider.Settings

internal object AndroidDeviceName {
    private var context: Context? = null

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    fun configuredName(): String? = runCatching {
        context?.contentResolver?.let { Settings.Global.getString(it, Settings.Global.DEVICE_NAME) }
    }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
}

/** Prefer the name from Android Settings; some manufacturers expose only a model name. */
internal actual fun syncDeviceName(): String {
    AndroidDeviceName.configuredName()?.let { return it }
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()

    val name = when {
        model.isEmpty() -> manufacturer
        manufacturer.isEmpty() -> model
        model.startsWith(manufacturer, ignoreCase = true) -> model
        else -> "$manufacturer $model"
    }
    return name.ifBlank { "Android device" }.replaceFirstChar { it.uppercase() }
}
