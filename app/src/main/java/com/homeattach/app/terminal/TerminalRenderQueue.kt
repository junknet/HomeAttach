package com.homeattach.app.terminal

internal sealed interface TerminalRenderOperation {
    data class Snapshot(val replayBytes: Long) : TerminalRenderOperation
    data class Output(val bytes: ByteArray) : TerminalRenderOperation
    data class Control(val action: () -> Unit) : TerminalRenderOperation
}

internal class TerminalRenderQueue(private val capacityBytes: Int = 2 * 1024 * 1024) {
    private sealed interface PendingEntry
    private class PendingOutput(val bytes: ByteArray, var offset: Int = 0) : PendingEntry
    private class PendingControl(val action: () -> Unit) : PendingEntry
    private val pending = ArrayDeque<PendingEntry>()
    private var snapshot: TerminalRenderOperation.Snapshot? = null
    private var bufferedBytes = 0

    @Synchronized
    fun beginSnapshot(replayBytes: Long) {
        require(replayBytes >= 0)
        pending.clear()
        bufferedBytes = 0
        snapshot = TerminalRenderOperation.Snapshot(replayBytes)
    }

    @Synchronized
    fun append(buffer: ByteArray, offset: Int, count: Int): Boolean {
        require(offset >= 0 && count >= 0 && offset <= buffer.size - count)
        if (count > capacityBytes - bufferedBytes) return false
        if (count > 0) {
            pending.addLast(PendingOutput(buffer.copyOfRange(offset, offset + count)))
            bufferedBytes += count
        }
        return true
    }

    @Synchronized
    fun take(maximumBytes: Int): TerminalRenderOperation? {
        require(maximumBytes > 0)
        snapshot?.let {
            snapshot = null
            return it
        }
        val entry = pending.firstOrNull() ?: return null
        if (entry is PendingControl) {
            pending.removeFirst()
            return TerminalRenderOperation.Control(entry.action)
        }
        val current = entry as PendingOutput
        val count = minOf(maximumBytes, current.bytes.size - current.offset)
        val bytes = current.bytes.copyOfRange(current.offset, current.offset + count)
        current.offset += count
        bufferedBytes -= count
        if (current.offset == current.bytes.size) pending.removeFirst()
        return TerminalRenderOperation.Output(bytes)
    }

    @Synchronized
    fun enqueueControl(action: () -> Unit) {
        pending.addLast(PendingControl(action))
    }

    @Synchronized
    fun hasPending(): Boolean = snapshot != null || pending.isNotEmpty()

    @Synchronized
    fun clear() {
        snapshot = null
        pending.clear()
        bufferedBytes = 0
    }
}
