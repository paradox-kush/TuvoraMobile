package com.nuvio.app.features.recordings.internal

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadPlatformRequest
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsTaskHandle
import com.nuvio.app.features.downloads.StrictResume
import com.nuvio.app.features.recordings.api.ArmKind
import com.nuvio.app.features.recordings.api.KnownSizeDecision
import com.nuvio.app.features.recordings.api.RecordingAlarms
import com.nuvio.app.features.recordings.api.RecordingCapabilities
import com.nuvio.app.features.recordings.api.RecordingFiles
import com.nuvio.app.features.recordings.api.RecordingProblem
import com.nuvio.app.features.recordings.api.RecordingSaver
import com.nuvio.app.features.recordings.api.SaveOutcome
import com.nuvio.app.features.recordings.api.SaveRequest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
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

/** iPhone/iPad capabilities. One constant, so wave 4 (live capture) flips it in one place. */
internal object IosRecordingCapabilities {
    /** TODO(live capture pending live-pause branch): wave 4 decides whether iOS ever records live. */
    const val CAN_RECORD_LIVE = false
    val value = RecordingCapabilities(canRecordLive = CAN_RECORD_LIVE, canSaveCatchUp = true, canWakeApp = false)
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
    /** Where upstream's downloader keeps the file it is working on for us (`rec-<name>` and `rec-<name>.part`). */
    private val downloadsDir: String = IosRecordingPaths.defaultDownloadsDir(),
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
        remove(path)
        if (path.endsWith(".part")) {
            // A partial has a sidecar naming its link, and a twin in the downloader's folder while a save runs.
            remove("$path.meta")
            val work = IosRecordingPaths.workName(path.substringAfterLast('/').removeSuffix(".part"))
            remove("$downloadsDir/$work.part")
            remove("$downloadsDir/$work")
        }
    }

    private fun remove(path: String) {
        if (fm.fileExistsAtPath(path)) fm.removeItemAtPath(path, null)
    }
}

/** Names shared by the saver and the file store, so neither guesses the other's layout. */
internal object IosRecordingPaths {
    fun defaultDownloadsDir(): String = "${NSHomeDirectory().trimEnd('/')}/Documents/nuvio_downloads"
    fun workName(targetFileName: String): String = "rec-$targetFileName"
}

/**
 * Moves a partial between the repository's folder and the downloader's, only ever handing the
 * downloader a partial that this very link wrote. The link (and the validators it answered with) is
 * pinned beside the partial in `<part>.meta`, the same sidecar name and layout Android uses, so the
 * repository deletes both the same way.
 *
 * There is one partial slot per recording. A link's partial stays in it until a bigger one replaces it:
 * a candidate that fails at once must not cost an earlier candidate its bytes.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosPartialStaging {
    private val fm get() = NSFileManager.defaultManager

    fun readResumePin(part: String): ResumePin? {
        val path = "$part.meta"
        if (!fm.fileExistsAtPath(path)) return null
        return ResumePin.decode(NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null))
    }

    /** The link a partial came from (first line of the sidecar). */
    fun readPin(part: String): String? = readResumePin(part)?.url

    fun writePin(part: String, url: String, etag: String? = null, lastModified: String? = null) {
        NSString.create(string = ResumePin(url, etag, lastModified).encode())
            .writeToFile("$part.meta", atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    private fun remove(path: String) {
        if (fm.fileExistsAtPath(path)) fm.removeItemAtPath(path, null)
    }

    private fun sizeOf(path: String): Long =
        (fm.attributesOfItemAtPath(path, null)?.get("NSFileSize") as? NSNumber)?.longLongValue ?: 0L

    /** Deletes the partial and its sidecar. */
    fun discard(part: String) {
        remove(part)
        remove("$part.meta")
    }

    /**
     * Readies [workPart] for a save from [url]. A partial pinned to the same link moves over so the
     * downloader resumes it. A partial from ANOTHER link is left where it is (an earlier candidate's
     * bytes survive this one failing) and the save starts at zero; one this link wrote that cannot be
     * resumed, or one with no known source, is deleted. Returns whether it resumes.
     */
    fun stageForResume(url: String, part: String, workPart: String, resumeFromBytes: Long): Boolean {
        remove(workPart)
        if (!fm.fileExistsAtPath(part)) return false
        val pin = readResumePin(part)
        if (RecordingResumePolicy.canResume(pin?.url, url, resumeFromBytes)) {
            fm.moveItemAtPath(part, workPart, null)
            return true
        }
        if (pin == null || pin.url == url) discard(part)
        return false
    }

    /**
     * After a save stopped for any reason (failure, pause, cancel): whatever the downloader wrote goes
     * back beside the target with its link, or is dropped if empty. Another link's bigger partial is
     * not replaced by a smaller one. Safe to call twice. Returns the bytes kept from [workPart].
     */
    fun parkAfterStop(url: String, part: String, workPart: String, etag: String? = null, lastModified: String? = null): Long {
        if (!fm.fileExistsAtPath(workPart)) return 0L
        val size = sizeOf(workPart)
        if (size <= 0L) {
            remove(workPart)
            return 0L
        }
        if (fm.fileExistsAtPath(part)) {
            val foreign = readPin(part) != url
            if (foreign && sizeOf(part) > size) {
                remove(workPart)
                return 0L
            }
            discard(part)
        }
        fm.moveItemAtPath(workPart, part, null)
        writePin(part, url, etag, lastModified)
        return size
    }
}

internal typealias DownloaderStart = (
    DownloadPlatformRequest,
    (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    (localFileUri: String, totalBytes: Long?) -> Unit,
    (message: String) -> Unit,
    () -> Unit,
) -> DownloadsTaskHandle

private fun startRealDownloader(
    request: DownloadPlatformRequest,
    onProgress: (Long, Long?) -> Unit,
    onSuccess: (String, Long?) -> Unit,
    onFailure: (String) -> Unit,
    onPaused: () -> Unit,
): DownloadsTaskHandle = DownloadsPlatformDownloader.start(request, onProgress, onSuccess, onFailure, onPaused)

/**
 * Saves through upstream's downloader, so the platform's own behaviour (and its limits) apply: this is
 * not a background URLSession, so the save only runs while Tuvora is open, and the UI says so. The
 * downloader wants a [DownloadItem]; a throwaway one is built here and never given to the downloads
 * repository, so nothing appears in the Downloads list. The downloader keeps its partial file in the
 * downloads folder; it is moved to and from `target.part` (pinned to its link by [IosPartialStaging]) so
 * the repository's view of the partial file stays true and a different link never extends it.
 *
 * A resume is guarded by the downloader itself (see [StrictResume]): If-Range with the validator the
 * partial came with, and a 206 that does not start where the partial ends restarts from zero.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosRecordingSaver(
    private val files: RecordingFiles,
    /** Where the downloader works. The real downloader always uses [IosRecordingPaths.defaultDownloadsDir]; tests point both at a temp folder. */
    private val downloadsDir: String = IosRecordingPaths.defaultDownloadsDir(),
    private val start: DownloaderStart = ::startRealDownloader,
    /** How long a stopped save waits for the downloader to really stop before it gives up and cleans up anyway. */
    private val stopWaitMs: Long = STOP_WAIT_MS,
) : RecordingSaver {
    private val staging = IosPartialStaging()

    override suspend fun save(req: SaveRequest, progress: (Long) -> Unit): SaveOutcome {
        val fm = NSFileManager.defaultManager
        val part = req.targetPath + ".part"
        val workName = IosRecordingPaths.workName(req.targetPath.substringAfterLast('/'))
        // The repository asked for a fresh start: whatever an earlier attempt left is not wanted.
        if (req.resumeFromBytes <= 0L) staging.discard(part)
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
                // An earlier candidate's parked partial is of no use any more.
                staging.discard(part)
                return SaveOutcome.Done(files.sizeOf(req.targetPath))
            }
            val failed = outcome as SaveOutcome.Failed
            if (best == null || failed.writtenBytes >= best.writtenBytes) best = failed
            if (failed.offline) break
            // Out of space: no other link will help.
            if (failed.problem == RecordingProblem.NO_SPACE) break
        }
        val last = best ?: SaveOutcome.Failed(RecordingProblem.NO_CATCH_UP, offline = false, writtenBytes = 0, rangeSupported = false)
        // Out of space part-way (not a refusal of a KNOWN size, which carries needBytes): keep what was
        // written as a playable truncated file under the final name instead of a partial to be cleaned up.
        if (last.problem == RecordingProblem.NO_SPACE && last.needBytes == null && files.sizeOf(part) > 0L) {
            if (fm.fileExistsAtPath(req.targetPath)) fm.removeItemAtPath(req.targetPath, null)
            if (fm.moveItemAtPath(part, req.targetPath, null)) {
                staging.discard(part) // the sidecar
                return SaveOutcome.Done(files.sizeOf(req.targetPath), stoppedFor = RecordingProblem.NO_SPACE)
            }
        }
        // Report the bytes that are really in the partial now, never a partial a later link replaced or removed.
        return last.copy(writtenBytes = files.sizeOf(part))
    }

    private suspend fun downloadOne(url: String, req: SaveRequest, workName: String, part: String, progress: (Long) -> Unit): SaveOutcome {
        val workPart = downloaderFilePath(workName) + ".part"
        val finishedPath = downloaderFilePath(workName)
        val fm = NSFileManager.defaultManager
        // Hand a previous partial back to the downloader only if THIS link wrote it; otherwise start at zero.
        val pinBefore = staging.readResumePin(part)
        val resumed = staging.stageForResume(url, part, workPart, req.resumeFromBytes)
        // A finished file an earlier stop left behind is not this save's.
        if (fm.fileExistsAtPath(finishedPath)) fm.removeItemAtPath(finishedPath, null)
        var etag = if (resumed) pinBefore?.etag else null
        var lastModified = if (resumed) pinBefore?.lastModified else null
        var declaredTotal: Long? = null
        val strict = StrictResume(
            validator = if (resumed) pinBefore?.validator else null,
            onValidators = { e, l -> etag = e; lastModified = l },
        )
        val item = throwawayItem(url, req, workName)
        var finished = false

        // Whatever the downloader holds goes back beside the target, with its link and validators.
        fun settle(): Long {
            // A download that completed just as it was stopped left its finished file: it is this link's partial.
            if (fm.fileExistsAtPath(finishedPath)) {
                if (fm.fileExistsAtPath(workPart)) fm.removeItemAtPath(workPart, null)
                fm.moveItemAtPath(finishedPath, workPart, null)
            }
            return staging.parkAfterStop(url, part, workPart, etag, lastModified)
        }

        try {
            val outcome = runDownload(item, strict, req, progress, onDiskBytes = if (resumed) sizeOfFile(workPart) else 0L) { declaredTotal = it }
            if (outcome is SaveOutcome.Done) {
                val size = sizeOfFile(finishedPath)
                val cut = size <= 0L ||
                    (declaredTotal?.let { size < it } == true) ||
                    RecordingCompletenessPolicy.endedTooEarly(size, declaredTotal, req.estimatedBytes)
                if (!cut) {
                    finished = true
                    return SaveOutcome.Done(size)
                }
                // The stream ended cleanly but short: the bytes are a partial, not a recording.
                return SaveOutcome.Failed(RecordingProblem.STALLED, offline = false, writtenBytes = settle(), rangeSupported = true)
            }
            return (outcome as SaveOutcome.Failed).copy(writtenBytes = settle())
        } finally {
            // A cancel throws past the code above: the partial still goes home, so the repository can
            // resume it after a teardown or delete it after a user cancel instead of leaking it.
            if (!finished) settle()
        }
    }

    private suspend fun runDownload(
        item: DownloadItem,
        strict: StrictResume,
        req: SaveRequest,
        progress: (Long) -> Unit,
        onDiskBytes: Long,
        onTotal: (Long) -> Unit,
    ): SaveOutcome = coroutineScope {
        val result = CompletableDeferred<SaveOutcome>()
        val ticks = Channel<Long>(Channel.CONFLATED)
        var succeeded = false
        var knownTotal: Long? = null
        val handle = start(
            DownloadPlatformRequest(item, strict),
            { downloaded, total -> total?.let { knownTotal = it; onTotal(it) }; progress(downloaded); ticks.trySend(downloaded) },
            { _, _ -> succeeded = true; result.complete(SaveOutcome.Done(0L)) },
            { _ ->
                // The downloader reports a message, not a class: a failure is NETWORK (retried, bounded by the schedule policy).
                result.complete(SaveOutcome.Failed(RecordingProblem.NETWORK, offline = false, writtenBytes = 0, rangeSupported = true))
            },
            { result.complete(SaveOutcome.Failed(RecordingProblem.STALLED, offline = false, writtenBytes = 0, rangeSupported = true)) },
        )
        val spaceCheck = req.spaceCheck
        val checker = launch {
            var last = -1L
            var knownChecked = req.onKnownTotal == null
            for (downloaded in ticks) {
                // The downloader reports the declared total with its first progress; refuse as soon as it is known.
                val total = knownTotal
                if (!knownChecked && total != null) {
                    knownChecked = true
                    val verdict = req.onKnownTotal?.invoke(total, onDiskBytes)
                    if (verdict is KnownSizeDecision.Refuse) {
                        handle.cancel()
                        result.complete(
                            SaveOutcome.Failed(
                                RecordingProblem.NO_SPACE, offline = false, writtenBytes = 0, rangeSupported = true,
                                needBytes = verdict.needBytes, freeBytes = verdict.freeBytes,
                            ),
                        )
                    }
                }
                if (last < 0) last = downloaded
                if (spaceCheck != null && downloaded - last >= SPACE_CHECK_BYTES) {
                    last = downloaded
                    val problem = spaceCheck(SPACE_CHECK_BYTES)
                    if (problem != null) {
                        handle.cancel()
                        result.complete(SaveOutcome.Failed(problem, offline = false, writtenBytes = 0, rangeSupported = true))
                    }
                }
            }
        }
        try {
            result.await()
        } finally {
            checker.cancel()
            ticks.close()
            if (!succeeded) {
                // Cancelled, failed or out of space: no network task may outlive this call, and the
                // downloader may still be moving a file it had already finished, so wait (bounded) for it
                // to stop before anyone cleans up. A cancelled caller must still do this, hence NonCancellable.
                handle.cancel()
                withContext(NonCancellable) { withTimeoutOrNull(stopWaitMs) { handle.awaitStopped() } }
            }
        }
    }

    private fun sizeOfFile(path: String): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        return (attrs?.get("NSFileSize") as? NSNumber)?.longLongValue ?: 0L
    }

    private fun downloaderFilePath(name: String): String = "$downloadsDir/$name"

    private fun throwawayItem(url: String, req: SaveRequest, workName: String) = DownloadItem(
        id = "recording:$workName", contentType = "recording", parentMetaId = "recording", parentMetaType = "recording",
        videoId = workName, title = workName, streamTitle = workName, providerName = "recording",
        sourceUrl = url, sourceHeaders = req.headers, fileName = workName, status = DownloadStatus.Downloading,
        createdAtEpochMs = 0, updatedAtEpochMs = 0,
    )

    private companion object {
        const val SPACE_CHECK_BYTES = 16L * 1024 * 1024
        const val STOP_WAIT_MS = 3_000L
    }
}
