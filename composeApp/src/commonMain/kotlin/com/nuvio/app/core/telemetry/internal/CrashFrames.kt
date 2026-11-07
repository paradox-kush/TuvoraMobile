package com.nuvio.app.core.telemetry.internal

/**
 * Turns a throwable into what may be sent about it: the TYPE of each exception in the cause chain plus its frame
 * lines, and never message text. Exception messages are free text written by libraries and carry provider data
 * (a JSON body with credentials, a host name, a MAC, a home-directory path), so none of it is sent: not the
 * message, and not the lines of a printed trace that repeat it. Frames are parsed with strict patterns; a line
 * that does not look like a frame is dropped, never scrubbed-and-kept.
 */
internal object CrashFrames {
    const val MAX_LINES = 64
    private const val MAX_CAUSES = 6
    private const val MAX_LINE = 200

    // JVM / ART:  at java.base/java.lang.Thread.run(Thread.java:840)   at a.B.c(Native Method)   at a.B.c(Unknown Source)
    private val jvmFrame = Regex("""^at ([A-Za-z0-9_.$<>/\-]+)\(([A-Za-z0-9_.$<> \-]+)(?::(\d+))?\)$""")
    // Kotlin/Native:  at 0   App   0x0000000104a8c1d4  kfun:com.nuvio.app.Foo#bar(){}kotlin.Int + 120 (/Users/me/proj/Foo.kt:12:3)
    private val nativeFrame = Regex("""^at \d+\s+\S+\s+0x[0-9a-fA-F]+\s+(kfun:[A-Za-z0-9_.$<>#(){}:,\-]+)""")
    private val nativeLocation = Regex("""\(([^()]*[/\\])?([A-Za-z0-9_.\-]+\.[A-Za-z]{1,6}):(\d+)(?::\d+)?\)\s*$""")

    fun linesOf(error: Throwable): List<String> {
        val out = ArrayList<String>(MAX_LINES)
        var current: Throwable? = error
        var depth = 0
        val seen = ArrayList<Throwable>()
        while (current != null && seen.none { it === current } && depth < MAX_CAUSES && out.size < MAX_LINES) {
            seen += current
            out += (if (depth == 0) "" else "Caused by: ") + CrashRecordCodec.exceptionType(current)
            for (frame in ownFrames(current)) {
                if (out.size >= MAX_LINES) break
                out += frame
            }
            current = current.cause
            depth++
        }
        return out
    }

    /** The frames printed for [t] itself: lines up to the first "Caused by:" / "Suppressed:" marker. */
    private fun ownFrames(t: Throwable): List<String> {
        val frames = ArrayList<String>()
        var first = true
        for (raw in t.stackTraceToString().lineSequence()) {
            val line = raw.trim()
            if (first) { first = false; continue } // "Type: message": the message never leaves the device
            if (line.startsWith("Caused by:") || line.startsWith("Suppressed:")) break
            parseFrame(line)?.let { frames += it }
        }
        return frames
    }

    internal fun parseFrame(line: String): String? {
        jvmFrame.matchEntire(line)?.let { m ->
            val lineNo = m.groupValues[3]
            return "at ${m.groupValues[1]}(${m.groupValues[2]}${if (lineNo.isNotEmpty()) ":$lineNo" else ""})".take(MAX_LINE)
        }
        nativeFrame.find(line)?.let { m ->
            val loc = nativeLocation.find(line)?.let { " (${it.groupValues[2]}:${it.groupValues[3]})" }.orEmpty()
            return "at ${m.groupValues[1]}$loc".take(MAX_LINE)
        }
        return null
    }
}
