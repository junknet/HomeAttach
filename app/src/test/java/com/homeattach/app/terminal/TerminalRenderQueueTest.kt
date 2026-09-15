package com.homeattach.app.terminal

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class TerminalRenderQueueTest {
    @Test
    fun largeFramesRetainEveryByteAcrossBoundedPasses() {
        val original = "\u001b[31m中文🙂\u001b[0m\r\n".repeat(4096).toByteArray()
        val queue = TerminalRenderQueue(original.size)
        assertTrue(queue.append(original, 0, original.size))
        val collected = ByteArrayOutputStream()
        while (queue.hasPending()) {
            val operation = queue.take(2048) as TerminalRenderOperation.Output
            assertTrue(operation.bytes.size <= 2048)
            collected.write(operation.bytes)
        }
        assertArrayEquals(original, collected.toByteArray())
    }

    @Test
    fun replacementSnapshotDiscardsStaleOutputAndMetadata() {
        val queue = TerminalRenderQueue()
        queue.append(byteArrayOf(1, 2), 0, 2)
        queue.enqueueControl { fail("Stale metadata executed") }
        queue.beginSnapshot(3)
        var metadataSeen = false
        queue.enqueueControl { metadataSeen = true }
        queue.append(byteArrayOf(3, 4, 5, 6), 0, 4)
        assertEquals(TerminalRenderOperation.Snapshot(3), queue.take(2048))
        (queue.take(2048) as TerminalRenderOperation.Control).action()
        assertTrue(metadataSeen)
        assertArrayEquals(byteArrayOf(3, 4, 5), (queue.take(3) as TerminalRenderOperation.Output).bytes)
        assertArrayEquals(byteArrayOf(6), (queue.take(2048) as TerminalRenderOperation.Output).bytes)
        assertFalse(queue.hasPending())
    }

    @Test
    fun fullQueueRejectsWholeIncomingFrameWithoutPartialWrites() {
        val queue = TerminalRenderQueue(4)
        val original = byteArrayOf(1, 2, 3)
        assertTrue(queue.append(original, 0, 3))
        original.fill(0)
        assertFalse(queue.append(byteArrayOf(4, 5), 0, 2))
        assertArrayEquals(byteArrayOf(1, 2, 3), (queue.take(10) as TerminalRenderOperation.Output).bytes)
        assertFalse(queue.hasPending())
        assertTrue(queue.append(byteArrayOf(6), 0, 1))
    }
}
