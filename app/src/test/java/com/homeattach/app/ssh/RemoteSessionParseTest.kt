package com.homeattach.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteSessionParseTest {

    @Test
    fun `parses every column the host reports`() {
        val session = parseSessionLine("s1\tbtop\t~/work\tpc\t120\t40\tfocused\t12\t37")

        assertEquals("s1", session?.name)
        assertEquals("btop", session?.command)
        assertEquals("~/work", session?.cwd)
        assertEquals("pc", session?.owner)
        assertEquals(120, session?.cols)
        assertEquals(40, session?.rows)
        assertEquals("focused", session?.status)
        assertEquals(12L, session?.bornEpochSeconds)
    }

    @Test
    fun `older servers reporting fewer columns still parse`() {
        val session = parseSessionLine("s1\tbash\t~/work")

        assertEquals("s1", session?.name)
        assertEquals("", session?.owner)
        assertNull(session?.cols)
        assertEquals(0L, session?.bornEpochSeconds)
    }

    @Test
    fun `a line that names no session is not one`() {
        assertNull(parseSessionLine(""))
        assertNull(parseSessionLine("s1\tbash"))
    }

    @Test
    fun `a SESSIONS frame is parsed as a whole block, in display order`() {
        val tsv = buildString {
            appendLine("s2\tvim\t~/b\tpc\t80\t24\tfocused\t20\t3")
            appendLine("s1\tbash\t~/a\tnone\t80\t24\tdetached\t10\t9")
            // Blank lines are how a trailing newline arrives; they are not sessions.
            appendLine("")
        }

        assertEquals(listOf("s1", "s2"), parseSessionList(tsv).map { it.name })
    }

    @Test
    fun `an empty SESSIONS frame means the host has no sessions`() {
        assertEquals(emptyList<RemoteSession>(), parseSessionList(""))
    }
}
