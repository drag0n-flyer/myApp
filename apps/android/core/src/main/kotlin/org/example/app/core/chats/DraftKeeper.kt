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
