package com.nuvio.app.features.iptv

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic background refresh of overdue IPTV playlists on Android (P3-B). Delegates the actual work
 * to the shared [IptvRefreshScheduler] (which decides what's due and how each source refreshes).
 *
 * Scheduling policy:
 *  - runs at most every [REPEAT_HOURS] hours (the WorkManager periodic floor is 15 min; the per-
 *    playlist `autoRefreshHours` is the real cadence — the scheduler skips playlists not yet due, so
 *    a frequent wake is cheap when nothing is overdue),
 *  - requires an unmetered-or-any network connection (constraint below),
 *  - defers when a player is on screen: if [IptvPlaybackGate.isPlaybackActive], the run retries later
 *    rather than firing a heavy M3U re-ingest mid-playback.
 */
class IptvRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Skip while a player is on screen — a big M3U re-ingest could stutter playback. WorkManager
        // re-runs the periodic work on its next window (or sooner via retry backoff).
        if (IptvPlaybackGate.isPlaybackActive) return Result.retry()
        return runCatching {
            IptvRefreshScheduler.refreshDuePlaylists()
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val UNIQUE_NAME = "iptv_playlist_refresh"
        private const val REPEAT_HOURS = 6L

        /**
         * Enqueues the periodic worker (idempotent — KEEP preserves an already-scheduled instance, so
         * calling this every app launch is safe). Network-constrained; battery-friendly cadence.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<IptvRefreshWorker>(REPEAT_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
