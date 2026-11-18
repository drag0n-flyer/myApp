package org.example.app.core.groups

/** One member's receipt for one of my messages: [kind] is `delivered` or `read`. */
data class GroupReceipt(val accountId: String, val kind: String)

/** The tick of my message in a group. */
object GroupTicks {
    enum class State { Sent, Delivered, Read }

    /**
     * Delivered or read only when every other current member (the stored list without [me]) has that receipt; a read
     * receipt counts as delivered. A member who left is not in [members] and holds nothing back; a receipt from anyone
     * not in the list counts for nothing. A group with no other member stays [State.Sent].
     */
    fun state(receipts: List<GroupReceipt>, members: List<Member>, me: String): State {
        val others = members.map { it.accountId }.filter { it != me }.toSet()
        if (others.isEmpty()) return State.Sent
        val delivered = receipts.filter { it.kind == "delivered" || it.kind == "read" }.map { it.accountId }.toSet()
        val read = receipts.filter { it.kind == "read" }.map { it.accountId }.toSet()
        return when {
            read.containsAll(others) -> State.Read
            delivered.containsAll(others) -> State.Delivered
            else -> State.Sent
        }
    }
}
