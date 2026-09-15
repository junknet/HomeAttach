package com.homeattach.app.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homeattach.app.ssh.MuxHistoryPage
import com.homeattach.app.ssh.MuxHistoryRow
import com.homeattach.app.ssh.MuxProtocol
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteTerminalRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var terminal: RemoteTerminalSession
    private lateinit var terminalView: TerminalView
    private val receivedInput = mutableListOf<ByteArray>()

    @Before
    fun createRemoteFixture() = onMain {
        val context = instrumentation.targetContext
        terminal = RemoteTerminalSession(context, { receivedInput += it }, { _, _ -> })
        terminal.session.updateSize(80, 24, 8, 16)
        terminalView = TerminalView(context, null).apply {
            setTerminalViewClient(QuietTerminalClient())
            setTextSize(16)
            attachSession(terminal.session)
            layout(0, 0, 800, 480)
        }
        terminal.onScreenUpdated = { terminalView.onScreenUpdated() }
    }

    @After
    fun releaseRemoteFixture() {
        if (::terminal.isInitialized) onMain { terminal.finish() }
    }

    @Test
    fun snapshotPublishesAtomicallyAndYieldsToMainThreadBetweenChunks() {
        val originalEmulator = AtomicReference<com.termux.terminal.TerminalEmulator>()
        val partialParsed = CountDownLatch(1)
        val snapshotRendered = CountDownLatch(1)
        val heartbeat = CountDownLatch(1)
        val heartbeatSawOriginal = AtomicBoolean(false)
        val historyCallbacks = AtomicInteger()
        val replay = buildString {
            append("\u001bc")
            repeat(2000) { lineIndex ->
                append("\u001b[32m历史 $lineIndex：中文内容与 Unicode 🚀\u001b[0m\r\n")
            }
            append("\u001b[2J\u001b[H\u001b[31m最终画面\u001b[0m\u001b[12;9H")
        }.toByteArray(Charsets.UTF_8)
        val firstChunkSize = 4093

        onMain {
            val previous = terminal.session.emulator
            val previousBytes = "旧画面".toByteArray(Charsets.UTF_8)
            previous.append(previousBytes, previousBytes.size)
            originalEmulator.set(previous)
            terminalView.setHistoryScrollListener { _, _, _ -> historyCallbacks.incrementAndGet() }
            terminal.onSnapshotRendered = { snapshotRendered.countDown() }
            terminal.beginSnapshot(replay.size.toLong())
            terminal.appendRemoteOutput(replay, 0, firstChunkSize)
            terminal.enqueueControl { partialParsed.countDown() }
        }
        awaitEvent(partialParsed, "partial snapshot parsing")

        onMain {
            assertSame(originalEmulator.get(), terminal.session.emulator)
            assertSame(originalEmulator.get(), terminalView.mEmulator)
            assertEquals("旧画面", terminal.session.emulator.screen.transcriptText)
            assertEquals(1L, snapshotRendered.count)
            var chunkOffset = firstChunkSize
            while (chunkOffset < replay.size) {
                val chunkSize = minOf(997, replay.size - chunkOffset)
                terminal.appendRemoteOutput(replay, chunkOffset, chunkSize)
                chunkOffset += chunkSize
            }
            Handler(Looper.getMainLooper()).post {
                heartbeatSawOriginal.set(
                    terminal.session.emulator === originalEmulator.get() && snapshotRendered.count == 1L,
                )
                heartbeat.countDown()
            }
        }
        awaitEvent(heartbeat, "main-thread heartbeat during snapshot")
        assertTrue("snapshot parsing must yield before publishing", heartbeatSawOriginal.get())
        awaitEvent(snapshotRendered, "complete snapshot publication")

        onMain {
            val restored = terminal.session.emulator
            assertNotSame(originalEmulator.get(), restored)
            assertSame(restored, terminalView.mEmulator)
            assertEquals(11, restored.cursorRow)
            assertEquals(8, restored.cursorCol)
            assertEquals("最终画面", restored.screen.getSelectedText(0, 0, restored.mColumns - 1, 0))
            assertEquals(1, TextStyle.decodeForeColor(restored.screen.getStyleAt(0, 0)))
            assertEquals(0, terminalView.topRow)
            assertEquals("initial live viewport must not request older pages", 0, historyCallbacks.get())
            saveTerminalScreenshot("snapshot-final.png")
        }
    }

    @Test
    fun historyPrependPreservesViewportLiveScreenAndCursor() {
        val initialParsed = CountDownLatch(1)
        val historyInserted = CountDownLatch(1)
        val insertedCount = AtomicInteger(-1)
        val historyCallbacks = AtomicInteger()
        val initialBytes = buildString {
            repeat(80) { lineIndex -> append("当前历史 $lineIndex\r\n") }
            append("\u001b[7;11H")
        }.toByteArray(Charsets.UTF_8)
        onMain {
            terminal.appendRemoteOutput(initialBytes)
            terminal.enqueueControl { initialParsed.countDown() }
        }
        awaitEvent(initialParsed, "initial terminal output")

        val screenBefore = AtomicReference<String>()
        val viewportBefore = AtomicReference<String>()
        val historyBefore = AtomicInteger()
        val cursorRowBefore = AtomicInteger()
        val cursorColumnBefore = AtomicInteger()
        onMain {
            val emulator = terminal.session.emulator
            terminalView.setHistoryScrollListener { _, _, _ -> historyCallbacks.incrementAndGet() }
            terminalView.topRow = -8
            assertEquals(1, historyCallbacks.get())
            screenBefore.set(emulator.screen.getSelectedText(0, 0, emulator.mColumns - 1, emulator.mRows - 1))
            viewportBefore.set(emulator.screen.getSelectedText(0, -8, emulator.mColumns - 1, -5))
            historyBefore.set(emulator.screen.activeTranscriptRows)
            cursorRowBefore.set(emulator.cursorRow)
            cursorColumnBefore.set(emulator.cursorCol)
            terminal.prependHistory(
                MuxHistoryPage(
                    status = "ok", anchor = "1", before = 0, next = 3,
                    columns = emulator.mColumns, more = false,
                    rows = listOf(
                        MuxHistoryRow("", false),
                        MuxHistoryRow("\u001b[31m更早的中文\u001b[0m", true),
                        MuxHistoryRow("上一页结尾 🚀", false),
                    ),
                ),
                canApply = { true },
                completed = { inserted ->
                    insertedCount.set(inserted)
                    historyInserted.countDown()
                },
            )
        }
        awaitEvent(historyInserted, "history page insertion")
        onMain {
            val emulator = terminal.session.emulator
            assertEquals(3, insertedCount.get())
            assertEquals(historyBefore.get() + 3, emulator.screen.activeTranscriptRows)
            assertEquals(-8, terminalView.topRow)
            assertEquals(cursorRowBefore.get(), emulator.cursorRow)
            assertEquals(cursorColumnBefore.get(), emulator.cursorCol)
            assertEquals(screenBefore.get(), emulator.screen.getSelectedText(0, 0, emulator.mColumns - 1, emulator.mRows - 1))
            assertEquals(viewportBefore.get(), emulator.screen.getSelectedText(0, -8, emulator.mColumns - 1, -5))
            val oldest = -emulator.screen.activeTranscriptRows
            assertEquals("", emulator.screen.getSelectedText(0, oldest, emulator.mColumns - 1, oldest))
            assertTrue(emulator.screen.getLineWrap(oldest + 1))
            assertFalse(emulator.screen.getLineWrap(oldest + 2))
            assertEquals(1, TextStyle.decodeForeColor(emulator.screen.getStyleAt(oldest + 1, 0)))
            assertEquals(2, historyCallbacks.get())
            saveTerminalScreenshot("history-anchored.png")
        }
    }

    @Test
    fun tabExtraKeySendsOneRawTabByte() = onMain {
        val inputEvents = AtomicInteger()
        terminal.onUserInput = { inputEvents.incrementAndGet() }
        terminal.write(byteArrayOf(0x09), 0, 1)
        assertEquals(1, inputEvents.get())
        assertEquals(1, receivedInput.size)
        assertArrayEquals(byteArrayOf(0x09), receivedInput.single())
    }

    @Test
    fun cancelledHistoryDecodingCannotModifyTranscript() {
        val completed = CountDownLatch(1)
        val beforeRows = AtomicInteger()
        onMain {
            val emulator = terminal.session.emulator
            beforeRows.set(emulator.screen.activeTranscriptRows)
            terminal.prependHistory(
                MuxHistoryPage("ok", "1", 0, 1, emulator.mColumns, false,
                    listOf(MuxHistoryRow("stale response", false))),
                canApply = { false },
                completed = { completed.countDown() },
            )
        }
        awaitEvent(completed, "cancelled history decoding")
        onMain {
            assertEquals(beforeRows.get(), terminal.session.emulator.screen.activeTranscriptRows)
            assertFalse(terminal.session.emulator.screen.transcriptText.contains("stale response"))
        }
    }

    @Test
    fun serverSnapshotAndPagesRetainEveryPhysicalRow() {
        for (scenario in listOf("numbered", "blank")) {
            val assets = instrumentation.context.assets
            val original = assets.open("history/$scenario/original.vt").use { it.readBytes() }
            val snapshot = assets.open("history/$scenario/snapshot.vt").use { it.readBytes() }
            val published = CountDownLatch(1)
            val expected = AtomicReference<com.termux.terminal.TerminalEmulator>()
            onMain {
                terminal.session.updateSize(40, 5, 8, 16)
                expected.set(terminal.session.createRemoteSnapshotEmulator().apply {
                    append(original, original.size)
                })
                terminal.onSnapshotRendered = { published.countDown() }
                terminal.beginSnapshot(snapshot.size.toLong())
                terminal.appendRemoteOutput(snapshot)
            }
            awaitEvent(published, "$scenario server snapshot")
            val filenames = assets.list("history/$scenario")!!.filter { it.startsWith("page-") }.sorted()
            for (filename in filenames) {
                val page = MuxProtocol.readHistoryPage(
                    assets.open("history/$scenario/$filename").use { it.readBytes() },
                )!!
                val inserted = CountDownLatch(1)
                val count = AtomicInteger()
                onMain {
                    terminal.prependHistory(page, canApply = { true }) {
                        count.set(it)
                        inserted.countDown()
                    }
                }
                awaitEvent(inserted, "$scenario $filename")
                assertEquals(page.rows.size, count.get())
            }
            onMain {
                val restored = terminal.session.emulator
                val baseline = expected.get()
                assertEquals(baseline.screen.activeTranscriptRows, restored.screen.activeTranscriptRows)
                assertEquals(baseline.cursorRow, restored.cursorRow)
                assertEquals(baseline.cursorCol, restored.cursorCol)
                for (rowIndex in -baseline.screen.activeTranscriptRows until baseline.mRows) {
                    assertEquals("$scenario row $rowIndex",
                        baseline.screen.getSelectedText(0, rowIndex, 39, rowIndex),
                        restored.screen.getSelectedText(0, rowIndex, 39, rowIndex))
                    assertEquals(baseline.screen.getLineWrap(rowIndex), restored.screen.getLineWrap(rowIndex))
                    for (columnIndex in 0 until baseline.mColumns) {
                        assertEquals(baseline.screen.getStyleAt(rowIndex, columnIndex),
                            restored.screen.getStyleAt(rowIndex, columnIndex))
                    }
                }
            }
        }
    }

    private fun awaitEvent(event: CountDownLatch, description: String) {
        assertTrue("Timed out waiting for $description", event.await(30, TimeUnit.SECONDS))
    }

    private fun onMain(action: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                action()
            } catch (problem: Throwable) {
                failure.set(problem)
            }
        }
        failure.get()?.let { throw it }
    }

    private fun saveTerminalScreenshot(filename: String) {
        val bitmap = Bitmap.createBitmap(terminalView.width, terminalView.height, Bitmap.Config.ARGB_8888)
        try {
            terminalView.draw(Canvas(bitmap))
            val output = File(instrumentation.targetContext.getExternalFilesDir(null), filename)
            output.outputStream().use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private class QuietTerminalClient : TerminalViewClient {
        override fun onScale(scale: Float) = scale
        override fun onSingleTapUp(event: MotionEvent) {}
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = false
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, event: KeyEvent, session: TerminalSession) = false
        override fun onKeyUp(keyCode: Int, event: KeyEvent) = false
        override fun onLongPress(event: MotionEvent) = false
        override fun readControlKey() = false
        override fun readAltKey() = false
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, controlDown: Boolean, session: TerminalSession) = false
        override fun onEmulatorSet() {}
        override fun logError(logLabel: String?, message: String?) {}
        override fun logWarn(logLabel: String?, message: String?) {}
        override fun logInfo(logLabel: String?, message: String?) {}
        override fun logDebug(logLabel: String?, message: String?) {}
        override fun logVerbose(logLabel: String?, message: String?) {}
        override fun logStackTraceWithMessage(logLabel: String?, message: String?, failure: Exception?) {}
        override fun logStackTrace(logLabel: String?, failure: Exception?) {}
    }
}
