package com.tuvora.tvos.telemetry

import com.nuvio.app.core.telemetry.internal.TelemetryFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvDurableMirrorFileStoreTest {
    private class MapStore : TelemetryFileStore {
        val files = mutableMapOf<String, String>()
        override fun read(name: String) = files[name]
        override fun writeAtomic(name: String, text: String): Boolean { files[name] = text; return true }
        override fun rename(from: String, to: String): Boolean { val t = files.remove(from) ?: return false; files[to] = t; return true }
        override fun delete(name: String) { files.remove(name) }
    }

    private class MapKv : TvKeyValue {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun `a crash record survives the system purging Caches while the app is not running`() {
        val files = MapStore(); val kv = MapKv(); val store = TvDurableMirrorFileStore(files, kv)
        store.writeAtomic("pending_crash.json", """{"crash_id":"a"}""")
        files.files.clear()   // the purge
        assertEquals("""{"crash_id":"a"}""", TvDurableMirrorFileStore(MapStore(), kv).read("pending_crash.json"))
    }

    @Test
    fun `moving pending to sending works from the mirror alone and delete clears both`() {
        val files = MapStore(); val kv = MapKv(); val store = TvDurableMirrorFileStore(files, kv)
        store.writeAtomic("pending_crash.json", "x")
        files.files.clear()
        assertEquals(true, store.rename("pending_crash.json", "sending.json"))
        assertNull(store.read("pending_crash.json"))
        assertEquals("x", store.read("sending.json"))
        store.delete("sending.json")
        assertNull(store.read("sending.json"))
        assertEquals(0, kv.map.size)
    }

    @Test
    fun `the ring snapshot and oversized records are not mirrored`() {
        val kv = MapKv(); val store = TvDurableMirrorFileStore(MapStore(), kv)
        store.writeAtomic("ring.json", "ring")
        store.writeAtomic("pending_crash.json", "x".repeat(TvDurableMirrorFileStore.MAX_MIRROR_CHARS + 1))
        assertEquals(0, kv.map.size)
    }
}
