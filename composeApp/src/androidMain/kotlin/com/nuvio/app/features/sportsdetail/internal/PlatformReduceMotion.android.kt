package com.nuvio.app.features.sportsdetail.internal

/** Compose already scales every animation by the system "animator duration scale" (Settings.Global.ANIMATOR_DURATION_SCALE), so nothing extra to do. */
internal actual fun platformReduceMotion(): Boolean = false
