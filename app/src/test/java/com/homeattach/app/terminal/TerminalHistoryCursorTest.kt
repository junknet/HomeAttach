package com.homeattach.app.terminal

import com.homeattach.app.ssh.MuxHistoryPage
import com.homeattach.app.ssh.MuxHistoryRow
import org.junit.Assert.*
import org.junit.Test

class TerminalHistoryCursorTest {
    private fun metadata(anchor: String = "123") =
        MuxHistoryPage("ok", anchor, 0, 0, 80, true, emptyList())

    private fun content(anchor: String = "123", before: Long = 0, more: Boolean = true) =
        MuxHistoryPage("ok", anchor, before, before + 2, 80, more,
            listOf(MuxHistoryRow("first", false), MuxHistoryRow("second", false)))

    @Test
    fun prefetchRequiresReadingNearOldestHistoryAndOnlyOneRequest() {
        val cursor = TerminalHistoryCursor()
        cursor.metadata(metadata())
        assertNull(cursor.request(0, 200, 24))
        assertNull(cursor.request(-50, 200, 24))
        assertEquals(TerminalHistoryRequest("123", 0, 128), cursor.request(-160, 200, 24))
        assertNull(cursor.request(-200, 200, 24))
        cursor.completed(content(), 2)
        assertEquals(2L, cursor.before)
        assertEquals(2L, cursor.request(-200, 202, 24)?.before)
    }

    @Test
    fun duplicateAndOldSessionResponsesCannotAdvanceCursor() {
        val cursor = TerminalHistoryCursor()
        cursor.metadata(metadata())
        cursor.request(-200, 200, 24)
        val page = content()
        cursor.completed(page, 2)
        assertFalse(cursor.accepts(page))
        cursor.metadata(metadata())
        assertEquals(2L, cursor.before)
        cursor.request(-200, 202, 24)
        assertFalse(cursor.accepts(page))
        cursor.reset()
        cursor.metadata(metadata("456"))
        cursor.request(-200, 200, 24)
        assertFalse(cursor.accepts(content(before = 2)))
    }

    @Test
    fun reconnectMetadataKeepsAnchorAndRetryInvalidatesPreviousWork() {
        val cursor = TerminalHistoryCursor()
        cursor.metadata(metadata())
        cursor.request(-200, 200, 24)
        val previousGeneration = cursor.generation
        cursor.failed()
        cursor.metadata(metadata("0"))
        cursor.request(-200, 200, 24)
        assertEquals("123", cursor.anchor)
        assertNotEquals(previousGeneration, cursor.generation)
        assertTrue(cursor.accepts(content()))
    }

    @Test
    fun exhaustedOrExpiredHistoryStopsRequesting() {
        val cursor = TerminalHistoryCursor()
        cursor.metadata(metadata())
        cursor.request(-200, 200, 24)
        cursor.completed(content(more = false), 2)
        assertNull(cursor.request(-202, 202, 24))
        cursor.reset()
        cursor.metadata(metadata())
        cursor.request(-200, 200, 24)
        cursor.completed(content(), 0)
        assertNull(cursor.request(-200, 200, 24))
    }
}
