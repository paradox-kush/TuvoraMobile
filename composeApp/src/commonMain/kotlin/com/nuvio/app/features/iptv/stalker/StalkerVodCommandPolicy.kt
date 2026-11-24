package com.nuvio.app.features.iptv.stalker

/**
 * B02: which `cmd` a VOD `create_link` is sent, decided from the catalogue row and nothing else (no I/O).
 *
 * Stock Ministra (`vod.class.php`) only accepts `/media/<id>.mpg` and `/media/file_<id>.mpg` there. A row that
 * PROVES it is a stock custom-URL row (`is_file`, a non-blank `rtsp_url`, `has_files > 0`) is rebuilt to the
 * `/media/` form from its id, as the real STB (`c/player.js`) does. Anything else keeps its raw cmd as the FIRST
 * attempt: portals emulated over Xtream carry `ffmpeg http://...` cmds that create_link accepts as-is. Only if that
 * attempt gives no link does the client fall back (file rows -> `/media/file_<id>.mpg`, else [Plan.Direct.fallback]).
 */
object StalkerVodCommandPolicy {

    /** What the catalogue row says. Every field is nullable: the cold-start store keeps only [cmd]. */
    data class Row(
        val id: Int,
        val cmd: String?,
        val rtspUrl: String? = null,
        /** `has_files` as a count; null = the row did not say. */
        val hasFiles: Int? = null,
        /** True for a file row of `get_ordered_list movie_id=`. [id] is then the FILE id. */
        val isFile: Boolean = false,
    )

    sealed interface Plan {
        /**
         * Send exactly this cmd to create_link. [fallback] (the `/media/<id>.mpg` form) is for an unproven raw-URL
         * cmd only: if create_link gives no link, the movie's file rows are tried, then [fallback].
         */
        data class Direct(val cmd: String, val fallback: String? = null) : Plan

        /**
         * Ask the portal for the movie's file rows first (`get_ordered_list movie_id=<id>`) and mint
         * `/media/file_<fileId>.mpg`. [fallback] is tried only when no file row exists; null = nothing else to try.
         */
        data class FilesFirst(val fallback: String?) : Plan
    }

    fun movieFileCmd(fileId: Int): String = "/media/file_$fileId.mpg"
    fun movieCmd(movieId: Int): String = "/media/$movieId.mpg"

    fun plan(row: Row): Plan {
        if (row.isFile) return Plan.Direct(movieFileCmd(row.id))
        val cmd = row.cmd?.trim().orEmpty()
        val hasFiles = (row.hasFiles ?: 0) > 0
        if (hasFiles) {
            // A file-form cmd is already the file id; anything else must go through the file rows.
            return if (isFileCmd(cmd)) Plan.Direct(cmd) else Plan.FilesFirst(fallback = null)
        }
        if (!row.rtspUrl.isNullOrBlank()) return Plan.Direct(movieCmd(row.id))
        if (cmd.isEmpty()) return Plan.FilesFirst(fallback = null)
        // A raw URL with no proof (no rtsp_url, has_files 0 or unknown, a cold row with only cmd) may be an
        // XC-emulator cmd: send it unchanged, keep the id form as the last resort.
        if (isRawUrl(cmd)) return Plan.Direct(cmd, fallback = movieCmd(row.id))
        return Plan.Direct(cmd)
    }

    /**
     * The create_link cmd of one FILE row of `get_ordered_list` (a movie's files, or a Ministra episode's): built
     * from the file id, whatever the row's own `cmd` (a custom URL) says. No id: a `/media/` cmd is kept, a raw
     * URL is never returned.
     */
    fun fileRowCmd(fileId: Int?, cmd: String?): String? {
        if (fileId != null) return (plan(Row(fileId, cmd, isFile = true)) as Plan.Direct).cmd
        val c = cmd?.trim().orEmpty()
        return c.takeIf { it.isNotEmpty() && !isRawUrl(it) }
    }

    /** A custom-URL cmd, with or without a launcher token (`ffmpeg http://...`). Never a create_link input. */
    fun isRawUrl(cmd: String): Boolean {
        val bare = StalkerProtocol.stripLauncherPrefix(cmd)
        return URL_SCHEMES.any { bare.startsWith(it, ignoreCase = true) }
    }

    private fun isFileCmd(cmd: String): Boolean = FILE_CMD.containsMatchIn(cmd)

    private val URL_SCHEMES = listOf("http://", "https://", "rtsp://", "rtmp://", "udp://", "rtp://", "mms://")
    private val FILE_CMD = Regex("""/media/file_\d+""")
}

/** Why a Stalker `create_link` gave no link. Never empty: a refusal is shown with its reason. */
enum class StalkerLinkRefusal { SESSION_LIMIT, NOTHING_TO_PLAY, ACCESS_DENIED, LINK_FAULT, PORTAL_ERROR, NO_LINK, REQUEST_FAILED }

/** The answer of one create_link. */
sealed interface StalkerLinkResult {
    data class Link(val url: String) : StalkerLinkResult
    data class Refused(val reason: StalkerLinkRefusal) : StalkerLinkResult
}

/** Pure reading of a create_link `js` reply (`cmd` + `error`) against the portal origin. */
object StalkerCreateLinkReply {

    fun parse(cmd: String?, error: String?, portalOrigin: String): StalkerLinkResult {
        val url = urlOf(cmd, portalOrigin)
        if (url != null) return StalkerLinkResult.Link(url)
        val err = error?.trim()?.lowercase().orEmpty()
        val reason = when {
            err.isEmpty() -> if (cmd?.trim() == "link_fault") StalkerLinkRefusal.LINK_FAULT else StalkerLinkRefusal.NO_LINK
            err == "limit" -> StalkerLinkRefusal.SESSION_LIMIT
            err == "nothing_to_play" -> StalkerLinkRefusal.NOTHING_TO_PLAY
            err == "access_denied" -> StalkerLinkRefusal.ACCESS_DENIED
            err == "link_fault" -> StalkerLinkRefusal.LINK_FAULT
            else -> StalkerLinkRefusal.PORTAL_ERROR
        }
        return StalkerLinkResult.Refused(reason)
    }

    /** Absolute http(s) link, or a relative one (`/media/..`, `?token=..`) resolved against [portalOrigin]. */
    fun urlOf(cmd: String?, portalOrigin: String): String? {
        if (cmd.isNullOrBlank()) return null
        StalkerProtocol.extractStreamUrl(cmd)?.let { return it }
        val stripped = StalkerProtocol.stripLauncherPrefix(cmd)
        // An unknown launcher token: keep the text after the first space.
        val candidates = listOf(stripped, stripped.substringAfter(' ', "").trim())
        val origin = portalOrigin.trimEnd('/')
        for (c in candidates) {
            if (c.isEmpty() || origin.isEmpty()) continue
            when {
                c.startsWith("/") && !c.startsWith("//") -> return origin + c
                c.startsWith("?") -> return "$origin/$c"
            }
        }
        return null
    }
}
