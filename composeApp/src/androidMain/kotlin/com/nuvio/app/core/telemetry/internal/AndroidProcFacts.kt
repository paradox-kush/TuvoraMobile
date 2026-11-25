package com.nuvio.app.core.telemetry.internal

import java.io.File

/**
 * Process facts read from `/proc/self`, plus the QA-mirror switch. Pure parsers over text so they test on the JVM; the
 * readers swallow every failure (an unreadable /proc must never cost a sample). Call off Main.
 */
internal object AndroidProcFacts {
    /** `Threads:\t42` from `/proc/self/status`. */
    fun threadsFromStatus(status: String): Long? =
        status.lineSequence().firstOrNull { it.startsWith("Threads:") }?.substringAfter(':')?.trim()?.toLongOrNull()

    fun readThreads(): Long? = runCatching { threadsFromStatus(File("/proc/self/status").readText()) }.getOrNull()

    /** Open file descriptors of this process. The listing itself holds one, so it is not subtracted: a trend, not an audit. */
    fun readFdCount(): Long? = runCatching { File("/proc/self/fd").list()?.size?.toLong() }.getOrNull()

    /** The value of `debug.tuvora.qa`, read through the hidden `SystemProperties.get`, then `getprop`. Null when unreadable. */
    fun readQaProperty(): String? {
        val viaReflection = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, com.nuvio.app.core.telemetry.api.QaMirror.ANDROID_PROPERTY) as? String
        }.getOrNull()
        // An unset property reads as "": that is an answer, not a reason to spawn `getprop` on every start.
        if (viaReflection != null) return viaReflection
        return runCatching {
            val p = ProcessBuilder("getprop", com.nuvio.app.core.telemetry.api.QaMirror.ANDROID_PROPERTY).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.destroy()
            out
        }.getOrNull()
    }
}
