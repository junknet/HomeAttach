package com.homeattach.app.ssh

import com.homeattach.app.data.HostConfig
import com.jcraft.jsch.Session

/**
 * The process's one SSH connection to the host.
 *
 * Every remote call — the live session feed, kill/focus/release, and the terminal channel itself —
 * runs as a channel on this one transport. That is what SSH multiplexing is for, and the
 * alternative bought nothing: a connection per caller meant a full handshake, its own keepalive,
 * and its own radio wakeup each, several times over, to the same host. On a phone the handshake is
 * the expensive part, so holding one connection open is also what makes re-entering a session
 * instant.
 *
 * Reconnects are lazy and implicit: a dead session is simply replaced on the next [acquire], so
 * callers never have to know whether the transport survived since last time.
 *
 * Nothing closes it. It is meant to outlive any one screen, and when the process dies the socket
 * goes with it — an explicit close would only ever be a way for one screen to break another's
 * connection.
 */
object SharedSshSession {
    private var session: Session? = null
    private var openedWith: HostConfig? = null

    /**
     * The live connection, reconnecting if the held one died or was opened against a different
     * [config] — Settings can hand us a host the held session knows nothing about, and silently
     * answering on the old one would make "Test connection" a lie.
     *
     * Serialized: callers arriving during a reconnect wait for that one handshake instead of
     * racing to open their own.
     */
    @Synchronized
    @Throws(SshAuthException::class, SshConnectException::class)
    fun acquire(config: HostConfig): Session {
        val held = session
        if (held != null && held.isConnected && openedWith == config) return held
        runCatching { held?.disconnect() }
        session = null
        openedWith = null
        return openSshSession(config).also {
            session = it
            openedWith = config
        }
    }

    /**
     * Force the next [acquire] to reconnect, when a caller saw the transport fail before JSch's
     * own keepalive did. No-op if [dead] has already been replaced.
     */
    @Synchronized
    fun invalidate(dead: Session) {
        if (session !== dead) return
        runCatching { dead.disconnect() }
        session = null
        openedWith = null
    }
}
