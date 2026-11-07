package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.internal.CrashRecordCodec
import com.nuvio.app.core.telemetry.internal.IosTelemetry
import com.nuvio.app.core.telemetry.internal.IosTelemetryFileStore
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.getUnhandledExceptionHook
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Kotlin/Native unhandled-exception hook that `IosTelemetry.start()` installs really writes the crash record. */
@OptIn(ExperimentalNativeApi::class)
class IosCrashHookTest {
    @Test
    fun `the installed hook writes the crash record`() {
        IosTelemetry.start()
        val store = IosTelemetryFileStore()
        store.delete("pending_crash.json")
        val hook = getUnhandledExceptionHook()
        assertNotNull(hook, "IosTelemetry.start() installed no unhandled-exception hook")
        hook(IllegalStateException("boom from http://prov.example.tv/x"))
        val text = store.read("pending_crash.json")
        assertNotNull(text, "the hook wrote no record")
        val record = CrashRecordCodec.decode(text)!!
        assertTrue(record.exception_type.endsWith("IllegalStateException"), record.exception_type)
        assertTrue(record.stack.isNotEmpty())
        store.delete("pending_crash.json")
    }
}
