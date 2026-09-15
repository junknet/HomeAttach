package com.homeattach.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.homeattach.app.terminal.RemoteTerminalSession
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalExtraKeysTest {
    @get:Rule
    val composition = createComposeRule()
    private lateinit var terminal: RemoteTerminalSession

    @After
    fun releaseTerminal() {
        if (::terminal.isInitialized) composition.runOnIdle { terminal.finish() }
    }

    @Test
    fun tabIsReachableAndScrollingKeysDoesNotType() {
        val received = mutableListOf<ByteArray>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composition.runOnIdle {
            terminal = RemoteTerminalSession(context, { received += it }, { _, _ -> })
        }
        composition.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) { ExtraKeysRow(terminal) }
            }
        }
        composition.onNodeWithText("Tab").performTouchInput { click() }
        composition.runOnIdle {
            assertEquals(1, received.size)
            assertArrayEquals(byteArrayOf(0x09), received.single())
            received.clear()
        }
        val image = composition.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "terminal-extra-keys.png").outputStream().use { output ->
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        composition.onRoot().performTouchInput { swipeLeft() }
        composition.runOnIdle { assertTrue("Scrolling must not type terminal keys", received.isEmpty()) }
        composition.onNodeWithText("→").performTouchInput { click() }
        composition.runOnIdle {
            assertArrayEquals(byteArrayOf(0x1b, 0x5b, 0x43), received.single())
        }
    }
}
