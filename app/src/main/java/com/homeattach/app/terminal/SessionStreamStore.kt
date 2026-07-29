package com.homeattach.app.terminal

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** What a session looked like when the app last held it, and where in the host's stream that was. */
internal class SavedStream(
    val epoch: Long,
    val offset: Long,
    val bytes: ByteArray,
)

/**
 * The tail of each session's byte stream, kept on disk so reopening the app is a continuation
 * rather than a reload.
 *
 * The app's process dies constantly - the user leaves for a day, Android reclaims it, the phone
 * reboots - and with it every terminal's emulator. Without this the only way back is to have the
 * host paint the whole session again, which measured at 134KB-850KB per session and rendered as a
 * visible, janky repaint of everything on every open. With it, the phone restores its own screen
 * from local bytes and asks the host only for what happened while it was away.
 *
 * Raw bytes rather than a serialized screen on purpose: the bytes are what we already receive, they
 * replay into an emulator with no format to keep in sync with Termux's internals, and the same file
 * doubles as the scrollback. The cost is that a truncated prefix can leave a mode set by an escape
 * we dropped - the same trade every terminal's scrollback cap makes.
 *
 * Thread-safety: [append] is called from the mux reader thread, [load]/[clear] from whoever owns
 * the attachment. Every method locks the store, and the file is only ever touched under it.
 */
internal class SessionStreamStore(private val directory: File) {

    /**
     * How much of each session is kept. Enough to refill a screen and a few scrolls of history;
     * past that the value drops off a cliff while the restore cost keeps rising, and the host's own
     * scrollback is the place to go for older output.
     */
    private val maxBytes: Int = DEFAULT_MAX_BYTES

    private val lock = Any()

    /**
     * Held open across appends. Terminal output arrives in small chunks many times a second, and
     * opening and closing the file for each one is the difference between a write that disappears
     * into the page cache and one that shows up as jank.
     */
    private var sink: FileOutputStream? = null
    private var sinkSession: String? = null

    init {
        pruneStale()
    }

    /** The saved tail for [sessionName], or null when there is nothing usable to continue from. */
    fun load(sessionName: String): SavedStream? = synchronized(lock) {
        val meta = metaFile(sessionName)
        val data = dataFile(sessionName)
        if (!meta.isFile || !data.isFile) return null
        val fields = runCatching { meta.readText().trim().split(" ") }.getOrNull() ?: return null
        if (fields.size < 3) return null
        val epoch = fields[0].toLongOrNull() ?: return null
        val offset = fields[1].toLongOrNull() ?: return null
        val length = fields[2].toIntOrNull() ?: return null
        if (epoch == 0L || length <= 0) return null

        // Only what the meta accounts for. The file is appended to first and the meta written
        // after, so a process killed between the two leaves bytes on disk that the saved cursor
        // does not cover - replaying those would duplicate whatever the host then resends.
        val bytes = runCatching {
            RandomAccessFile(data, "r").use { file ->
                if (file.length() < length) return@runCatching null
                ByteArray(length).also { file.readFully(it) }
            }
        }.getOrNull() ?: return null

        return SavedStream(epoch, offset, bytes)
    }

    /**
     * Records bytes the host sent for [sessionName] and the cursor they leave it at.
     *
     * [offset] is the stream position *after* [data]. Bytes land first and the cursor second, so a
     * crash between them under-claims rather than over-claims - see [load].
     */
    fun append(sessionName: String, epoch: Long, offset: Long, data: ByteArray) {
        if (epoch == 0L || data.isEmpty()) return
        synchronized(lock) {
            runCatching {
                directory.mkdirs()
                val file = dataFile(sessionName)
                val stream = openSink(sessionName, file)
                stream.write(data)
                var length = file.length()
                if (length > maxBytes + TRIM_SLACK_BYTES) {
                    length = trim(sessionName, file)
                }
                writeMeta(sessionName, epoch, offset, length.toInt())
            }.onFailure { closeSink() }
        }
    }

    /**
     * Replaces everything held for [sessionName]. Used when the host says it started the session's
     * picture over: what is on disk then describes a screen that no longer exists, and keeping it
     * would put stale content above content that replaced it.
     */
    fun reset(sessionName: String, epoch: Long, offset: Long) = synchronized(lock) {
        runCatching {
            closeSink()
            directory.mkdirs()
            dataFile(sessionName).delete()
            metaFile(sessionName).delete()
            if (epoch != 0L) writeMeta(sessionName, epoch, offset, 0)
        }
        Unit
    }

    fun clear(sessionName: String) = synchronized(lock) {
        runCatching {
            closeSink()
            dataFile(sessionName).delete()
            metaFile(sessionName).delete()
        }
        Unit
    }

    /** Closes the open file, if any. The next append reopens it. */
    fun close() = synchronized(lock) { closeSink() }

    private fun openSink(sessionName: String, file: File): FileOutputStream {
        val held = sink
        if (held != null && sinkSession == sessionName) return held
        closeSink()
        return FileOutputStream(file, true).also {
            sink = it
            sinkSession = sessionName
        }
    }

    private fun closeSink() {
        runCatching { sink?.close() }
        sink = null
        sinkSession = null
    }

    /**
     * Forgets sessions nobody has attached in a week. Names come and go on the host - every yakuake
     * tab is a new one - and without this the directory grows by one file pair per name, forever.
     */
    private fun pruneStale() = synchronized(lock) {
        runCatching {
            val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
            directory.listFiles()?.forEach { file ->
                if (file.lastModified() in 1 until cutoff) file.delete()
            }
        }
        Unit
    }

    /** Keeps the newest [maxBytes]; returns the file's new length. */
    private fun trim(sessionName: String, file: File): Long {
        closeSink()
        val keep = ByteArray(maxBytes)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(raf.length() - maxBytes)
            raf.readFully(keep)
            raf.seek(0)
            raf.write(keep)
            raf.setLength(maxBytes.toLong())
        }
        // Reopened by the next append; the handle above rewrote the file underneath it.
        openSink(sessionName, file)
        return maxBytes.toLong()
    }

    private fun writeMeta(sessionName: String, epoch: Long, offset: Long, length: Int) {
        metaFile(sessionName).writeText("$epoch $offset $length")
    }

    private fun dataFile(sessionName: String) = File(directory, "${key(sessionName)}.bin")

    private fun metaFile(sessionName: String) = File(directory, "${key(sessionName)}.meta")

    /**
     * Session names come from the host and are only constrained there; hashing keeps a name from
     * ever being read as a path.
     */
    private fun key(sessionName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sessionName.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 512 * 1024

        /** Trimming rewrites the file, so it is worth doing in one go rather than per append. */
        const val TRIM_SLACK_BYTES = 128 * 1024

        const val STALE_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}
