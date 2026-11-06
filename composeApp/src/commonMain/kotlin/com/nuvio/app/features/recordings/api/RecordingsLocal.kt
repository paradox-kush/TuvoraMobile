package com.nuvio.app.features.recordings.api

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What a screen gets when nothing provided [LocalRecordings]: the port the platform registered at process
 * start, or null where there is none. iOS never wraps its UI in `installFeatures` (only Android's activity and
 * the desktop window do), so a null default hid every recording affordance there.
 */
internal fun defaultRecordingsPort(current: () -> RecordingsPort? = RecordingsAccess::current): RecordingsPort? = current()

/** The recordings port for screens; null only where the platform registered none. */
val LocalRecordings = staticCompositionLocalOf<RecordingsPort?> { defaultRecordingsPort() }

/**
 * Builds and publishes this platform's [RecordingsPort] into [RecordingsAccess], once per process,
 * called by the composition root. Android installs from its Application (it needs a Context) so its
 * actual does nothing; iOS builds it here.
 */
expect fun installPlatformRecordings()
