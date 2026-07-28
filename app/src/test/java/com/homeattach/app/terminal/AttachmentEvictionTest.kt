package com.homeattach.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pool's admission policy. Everything else about an attachment needs a live SSH channel and a
 * main looper to exercise, but which sessions get dropped is pure bookkeeping — and it is the part
 * that decides whether switching costs a handshake, so it gets a real test.
 */
class AttachmentEvictionTest {
    @Test
    fun `keeps everything while the pool has room`() {
        assertEquals(
            emptyList<String>(),
            sessionsToEvict(listOf("a", "b"), incoming = "c", onScreen = "b", maxSize = 5),
        )
    }

    @Test
    fun `evicts nothing when the incoming session is already pooled`() {
        // A hit must not cost a release: the caller is asking to reuse this very attachment.
        assertEquals(
            emptyList<String>(),
            sessionsToEvict(listOf("a", "b", "c", "d", "e"), incoming = "a", onScreen = "e", maxSize = 5),
        )
    }

    @Test
    fun `evicts the least recently used to make room for one more`() {
        assertEquals(
            listOf("a"),
            sessionsToEvict(listOf("a", "b", "c", "d", "e"), incoming = "f", onScreen = "e", maxSize = 5),
        )
    }

    @Test
    fun `never evicts the session on screen even when it is the oldest`() {
        assertEquals(
            listOf("b"),
            sessionsToEvict(listOf("a", "b", "c", "d", "e"), incoming = "f", onScreen = "a", maxSize = 5),
        )
    }

    @Test
    fun `evicts enough to absorb a pool that is already over the cap`() {
        assertEquals(
            listOf("a", "b", "c"),
            sessionsToEvict(listOf("a", "b", "c", "d", "e"), incoming = "f", onScreen = "e", maxSize = 3),
        )
    }

    @Test
    fun `evicts nothing but the protected entries when they are all that is left`() {
        // Overflow of 1 with only the on-screen session evictable: the pool is allowed to run one
        // over its cap rather than blank a live terminal.
        assertEquals(
            emptyList<String>(),
            sessionsToEvict(listOf("a"), incoming = "b", onScreen = "a", maxSize = 1),
        )
    }

    @Test
    fun `tolerates an empty pool`() {
        assertEquals(
            emptyList<String>(),
            sessionsToEvict(emptyList(), incoming = "a", onScreen = null, maxSize = 5),
        )
    }
}
