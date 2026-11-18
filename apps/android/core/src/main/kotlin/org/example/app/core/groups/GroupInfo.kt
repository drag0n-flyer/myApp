package org.example.app.core.groups

import java.text.Collator
import java.util.Locale

/** Whether this account is still in the group (the stored `my_state`). */
enum class MyGroupState { Member, Removed, Left }

/**
 * What a group chat's screens show of the server's member list: who is in it with what role, and whether this account
 * still is. Only the server's list decides roles; a screen only reads this.
 */
data class GroupInfo(val members: List<Member>, val myState: MyGroupState = MyGroupState.Member) {
    fun roleOf(accountId: String): GroupRole? = members.firstOrNull { it.accountId == accountId }?.role

    /** How many more people fit (a group holds [MAX_MEMBERS] in total). */
    fun roomLeft(): Int = (MAX_MEMBERS - members.size).coerceAtLeast(0)

    companion object {
        const val MAX_MEMBERS = 100
    }
}

/** The member list as shown: the owner, then the admins, then the members, each by name. */
object MemberOrder {
    fun sorted(members: List<Member>, nameOf: (String) -> String, locale: Locale): List<Member> {
        val collator = Collator.getInstance(locale)
        return members.sortedWith(
            compareBy<Member> { it.role.ordinal }.thenComparing({ nameOf(it.accountId) }, collator).thenBy { it.accountId },
        )
    }
}

/** An entry of the per-member menu. */
enum class MemberAction { Message, ViewSafetyNumber, MakeAdmin, RemoveAdmin, MakeOwner, Remove }

object MemberActions {
    /**
     * What [myRole] may do with [target], in menu order. [myRole] is null when this account is not in the group any more
     * (nothing then). Nothing is offered on oneself. "Message" and "View safety number" need a contact page to open
     * ([canMessage], [canVerify]); the role actions follow [GroupRole]: only the owner sets roles, the owner removes an
     * admin or a member, an admin removes a member, and nobody removes the owner.
     */
    fun forMember(myRole: GroupRole?, target: Member, isMe: Boolean, canMessage: Boolean, canVerify: Boolean): List<MemberAction> {
        if (myRole == null || isMe) return emptyList()
        return buildList {
            if (canMessage) add(MemberAction.Message)
            if (canVerify) add(MemberAction.ViewSafetyNumber)
            if (myRole.canSetRoles && target.role == GroupRole.Member) add(MemberAction.MakeAdmin)
            if (myRole.canSetRoles && target.role == GroupRole.Admin) add(MemberAction.RemoveAdmin)
            if (myRole.canSetRoles && target.role != GroupRole.Owner) add(MemberAction.MakeOwner)
            if (myRole.canRemove(target.role)) add(MemberAction.Remove)
        }
    }
}
