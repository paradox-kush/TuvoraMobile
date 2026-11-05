package com.nuvio.app.features.recordings

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import com.nuvio.app.features.recordings.api.LocalRecordings
import com.nuvio.app.features.recordings.api.RecordingsAccess
import com.nuvio.app.features.recordings.api.RecordingsPort
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertSame
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * iOS never wraps its UI in `installFeatures` (only Android's MainActivity and the desktop window do), so a screen
 * there reads [LocalRecordings] with nothing provided. With a null default every recording affordance (Record, Record
 * series, REC badge, the Recordings page) stayed hidden on iPhone. The default must be the registered port.
 */
class LocalRecordingsDefaultTest {
    @Test
    fun `a composition nobody wrapped in installFeatures reads the registered port`() = runBlocking {
        // Identity is all that is compared, so a stub that fails if anything calls it is enough.
        val registered = Proxy.newProxyInstance(
            RecordingsPort::class.java.classLoader, arrayOf<Class<*>>(RecordingsPort::class.java),
        ) { _, method, _ -> error("unexpected call to ${method.name}") } as RecordingsPort
        RecordingsAccess.register(registered)

        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(NoOpApplier(), recomposer)
        val runner = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        var seen: RecordingsPort? = null
        try {
            composition.setContent { seen = LocalRecordings.current }
            Snapshot.sendApplyNotifications()
            yield()
            frameClock.sendFrame(0L)
            yield()
            assertSame(registered, seen, "with no provider the screens must still get the registered recordings port (iOS)")
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.join()
        }
    }

    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
