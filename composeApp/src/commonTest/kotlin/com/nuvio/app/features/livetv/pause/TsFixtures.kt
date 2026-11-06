package com.nuvio.app.features.livetv.pause

/** Byte fixtures for [TsSegmenter] tests: real 188-byte TS packets built by hand. */
internal object Ts {
    const val PMT_PID = 0x100
    const val VIDEO_PID = 0x101
    const val AUDIO_PID = 0x102
    const val PACKET = 188

    private fun blank(pid: Int, pusi: Boolean, afc: Int, fill: Int = 0xFF): ByteArray {
        val p = ByteArray(PACKET) { fill.toByte() }
        p[0] = 0x47
        p[1] = (((if (pusi) 0x40 else 0) or (pid shr 8))).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = ((afc shl 4)).toByte()
        return p
    }

    /** PAT on pid 0 with a pointer field and one program pointing at [PMT_PID]. */
    fun pat(): ByteArray {
        val p = blank(0, pusi = true, afc = 1)
        p[4] = 0 // pointer field
        p[5] = 0 // table id
        p[6] = 0xB0.toByte(); p[7] = 13 // section length: 5 + 4 + 4
        p[8] = 0; p[9] = 1 // transport stream id
        p[10] = 0xC1.toByte(); p[11] = 0; p[12] = 0
        p[13] = 0; p[14] = 1 // program 1
        p[15] = (0xE0 or (PMT_PID shr 8)).toByte(); p[16] = (PMT_PID and 0xFF).toByte()
        for (i in 17..20) p[i] = 0 // crc, not checked
        return p
    }

    /** PMT whose PCR_PID is [pcrPid]. */
    fun pmt(pcrPid: Int = VIDEO_PID): ByteArray {
        val p = blank(PMT_PID, pusi = true, afc = 1)
        p[4] = 0
        p[5] = 2 // table id
        p[6] = 0xB0.toByte(); p[7] = 18
        p[8] = 0; p[9] = 1
        p[10] = 0xC1.toByte(); p[11] = 0; p[12] = 0
        p[13] = (0xE0 or (pcrPid shr 8)).toByte(); p[14] = (pcrPid and 0xFF).toByte()
        p[15] = 0xF0.toByte(); p[16] = 0 // program info length
        return p
    }

    /**
     * An elementary-stream packet. [pcrBase] (90 kHz) puts a PCR in the adaptation field, [rai] sets the
     * random-access flag, [pusi] the payload-unit-start flag. The payload is filled with [tag].
     */
    fun media(pid: Int = VIDEO_PID, pcrBase: Long? = null, rai: Boolean = false, pusi: Boolean = false, tag: Int = 0x11): ByteArray {
        if (pcrBase == null && !rai) return blank(pid, pusi, afc = 1, fill = tag)
        val p = blank(pid, pusi, afc = 3, fill = tag)
        var flags = 0
        if (rai) flags = flags or 0x40
        if (pcrBase != null) {
            flags = flags or 0x10
            p[4] = 7
            p[5] = flags.toByte()
            p[6] = ((pcrBase shr 25) and 0xFF).toByte()
            p[7] = ((pcrBase shr 17) and 0xFF).toByte()
            p[8] = ((pcrBase shr 9) and 0xFF).toByte()
            p[9] = ((pcrBase shr 1) and 0xFF).toByte()
            p[10] = ((((pcrBase and 1L) shl 7) or 0x7EL)).toByte()
            p[11] = 0
        } else {
            p[4] = 1
            p[5] = flags.toByte()
        }
        return p
    }

    fun seconds(s: Double): Long = (s * 90_000).toLong()

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) { part.copyInto(out, at); at += part.size }
        return out
    }

    fun pidOf(packet: ByteArray): Int = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)

    fun packetsOf(bytes: ByteArray): List<ByteArray> = (0 until bytes.size / PACKET).map { bytes.copyOfRange(it * PACKET, (it + 1) * PACKET) }
}

internal class TestIoException(message: String) : RuntimeException(message)

internal class FakeSegmentStore : LiveSegmentStore {
    val files = LinkedHashMap<String, ByteArray>()
    val deleted = mutableListOf<String>()
    val playlists = mutableListOf<String>()
    /** "playlist" and "delete:<name>" in the order they happened. */
    val events = mutableListOf<String>()
    var failOpen = false
    var failWrite = false
    var failPlaylist = false

    override fun openPiece(name: String): LiveSegmentWriter {
        if (failOpen) throw TestIoException("disk full on open")
        files[name] = ByteArray(0)
        return object : LiveSegmentWriter {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (failWrite) throw TestIoException("disk full on write")
                files[name] = files.getValue(name) + bytes.copyOfRange(offset, offset + length)
            }
            override fun close() = Unit
        }
    }

    override fun delete(name: String) {
        deleted += name
        events += "delete:$name"
        files.remove(name)
    }

    override fun writePlaylistAtomically(text: String) {
        if (failPlaylist) throw TestIoException("disk full on playlist")
        playlists += text
        events += "playlist"
    }
}
