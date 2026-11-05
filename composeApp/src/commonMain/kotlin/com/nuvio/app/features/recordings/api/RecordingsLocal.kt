package com.nuvio.app.features.recordings.api

import androidx.compose.runtime.staticCompositionLocalOf

/** The recordings port for screens; null where the platform has no recordings (the composition root provides it). */
val LocalRecordings = staticCompositionLocalOf<RecordingsPort?> { null }

/**
 * Builds and publishes this platform's [RecordingsPort] into [RecordingsAccess], once per process,
 * called by the composition root. Android installs from its Application (it needs a Context) so its
 * actual does nothing; iOS builds it here.
 */
expect fun installPlatformRecordings()
