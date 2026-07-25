package com.homeattach.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteSessionSortTest {

    private fun session(name: String, cwd: String, command: String) = RemoteSession(
        name = name,
        cwd = cwd,
        command = command,
    )

    @Test
    fun `groups sessions by cwd then orders processes by name`() {
        val sorted = listOf(
            session("three", "/work/api", "zsh"),
            session("four", "/work/web", "node"),
            session("one", "/work/api", "gradle"),
            session("two", "/work/api", "bash"),
        ).sortedForDisplay()

        assertEquals(listOf("two", "one", "three", "four"), sorted.map { it.name })
    }

    @Test
    fun `uses the session name to deterministically order duplicate processes`() {
        val sorted = listOf(
            session("session-b", "/work/api", "zsh"),
            session("session-a", "/work/api", "zsh"),
        ).sortedForDisplay()

        assertEquals(listOf("session-a", "session-b"), sorted.map { it.name })
    }
}
