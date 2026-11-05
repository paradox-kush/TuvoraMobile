package com.nuvio.app.features.recordings.internal

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadPlatformRequest
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.recordings.api.ArmKind
import com.nuvio.app.features.recordings.api.RecordingAlarms
import com.nuvio.app.features.recordings.api.RecordingCapabilities
import com.nuvio.app.features.recordings.api.RecordingFiles
import com.nuvio.app.features.recordings.api.RecordingProblem
import com.nuvio.app.features.recordings.api.RecordingSaver
import com.nuvio.app.features.recordings.api.SaveOutcome
import com.nuvio.app.features.recordings.api.SaveRequest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import kotlin.coroutines.resume

/** iPhone/iPad capabilities. One constant, so wave 4 (live capture) flips it in one place. */
internal object IosRecordingCapabilities {
    /** TODO(live capture pending live-pause branch): wave 4 decides whether iOS ever records live. */
    const val CAN_RECORD_LIVE = false
    val value = RecordingCapabilities(canRecordLive = CAN_RECORD_LIVE, canSaveCatchUp = true)
}

/** iOS cannot wake the app at a time: the schedule waits and the foreground sweep runs it. */
internal class IosRecordingAlarms : RecordingAlarms {
    override fun arm(scheduleId: String, atMs: Long): ArmKind = ArmKind.NONE
    override fun disarm(scheduleId: String) = Unit
    override fun exactAllowed(): Boolean = false
}

@OptIn(ExperimentalForeignApi::class)
internal class IosRecordingFiles(
    private val stateDir: String = "${NSHomeDirectory().trimEnd('/')}/Library/Application Support/recordings-state",
    private val recordingsDir: String = "${NSHomeDirectory().trimEnd('/')}/Documents/nuvio_recordings",
) : RecordingFiles {
    private val fm get() = NSFileManager.defaultManager

    private fun ensure(dir: String) {
        fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    }

    override suspend fun read(name: String): String? {
        val path = "$stateDir/$name"
        if (!fm.fileExistsAtPath(path)) return null
        return NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
    }

    override suspend fun write(name: String, text: String) {
        ensure(stateDir)
        // atomically = true: written to a temporary file and moved over the old one.
        val ok = NSString.create(string = text).writeToFile("$stateDir/$name", atomically = true, encoding = NSUTF8StringEncoding, error = null)
        check(ok) { "Cannot write recordings state" }
    }

    override suspend fun keepAside(name: String) {
        val path = "$stateDir/$name"
        if (fm.fileExistsAtPath(path)) {
            fm.moveItemAtPath(path, "$path.corrupt-${(NSDate().timeIntervalSince1970 * 1000).toLong()}", null)
        }
    }

    override fun pathFor(fileName: String): String {
        require(!fileName.contains('/') && fileName.isNotBlank()) { "not a plain file name" }
        ensure(recordingsDir)
        return "$recordingsDir/$fileName"
    }

    override fun freeBytes(): Long {
        val attrs = fm.attributesOfFileSystemForPath(NSHomeDirectory(), null)
        return (attrs?.get("NSFileSystemFreeSize") as? NSNumber)?.longLongValue ?: 0L
    }

    override fun sizeOf(path: String): Long {
        val attrs = fm.attributesOfItemAtPath(path, null)
        return (attrs?.get("NSFileSize") as? NSNumber)?.longLongValue ?: 0L
    }

    override suspend fun delete(path: String) {
        if (fm.fileExistsAtPath(path)) fm.removeItemAtPath(path, null)
    }
}

/**
 * Saves through upstream's downloader, so the platform's own behaviour (and its limits) apply: a
 * force-quit cancels the save, and the UI says so. The downloader wants a [DownloadItem]; a throwaway
 * one is built here and never given to the downloads repository, so nothing appears in the
 * Downloads list. The downloader keeps its partial file in the downloads folder; it is moved to and
 * from `target.part` so the repository's view of the partial file stays true.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosRecordingSaver(private val files: RecordingFiles) : RecordingSaver {

    override suspend fun save(req: SaveRequest, progress: (Long) -> Unit): SaveOutcome {
        val fm = NSFileManager.defaultManager
        val part = req.targetPath + ".part"
        val workName = "rec-" + req.targetPath.substringAfterLast('/')
        var best: SaveOutcome.Failed? = null
        for (url in req.candidates) {
            val outcome = downloadOne(url, req, workName, part, progress)
            if (outcome is SaveOutcome.Done) {
                // The downloader finished at <downloads>/<workName>; move it where the repository expects it.
                val finished = downloaderFilePath(workName)
                if (fm.fileExistsAtPath(req.targetPath)) fm.removeItemAtPath(req.targetPath, null)
                if (!fm.moveItemAtPath(finished, req.targetPath, null)) {
                    return SaveOutcome.Failed(RecordingProblem.UNKNOWN, offline = false, writtenBytes = 0, rangeSupported = true)
                }
                return SaveOutcome.Done(files.sizeOf(req.targetPath))
            }
            val failed = outcome as SaveOutcome.Failed
            if (best == null || failed.writtenBytes >= best.writtenBytes) best = failed
            if (failed.offline) break
        }
        return best ?: SaveOutcome.Failed(RecordingProblem.NO_CATCH_UP, offline = false, writtenBytes = 0, rangeSupported = false)
    }

    private suspend fun downloadOne(url: String, req: SaveRequest, workName: String, part: String, progress: (Long) -> Unit): SaveOutcome {
        val fm = NSFileManager.defaultManager
        val workPart = downloaderFilePath(workName) + ".part"
        // Hand a previous partial back to the downloader, which resumes it with a Range request.
        if (fm.fileExistsAtPath(part)) {
            if (fm.fileExistsAtPath(workPart)) fm.removeItemAtPath(workPart, null)
            fm.moveItemAtPath(part, workPart, null)
        }
        val item = throwawayItem(url, req, workName)
        val outcome = suspendCancellableCoroutine<SaveOutcome> { cont ->
            val handle = DownloadsPlatformDownloader.start(
                request = DownloadPlatformRequest(item),
                onProgress = { downloaded, _ -> progress(downloaded) },
                onSuccess = { _, total -> cont.resumeOnce(SaveOutcome.Done(total ?: 0L)) },
                onFailure = { _ ->
                    // The downloader reports a message, not a class: classify by whether the device is online is not
                    // available here, so a failure is NETWORK (retried, bounded by the schedule policy).
                    cont.resumeOnce(SaveOutcome.Failed(RecordingProblem.NETWORK, offline = false, writtenBytes = 0, rangeSupported = true))
                },
                onPaused = { cont.resumeOnce(SaveOutcome.Failed(RecordingProblem.STALLED, offline = false, writtenBytes = 0, rangeSupported = true)) },
            )
            cont.invokeOnCancellation { handle.cancel() }
        }
        if (outcome is SaveOutcome.Failed) {
            // Keep the partial where the repository looks for it.
            val written = files.sizeOf(workPart)
            if (written > 0 && fm.fileExistsAtPath(workPart)) fm.moveItemAtPath(workPart, part, null)
            return outcome.copy(writtenBytes = written)
        }
        return outcome
    }

    private fun CancellableContinuation<SaveOutcome>.resumeOnce(value: SaveOutcome) {
        if (isActive) resume(value)
    }

    private fun downloaderFilePath(name: String): String = "${NSHomeDirectory().trimEnd('/')}/Documents/nuvio_downloads/$name"

    private fun throwawayItem(url: String, req: SaveRequest, workName: String) = DownloadItem(
        id = "recording:$workName", contentType = "recording", parentMetaId = "recording", parentMetaType = "recording",
        videoId = workName, title = workName, streamTitle = workName, providerName = "recording",
        sourceUrl = url, sourceHeaders = req.headers, fileName = workName, status = DownloadStatus.Downloading,
        createdAtEpochMs = 0, updatedAtEpochMs = 0,
    )
}

