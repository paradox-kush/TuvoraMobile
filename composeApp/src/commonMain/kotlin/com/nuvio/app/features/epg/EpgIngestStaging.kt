package com.nuvio.app.features.epg

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One staged channel row: its source's ORDINAL (stable per-source id assigned at source-begin, so a
 * later/duplicate `slug` cannot mis-attribute it), the normalized epg id, and one display name.
 * NDJSON on disk — JSON escapes any tab/newline inside a name, so line-splitting is safe.
 */
@Serializable
internal data class EpgStagedRow(val o: Int, val i: String, val n: String)

private val stagingJson = Json { ignoreUnknownKeys = true }

internal fun encodeStagedRow(row: EpgStagedRow): String = stagingJson.encodeToString(EpgStagedRow.serializer(), row)
internal fun decodeStagedRow(line: String): EpgStagedRow = stagingJson.decodeFromString(EpgStagedRow.serializer(), line)

/**
 * Bounded, off-DB, off-heap staging for one channels-index ingest.
 *
 * Phase 1 streams the (gunzipped) network response and appends EVERY source's channels here — one
 * [EpgStagedRow] per line — WITHOUT touching the EPG database, so the reader-guarding DB mutex is
 * never held while waiting on the network. The whole document, a whole source's channel list, and the
 * whole selected catalog are all kept out of memory (peak retained = one row + the writer's buffer).
 * Phase 2 reads the rows back in a plain suspend loop and promotes only the kept sources' rows into
 * the shadow table under short per-batch locks. On failure/cancel the file is [dispose]d and no DB
 * generation is touched.
 *
 * Each instance owns a UNIQUE temp file (so overlapping ingests, though single-flighted upstream,
 * can never read or delete each other's staging).
 */
internal expect class EpgIngestStaging() {
    /** Append one line (no newline needed; the writer adds it). Buffered. */
    fun append(line: String)

    /** Flush all appends and switch to read mode. Call once, after the last [append], before
     *  [nextLine]. A PULL cursor (not a callback) so the promote loop can suspend for the per-batch
     *  DB insert between lines without ever holding a lock across the read. */
    fun openForRead()

    /** The next staged line in append order, or null at end of file. */
    fun nextLine(): String?

    /** Delete the temp file and release any handle. Safe to call more than once. */
    fun dispose()
}
