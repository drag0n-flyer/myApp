package org.example.app.core.chats

/** Decides when the chat asks the store for an older page, and when to stop asking. Not thread safe: use from one thread. */
class HistoryPager {
    companion object {
        const val PAGE_SIZE = 50

        /** Ask when the oldest visible message is this close to the oldest loaded one. */
        const val LOOKAHEAD = 5
    }

    var reachedStart: Boolean = false
        private set
    var isLoading: Boolean = false
        private set

    /** [oldestVisibleIndex] counts from the oldest loaded message (0). */
    fun shouldLoad(oldestVisibleIndex: Int): Boolean = oldestVisibleIndex < LOOKAHEAD && !isLoading && !reachedStart

    /** True when a load may start now; false when one is running or history has ended. */
    fun begin(): Boolean {
        if (isLoading || reachedStart) return false
        isLoading = true
        return true
    }

    fun finish(loadedCount: Int) {
        isLoading = false
        if (loadedCount < PAGE_SIZE) reachedStart = true
    }

    /** A load that failed or was cancelled: it may be tried again. */
    fun abort() {
        isLoading = false
    }
}
