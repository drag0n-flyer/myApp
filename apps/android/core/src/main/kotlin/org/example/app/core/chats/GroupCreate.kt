package org.example.app.core.chats

/** A group chat made by `ChatStore.createGroup`. [notAdded] are contact IDs that are not in it yet. */
class GroupCreated(val chatId: String, val notAdded: List<String>)

/** A group that could not be made. Nothing was left behind except, for [Reason.Other] after the server made it, a group that finishes later. */
class GroupCreateFailed(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason {
        /** Nobody picked could be added (no device ready yet). */
        NobodyReachable,
        /** The server does not know one of the people. */
        NotFound,
        /** The group would hold more than 100 people. */
        Full,
        /** The input broke a rule (fewer than two people, a bad name or photo), or anything else. */
        Other,
    }
}
