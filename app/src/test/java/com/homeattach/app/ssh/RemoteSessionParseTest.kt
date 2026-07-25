package com.homeattach.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteSessionParseTest {

    @Test
    fun `parses the server PTY output sequence when available`() {
        val session = parseSessionLine("s1\tbtop\t~/work\tpc\t120\t40\tfocused\t12\t37")

        assertEquals(37L, session?.outputSequence)
    }

    @Test
    fun `older servers default missing output sequence to zero`() {
        val session = parseSessionLine("s1\tbash\t~/work\tnone\t80\t24\tdetached\t12")

        assertEquals(0L, session?.outputSequence)
    }
}
