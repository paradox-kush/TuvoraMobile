package com.nuvio.app.features.epg

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

/**
 * JVM staging: a unique temp file under java.io.tmpdir, buffered writes, line-streamed pull read
 * (never loads the whole file into memory). Deleted on [dispose].
 */
internal actual class EpgIngestStaging actual constructor() {
    private val file: File = File.createTempFile("epg-index-stage-", ".ndjson").apply { deleteOnExit() }
    private var writer: BufferedWriter? = file.bufferedWriter()
    private var reader: BufferedReader? = null

    actual fun append(line: String) {
        writer?.apply { write(line); newLine() }
    }

    actual fun openForRead() {
        writer?.let { runCatching { it.flush(); it.close() } }
        writer = null
        reader = file.bufferedReader()
    }

    actual fun nextLine(): String? = reader?.readLine()

    actual fun dispose() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        writer = null
        reader = null
        runCatching { file.delete() }
    }
}
