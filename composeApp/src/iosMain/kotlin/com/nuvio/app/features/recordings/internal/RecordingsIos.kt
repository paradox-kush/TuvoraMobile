package com.nuvio.app.features.recordings.internal

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadPlatformRequest
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsTaskHandle
import com.nuvio.app.features.downloads.StrictResume
import com.nuvio.app.features.recordings.api.ArmKind
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
import kotlinx.coroutines.coroutineScope
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
 * downloader a partial that this very link wrote. The link is pinned beside the partial in
 * `<part>.meta`, the same sidecar name Android uses, so the repository deletes both the same way.
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

    /**
     * Readies [workPart] for a save from [url]. A partial pinned to the same link moves over so the
     * downloader resumes it; any other partial is deleted so the save starts at zero. Returns whether it resumes.
     */
    fun stageForResume(url: String, part: String, workPart: String, resumeFromBytes: Long): Boolean {
        remove(workPart)
        val resume = fm.fileExistsAtPath(part) && RecordingResumePolicy.canResume(readPin(part), url, resumeFromBytes)
        if (resume) {
            fm.moveItemAtPath(part, workPart, null)
            return true
        }
        remove(part)
        remove("$part.meta")
        return false
    }

    /**
     * After a save stopped for any reason (failure, pause, cancel): whatever the downloader wrote goes
     * back beside the target with its link, or is dropped if empty. Safe to call twice. Returns the bytes kept.
     */
    fun parkAfterStop(url: String, part: String, workPart: String, etag: String? = null, lastModified: String? = null): Long {
        if (!fm.fileExistsAtPath(workPart)) return 0L
        val size = sizeOf(workPart)
        if (size <= 0L) {
            remove(workPart)
            return 0L
        }
        remove(part)
        fm.moveItemAtPath(workPart, part, null)
        writePin(part, url, etag, lastModified)
        return size
    }

    fun clearPin(part: String) = remove("$part.meta")
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
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosRecordingSaver(
    private val files: RecordingFiles,
    /** Where the downloader works. The real downloader always uses [IosRecordingPaths.defaultDownloadsDir]; tests point both at a temp folder. */
    private val downloadsDir: String = IosRecordingPaths.defaultDownloadsDir(),
    private val start: DownloaderStart = ::startRealDownloader,
    /** How long a stopped save waits for the downloader to really stop before it gives up and cleans up anyway. */
    @Suppress("unused") private val stopWaitMs: Long = 3_000L,
) : RecordingSaver {
    private val staging = IosPartialStaging()

    override suspend fun save(req: SaveRequest, progress: (Long) -> Unit): SaveOutcome {
        val fm = NSFileManager.defaultManager
        val part = req.targetPath + ".part"
        val workName = IosRecordingPaths.workName(req.targetPath.substringAfterLast('/'))
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
                staging.clearPin(part)
                return SaveOutcome.Done(files.sizeOf(req.targetPath))
            }
            val failed = outcome as SaveOutcome.Failed
            if (best == null || failed.writtenBytes >= best.writtenBytes) best = failed
            if (failed.offline) break
            // Out of space: no other link will help.
            if (failed.problem == RecordingProblem.NO_SPACE) break
        }
        return best ?: SaveOutcome.Failed(RecordingProblem.NO_CATCH_UP, offline = false, writtenBytes = 0, rangeSupported = false)
    }

    private suspend fun downloadOne(url: String, req: SaveRequest, workName: String, part: String, progress: (Long) -> Unit): SaveOutcome {
        val workPart = downloaderFilePath(workName) + ".part"
        // Hand a previous partial back to the downloader only if THIS link wrote it; otherwise start at zero.
        val pinBefore = staging.readResumePin(part)
        val resumed = staging.stageForResume(url, part, workPart, req.resumeFromBytes)
        // The partial keeps the validators it came with; a fresh response replaces them (the downloader reports those).
        var etag = if (resumed) pinBefore?.etag else null
        var lastModified = if (resumed) pinBefore?.lastModified else null
        val strict = StrictResume(
            validator = if (resumed) pinBefore?.validator else null,
            onValidators = { e, l -> etag = e; lastModified = l },
        )
        val item = throwawayItem(url, req, workName)
        var finished = false
        try {
            val outcome = runDownload(item, strict, req, progress)
            if (outcome is SaveOutcome.Done) {
                finished = true
                return outcome
            }
            // Keep the partial (and its link) where the repository looks for it.
            val written = staging.parkAfterStop(url, part, workPart, etag, lastModified)
            return (outcome as SaveOutcome.Failed).copy(writtenBytes = written)
        } finally {
            // A cancel throws past the code above: the partial still goes home, so the repository can
            // resume it after a teardown or delete it after a user cancel instead of leaking it.
            if (!finished) staging.parkAfterStop(url, part, workPart, etag, lastModified)
        }
    }

    private suspend fun runDownload(item: DownloadItem, strict: StrictResume, req: SaveRequest, progress: (Long) -> Unit): SaveOutcome = coroutineScope {
        val result = CompletableDeferred<SaveOutcome>()
        val ticks = Channel<Long>(Channel.CONFLATED)
        val handle = start(
            DownloadPlatformRequest(item, strict),
            { downloaded, _ -> progress(downloaded); ticks.trySend(downloaded) },
            { _, total -> result.complete(SaveOutcome.Done(total ?: 0L)) },
            { _ ->
                // The downloader reports a message, not a class: a failure is NETWORK (retried, bounded by the schedule policy).
                result.complete(SaveOutcome.Failed(RecordingProblem.NETWORK, offline = false, writtenBytes = 0, rangeSupported = true))
            },
            { result.complete(SaveOutcome.Failed(RecordingProblem.STALLED, offline = false, writtenBytes = 0, rangeSupported = true)) },
        )
        val spaceCheck = req.spaceCheck
        val checker = launch {
            var last = -1L
            for (downloaded in ticks) {
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
            // Cancelled (or finished): make sure no network task outlives this call.
            if (!result.isCompleted || result.isCancelled) handle.cancel()
        }
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
    }
}
