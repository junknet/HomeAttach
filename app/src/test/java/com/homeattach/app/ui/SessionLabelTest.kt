package com.homeattach.app.ui

import com.homeattach.app.ssh.RemoteSession
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Labels exist to be told apart. These cases are taken from a real host's list, where three
 * separate collisions were live at once.
 */
class SessionLabelTest {

    private fun session(name: String, cwd: String) = RemoteSession(name = name, command = "zsh", cwd = cwd)

    @Test
    fun `distinct directories keep their short names`() {
        val labels = listOf(
            session("sa1", "~/Desktop/test"),
            session("sb2", "~/linege/gpu-ops"),
        ).displayLabels()

        assertEquals("test", labels["sa1"])
        assertEquals("gpu-ops", labels["sb2"])
    }

    @Test
    fun `same leaf in different parents widens along the path`() {
        val labels = listOf(
            session("sa1", "~/alpha/build"),
            session("sb2", "~/beta/build"),
        ).displayLabels()

        assertEquals("alpha/build", labels["sa1"])
        assertEquals("beta/build", labels["sb2"])
    }

    @Test
    fun `only the colliding sessions widen, the rest stay short`() {
        val labels = listOf(
            session("sa1", "~/alpha/build"),
            session("sb2", "~/beta/build"),
            session("sc3", "~/Desktop/tmp"),
        ).displayLabels()

        assertEquals("alpha/build", labels["sa1"])
        assertEquals("beta/build", labels["sb2"])
        assertEquals("tmp", labels["sc3"])
    }

    @Test
    fun `identical directories fall back to the session id`() {
        val labels = listOf(
            session("s4c41616bd", "~/linege/gpu-ops"),
            session("s4c759af61", "~/linege/gpu-ops"),
        ).displayLabels()

        assertEquals("gpu-ops·16bd", labels["s4c41616bd"])
        assertEquals("gpu-ops·af61", labels["s4c759af61"])
    }

    @Test
    fun `a session with no resolvable cwd falls back to its name`() {
        val labels = listOf(session("sa1", "?")).displayLabels()

        assertEquals("sa1", labels["sa1"])
    }

    @Test
    fun `every session in a list always gets a label`() {
        val sessions = listOf(
            session("sa1", "~/linege/gpu-ops"),
            session("sb2", "~/linege/gpu-ops"),
            session("sc3", "~/Desktop/test"),
            session("sd4", "~/Desktop/test"),
            session("se5", "~/Desktop/tmp"),
            session("sf6", "?"),
        )

        val labels = sessions.displayLabels()

        assertEquals(sessions.size, labels.size)
        assertEquals(sessions.size, labels.values.toSet().size)
    }
}
