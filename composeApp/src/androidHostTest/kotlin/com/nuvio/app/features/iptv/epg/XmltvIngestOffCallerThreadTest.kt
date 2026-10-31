package com.nuvio.app.features.iptv.epg

import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.content.IptvContentDbDriver
import com.nuvio.app.features.iptv.content.IptvStreamRow
import com.nuvio.app.features.iptv.overlay.IptvOverlayStore
import com.nuvio.app.features.iptv.overlay.OverlayDbDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Play ANR (1.15.0, 15.8% of users): `XmltvGuideReplay.finish` decoded the staged guide on the main
 * thread because [XmltvClient.ensureEpg] ran the ingest on its CALLER's dispatcher. The caller here
 * is `runBlocking` (stand-in for Main); the whole ingest must hop to [XmltvClient.ingestDispatcher].
 */
class XmltvIngestOffCallerThreadTest {
    private lateinit var server: MockWebServer
    private lateinit var overlayFile: File
    private val fmt = SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = TimeZone.getTimeZone("UTC") }

    @BeforeTest
    fun setUp() {
        IptvContentDbDriver.openForTests = { androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(":memory:") }
        overlayFile = File.createTempFile("overlay_offthread", ".db").also { it.delete() }
        OverlayDbDriver.openForTests = { androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(overlayFile.absolutePath) }
        runBlocking { IptvOverlayStore.closeForTests() }
        val now = System.currentTimeMillis()
        val guide = "<?xml version=\"1.0\"?>\n<tv>\n<channel id=\"cnn.us\"><display-name>CNN</display-name></channel>\n" +
            "<programme start=\"${fmt.format(Date(now - 30 * 60_000L))}\" stop=\"${fmt.format(Date(now + 30 * 60_000L))}\" channel=\"cnn.us\"><title>CNN now</title></programme>\n</tv>\n"
        server = MockWebServer().also { it.enqueue(MockResponse().setBody(guide)); it.start() }
    }

    @AfterTest
    fun tearDown() {
        XmltvClient.ingestDispatcher = Dispatchers.Default
        server.shutdown()
        runBlocking { IptvOverlayStore.closeForTests() }
        OverlayDbDriver.openForTests = null
        overlayFile.delete()
    }

    private class CountingDispatcher : CoroutineDispatcher() {
        val dispatched = AtomicInteger()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched.incrementAndGet()
            Dispatchers.Default.dispatch(context, block)
        }
    }

    @Test
    fun `ensureEpg runs the guide ingest on the ingest dispatcher not the caller`() = runBlocking {
        val counting = CountingDispatcher()
        XmltvClient.ingestDispatcher = counting
        val acc = XtreamAccount(
            id = "m3u|offthread", name = "t", baseUrl = server.url("/playlist.m3u").toString(),
            username = "", password = "", sourceType = "m3u_url", epgUrl = server.url("/guide.xml").toString(),
        )
        IptvContentDb.replaceLiveLineup(
            acc.id,
            listOf(IptvStreamRow(sid = 1, name = "CNN", logo = null, tvgId = "cnn.us", categoryId = "1", url = "http://x/1.ts", ext = null)),
            listOf("1" to "US"),
        )

        assertTrue(XmltvClient.ensureEpg(acc, force = true), "ingest stored programmes")

        assertTrue(counting.dispatched.get() > 0, "the ingest hopped to the ingest dispatcher")
        assertEquals("CNN now", XmltvClient.storedNowNext(acc, 1).firstOrNull()?.title)
    }
}
