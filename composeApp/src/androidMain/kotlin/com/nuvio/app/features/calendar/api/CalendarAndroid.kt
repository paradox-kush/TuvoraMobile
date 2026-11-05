package com.nuvio.app.features.calendar.api

import android.content.Context
import com.nuvio.app.features.calendar.internal.CalendarStorage

/** Android-only entry point for the startup wiring (the wiring file may not reach into internal/). */
object CalendarAndroid {
    fun initialize(context: Context) = CalendarStorage.initialize(context)
}
