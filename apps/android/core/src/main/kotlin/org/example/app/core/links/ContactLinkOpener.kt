package org.example.app.core.links

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.example.app.core.chats.Contact

/** Who a one-time link belonged to, as the server answers a redeem. */
data class RedeemedContact(val accountId: String, val username: String, val displayName: String)

/** Why a link could not be used. The screens turn each into its message. */
enum class LinkProblem { NotALink, OtherServer, OwnLink, Expired, RateLimited, Failed,
    /** Making a link: 20 unused links already exist. */
    TooManyLinks,
}

class LinkProblemException(val problem: LinkProblem) : Exception(problem.name)

sealed class LinkOutcome {
    /** Show the add-contact confirmation; [redeemed] is set when the link was a one-time one. */
    data class Confirm(val username: String, val redeemed: RedeemedContact?) : LinkOutcome()
    /** Already a contact: open the chat. */
    data class OpenChat(val contactId: String) : LinkOutcome()
    data class Problem(val problem: LinkProblem) : LinkOutcome()
}

/**
 * Opens a link someone followed, pasted or scanned. Nothing is sent for a link that is not ours; a one-time link is
 * redeemed exactly once, here, and its token goes no further than the [redeem] call (never into an outcome).
 */
class ContactLinkOpener(
    private val serverHost: () -> String,
    /** The signed-in person's username ("" when not known yet). */
    private val myUsername: () -> String,
    private val contacts: () -> List<Contact>,
    private val redeem: suspend (token: String) -> RedeemedContact,
) {
    suspend fun open(url: String): LinkOutcome = when (val link = ContactLink.parse(url, serverHost())) {
        ContactLink.NotALink -> LinkOutcome.Problem(LinkProblem.NotALink)
        ContactLink.OtherServer -> LinkOutcome.Problem(LinkProblem.OtherServer)
        is ContactLink.User ->
            if (isMe(link.username)) LinkOutcome.Problem(LinkProblem.OwnLink)
            else known { it.username == link.username } ?: LinkOutcome.Confirm(link.username, null)
        is ContactLink.Invite -> {
            val who = try {
                redeem(link.token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkProblemException) {
                return LinkOutcome.Problem(e.problem)
            } catch (_: Exception) {
                return LinkOutcome.Problem(LinkProblem.Failed)
            }
            if (isMe(who.username)) return LinkOutcome.Problem(LinkProblem.OwnLink)
            known { it.id == who.accountId } ?: LinkOutcome.Confirm(who.username, who)
        }
    }

    private fun isMe(username: String) = myUsername().isNotBlank() && myUsername() == username

    private fun known(match: (Contact) -> Boolean): LinkOutcome? =
        contacts().firstOrNull(match)?.let { LinkOutcome.OpenChat(it.id) }
}

/**
 * The one link waiting for the app to be ready (unlocked and signed in): a newer link replaces an older one, and it
 * is handed out once. The link may hold a secret, so it is never printed.
 */
class PendingLink {
    private val link = MutableStateFlow<String?>(null)
    val value: StateFlow<String?> get() = link

    fun offer(url: String?) { link.value = url?.takeIf { it.isNotBlank() } }

    /** The waiting link, cleared, when [ready]; otherwise null and it stays. */
    fun take(ready: Boolean): String? {
        if (!ready) return null
        val url = link.value ?: return null
        link.value = null
        return url
    }

    override fun toString() = "PendingLink(${if (link.value == null) "empty" else "waiting"})"
}
