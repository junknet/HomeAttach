package com.homeattach.app.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's half of the `tsess-mux` wire format.
 *
 * The vectors below were produced by the host's own encoder (`server/tests/muxproto.py`), so these
 * are not self-consistency checks — they are the only thing standing between a protocol tweak on
 * one side and a terminal that silently decodes garbage on the other.
 */
class MuxProtocolTest {

    private fun drain(reader: MuxFrameReader, vararg chunks: ByteArray): List<MuxFrame> {
        val out = mutableListOf<MuxFrame>()
        for (chunk in chunks) reader.feed(chunk, 0, chunk.size) { out += it }
        return out
    }

    // ---------- byte-level agreement with the host encoder ----------

    @Test
    fun `open frame matches the host encoder`() {
        assertArrayEquals(
            byteArrayOf(1, 1, 0, 0, 0, 11, 113, 117, 105, 101, 116, 45, 97, 108, 112, 104, 97),
            MuxProtocol.open(1, "quiet-alpha"),
        )
    }

    @Test
    fun `open frame encodes a non-ascii session name as utf8`() {
        assertArrayEquals(
            byteArrayOf(1, 2, 0, 0, 0, 9, -28, -68, -102, -24, -81, -99, 45, -61, -91),
            MuxProtocol.open(2, "会话-å"),
        )
    }

    @Test
    fun `close frame matches the host encoder`() {
        assertArrayEquals(byteArrayOf(2, 7, 0, 0, 0, 0), MuxProtocol.close(7))
    }

    @Test
    fun `input frame matches the host encoder`() {
        assertArrayEquals(byteArrayOf(3, 3, 0, 0, 0, 1, 3), MuxProtocol.input(3, byteArrayOf(3)))
    }

    @Test
    fun `focus frame matches the host encoder`() {
        assertArrayEquals(byteArrayOf(4, 5, 0, 0, 0, 4, 0, 120, 0, 48), MuxProtocol.focus(5, 120, 48))
    }

    @Test
    fun `length is big endian across the whole header`() {
        // 300 bytes is 0x0000012C: catches a reader that only looks at the low byte, which every
        // frame under 256 bytes would hide.
        val header = MuxProtocol.encode(MuxProtocol.OUTPUT, 255, ByteArray(300) { 0xAB.toByte() })
            .copyOfRange(0, MuxProtocol.HEADER_BYTES)
        assertArrayEquals(byteArrayOf(-126, -1, 0, 0, 1, 44), header)
    }

    @Test
    fun `slot 255 survives the round trip unsigned`() {
        val wire = MuxProtocol.encode(MuxProtocol.OUTPUT, 255, byteArrayOf(1))
        val frame = drain(MuxFrameReader(), wire).single()
        assertEquals(255, frame.sid)
    }

    // ---------- reassembly ----------

    @Test
    fun `reassembles a frame split byte by byte`() {
        val wire = MuxProtocol.encode(MuxProtocol.OUTPUT, 3, "abcdefghij".toByteArray())
        val reader = MuxFrameReader()
        val frames = mutableListOf<MuxFrame>()
        for (i in wire.indices) reader.feed(wire, i, 1) { frames += it }
        assertEquals(1, frames.size)
        assertEquals("abcdefghij", frames.single().text)
    }

    @Test
    fun `splits a chunk holding several frames`() {
        val wire = MuxProtocol.encode(MuxProtocol.OUTPUT, 1, "one".toByteArray()) +
            MuxProtocol.encode(MuxProtocol.OUTPUT, 2, "two".toByteArray()) +
            MuxProtocol.encode(MuxProtocol.ENDED, 1, "bye".toByteArray())
        val frames = drain(MuxFrameReader(), wire)
        assertEquals(listOf(1, 2, 1), frames.map { it.sid })
        assertEquals(listOf("one", "two", "bye"), frames.map { it.text })
    }

    @Test
    fun `holds an incomplete frame instead of yielding it`() {
        val wire = MuxProtocol.encode(MuxProtocol.OUTPUT, 1, "payload".toByteArray())
        val reader = MuxFrameReader()
        assertTrue(drain(reader, wire.copyOfRange(0, wire.size - 1)).isEmpty())
        assertEquals(wire.size - 1, reader.pendingBytes)
        assertEquals("payload", drain(reader, wire.copyOfRange(wire.size - 1, wire.size)).single().text)
    }

    @Test
    fun `holds a frame whose header alone is split`() {
        val wire = MuxProtocol.encode(MuxProtocol.OUTPUT, 1, "x".toByteArray())
        val reader = MuxFrameReader()
        assertTrue(drain(reader, wire.copyOfRange(0, 3)).isEmpty())
        assertEquals("x", drain(reader, wire.copyOfRange(3, wire.size)).single().text)
    }

    @Test
    fun `carries arbitrary binary including nul and high bytes`() {
        val payload = ByteArray(1024) { (it % 256).toByte() }
        val frame = drain(MuxFrameReader(), MuxProtocol.encode(MuxProtocol.INPUT, 9, payload)).single()
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `carries an empty payload`() {
        val frame = drain(MuxFrameReader(), MuxProtocol.close(4)).single()
        assertEquals(MuxProtocol.CLOSE, frame.type)
        assertEquals(0, frame.payload.size)
    }

    @Test
    fun `survives a payload larger than the reader's initial buffer`() {
        // 64KB against a 16KB buffer: proves the growth path, which a terminal repainting a full
        // screen after a reconnect hits immediately.
        val payload = ByteArray(64 * 1024) { 0x5A }
        val frame = drain(MuxFrameReader(), MuxProtocol.encode(MuxProtocol.OUTPUT, 1, payload)).single()
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `keeps decoding across many feeds without the buffer creeping`() {
        val reader = MuxFrameReader()
        var seen = 0
        repeat(500) { i ->
            val wire = MuxProtocol.encode(MuxProtocol.OUTPUT, 1, ByteArray(700) { i.toByte() })
            reader.feed(wire, 0, wire.size) { seen++ }
        }
        assertEquals(500, seen)
        assertEquals(0, reader.pendingBytes)
    }

    @Test
    fun `rejects a length past the cap rather than waiting on it forever`() {
        val poisoned = byteArrayOf(MuxProtocol.OUTPUT.toByte(), 1, 0x7F, -1, -1, -1)
        assertThrows(MuxFramingException::class.java) { drain(MuxFrameReader(), poisoned) }
    }

    @Test
    fun `refuses to encode an out of range slot`() {
        assertThrows(IllegalArgumentException::class.java) {
            MuxProtocol.encode(MuxProtocol.OUTPUT, 256, ByteArray(0))
        }
    }
}
