package com.nuvio.app.features.downloads

internal data class DownloadPlatformRequest(
    val item: DownloadItem,
    /** Opt-in, used by recordings only: guards a resume against a different byte stream. Null = the downloader behaves as it always did. */
    val strictResume: StrictResume? = null,
) {
    val sourceUrl: String get() = item.sourceUrl
    val sourceHeaders: Map<String, String> get() = item.sourceHeaders
    val destinationFileName: String get() = item.fileName
}

/**
 * A resume that must never splice a different byte stream into a partial. The downloader sends
 * `If-Range: [validator]` with its Range request, treats a 206 whose Content-Range does not start at the
 * partial's end as "start over from zero", and reports the validators of every fresh (non-resumed)
 * response through [onValidators] so the caller can keep them beside the partial.
 */
internal class StrictResume(
    val validator: String?,
    val onValidators: (etag: String?, lastModified: String?) -> Unit,
)

/** Pure checks on a resume answer. */
internal object ResumeResponseCheck {
    private val RANGE_START = Regex("bytes\\s+(\\d+)-")

    /** First byte named by a `Content-Range: bytes a-b/total` header, or null when absent or unparsable. */
    fun startOfRange(header: String?): Long? =
        header?.let { RANGE_START.find(it.trim())?.groupValues?.get(1)?.toLongOrNull() }

    /** False for a 206 that does not begin at [offset] (a missing Content-Range counts as not beginning there); any other status is not judged here. */
    fun startsWhereAsked(status: Int, contentRange: String?, offset: Long): Boolean =
        status != 206 || startOfRange(contentRange) == offset
}

internal interface DownloadsTaskHandle {
    fun cancel()

    /**
     * Returns once the task has really stopped: after [cancel] a platform task may still be finishing
     * (moving a completed file into place). Callers that clean up files wait here first. Default: nothing to wait for.
     */
    suspend fun awaitStopped() {}
}

internal expect object DownloadsPlatformDownloader {
    fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle

    fun restoreItem(item: DownloadItem): DownloadItem

    fun removeFile(localFileUri: String?): Boolean

    fun removePartialFile(destinationFileName: String): Boolean

    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    fun openDownloadsDirectory(): Boolean
}
