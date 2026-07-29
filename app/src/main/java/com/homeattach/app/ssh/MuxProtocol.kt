package com.homeattach.app.ssh

/**
 * Wire format of the `tsess-mux` channel.
 *
 * The authoritative definition lives in `server/tests/muxproto.py`, which the host-side suite
 * speaks; this is the same format expressed for the phone. Any change has to land in both, and the
 * byte-level agreement is what [MuxProtocolTest] pins down.
 *
 *     | type:1 | sid:1 | length:4 big-endian | payload |
 *
 * One SSH channel carries every attached session, so framing is ours to do. Slot 0 is reserved for
 * frames that belong to the connection rather than to a session, which gives an ERROR with no
 * session context somewhere to go.
 *
 * OPEN declares what this phone already holds of a session - which daemon its bytes came from and
 * how many of them it has - and READY answers with what the host did about it. [RESUME_CONTINUED]
 * means the OUTPUT frames that follow carry on from exactly that point; [RESUME_SNAPSHOT] means
 * they replace the screen. The cursor to save is the offset in READY plus every OUTPUT byte
 * received after it, which is why an OUTPUT frame may carry nothing but session output.
 */
internal object MuxProtocol {
    // phone -> host
    const val OPEN = 0x01
    const val CLOSE = 0x02
    const val INPUT = 0x03
    const val FOCUS = 0x04

    // host -> phone
    const val READY = 0x81
    const val OUTPUT = 0x82
    const val ENDED = 0x83
    const val ERROR = 0x84

    // host -> phone, on the connection slot: the host's own state rather than any one session's.
    // The session list and the activity ticks ride here instead of on a channel of their own,
    // which is what makes every lamp read from one clock.
    const val SESSIONS = 0x85
    const val ACTIVITY = 0x86

    const val CONNECTION_SLOT = 0
    const val HEADER_BYTES = 6

    /** `epoch:8 | offset:8 | tailRows:4` ahead of the name in OPEN. */
    const val OPEN_HEADER_BYTES = 20

    /** `mode:1 | epoch:8 | offset:8 | replayBytes:8` ahead of the name in READY. */
    const val READY_HEADER_BYTES = 25

    /** The host started this session's picture over; anything held for it is stale. */
    const val RESUME_SNAPSHOT = 0

    /** The host sent exactly what was missed; the screen already on the phone stands. */
    const val RESUME_CONTINUED = 1

    /**
     * A cap is part of the protocol, not a local guard: without it a corrupted length header makes
     * the reader wait for bytes that are never coming, and the terminal simply stops.
     */
    const val MAX_PAYLOAD = 1 shl 20

    fun encode(type: Int, sid: Int, payload: ByteArray = EMPTY): ByteArray {
        require(sid in 0..255) { "slot out of range: $sid" }
        require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
        val frame = ByteArray(HEADER_BYTES + payload.size)
        frame[0] = type.toByte()
        frame[1] = sid.toByte()
        frame[2] = (payload.size ushr 24).toByte()
        frame[3] = (payload.size ushr 16).toByte()
        frame[4] = (payload.size ushr 8).toByte()
        frame[5] = payload.size.toByte()
        payload.copyInto(frame, HEADER_BYTES)
        return frame
    }

    /**
     * [epoch]/[offset] are what this phone already holds of the session; 0/0 means nothing.
     * [tailRows] caps the scrollback a snapshot may carry when the host cannot continue.
     */
    fun open(
        sid: Int,
        sessionName: String,
        epoch: Long = 0,
        offset: Long = 0,
        tailRows: Int = 0,
    ): ByteArray {
        val name = sessionName.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(OPEN_HEADER_BYTES + name.size)
        writeLong(payload, 0, epoch)
        writeLong(payload, 8, offset)
        writeInt(payload, 16, tailRows)
        name.copyInto(payload, OPEN_HEADER_BYTES)
        return encode(OPEN, sid, payload)
    }

    private fun writeLong(target: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) target[at + i] = (value ushr (56 - 8 * i)).toByte()
    }

    private fun writeInt(target: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) target[at + i] = (value ushr (24 - 8 * i)).toByte()
    }

    private fun readLong(source: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (source[at + i].toLong() and 0xff)
        return value
    }

    /** The host's answer to an OPEN, or null when the frame is too short to be one. */
    fun readReady(payload: ByteArray): MuxReady? {
        if (payload.size < READY_HEADER_BYTES) return null
        return MuxReady(
            continued = payload[0].toInt() == RESUME_CONTINUED,
            epoch = readLong(payload, 1),
            offset = readLong(payload, 9),
            replayBytes = readLong(payload, 17),
            sessionName = String(payload, READY_HEADER_BYTES, payload.size - READY_HEADER_BYTES,
                Charsets.UTF_8),
        )
    }

    fun close(sid: Int): ByteArray = encode(CLOSE, sid)

    fun input(sid: Int, data: ByteArray): ByteArray = encode(INPUT, sid, data)

    fun focus(sid: Int, columns: Int, rows: Int): ByteArray {
        val payload = ByteArray(4)
        payload[0] = (columns ushr 8).toByte()
        payload[1] = columns.toByte()
        payload[2] = (rows ushr 8).toByte()
        payload[3] = rows.toByte()
        return encode(FOCUS, sid, payload)
    }

    private val EMPTY = ByteArray(0)
}

/**
 * What the host did with an OPEN: whether the output that follows continues this phone's screen or
 * replaces it, and the cursor to count from.
 */
internal data class MuxReady(
    val continued: Boolean,
    val epoch: Long,
    val offset: Long,
    /**
     * How many of the bytes about to arrive are replay that [offset] already counts.
     *
     * They must not advance the cursor. On a snapshot they are a picture the host synthesized,
     * which was never in the stream; on a continuation they are bytes [offset] is already past.
     * Counting them puts the cursor beyond the stream, and a cursor beyond the stream can never be
     * resumed from again - the session quietly reloads in full, forever.
     */
    val replayBytes: Long,
    val sessionName: String,
)

/** One decoded frame. [payload] is owned by the receiver and is never reused by the reader. */
internal class MuxFrame(val type: Int, val sid: Int, val payload: ByteArray) {
    val text: String get() = String(payload, Charsets.UTF_8)

    override fun toString(): String {
        val kind = when (type) {
            MuxProtocol.OPEN -> "OPEN"
            MuxProtocol.CLOSE -> "CLOSE"
            MuxProtocol.INPUT -> "INPUT"
            MuxProtocol.FOCUS -> "FOCUS"
            MuxProtocol.READY -> "READY"
            MuxProtocol.OUTPUT -> "OUTPUT"
            MuxProtocol.ENDED -> "ENDED"
            MuxProtocol.ERROR -> "ERROR"
            MuxProtocol.SESSIONS -> "SESSIONS"
            MuxProtocol.ACTIVITY -> "ACTIVITY"
            else -> "0x%02x".format(type)
        }
        return "MuxFrame($kind, sid=$sid, ${payload.size}B)"
    }
}

/** Raised when the stream can no longer be trusted to be on a frame boundary. */
internal class MuxFramingException(message: String) : Exception(message)

/**
 * Reassembles frames from a byte stream that splits wherever the transport feels like it.
 *
 * Not thread-safe by design: it is fed only by the single reader thread that owns the channel.
 * Frames are handed to [onFrame] as they complete rather than returned in a list, because terminal
 * output arrives continuously and collecting it into throwaway lists would allocate on every read.
 */
internal class MuxFrameReader {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var readPos = 0
    private var writePos = 0

    val pendingBytes: Int get() = writePos - readPos

    @Throws(MuxFramingException::class)
    fun feed(chunk: ByteArray, offset: Int, count: Int, onFrame: (MuxFrame) -> Unit) {
        append(chunk, offset, count)
        while (true) {
            val available = writePos - readPos
            if (available < MuxProtocol.HEADER_BYTES) break
            val type = buffer[readPos].toInt() and 0xff
            val sid = buffer[readPos + 1].toInt() and 0xff
            val length =
                ((buffer[readPos + 2].toInt() and 0xff) shl 24) or
                    ((buffer[readPos + 3].toInt() and 0xff) shl 16) or
                    ((buffer[readPos + 4].toInt() and 0xff) shl 8) or
                    (buffer[readPos + 5].toInt() and 0xff)
            if (length < 0 || length > MuxProtocol.MAX_PAYLOAD) {
                throw MuxFramingException("framing lost: length $length over cap")
            }
            if (available < MuxProtocol.HEADER_BYTES + length) break
            val start = readPos + MuxProtocol.HEADER_BYTES
            val payload = buffer.copyOfRange(start, start + length)
            readPos = start + length
            onFrame(MuxFrame(type, sid, payload))
        }
        compact()
    }

    private fun append(chunk: ByteArray, offset: Int, count: Int) {
        ensureCapacity(count)
        chunk.copyInto(buffer, writePos, offset, offset + count)
        writePos += count
    }

    private fun ensureCapacity(incoming: Int) {
        if (writePos + incoming <= buffer.size) return
        compact()
        if (writePos + incoming <= buffer.size) return
        var capacity = buffer.size
        while (capacity < writePos + incoming) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }

    /** Slides a partial frame back to the front so the buffer does not creep forever. */
    private fun compact() {
        if (readPos == 0) return
        val remaining = writePos - readPos
        if (remaining > 0) buffer.copyInto(buffer, 0, readPos, writePos)
        readPos = 0
        writePos = remaining
    }

    private companion object {
        const val INITIAL_CAPACITY = 16 * 1024
    }
}
