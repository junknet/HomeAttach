package com.homeattach.app.ssh

import com.homeattach.app.data.HostConfig
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class RemoteTerminalSize(
    val columns: Int,
    val rows: Int,
) {
    val displayText: String = "${columns}x${rows}"
}

data class TerminalAttachRequest(
    val sessionName: String,
    val ownerIdentifier: String,
    val terminalSize: RemoteTerminalSize,
)

/**
 * One live terminal session as reported by `tsess-list` on the host. The list format is TSV:
 * `name\tcmd\tcwd\towner\tcols\trows\tstatus`. Older builds of the host script emit only the
 * first three columns, so everything past [cwd] is optional and defaults to "unknown"
 * ([owner]/[status] empty, sizes null) rather than failing the parse.
 */
data class RemoteSession(
    val name: String,
    val command: String,
    val cwd: String,
    /** Which side currently owns the pty size: "pc", "android", or "none". */
    val owner: String = "",
    val cols: Int? = null,
    val rows: Int? = null,
    /** "focused" when a viewer is actively attached, "detached" otherwise. */
    val status: String = "",
)

class SshAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
class SshConnectException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val CONNECT_TIMEOUT_MS = 8000

// 15s probe x 2 misses = a dead transport is detected within ~30s of the radio coming back,
// bounded by one keepalive round-trip. Short enough to feel responsive on resume, long enough
// not to kill a session over one slow cellular RTT.
private const val SERVER_ALIVE_INTERVAL_MS = 15_000
private const val SERVER_ALIVE_COUNT_MAX = 2

// Non-interactive/non-login exec sessions don't source shell startup files, so PATH is not
// reliable. $HOME is still provided by sshd, making this portable across host usernames.
private const val SESSION_LIST_COMMAND = "\$HOME/.local/bin/tsess-list"
private const val SESSION_KILL_COMMAND = "\$HOME/.local/bin/tsess-kill"
private const val SESSION_FOCUS_COMMAND = "\$HOME/.local/bin/tsess-focus"
private const val SESSION_RELEASE_COMMAND = "\$HOME/.local/bin/tsess-release"
private const val SESSION_WATCH_COMMAND = "\$HOME/.local/bin/tsess-watch"

@Volatile
private var ed25519ConfigApplied = false

/**
 * JSch (even the mwiede fork) picks between two ed25519 signature implementations at class-init
 * time: a plain-JCE one that needs the JDK's built-in "Ed25519" `java.security.Signature`
 * algorithm (added in JDK 15, not present on the Android runtime), or a BouncyCastle-backed one.
 * The BouncyCastle artifact is an *optional* dependency of jsch, so without both forcing the
 * config and adding `org.bouncycastle:bcprov-jdk18on` explicitly, signing silently fails and
 * every connection attempt using an ed25519 key reports a generic "Auth fail for methods
 * 'publickey'" with no further detail. This must run before the first [Session] is created.
 */
private fun ensureEd25519Support() {
    if (ed25519ConfigApplied) return
    synchronized(SshClient::class.java) {
        if (ed25519ConfigApplied) return
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        ed25519ConfigApplied = true
    }
}

private object SshClient

/** Opens (and fully connects) a new JSch [Session] using the given config. Caller must disconnect(). */
@Throws(SshAuthException::class, SshConnectException::class)
fun openSshSession(config: HostConfig): Session {
    ensureEd25519Support()
    try {
        val normalizedPrivateKeyPem = normalizePrivateKeyPem(config.privateKeyPem)
        val jsch = JSch()
        jsch.addIdentity(
            "homeattach-key",
            normalizedPrivateKeyPem.toByteArray(StandardCharsets.UTF_8),
            null,
            null,
        )
        val session = jsch.getSession(config.username, config.host, config.port)
        val props = java.util.Properties()
        props["StrictHostKeyChecking"] = "no"
        session.setConfig(props)
        session.timeout = CONNECT_TIMEOUT_MS
        // Half-dead TCP is the norm on mobile: Doze/network handoff kills the path without a
        // FIN/RST ever reaching us, and a blocked channel read then hangs forever (frozen
        // terminal, keystrokes silently void). Keepalives make JSch itself probe the transport;
        // after interval*countMax of silence the session dies, channels EOF, and the reader
        // thread surfaces ConnectionLost instead of freezing.
        session.serverAliveInterval = SERVER_ALIVE_INTERVAL_MS
        session.serverAliveCountMax = SERVER_ALIVE_COUNT_MAX
        session.connect(CONNECT_TIMEOUT_MS)
        return session
    } catch (e: JSchException) {
        val msg = e.message ?: ""
        if (msg.contains("Auth", ignoreCase = true) ||
            msg.contains("PublicKey", ignoreCase = true) ||
            msg.contains("permission denied", ignoreCase = true)
        ) {
            throw SshAuthException("Authentication failed: $msg", e)
        }
        throw SshConnectException("Could not connect to ${config.host}:${config.port}: $msg", e)
    } catch (e: PrivateKeyFormatException) {
        throw SshAuthException("Invalid private key format: ${e.message}", e)
    }
}

/** Result of one remote script run: captured stdout/stderr plus the script's exit status. */
private class ExecResult(val stdout: String, val exitStatus: Int, val stderr: String)

/**
 * Runs one command over a one-shot exec channel on an already-connected [session], waits for it
 * to finish, and returns its output. Shared plumbing for the `tsess-*` helpers; callers apply
 * their own exit-status policy. Caller owns the [session]'s lifecycle.
 */
private fun execRemote(session: Session, command: String): ExecResult {
    val channel = session.openChannel("exec") as ChannelExec
    channel.setCommand(command)
    val errBuf = ByteArrayOutputStream()
    channel.setErrStream(errBuf)
    val out = channel.inputStream
    channel.connect(CONNECT_TIMEOUT_MS)

    val stdout = out.readBytes().toString(StandardCharsets.UTF_8)
    while (!channel.isClosed) {
        Thread.sleep(20)
    }
    channel.disconnect()
    return ExecResult(stdout, channel.exitStatus, errBuf.toString(StandardCharsets.UTF_8.name()))
}

/** Runs [command] and throws [SshConnectException] carrying stderr when it exits non-zero. */
private fun execRemoteOrThrow(session: Session, command: String, operation: String): ExecResult {
    val result = execRemote(session, command)
    if (result.exitStatus != 0) {
        val err = result.stderr.trim()
        throw SshConnectException(
            "$operation failed: exitStatus=${result.exitStatus}, stderr=${err.ifBlank { "<empty>" }}",
        )
    }
    return result
}

/**
 * Runs `tsess-list` over a one-shot exec channel on an already-connected [session] and parses
 * its TSV output. Caller owns the [session]'s lifecycle (this does not disconnect it) - this
 * overload exists so a poller can reuse one SSH connection across repeated calls instead of
 * paying a fresh handshake every time.
 */
@Throws(SshConnectException::class)
fun fetchRemoteSessions(session: Session): List<RemoteSession> {
    val result = execRemote(session, SESSION_LIST_COMMAND)

    if (result.exitStatus != 0 && result.stdout.isBlank()) {
        throw SshConnectException("tsess-list exited with ${result.exitStatus}: ${result.stderr}")
    }

    return result.stdout.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull(::parseSessionLine)
        .toList()
}

private fun parseSessionLine(line: String): RemoteSession? {
    val parts = line.split("\t")
    if (parts.size < 3) return null
    return RemoteSession(
        name = parts[0],
        command = parts[1],
        cwd = parts[2],
        owner = parts.getOrElse(3) { "" },
        cols = parts.getOrNull(4)?.toIntOrNull(),
        rows = parts.getOrNull(5)?.toIntOrNull(),
        status = parts.getOrElse(6) { "" },
    )
}

/**
 * Runs `tsess-watch` over a long-lived exec channel and invokes [onUpdate] with the full
 * session list every time the host emits one: immediately on start, the instant a session is
 * born or dies (inotify on the host), and every ~5s as a heartbeat. Blocks until the channel
 * closes (returns normally on clean EOF) or throws on transport errors. Caller owns the
 * [session]'s lifecycle and should call this from a background dispatcher.
 */
@Throws(SshConnectException::class)
fun watchRemoteSessions(session: Session, onUpdate: (List<RemoteSession>) -> Unit) {
    val channel = session.openChannel("exec") as ChannelExec
    channel.setCommand(SESSION_WATCH_COMMAND)
    val input = channel.inputStream
    channel.connect(CONNECT_TIMEOUT_MS)
    try {
        val reader = input.bufferedReader(StandardCharsets.UTF_8)
        var block: MutableList<RemoteSession>? = null
        var sawAnyBlock = false
        while (true) {
            val line = reader.readLine() ?: break
            when {
                line == "#BEGIN" -> block = mutableListOf()
                line == "#END" -> {
                    block?.let {
                        sawAnyBlock = true
                        onUpdate(it)
                    }
                    block = null
                }
                else -> block?.let { b -> parseSessionLine(line)?.let(b::add) }
            }
        }
        // An immediate EOF without a single block means the watch script is missing or
        // failed on the host - surface that so the caller can fall back to polling.
        if (!sawAnyBlock) {
            throw SshConnectException("tsess-watch produced no output (exit=${channel.exitStatus})")
        }
    } finally {
        runCatching { channel.disconnect() }
    }
}

/** Opens a fresh, one-shot SSH connection, runs `tsess-list`, then disconnects. */
@Throws(SshAuthException::class, SshConnectException::class)
fun fetchRemoteSessions(config: HostConfig): List<RemoteSession> {
    val session = openSshSession(config)
    try {
        return fetchRemoteSessions(session)
    } finally {
        session.disconnect()
    }
}

/** Wraps a value in single quotes for safe use as one word in a remote shell command line. */
private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/**
 * Runs `tsess-kill <name>` over a one-shot exec channel on an already-connected [session] to end
 * a HomeAttach session outright (daemon + inner shell + whatever it's running). Caller owns the
 * [session]'s lifecycle. Throws [SshConnectException] with the script's stderr message (e.g.
 * "no session named '<name>'") if the kill did not succeed.
 */
@Throws(SshConnectException::class)
fun killRemoteSession(session: Session, name: String) {
    val result = execRemote(session, "$SESSION_KILL_COMMAND " + shellQuote(name))
    if (result.exitStatus != 0) {
        val err = result.stderr.trim()
        throw SshConnectException(err.ifBlank { "tsess-kill exited with ${result.exitStatus}" })
    }
}

/**
 * Opens a fresh, one-shot SSH connection, runs `tsess-kill <name>`, then disconnects. Mirrors
 * the [fetchRemoteSessions] config overload for callers that don't hold a live [Session];
 * self-contained and safe to call from any background thread/dispatcher.
 */
@Throws(SshAuthException::class, SshConnectException::class)
fun killRemoteSession(config: HostConfig, name: String) {
    val session = openSshSession(config)
    try {
        killRemoteSession(session, name)
    } finally {
        session.disconnect()
    }
}

@Throws(SshConnectException::class)
fun focusRemoteSession(session: Session, request: TerminalAttachRequest) {
    val command = buildString {
        append(SESSION_FOCUS_COMMAND)
        append(' ')
        append(shellQuote(request.sessionName))
        append(' ')
        append(shellQuote(request.ownerIdentifier))
        append(' ')
        append(request.terminalSize.columns)
        append(' ')
        append(request.terminalSize.rows)
    }
    execRemoteOrThrow(session, command, "tsess-focus")
}

@Throws(SshAuthException::class, SshConnectException::class)
fun releaseRemoteSessionFocus(config: HostConfig, sessionName: String, ownerIdentifier: String) {
    val session = openSshSession(config)
    try {
        releaseRemoteSessionFocus(session, sessionName, ownerIdentifier)
    } finally {
        session.disconnect()
    }
}

@Throws(SshConnectException::class)
fun releaseRemoteSessionFocus(session: Session, sessionName: String, ownerIdentifier: String) {
    val command = buildString {
        append(SESSION_RELEASE_COMMAND)
        append(' ')
        append(shellQuote(sessionName))
        append(' ')
        append(shellQuote(ownerIdentifier))
    }
    execRemoteOrThrow(session, command, "tsess-release")
}
