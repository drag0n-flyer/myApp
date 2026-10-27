package org.example.app.core.chats

data class ChatFolder(val id: String, val name: String, val kind: Kind) {
    enum class Kind { All, Unread, Custom }

    companion object {
        /** `All` and `Unread` are fixed; the UI shows their names from string resources. */
        val All = ChatFolder("all", "All", Kind.All)
        val Unread = ChatFolder("unread", "Unread", Kind.Unread)
    }
}
