package org.example.app.core.groups

import org.example.app.core.profile.DisplayNameRule

/** Group chat IDs: `g:<conversationID>`. A one-to-one chat's ID stays the peer's account ID. */
object ChatID {
    const val PREFIX = "g:"

    fun group(conversationID: String): String = PREFIX + conversationID

    /** True only for the prefix followed by at least one character. */
    fun isGroup(id: String): Boolean = id.length > PREFIX.length && id.startsWith(PREFIX)

    /** The conversation ID of a group chat ID; null for anything else (a contact's account ID, the empty string). */
    fun conversationID(id: String): String? = if (isGroup(id)) id.substring(PREFIX.length) else null
}

/** A member's role. Only the server's member list decides who holds one. */
enum class GroupRole(val wire: String) {
    Owner("owner"), Admin("admin"), Member("member");

    /** Owner and admin add people. */
    val canAdd: Boolean get() = this != Member

    /** The owner removes an admin or a member; an admin removes only a member; nobody removes the owner. */
    fun canRemove(target: GroupRole): Boolean = when (this) {
        Owner -> target != Owner
        Admin -> target == Member
        Member -> false
    }

    /** Only the owner sets roles. */
    val canSetRoles: Boolean get() = this == Owner

    /** Name and photo. */
    val canEditInfo: Boolean get() = this != Member

    /** The disappearing-message timer. */
    val canSetTimer: Boolean get() = this != Member

    companion object {
        /** Null for a role this version does not know. */
        fun from(wire: String): GroupRole? = entries.firstOrNull { it.wire == wire }
    }
}

data class Member(val accountId: String, val role: GroupRole)

/** A line in the chat derived from the difference between two member lists. A null `by` is the form without an actor. */
sealed interface SystemLine {
    data class Added(val by: String?, val who: String) : SystemLine
    data class Removed(val by: String?, val who: String) : SystemLine
    data class Left(val who: String) : SystemLine
    data object YouWereRemoved : SystemLine
    data class MadeAdmin(val who: String) : SystemLine
    data class NoLongerAdmin(val who: String) : SystemLine
}

object MemberDiff {
    /**
     * The lines that take [old] to [new], ordered by account ID (one line per account at most). [actor] is the account
     * that made the change, when known: a removal whose target is the actor is a leave; with no actor a removal and an
     * addition have no "by". [me] being removed by someone else is [SystemLine.YouWereRemoved]. A role change gives a
     * line only for becoming (member or owner to admin) or stopping being an admin (admin to member); becoming owner
     * says nothing.
     */
    fun lines(old: List<Member>, new: List<Member>, actor: String?, me: String): List<SystemLine> {
        val before = old.associateBy { it.accountId }
        val after = new.associateBy { it.accountId }
        val out = sortedMapOf<String, SystemLine>()
        for ((id, m) in after) {
            val was = before[id]
            if (was == null) {
                out[id] = SystemLine.Added(actor, id)
            } else if (was.role != m.role) {
                when {
                    m.role == GroupRole.Admin -> out[id] = SystemLine.MadeAdmin(id)
                    was.role == GroupRole.Admin && m.role == GroupRole.Member -> out[id] = SystemLine.NoLongerAdmin(id)
                }
            }
        }
        for (id in before.keys) {
            if (id in after) continue
            out[id] = when {
                actor != null && actor == id -> SystemLine.Left(id)
                id == me -> SystemLine.YouWereRemoved
                else -> SystemLine.Removed(actor, id)
            }
        }
        return out.values.toList()
    }
}

/** A group name: 1 to 50 code points under the display-name character rules. */
object GroupNameRule {
    const val MAX = 50

    /** The trimmed name, or null when it is not acceptable. */
    fun clean(name: String): String? = DisplayNameRule.cleanUpTo(name, MAX)
}

/** Which `group` envelope or change a device keeps. */
object GroupMerge {
    /**
     * Keep an incoming name and photo only when it comes from an owner or admin ([senderRole] null for a sender that is
     * not in the member list) and its version is at least 1 and strictly higher than the stored one.
     */
    fun accept(currentVer: Int?, incomingVer: Int, senderRole: GroupRole?): Boolean {
        if (senderRole == null || !senderRole.canEditInfo) return false
        return incomingVer >= 1 && (currentVer == null || incomingVer > currentVer)
    }

    /**
     * Two admins who changed the group at the same moment both wrote the same `ver`. On equal versions the info whose
     * (name, photo) is greater wins, so every device ends on one value whatever order the messages arrive in: the name
     * is compared as a plain string by Unicode code points; when the names are equal, by the photo's SHA-256 in lower-case
     * hex, where no photo is the least. True when the incoming info beats the stored one.
     */
    fun beats(currentName: String, currentPhoto: ByteArray?, incomingName: String, incomingPhoto: ByteArray?): Boolean {
        val byName = compareCodePoints(incomingName, currentName)
        if (byName != 0) return byName > 0
        val a = photoKey(incomingPhoto)
        val b = photoKey(currentPhoto)
        return a != null && (b == null || a > b)
    }

    private fun photoKey(photo: ByteArray?): String? =
        photo?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } }

    private fun compareCodePoints(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
