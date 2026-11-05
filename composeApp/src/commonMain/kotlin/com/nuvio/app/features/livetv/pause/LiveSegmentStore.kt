package com.nuvio.app.features.livetv.pause

/**
 * Where a live capture puts its pieces. A port, so [TsSegmenter] runs from byte fixtures in commonTest and
 * compiles for Kotlin/Native; Android supplies a file-backed implementation. Errors propagate: a failing
 * disk must end the capture visibly, never leave a playlist pointing at nothing.
 */
interface LiveSegmentStore {
    fun openPiece(name: String): LiveSegmentWriter
    fun delete(name: String)

    /** Temp file + rename, so the player never reads half a playlist. */
    fun writePlaylistAtomically(text: String)
}

interface LiveSegmentWriter {
    /** Consumes the bytes before returning (the caller reuses the buffer). */
    fun write(bytes: ByteArray, offset: Int, length: Int)
    fun close()
}
