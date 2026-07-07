package com.homeattach.app.terminal

import jackpal.androidterm.emulatorview.TermSession
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class RemoteTerminalSession(
    private val onInput: (ByteArray) -> Unit,
    private val onResize: (columns: Int, rows: Int) -> Unit,
) : TermSession(true) {
    private val remoteInput = BlockingRemoteInputStream()
    var currentColumns = 0
        private set
    var currentRows = 0
        private set

    init {
        setDefaultUTF8Mode(true)
        setTermIn(remoteInput)
        setTermOut(object : OutputStream() {
            override fun write(value: Int) {
                onInput(byteArrayOf(value.toByte()))
            }

            override fun write(buffer: ByteArray, offset: Int, count: Int) {
                if (count <= 0) return
                onInput(buffer.copyOfRange(offset, offset + count))
            }
        })
    }

    fun appendRemoteOutput(buffer: ByteArray, offset: Int = 0, count: Int = buffer.size) {
        remoteInput.append(buffer, offset, count)
    }

    override fun updateSize(columns: Int, rows: Int) {
        val changed = columns != currentColumns || rows != currentRows
        super.updateSize(columns, rows)
        if (changed) {
            currentColumns = columns
            currentRows = rows
            onResize(columns, rows)
        }
    }

    override fun finish() {
        remoteInput.close()
        runCatching { super.finish() }
    }
}

private class BlockingRemoteInputStream : InputStream() {
    private val lock = Object()
    private val chunks = ArrayDeque<ByteArray>()
    private var currentChunk = ByteArray(0)
    private var currentOffset = 0
    private var closed = false

    fun append(buffer: ByteArray, offset: Int, count: Int) {
        if (count <= 0) return
        val chunk = buffer.copyOfRange(offset, offset + count)
        synchronized(lock) {
            if (closed) return
            chunks.addLast(chunk)
            lock.notifyAll()
        }
    }

    override fun read(): Int {
        val singleByte = ByteArray(1)
        val read = read(singleByte, 0, 1)
        return if (read < 0) -1 else singleByte[0].toInt() and BYTE_MASK
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        synchronized(lock) {
            while (!closed && !hasReadableBytes()) {
                try {
                    lock.wait()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for terminal input", e)
                }
            }
            if (closed && !hasReadableBytes()) return -1
            if (currentOffset >= currentChunk.size) {
                currentChunk = chunks.removeFirst()
                currentOffset = 0
            }
            val count = minOf(length, currentChunk.size - currentOffset)
            System.arraycopy(currentChunk, currentOffset, buffer, offset, count)
            currentOffset += count
            return count
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            chunks.clear()
            currentChunk = ByteArray(0)
            currentOffset = 0
            lock.notifyAll()
        }
    }

    private fun hasReadableBytes(): Boolean =
        currentOffset < currentChunk.size || chunks.isNotEmpty()

    private companion object {
        const val BYTE_MASK = 0xff
    }
}
