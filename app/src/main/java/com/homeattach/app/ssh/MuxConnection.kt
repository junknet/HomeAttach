package com.homeattach.app.ssh

import com.homeattach.app.data.HostConfig
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * The host cannot run the mux at all — almost always because its scripts predate it. Retrying
 * cannot fix a missing file, so this is reported to the user rather than looped on.
 */
internal class MuxUnavailableException(message: String) : Exception(message)

/**
 * The one channel every attached terminal rides.
 *
 * Where the old design opened an SSH channel per session, this opens a single `tsess-mux` and
 * multiplexes sessions inside it as frames. That is what makes opening and closing a terminal a
 * frame rather than an SSH channel lifecycle — and closing one terminal can no longer disturb the
 * others, which on the per-channel design cost every surviving session a reconnect.
 *
 * Writes are serialized through one executor: JSch multiplexes channels over a single TCP socket
 * and is not safe to write to concurrently, and here a torn write is worse than before — it would
 * desynchronise the frame stream for every session at once, not just corrupt one terminal.
 */
internal class MuxConnection private constructor(
    private val session: Session,
    private val channel: ChannelExec,
    val input: InputStream,
    private val output: OutputStream,
) {
    @Volatile
    var closed = false
        private set

    private val writer = Executors.newSingleThreadExecutor()

    /** Captured so a channel that dies before saying anything can explain itself. */
    private val stderr = ByteArrayOutputStream()

    /**
     * Why the channel ended without ever producing a frame, or null when retrying is the right
     * answer. A host whose scripts predate `tsess-mux` answers the exec with a shell error and
     * status 127, and the reconnect loop would otherwise spin on that forever at full speed while
     * the screen said nothing more useful than "reconnecting".
     */
    fun diagnoseSilentExit(): String? {
        // exitStatus is only meaningful once the channel is closed, and EOF slightly precedes that.
        val deadline = System.currentTimeMillis() + EXIT_STATUS_GRACE_MS
        while (!channel.isClosed && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        val message = stderr.toString(StandardCharsets.UTF_8.name()).trim()
        val notFound = channel.exitStatus == COMMAND_NOT_FOUND ||
            message.contains("not found", ignoreCase = true) ||
            message.contains("No such file", ignoreCase = true)
        return if (notFound) MISSING_MUX else null
    }

    /** Queues one already-encoded frame. Safe to call from any thread. */
    fun send(frame: ByteArray) {
        if (closed) return
        writer.execute {
            try {
                output.write(frame)
                output.flush()
            } catch (_: Exception) {
                // best-effort; a broken pipe surfaces as EOF on the reader thread, which is the
                // one place that decides to reconnect
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            channel.disconnect()
        } catch (_: Exception) {
        }
        writer.shutdown()
    }

    /** Drops the shared transport too, when the caller saw it fail before the keepalive did. */
    fun invalidateTransport() {
        if (!session.isConnected) SharedSshSession.invalidate(session)
    }

    companion object {
        private const val MUX_COMMAND = "\$HOME/.local/bin/tsess-mux"
        private const val COMMAND_NOT_FOUND = 127
        private const val EXIT_STATUS_GRACE_MS = 300L
        private const val MISSING_MUX =
            "This PC is missing tsess-mux. Run server/install.sh on it, then reconnect."

        @Throws(SshAuthException::class, SshConnectException::class)
        fun open(config: HostConfig): MuxConnection {
            val session = SharedSshSession.acquire(config)
            try {
                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand(MUX_COMMAND)
                // No pty, and this is not a detail: the mux stream is binary frames, and a pty's
                // line discipline would rewrite \n as \r\n inside payloads and mangle every frame
                // header that happened to contain one. The sessions get their ptys on the host,
                // where they belong.
                channel.setPty(false)
                val input = channel.inputStream
                val output = channel.outputStream
                val connection = MuxConnection(session, channel, input, output)
                // Before connect: a host that cannot run the command answers immediately, and the
                // error has to have somewhere to land or the failure is indistinguishable from a
                // dropped network.
                channel.setErrStream(connection.stderr)
                channel.connect(CONNECT_TIMEOUT_MS)
                return connection
            } catch (e: Exception) {
                if (!session.isConnected) SharedSshSession.invalidate(session)
                throw e
            }
        }
    }
}
