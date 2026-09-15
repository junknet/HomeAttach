package com.homeattach.app.terminal

import com.homeattach.app.ssh.MuxHistoryPage

internal data class TerminalHistoryRequest(val anchor: String, val before: Long, val limit: Int)

internal class TerminalHistoryCursor {
    var anchor: String = "0"
        private set
    var before: Long = 0
        private set
    var more: Boolean = false
        private set
    var loading: Boolean = false
        private set
    var generation: Long = 0
        private set
    private var requestedBefore: Long? = null

    fun reset() {
        generation++
        anchor = "0"
        before = 0
        more = false
        loading = false
        requestedBefore = null
    }

    fun metadata(page: MuxHistoryPage) {
        if (page.anchor == "0" || page.rows.isNotEmpty() || page.before != 0L || page.next != 0L) return
        if (anchor == page.anchor) return
        anchor = page.anchor
        before = 0
        more = page.status == "ok" && page.more
        loading = false
        requestedBefore = null
    }

    fun request(topRow: Int, historyRows: Int, visibleRows: Int): TerminalHistoryRequest? {
        if (topRow >= 0 || historyRows + topRow > maxOf(16, visibleRows * 2)) return null
        if (!more || loading || anchor == "0") return null
        loading = true
        generation++
        requestedBefore = before
        return TerminalHistoryRequest(anchor, before, PAGE_ROWS)
    }

    fun accepts(page: MuxHistoryPage): Boolean =
        loading && anchor == page.anchor && requestedBefore == page.before

    fun completed(page: MuxHistoryPage, insertedRows: Int) {
        if (!accepts(page)) return
        loading = false
        requestedBefore = null
        if (page.status != "ok" || insertedRows != page.rows.size) {
            more = false
            return
        }
        before = page.next
        more = page.more && page.next > page.before
    }

    fun failed() {
        generation++
        loading = false
        requestedBefore = null
    }

    fun expired() {
        failed()
        more = false
    }

    private companion object {
        const val PAGE_ROWS = 128
    }
}
