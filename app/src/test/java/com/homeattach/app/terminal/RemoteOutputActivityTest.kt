package com.homeattach.app.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteOutputActivityTest {

    @Test
    fun `received terminal output stays active for the lamp window`() {
        assertTrue(isRemoteOutputActive(lastOutputElapsedMs = 1_000L, nowElapsedMs = 1_400L))
    }

    @Test
    fun `activity expires when the lamp window ends`() {
        assertFalse(
            isRemoteOutputActive(
                lastOutputElapsedMs = 1_000L,
                nowElapsedMs = 1_000L + REMOTE_OUTPUT_ACTIVITY_WINDOW_MS,
            ),
        )
    }

    @Test
    fun `no remote output is never activity`() {
        assertFalse(isRemoteOutputActive(lastOutputElapsedMs = 0L, nowElapsedMs = 1_000L))
    }
}
