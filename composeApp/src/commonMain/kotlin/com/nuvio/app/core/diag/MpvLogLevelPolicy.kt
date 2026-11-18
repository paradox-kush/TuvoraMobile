package com.nuvio.app.core.diag

/**
 * B116 — which raw libmpv/FFmpeg log modules may reach the device log at all.
 *
 * mpv-android-lib's native layer prints EVERY log message mpv delivers straight to logcat (tag `mpv`,
 * `[%s:%s] %s`), before any Kotlin observer sees it, so those lines cannot be redacted — only not
 * produced. Its default `msg-level` is `all=v`, which prints `[cplayer] Playing: <url>`,
 * `[stream] Opening <url>` and, at error level, `[stream] Failed to open <url>.`; FFmpeg's HLS demuxer
 * warns with segment URLs (`keepalive request failed for '<url>'`). Provider credentials ride in
 * those URLs (Xtream `/timeshift/<user>/<pass>/…`, `get.php?username=&password=`).
 *
 * So raw output is OFF (TV's clean adapter already runs `all=no`), with an allowlist of modules that
 * report decoder/output state and never format a URL: video/audio output and decoders.
 * Matching follows mpv `common/msg.c`: an entry applies to the module and its `/` sub-modules, and
 * the LAST matching entry wins.
 *
 * The one network line let through is FFmpeg's http status: libmpv ends a refused open with only
 * "loading failed", and the provider's status ("HTTP error 458 ...", no URL in it) is the sole carrier of the
 * reason the live screen names and retries on. Device-verified (Android libmpv 0.41, `all=v`): it arrives as
 * module `ffmpeg` (NOT `ffmpeg/http`), text `http: HTTP error 458`, level warn, so `ffmpeg/http=warn` never
 * matched. mpv cannot narrow below the module, and the `ffmpeg` module also carries tcp/tls/hls warnings with
 * hosts and segment URLs, so it is raised to warn ([LIVE_OPEN_MSG_LEVEL]) only while a live open is pending and
 * dropped back to [MSG_LEVEL] once the file loaded or the open ended; [MpvErrorLog] keeps only the status line.
 *
 * Android only: the iOS bridge prints from Swift, where each line goes through [LogRedaction.text]
 * instead (it keeps the warn/error text that composes the on-screen playback error).
 */
object MpvLogLevelPolicy {
    const val MSG_LEVEL = "all=no,vo=warn,ao=warn,vd=warn,ad=warn,ffmpeg/video=warn,ffmpeg/audio=warn"

    /** [MSG_LEVEL] plus the whole `ffmpeg` module at warn, for the window of a live open. */
    const val LIVE_OPEN_MSG_LEVEL = "$MSG_LEVEL,ffmpeg=warn"

    fun specFor(liveOpen: Boolean): String = if (liveOpen) LIVE_OPEN_MSG_LEVEL else MSG_LEVEL

    /** The effective level mpv would apply to [module] under [spec] (default when nothing matches: "status"). */
    fun levelFor(module: String, spec: String = MSG_LEVEL): String {
        var level = "status"
        spec.split(',').forEach { entry ->
            val name = entry.substringBefore('=').trim()
            val value = entry.substringAfter('=', "").trim()
            if (name.isNotEmpty() && value.isNotEmpty() && matches(module, name)) level = value
        }
        return level
    }

    private fun matches(module: String, name: String): Boolean =
        name == "all" || module == name || module.startsWith("$name/")
}
