package org.example.app.core.chats

/** A change to a group (add, remove, leave, role, name, photo) that could not be made. Nothing was changed on this device. */
class GroupChangeFailed(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason {
        /** The owner must hand the group to someone else before leaving. */
        OwnerMustTransfer,
        /** This account's role does not allow it (or the chat is read-only). */
        Forbidden,
        /** The group would hold more than 100 people. */
        Full,
        /** The server does not know the group or the person. */
        NotFound,
        /** A bad input, a network error or anything else. */
        Other,
    }
}
