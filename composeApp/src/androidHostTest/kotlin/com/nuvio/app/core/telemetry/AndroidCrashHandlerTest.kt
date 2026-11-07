package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.internal.AndroidTelemetry
import com.nuvio.app.core.telemetry.internal.CrashRecordCodec
import com.nuvio.app.core.telemetry.internal.TelemetryRuntime
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The part the service tests cannot prove: that the INSTALLED uncaught-exception handler chain really writes the
 * record when a thread dies, and still hands the crash on to the previous handler (PostHog's, then the system's).
 */
class AndroidCrashHandlerTest {

    private fun crashHere(): Throwable = IllegalStateException("boom from http://prov.example.tv/x")

    @Test
    fun `an exception that kills a thread writes the crash record and reaches the previous handler`() {
        val saved = Thread.getDefaultUncaughtExceptionHandler()
        try {
            TelemetryRuntime.resetForTest()
            val host = FakeTelemetryHost()
            TelemetryRuntime.start(host)
            val delegatedTo = AtomicReference<Throwable?>(null)
            Thread.setDefaultUncaughtExceptionHandler { _, e -> delegatedTo.set(e) } // stands in for PostHog + system
            AndroidTelemetry.installCrashHandler()

            val boom = crashHere()
            val t = Thread({ throw boom }, "crash-test")
            t.start()
            t.join()

            val file = host.store.files["pending_crash.json"]
            assertNotNull(file, "the handler wrote no record")
            val record = CrashRecordCodec.decode(file)!!
            assertEquals("java.lang.IllegalStateException", record.exception_type)
            assertTrue(record.stack.any { it.contains("crashHere(AndroidCrashHandlerTest.kt:") }, record.stack.toString())
            assertTrue(record.stack.none { it.contains("prov.example") })
            assertEquals(boom, delegatedTo.get(), "the crash must still reach the previous handler")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(saved)
            TelemetryRuntime.resetForTest()
        }
    }

    @Test
    fun `re-installing on top of the SDK handler records one crash once`() {
        val saved = Thread.getDefaultUncaughtExceptionHandler()
        try {
            TelemetryRuntime.resetForTest()
            val host = FakeTelemetryHost()
            TelemetryRuntime.start(host)
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
            AndroidTelemetry.installCrashHandler()
            AndroidTelemetry.installCrashHandler() // configurePostHog re-wraps after the SDK installed its own
            val t = Thread({ throw IllegalStateException("once") }, "crash-test-2")
            t.start()
            t.join()
            assertEquals(1, host.store.files.keys.count { it == "pending_crash.json" })
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(saved)
            TelemetryRuntime.resetForTest()
        }
    }
}
