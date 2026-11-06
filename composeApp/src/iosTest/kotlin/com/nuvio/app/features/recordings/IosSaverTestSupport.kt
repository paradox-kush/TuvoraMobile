package com.nuvio.app.features.recordings

import com.nuvio.app.features.downloads.DownloadPlatformRequest
import com.nuvio.app.features.downloads.DownloadsTaskHandle
import com.nuvio.app.features.recordings.api.KnownSizeDecision
import com.nuvio.app.features.recordings.api.RecordingProblem
import com.nuvio.app.features.recordings.api.SaveRequest
import com.nuvio.app.features.recordings.internal.DownloaderStart
import com.nuvio.app.features.recordings.internal.IosRecordingFiles
import com.nuvio.app.features.recordings.internal.IosRecordingSaver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/** A temp recordings folder + downloader folder, and the saver wired to them. */
@OptIn(ExperimentalForeignApi::class)
internal class SaverRig(downloadsDir: String? = null) {
    private val fm = NSFileManager.defaultManager
    val root = NSTemporaryDirectory().trimEnd('/') + "/rec-saver-" + NSUUID().UUIDString
    val recs = "$root/recs"
    val downloads = downloadsDir ?: "$root/downloads"
    val files = IosRecordingFiles(stateDir = "$root/state", recordingsDir = recs, downloadsDir = downloads)

    init {
        fm.createDirectoryAtPath(recs, true, null, null)
        fm.createDirectoryAtPath(downloads, true, null, null)
    }

    fun put(path: String, text: String) { NSString.create(string = text).writeToFile(path, true, NSUTF8StringEncoding, null) }
    fun read(path: String): String? = if (exists(path)) NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) else null
    fun exists(path: String) = fm.fileExistsAtPath(path)
    fun size(path: String) = files.sizeOf(path)
    fun move(from: String, to: String) { fm.moveItemAtPath(from, to, null) }

    /** Names in the downloader's folder that belong to a recording save. */
    fun recFilesInDownloads(): List<String> =
        (fm.contentsOfDirectoryAtPath(downloads, null) as? List<*>)?.map { it.toString() }?.filter { it.startsWith("rec-") }.orEmpty()

    fun close() { fm.removeItemAtPath(root, null) }

    fun saver(start: DownloaderStart? = null, stopWaitMs: Long = 3_000L) =
        if (start == null) IosRecordingSaver(files, downloads) else IosRecordingSaver(files, downloads, start, stopWaitMs)

    fun request(
        vararg urls: String,
        name: String = "show.ts",
        resumeFrom: Long = 0,
        estimated: Long = 0,
        spaceCheck: (suspend (Long) -> RecordingProblem?)? = null,
        onKnownTotal: (suspend (Long, Long) -> KnownSizeDecision)? = null,
    ) = SaveRequest(
        candidates = urls.toList(), headers = emptyMap(), targetPath = files.pathFor(name),
        resumeFromBytes = resumeFrom, estimatedBytes = estimated, spaceCheck = spaceCheck, onKnownTotal = onKnownTotal,
    )
}

/** The downloader's four callbacks, handed to a scripted fake. */
internal class DownloaderCallbacks(
    val onProgress: (Long, Long?) -> Unit,
    val onSuccess: (String, Long?) -> Unit,
    val onFailure: (String) -> Unit,
    val onPaused: () -> Unit,
)

/** A downloader stand-in: [script] runs for each started link and returns the handle the saver gets. */
internal fun fakeDownloader(script: (url: String, cb: DownloaderCallbacks) -> DownloadsTaskHandle): DownloaderStart =
    { req: DownloadPlatformRequest, p, s, f, pa -> script(req.sourceUrl, DownloaderCallbacks(p, s, f, pa)) }

internal class FakeHandle(
    private val onCancel: () -> Unit = {},
    private val stopped: suspend () -> Unit = {},
) : DownloadsTaskHandle {
    var cancels = 0
    override fun cancel() { cancels++; onCancel() }
    override suspend fun awaitStopped() = stopped()
}
