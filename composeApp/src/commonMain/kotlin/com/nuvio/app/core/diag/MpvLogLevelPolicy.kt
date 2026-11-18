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
 * `ffmpeg/http=warn` is the one network module let through: libmpv ends a refused open with only
 * "loading failed", and the provider's status ("HTTP error 458 ...", FFmpeg's http warning, no URL in it)
 * is the sole carrier of the reason the live screen names and retries on. hls/tcp/stream stay off.
 *
 * Android only: the iOS bridge prints from Swift, where each line goes through [LogRedaction.text]
 * instead (it keeps the warn/error text that composes the on-screen playback error).
 */
object MpvLogLevelPolicy {
    const val MSG_LEVEL = "all=no,vo=warn,ao=warn,vd=warn,ad=warn,ffmpeg/video=warn,ffmpeg/audio=warn,ffmpeg/http=warn"

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
