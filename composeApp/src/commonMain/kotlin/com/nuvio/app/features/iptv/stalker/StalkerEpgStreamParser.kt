package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamProgram
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Incremental parser for Stalker's bulk-EPG response, `{"js":{…,"data":{"<chId>":[prog,…],…}}}`,
 * fed by whatever chunks the transport hands over (boundaries can fall anywhere — mid-token,
 * mid-string, mid-escape).
 *
 * [StalkerClient.bulkEpg] used to read the WHOLE body into one String and then build a full
 * JsonElement tree over it — two copies of a response that a large panel can make enormous
 * (TiviMate pulled 174.5 MB from our research mock in one `get_epg_info`; see
 * research/iptv-catalog-loading.md). Here nothing is retained but the current programme element
 * (a few hundred bytes), the current channel key, and the caller's insert buffer, so peak memory
 * no longer scales with guide size. The per-element field parsing is handed to kotlinx over the
 * SMALL captured element, so the lenient field handling matches the old tree walk exactly.
 *
 * Emission: [onProgramme] with the data-object key (the portal's channel id) and the programme.
 * Everything outside the `data` object is scanned and discarded (string-aware, so a `"data"`
 * inside some other field's VALUE can't fool the scanner — keys are only matched at data depth).
 *
 * Not thread-safe — one instance per response, driven from the transport's reader thread.
 */
internal class StalkerEpgStreamParser(
    private val json: Json,
    private val onProgramme: (channelId: Int, programme: XtreamProgram) -> Unit,
) {
    // ---- outer scan: find the `data` object's opening brace -------------------------------
    private var inData = false
    private var afterData = false          // data object closed — ignore the rest of the body
    private var sawDataObject = false      // "data" existed and was an object (vs absent/[]/null)

    // String-aware scanner state (shared by outer scan and data machine).
    private var inString = false
    private var escaped = false

    // Outer-scan key tracking: the most recently CLOSED string at the current position, so when
    // we hit a ':' we know what key it belongs to; `{` after `"data":` enters the data machine.
    private val lastString = StringBuilder()
    private var pendingDataColon = false

    // ---- data machine ---------------------------------------------------------------------
    // Depth inside the data object: 0 = between entries (keys live here), 1+ = inside a value.
    private var dataDepth = 0
    private val keyBuf = StringBuilder()
    private var currentChannelId: Int? = null
    private var inProgrammeArray = false
    private var elementDepth = 0
    private val element = StringBuilder()

    var programmeCount = 0
        private set

    /** True when the body carried a non-empty parsed `data` object — the "portal supports it" signal. */
    val sawData: Boolean get() = sawDataObject

    fun feed(chunk: String) {
        for (c in chunk) {
            if (afterData) return
            if (inData) dataChar(c) else outerChar(c)
        }
    }

    // ---- outer ----------------------------------------------------------------------------

    private fun outerChar(c: Char) {
        if (inString) {
            when {
                escaped -> { lastString.append(c); escaped = false }
                c == '\\' -> escaped = true
                c == '"' -> inString = false
                else -> lastString.append(c)
            }
            return
        }
        when {
            c == '"' -> { inString = true; lastString.clear() }
            c == ':' -> pendingDataColon = lastString.toString() == "data"
            c == '{' && pendingDataColon -> {
                inData = true
                sawDataObject = true
                pendingDataColon = false
                dataDepth = 0
            }
            c.isWhitespace() -> Unit          // ':' may be separated from '{' by whitespace only
            else -> if (c != ',') pendingDataColon = false
        }
    }

    // ---- inside data ----------------------------------------------------------------------

    private fun dataChar(c: Char) {
        // Capturing a programme element: raw-copy until its braces balance, then parse it.
        if (inProgrammeArray && (elementDepth > 0 || c == '{')) {
            element.append(c)
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                return
            }
            when (c) {
                '"' -> inString = true
                '{' -> elementDepth++
                '}' -> {
                    elementDepth--
                    if (elementDepth == 0) emitElement()
                }
            }
            return
        }

        if (inString) {
            when {
                escaped -> { keyBuf.append(c); escaped = false }
                c == '\\' -> escaped = true
                c == '"' -> inString = false
                else -> keyBuf.append(c)
            }
            return
        }

        when (c) {
            '"' -> { inString = true; if (dataDepth == 0 && !inProgrammeArray) keyBuf.clear() }
            ':' -> if (dataDepth == 0) currentChannelId = keyBuf.toString().trim().toIntOrNull()
            '[' -> if (dataDepth == 0) { inProgrammeArray = true; dataDepth++ } else dataDepth++
            ']' -> { dataDepth--; if (dataDepth == 0) inProgrammeArray = false }
            '{' -> dataDepth++
            '}' -> {
                if (dataDepth == 0) { inData = false; afterData = true }   // data object closed
                else dataDepth--
            }
        }
    }

    private fun emitElement() {
        val text = element.toString()
        element.clear()
        val id = currentChannelId ?: return
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        val startMs = (obj.str("start_timestamp")?.toLongOrNull() ?: 0L) * 1000
        val endMs = (obj.str("stop_timestamp")?.toLongOrNull() ?: 0L) * 1000
        if (endMs <= 0) return
        programmeCount++
        onProgramme(
            id,
            XtreamProgram(
                title = obj.str("name").orEmpty(),
                description = obj.str("descr").orEmpty(),
                startMs = startMs,
                endMs = endMs,
                nowPlaying = false,   // decided against the clock at read time, not ingest time
                programmeId = obj.str("id")?.trim()?.takeIf { it.isNotEmpty() },
            )
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
