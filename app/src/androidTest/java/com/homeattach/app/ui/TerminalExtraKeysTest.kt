package com.homeattach.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.homeattach.app.R
import com.homeattach.app.terminal.RemoteTerminalSession
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * The extra-keys row has one job beyond sending bytes: every key has to be *reachable*.
 *
 * It used to scroll horizontally, which on a 360dp phone parked the arrows off the right edge -
 * the keys a terminal needs most were the ones behind a swipe. Fitting them by choosing smaller
 * fixed sizes only moves that failure to the next narrower device, so the row derives its fit from
 * the width it is actually given. These tests are that claim: the same row, rendered at widths
 * narrower than any phone this ships to, still shows all nine keys inside its own bounds.
 */
class TerminalExtraKeysTest {
    @get:Rule
    val composition = createComposeRule()
    private lateinit var terminal: RemoteTerminalSession

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val newLabel = context.getString(R.string.terminal_key_new)

    /** Everything the shipping row shows, `New` included - that is the width it has to survive. */
    private val allKeys
        get() = listOf(newLabel, "Esc", "Tab", "^C", "^D", "Paste", "←", "↑", "↓", "→")

    @After
    fun releaseTerminal() {
        if (::terminal.isInitialized) composition.runOnIdle { terminal.finish() }
    }

    private fun renderRow(
        width: Dp,
        onInput: (ByteArray) -> Unit = {},
        busy: Boolean = false,
        onNewSession: () -> Unit = {},
    ) {
        composition.runOnIdle {
            terminal = RemoteTerminalSession(context, onInput, { _, _ -> })
        }
        composition.setContent {
            MaterialTheme {
                Box(Modifier.width(width)) {
                    ExtraKeysRow(
                        remoteTerminalSession = terminal,
                        newSessionBusy = busy,
                        onNewSession = onNewSession,
                    )
                }
            }
        }
    }

    @Test
    fun everyKeyFitsWithoutScrollingAtPhoneWidth() {
        renderRow(320.dp)
        assertEveryKeyIsInside(320.dp)
    }

    @Test
    fun everyKeyStillFitsOnAWidthNarrowerThanAnyPhone() {
        // 240dp is below the narrowest Android phone in circulation. If the row survives here it
        // is fitting by construction rather than by having been tuned on one device.
        renderRow(240.dp)
        captureRow("terminal-extra-keys-240.png")
        assertEveryKeyIsInside(240.dp)
    }

    private fun captureRow(name: String) {
        val image = composition.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), name).outputStream().use { out ->
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out))
        }
    }

    private fun assertEveryKeyIsInside(width: Dp) {
        allKeys.forEach { label ->
            val node = composition.onNodeWithText(label)
            node.assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(
                "key '$label' starts off the left edge at $width (left=${bounds.left})",
                bounds.left.value >= -0.5f,
            )
            assertTrue(
                "key '$label' runs past the right edge at $width (right=${bounds.right})",
                bounds.right.value <= width.value + 0.5f,
            )
            // A cap inside the row whose *label* is clipped is still a key the user cannot read.
            // Bounds alone do not catch that: the text is clipped within a correctly placed cap,
            // which is exactly how a first version of this passed while rendering "Past".
            val layouts = mutableListOf<TextLayoutResult>()
            node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
                .action?.invoke(layouts)
            assertTrue("no text layout reported for '$label'", layouts.isNotEmpty())
            assertFalse(
                "label '$label' is truncated at $width",
                layouts.first().didOverflowWidth,
            )
        }
    }

    @Test
    fun newAsksForASessionOnceAndTypesNothing() {
        val received = mutableListOf<ByteArray>()
        var starts = 0
        renderRow(320.dp, onInput = { received += it }) { starts++ }

        composition.onNodeWithText(newLabel).performTouchInput { click() }
        composition.runOnIdle {
            assertEquals("New must start exactly one session", 1, starts)
            assertTrue("New must not type into the terminal", received.isEmpty())
        }
    }

    @Test
    fun newIsInertWhileTheSessionIsStarting() {
        // The PC takes a moment to spawn the tab. A cap that still answered taps through that
        // would open a second session nobody asked for, so while busy it shows progress instead
        // of its label and does nothing at all.
        var starts = 0
        renderRow(320.dp, busy = true) { starts++ }

        composition.onNodeWithText(newLabel).assertDoesNotExist()
        composition.onRoot().performTouchInput { click() }
        composition.runOnIdle { assertEquals(0, starts) }
    }

    @Test
    fun tabTypesAndAStraySwipeDoesNot() {
        val received = mutableListOf<ByteArray>()
        renderRow(320.dp, onInput = { received += it })

        composition.onNodeWithText("Tab").performTouchInput { click() }
        composition.runOnIdle {
            assertEquals(1, received.size)
            assertArrayEquals(byteArrayOf(0x09), received.single())
            received.clear()
        }

        val image = composition.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "terminal-extra-keys.png").outputStream().use { out ->
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out))
        }

        // A finger dragged across the row - reaching past it, or a habitual swipe left over from
        // when the row scrolled - must send nothing at all.
        composition.onRoot().performTouchInput { swipeLeft() }
        composition.runOnIdle { assertTrue("A swipe across the row must not type", received.isEmpty()) }

        composition.onNodeWithText("→").performTouchInput { click() }
        composition.runOnIdle {
            assertArrayEquals(byteArrayOf(0x1b, 0x5b, 0x43), received.single())
        }
    }
}
