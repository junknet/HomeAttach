package com.homeattach.app.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            byteArrayOf(
                1, 1, 0, 0, 0, 35,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                113, 117, 105, 101, 116, 45, 97, 108, 112, 104, 97,
            ),
            MuxProtocol.open(1, "quiet-alpha"),
        )
    }

    @Test
    fun `open frame encodes a non-ascii session name as utf8`() {
        assertArrayEquals(
            byteArrayOf(
                1, 2, 0, 0, 0, 33,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                -28, -68, -102, -24, -81, -99, 45, -61, -91,
            ),
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

    // ---------- the control slot ----------

    // ---------- resuming a session the phone already holds ----------

    @Test
    fun `open declares the cursor and the grid ahead of the name`() {
        val wire = MuxProtocol.open(
            3, "alpha", epoch = 9, offset = 4096, tailRows = 200, columns = 60, rows = 50,
        )
        val payload = drain(MuxFrameReader(), wire).single().payload

        assertArrayEquals(
            // epoch 9, offset 4096 (0x1000), tail 200 (0xC8), 60x50 - big-endian throughout
            byteArrayOf(
                0, 0, 0, 0, 0, 0, 0, 9,
                0, 0, 0, 0, 0, 0, 0x10, 0,
                0, 0, 0, 0xC8.toByte(),
                0, 60, 0, 50,
            ),
            payload.copyOfRange(0, MuxProtocol.OPEN_HEADER_BYTES),
        )
        assertEquals("alpha", String(payload, MuxProtocol.OPEN_HEADER_BYTES, 5))
    }

    @Test
    fun `a session that is not on screen declares no grid`() {
        // A size in this frame is a claim. From a backgrounded session that would resize the
        // terminal the user is actually looking at.
        val payload = drain(MuxFrameReader(), MuxProtocol.open(1, "alpha")).single().payload
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), payload.copyOfRange(20, 24))
    }

    @Test
    fun `an open with nothing held still carries a zeroed header`() {
        // The host reads a fixed-size prefix; a bare name would be parsed as an epoch.
        val payload = drain(MuxFrameReader(), MuxProtocol.open(1, "alpha")).single().payload
        assertEquals(MuxProtocol.OPEN_HEADER_BYTES + 5, payload.size)
        assertTrue(payload.copyOfRange(0, MuxProtocol.OPEN_HEADER_BYTES).all { it == 0.toByte() })
    }

    @Test
    fun `ready says whether the output that follows continues the screen`() {
        val payload = byteArrayOf(MuxProtocol.RESUME_CONTINUED.toByte()) +
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 9) +
            byteArrayOf(0, 0, 0, 0, 0, 0, 0x10, 0) +
            byteArrayOf(0, 0, 0, 0, 0, 0, 2, 0) +
            "alpha".toByteArray()
        val ready = MuxProtocol.readReady(payload)!!

        assertTrue(ready.continued)
        assertEquals(9L, ready.epoch)
        assertEquals(4096L, ready.offset)
        // The bytes about to arrive that the offset already counts. Counting them again is what
        // puts the cursor past the stream, permanently unresumable.
        assertEquals(512L, ready.replayBytes)
        assertEquals("alpha", ready.sessionName)
    }

    @Test
    fun `a snapshot ready is not a continuation`() {
        val payload = ByteArray(MuxProtocol.READY_HEADER_BYTES) + "alpha".toByteArray()
        val ready = MuxProtocol.readReady(payload)!!

        assertFalse(ready.continued)
        assertEquals(0L, ready.epoch)
        assertEquals("alpha", ready.sessionName)
    }

    @Test
    fun `a truncated ready is refused rather than half read`() {
        assertNull(MuxProtocol.readReady(ByteArray(MuxProtocol.READY_HEADER_BYTES - 1)))
    }

    @Test
    fun `a cursor past two gigabytes survives the round trip`() {
        // Offsets are byte counts on a stream that runs for days; they outgrow Int quickly.
        val far = 9_000_000_000L
        val payload = drain(MuxFrameReader(), MuxProtocol.open(1, "a", epoch = far, offset = far))
            .single().payload
        val ready = MuxProtocol.readReady(
            byteArrayOf(MuxProtocol.RESUME_CONTINUED.toByte()) +
                payload.copyOfRange(0, 16) + ByteArray(8) + "a".toByteArray()
        )!!
        assertEquals(far, ready.epoch)
        assertEquals(far, ready.offset)
    }

    @Test
    fun `control frame type codes match the host's`() {
        // Byte-for-byte with server/tests/muxproto.py. A drifted code here does not fail loudly;
        // it makes the phone quietly ignore the host's session list.
        assertEquals(0x85, MuxProtocol.SESSIONS)
        assertEquals(0x86, MuxProtocol.ACTIVITY)
        assertEquals(0, MuxProtocol.CONNECTION_SLOT)
    }

    @Test
    fun `a session list arrives on slot zero as tsv`() {
        val tsv = "s1\tbash\t~/a\tnone\t80\t24\tdetached\t10\t2\n"
        val wire = MuxProtocol.encode(MuxProtocol.SESSIONS, 0, tsv.toByteArray())
        val frame = drain(MuxFrameReader(), wire).single()

        assertEquals(MuxProtocol.SESSIONS, frame.type)
        assertEquals(MuxProtocol.CONNECTION_SLOT, frame.sid)
        assertEquals(listOf("s1"), parseSessionList(frame.text).map { it.name })
    }

    @Test
    fun `an activity frame is a newline separated name list`() {
        val wire = MuxProtocol.encode(MuxProtocol.ACTIVITY, 0, "alpha\nbeta\n".toByteArray())
        val frame = drain(MuxFrameReader(), wire).single()

        assertEquals(
            listOf("alpha", "beta"),
            frame.text.lineSequence().filter { it.isNotBlank() }.toList(),
        )
    }

    @Test
    fun `refuses to encode an out of range slot`() {
        assertThrows(IllegalArgumentException::class.java) {
            MuxProtocol.encode(MuxProtocol.OUTPUT, 256, ByteArray(0))
        }
    }
}
