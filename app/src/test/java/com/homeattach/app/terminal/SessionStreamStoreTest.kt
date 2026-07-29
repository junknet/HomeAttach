package com.homeattach.app.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The saved tail is what the phone paints before the host says anything, and the cursor it saves is
 * what the host is asked to continue from. A mistake here is not a lost optimisation - it is a
 * terminal showing content that never existed, or missing content that did.
 */
class SessionStreamStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(dir: File = folder.root) = SessionStreamStore(dir)

    @Test
    fun `a session never seen has nothing to continue from`() {
        assertNull(store().load("alpha"))
    }

    @Test
    fun `what was saved comes back with the cursor it ended at`() {
        val subject = store()
        subject.append("alpha", epoch = 7, offset = 5, data = "hello".toByteArray())
        subject.close()

        val saved = store().load("alpha")!!
        assertEquals(7L, saved.epoch)
        assertEquals(5L, saved.offset)
        assertArrayEquals("hello".toByteArray(), saved.bytes)
    }

    @Test
    fun `appends accumulate into one stream`() {
        val subject = store()
        subject.append("alpha", 7, 3, "abc".toByteArray())
        subject.append("alpha", 7, 6, "def".toByteArray())
        subject.close()

        assertArrayEquals("abcdef".toByteArray(), store().load("alpha")!!.bytes)
    }

    @Test
    fun `sessions do not share a stream`() {
        val subject = store()
        subject.append("alpha", 7, 1, "a".toByteArray())
        subject.append("beta", 7, 1, "b".toByteArray())
        subject.close()

        assertArrayEquals("a".toByteArray(), store().load("alpha")!!.bytes)
        assertArrayEquals("b".toByteArray(), store().load("beta")!!.bytes)
    }

    @Test
    fun `a session name is never read as a path`() {
        val subject = store()
        subject.append("../../escape", 7, 1, "x".toByteArray())
        subject.close()

        assertTrue(
            "store wrote outside its directory",
            folder.root.listFiles()!!.all { it.isFile && !it.name.contains("..") },
        )
        assertArrayEquals("x".toByteArray(), store().load("../../escape")!!.bytes)
    }

    @Test
    fun `the stream is capped and keeps the newest bytes`() {
        val subject = store()
        // Well past the cap: what survives has to be the end of the stream, because that is the
        // part that reconstructs the screen the user was looking at.
        val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
        repeat(12) { subject.append("alpha", 7, 0, chunk) }
        val tail = "THE-END".toByteArray()
        subject.append("alpha", 7, 12L * chunk.size + tail.size, tail)
        subject.close()

        val saved = store().load("alpha")!!
        assertTrue("stream grew without bound: ${saved.bytes.size}", saved.bytes.size <= 700 * 1024)
        assertArrayEquals(tail, saved.bytes.copyOfRange(saved.bytes.size - tail.size, saved.bytes.size))
    }

    @Test
    fun `a restart drops what was held and starts the cursor over`() {
        val subject = store()
        subject.append("alpha", 7, 5, "stale".toByteArray())
        subject.reset("alpha", epoch = 9, offset = 100)
        subject.close()

        // Nothing to replay, but the cursor and epoch the host just gave us are worth keeping:
        // the next open continues from there instead of asking for the picture again.
        assertNull(store().load("alpha"))
        store().append("alpha", 9, 103, "new".toByteArray())
        val saved = store().load("alpha")!!
        assertArrayEquals("new".toByteArray(), saved.bytes)
        assertEquals(103L, saved.offset)
    }

    @Test
    fun `bytes past the saved cursor are not replayed`() {
        // A process killed between writing bytes and writing the cursor leaves the file ahead of
        // the meta. Replaying the excess would duplicate exactly what the host is about to resend.
        val subject = store()
        subject.append("alpha", 7, 3, "abc".toByteArray())
        subject.close()
        val data = folder.root.listFiles()!!.first { it.name.endsWith(".bin") }
        data.appendBytes("UNACCOUNTED".toByteArray())

        assertArrayEquals("abc".toByteArray(), store().load("alpha")!!.bytes)
    }

    @Test
    fun `a stream saved without an epoch is not resumable`() {
        val subject = store()
        subject.append("alpha", epoch = 0, offset = 3, data = "abc".toByteArray())
        subject.close()

        assertNull(store().load("alpha"))
    }
}
