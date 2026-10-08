package com.nuvio.app.features.addons

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GzipStreamTest {
    private val xml = "<?xml version=\"1.0\"?><tv><channel id=\"bbc\"><display-name>BBC One</display-name></channel><programme channel=\"bbc\" start=\"20261007000000 +0000\" stop=\"20261008000000 +0000\"><title>Match</title></programme></tv>"
    private val gzip = "1f8b08000000000002ff558e4d0ac2301085af32642b9ab40b753149a1aec533a469b081fc9184a2b737b5a5d0590c33ef63de3cec3ecec2ac5336c173d25c18e9049659a09aa4f7da821939190645048e26472bbf672f9d167dff8097d7480f2ad2ed4c604ce19da4731a3669b5815c642a9cb4acbd368cddd8bfe0b4f40586b8b3fb81d554a6582d9eb2a809e9ba20dddfd4b9c6fe017cf3efbbd0000000".unhex()
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private suspend fun decode(bytes: ByteArray, limit: Long = Long.MAX_VALUE): String {
        val chunks = mutableListOf<String>()
        streamMaybeGzipChunks(ByteReadChannel(bytes), limit) { b, n -> chunks += b.decodeToString(0, n) }
        return chunks.joinToString("")
    }
    @Test fun plainXmlAndRawGzipDecodeIdentically() = runTest {
        assertEquals(xml, decode(xml.encodeToByteArray()))
        assertEquals(xml, decode(gzip))
    }
    @Test fun emptyAndOneByteBodiesKeepTheirBytes() = runTest {
        assertEquals("", decode(byteArrayOf()))
        assertEquals("<", decode(byteArrayOf(60)))
    }
    @Test fun decodedLimitStopsExpansionBeforeTheWholeFeed() = runTest {
        val large = "1f8b08000000000002ffedc1010d000000c2a0da8f6f0f07140000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000fc18cd2ac8b3e0930400".unhex()
        assertEquals("x".repeat(1024), decode(large, 1024))
        var total = 0
        var maxChunk = 0
        streamMaybeGzipChunks(ByteReadChannel(large), Long.MAX_VALUE) { _, n ->
            total += n
            maxChunk = maxOf(maxChunk, n)
        }
        assertEquals(300000, total)
        assertTrue(maxChunk <= 64 * 1024)
    }
    @Test fun truncatedAndCorruptGzipFailInsteadOfCompletingIngest() = runTest {
        assertFailsWith<IllegalStateException> { decode(gzip.copyOf(gzip.size - 5)) }
        val corrupt = gzip.copyOf().also { it[it.size - 8] = (it[it.size - 8].toInt() xor 1).toByte() }
        assertFailsWith<IllegalStateException> { decode(corrupt) }
    }
    @Test fun concatenatedMembersAreDecoded() = runTest {
        assertEquals(xml + xml, decode(gzip + gzip))
    }
    @Test fun fragmentedMagicAndBodyDecodeWithoutLosingBytes() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val producer = launch {
            for (byte in gzip) {
                channel.writeFully(byteArrayOf(byte))
                yield()
            }
            channel.close()
        }
        val chunks = mutableListOf<String>()
        streamMaybeGzipChunks(channel, Long.MAX_VALUE) { b, n -> chunks += b.decodeToString(0, n) }
        producer.join()
        assertEquals(xml, chunks.joinToString(""))
    }

}
