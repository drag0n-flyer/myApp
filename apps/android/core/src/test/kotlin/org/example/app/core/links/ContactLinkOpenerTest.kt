package org.example.app.core.links

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.example.app.core.chats.Contact

class ContactLinkOpenerTest {
    private val token = "AbCdEfGhIjKlMnOpQrSt-_"
    private val bob = Contact("acc-bob", "Bob", "bob")
    private var contacts = listOf(bob)
    private var redeemed = mutableListOf<String>()
    private var redeemResult: () -> RedeemedContact = { RedeemedContact("acc-cy", "cy", "Cy") }

    private fun opener() = ContactLinkOpener(
        serverHost = { "home.example.org" },
        contacts = { contacts },
        redeem = { t -> redeemed += t; redeemResult() },
    )

    private fun open(url: String) = runBlocking { opener().open(url) }

    @Test fun userLinkAsksForConfirmation() =
        assertEquals(LinkOutcome.Confirm("alice", null), open("https://home.example.org/u/alice"))

    @Test fun userLinkOfExistingContactOpensTheChat() =
        assertEquals(LinkOutcome.OpenChat("acc-bob"), open("https://home.example.org/u/bob"))

    @Test fun inviteIsRedeemedThenConfirmed() {
        assertEquals(LinkOutcome.Confirm("cy", RedeemedContact("acc-cy", "cy", "Cy")), open("https://home.example.org/c/$token"))
        assertEquals(listOf(token), redeemed)
    }

    @Test fun inviteOfExistingContactOpensTheChat() {
        redeemResult = { RedeemedContact("acc-bob", "bob", "Bob") }
        assertEquals(LinkOutcome.OpenChat("acc-bob"), open("https://home.example.org/c/$token"))
    }

    @Test fun otherServerSendsNothing() {
        assertEquals(LinkOutcome.Problem(LinkProblem.OtherServer), open("https://elsewhere.example.net/c/$token"))
        assertEquals(LinkOutcome.Problem(LinkProblem.OtherServer), open("https://elsewhere.example.net/u/alice"))
        assertEquals(emptyList(), redeemed)
    }

    @Test fun notALinkSendsNothing() {
        assertEquals(LinkOutcome.Problem(LinkProblem.NotALink), open("hello"))
        assertEquals(LinkOutcome.Problem(LinkProblem.NotALink), open("http://home.example.org/c/$token"))
        assertEquals(emptyList(), redeemed)
    }

    @Test fun serverRefusalsKeepTheirMeaning() {
        for (p in listOf(LinkProblem.OwnLink, LinkProblem.Expired, LinkProblem.RateLimited, LinkProblem.Failed)) {
            redeemResult = { throw LinkProblemException(p) }
            assertEquals(LinkOutcome.Problem(p), open("https://home.example.org/c/$token"))
        }
    }

    @Test fun anUnexpectedFailureIsAProblemNotACrash() {
        redeemResult = { throw IllegalStateException("boom") }
        assertEquals(LinkOutcome.Problem(LinkProblem.Failed), open("https://home.example.org/c/$token"))
    }

    @Test fun theOutcomeNeverHoldsTheToken() {
        val text = open("https://home.example.org/c/$token").toString()
        assertEquals(false, text.contains(token))
    }

    // ---- The one pending link

    @Test fun pendingKeepsOnlyTheLatest() {
        val p = PendingLink()
        p.offer("https://home.example.org/u/a1x")
        p.offer("https://home.example.org/u/b2y")
        assertEquals("https://home.example.org/u/b2y", p.take(ready = true))
        assertNull(p.take(ready = true))
    }

    @Test fun pendingWaitsUntilReadyAndIsHandledOnce() {
        val p = PendingLink()
        p.offer("https://home.example.org/u/alice")
        assertNull(p.take(ready = false))
        assertEquals("https://home.example.org/u/alice", p.value.value)
        assertEquals("https://home.example.org/u/alice", p.take(ready = true))
        assertNull(p.take(ready = true))
        assertNull(p.value.value)
    }

    @Test fun pendingToStringHidesTheLink() {
        val p = PendingLink()
        p.offer("https://home.example.org/c/$token")
        assertEquals(false, p.toString().contains(token))
    }
}
