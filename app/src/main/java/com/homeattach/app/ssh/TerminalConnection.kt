package com.homeattach.app.ssh

import com.homeattach.app.data.HostConfig
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A live interactive shell attached to the same sharepty session as the desktop client. Reads from
 * [InputStream] are the terminal's stdout/stderr (already merged into one PTY stream by the
 * remote tty layer).
 *
 * All writes to the channel - both keystroke data and PTY resize control requests - MUST be
 * serialized: JSch's [Session] multiplexes every channel over one TCP socket and is not safe to
 * write to concurrently from multiple threads. Interleaving a `setPtySize` control packet with a
 * data write from another thread corrupts the outgoing packet stream and the server tears the
 * whole connection down with a MAC error. So both [send] and [resizePty] go through the same
 * single-thread executor here rather than letting callers touch [output] directly.
 */
class TerminalConnection private constructor(
    private val config: HostConfig,
    private val attachRequest: TerminalAttachRequest,
    private val session: Session,
    private val channel: ChannelExec,
    val input: InputStream,
    private val output: OutputStream,
) {
    @Volatile
    var closed = false
        private set

    private val ioExecutor = Executors.newSingleThreadExecutor()

    /** Send raw bytes (keystrokes) to the remote pty. Safe to call from any thread. */
    fun send(bytes: ByteArray) {
        if (closed) return
        ioExecutor.execute {
            try {
                output.write(bytes)
                output.flush()
            } catch (_: Exception) {
                // best-effort; a broken pipe here will also surface via the reader thread's EOF
            }
        }
    }

    /** Resize the remote pty and mark this Android terminal as the focused size owner. */
    fun resizePty(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0 || closed) return
        ioExecutor.execute {
            try {
                channel.setPtySize(cols, rows, cols * 8, rows * 16)
                focusRemoteSession(
                    session = session,
                    request = attachRequest.copy(
                        terminalSize = RemoteTerminalSize(
                            columns = cols.coerceAtLeast(2),
                            rows = rows.coerceAtLeast(2),
                        ),
                    ),
                )
            } catch (_: Exception) {
                // best-effort; a failed resize should never crash the terminal
            }
        }
    }

    /** Mark this Android terminal as focused without waiting for a layout size change. */
    fun focusPty(cols: Int, rows: Int) {
        resizePty(cols = cols, rows = rows)
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            channel.disconnect()
        } catch (_: Exception) {
        }
        try {
            session.disconnect()
        } catch (_: Exception) {
        }
        releaseFocusInBackground()
        ioExecutor.shutdownNow()
    }

    private fun releaseFocusInBackground() {
        thread(name = "ssh-terminal-release-focus", isDaemon = true) {
            runCatching {
                releaseRemoteSessionFocus(
                    config = config,
                    sessionName = attachRequest.sessionName,
                    ownerIdentifier = attachRequest.ownerIdentifier,
                )
            }
        }
    }

    companion object {
        /** Marks Android as focused, then opens a new SSH connection attached to the session. */
        @Throws(SshAuthException::class, SshConnectException::class)
        fun attach(config: HostConfig, request: TerminalAttachRequest): TerminalConnection {
            val session = openSshSession(config)
            var focusGranted = false
            try {
                focusRemoteSession(session, request)
                focusGranted = true
                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand("\$HOME/.local/bin/tsess-attach ${shellQuote(request.sessionName)} 256k app")
                channel.setPty(true)
                channel.setPtyType("xterm-256color")
                channel.setPtySize(
                    request.terminalSize.columns.coerceAtLeast(2),
                    request.terminalSize.rows.coerceAtLeast(2),
                    0,
                    0,
                )
                val input = channel.inputStream
                val output = channel.outputStream
                channel.connect(8000)
                return TerminalConnection(config, request, session, channel, input, output)
            } catch (e: Exception) {
                if (focusGranted) {
                    runCatching {
                        releaseRemoteSessionFocus(
                            session = session,
                            sessionName = request.sessionName,
                            ownerIdentifier = request.ownerIdentifier,
                        )
                    }
                }
                session.disconnect()
                throw e
            }
        }

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
