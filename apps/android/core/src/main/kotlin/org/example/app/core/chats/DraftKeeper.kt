package org.example.app.core.chats

import java.util.concurrent.ConcurrentHashMap

/** Unsent composer text per chat, for the life of the process. Whitespace-only text is not a draft. */
class DraftKeeper {
    private val drafts = ConcurrentHashMap<String, String>()

    fun set(text: String, chatId: String) {
        if (text.isBlank()) drafts.remove(chatId) else drafts[chatId] = text
    }

    fun draft(chatId: String): String = drafts[chatId].orEmpty()
}

/** One [DraftKeeper] per signed-in account: another account signing in later in the same process starts with none. */
class AccountDrafts {
    private var owner: String? = null
    private var keeper = DraftKeeper()

    @Synchronized
    fun keeperFor(accountId: String): DraftKeeper {
        if (owner != accountId) {
            owner = accountId
            keeper = DraftKeeper()
        }
        return keeper
    }
}
