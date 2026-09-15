package com.homeattach.app.ssh

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MuxHistoryProtocolTest {
    private fun pagePayload(): JSONObject = JSONObject(
        """{"status":"ok","anchor":"18446744073709551615","before":7,"next":9,
            "columns":80,"more":true,"rows":[{"text":"\u001b[31m中文\u001b[0m","wrapped":true},
            {"text":"","wrapped":false}]}""",
    )

    @Test
    fun `history request matches host wire encoding`() {
        val content = """{"anchor":"18446744073709551615","before":7,"limit":128}""".toByteArray()
        assertArrayEquals(
            byteArrayOf(5, 3, 0, 0, 0, content.size.toByte()) + content,
            MuxProtocol.history(3, "18446744073709551615", 7),
        )
        assertEquals(0x87, MuxProtocol.HISTORY_PAGE)
    }

    @Test
    fun `styled rows retain blank physical lines and wrapping`() {
        val result = MuxProtocol.readHistoryPage(pagePayload().toString().toByteArray())!!
        assertEquals("18446744073709551615", result.anchor)
        assertEquals(7L, result.before)
        assertEquals(9L, result.next)
        assertEquals(listOf(
            MuxHistoryRow("\u001b[31m中文\u001b[0m", true), MuxHistoryRow("", false),
        ), result.rows)
    }

    @Test
    fun `initial metadata carries availability without consuming history`() {
        val content = pagePayload().put("before", 0).put("next", 0).put("rows", JSONArray())
        assertNotNull(MuxProtocol.readHistoryPage(content.toString().toByteArray()))
    }

    @Test
    fun `unsupported and expired require empty complete pages`() {
        for (status in listOf("unsupported", "expired")) {
            val content = pagePayload().put("status", status).put("next", 7)
                .put("rows", JSONArray()).put("more", false).put("columns", 0)
            assertEquals(status, MuxProtocol.readHistoryPage(content.toString().toByteArray())!!.status)
            content.put("more", true)
            assertNull(MuxProtocol.readHistoryPage(content.toString().toByteArray()))
        }
    }

    @Test
    fun `history fields reject coercion overflow and invalid cursors`() {
        val invalidFields = listOf(
            "anchor" to 7, "anchor" to "18446744073709551616", "anchor" to "01",
            "before" to -1, "before" to "7", "before" to true, "before" to 7.5,
            "next" to 8, "next" to "9", "columns" to 0, "columns" to 65536,
            "more" to "true", "status" to "unknown", "rows" to "[]",
        )
        for ((field, value) in invalidFields) {
            val content = pagePayload().put(field, value).toString().toByteArray()
            assertNull("accepted $field=$value", MuxProtocol.readHistoryPage(content))
        }
        assertNull(MuxProtocol.readHistoryPage(
            pagePayload().put("before", Long.MAX_VALUE).put("next", 1).toString().toByteArray(),
        ))
    }

    @Test
    fun `history parser rejects malformed rows and trailing payload`() {
        for (invalid in listOf(
            JSONObject().put("text", "two\nlines").put("wrapped", false),
            JSONObject().put("text", "carriage\rreturn").put("wrapped", false),
            JSONObject().put("text", "valid").put("wrapped", "false"),
            JSONObject().put("text", 123).put("wrapped", false),
        )) {
            val content = pagePayload().put("rows", JSONArray().put(invalid)).put("next", 8)
            assertNull(MuxProtocol.readHistoryPage(content.toString().toByteArray()))
        }
        assertNull(MuxProtocol.readHistoryPage((pagePayload().toString() + "{}").toByteArray()))
        assertNull(MuxProtocol.readHistoryPage(byteArrayOf(0xC3.toByte(), 0x28)))
        assertNull(MuxProtocol.readHistoryPage(ByteArray(MuxProtocol.HISTORY_PAYLOAD_LIMIT + 1)))
    }

    @Test
    fun `history requests reject invalid cursor and oversized pages`() {
        assertThrows(IllegalArgumentException::class.java) { MuxProtocol.history(1, "-1", 0) }
        assertThrows(IllegalArgumentException::class.java) { MuxProtocol.history(1, "1", -1) }
        assertThrows(IllegalArgumentException::class.java) { MuxProtocol.history(1, "1", 0, 257) }
    }
}
