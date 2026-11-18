package org.example.app.core.links

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.example.app.core.chats.Contact

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class LinkFlowTest {
    private val token = "AbCdEfGhIjKlMnOpQrSt-_"
    private var host = "home.example.org"
    private var gen = 0
    private var gate: CompletableDeferred<Unit>? = null
    private val redeemed = mutableListOf<String>()
    private val jobs = mutableListOf<Job>()

    private val opener = ContactLinkOpener({ host }, { listOf(Contact("acc-bob", "Bob", "bob")) }) { t ->
        redeemed += t
        gate?.await()
        RedeemedContact("acc-cy", "cy", "Cy")
    }
    private val flow = LinkFlow(opener, { gen }) { block -> jobs += GlobalScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { block() } }
    private fun settle() = runBlocking { jobs.forEach { it.join() } }

    @Test fun theHostIsReadWhenTheLinkIsHandledNotWhenItArrived() {
        flow.pending.offer("https://other.example.net/u/alice") // arrived signed out
        host = "other.example.net" // then the person signs in to that server
        flow.start(ready = true); settle()
        assertEquals(LinkOutcome.Confirm("alice", null), flow.outcome.value)
    }

    @Test fun aLinkForTheOldServerIsRefusedAfterSignInToAnother() {
        flow.pending.offer("https://home.example.org/c/$token")
        host = "other.example.net"
        flow.start(ready = true); settle()
        assertEquals(LinkOutcome.Problem(LinkProblem.OtherServer), flow.outcome.value)
        assertEquals(emptyList(), redeemed)
    }

    @Test fun nothingStartsWhileNotReady() {
        flow.pending.offer("https://home.example.org/c/$token")
        flow.start(ready = false); settle()
        assertEquals(emptyList(), redeemed)
        assertNull(flow.outcome.value)
        flow.start(ready = true); settle()
        assertEquals(listOf(token), redeemed)
    }

    @Test fun signOutDuringARedeemDiscardsTheResult() {
        gate = CompletableDeferred()
        flow.pending.offer("https://home.example.org/c/$token")
        flow.start(ready = true)
        gen++ // signed out while the call is out
        gate!!.complete(Unit); settle()
        assertNull(flow.outcome.value)
        assertEquals(false, flow.busy.value)
    }

    @Test fun anOutcomeWaitsForTheUnlockAndIsNotLostByALock() {
        flow.pending.offer("https://home.example.org/u/bob")
        flow.start(ready = true); settle()
        assertNull(flow.takeOutcome(ready = false))
        assertEquals(LinkOutcome.OpenChat("acc-bob"), flow.takeOutcome(ready = true))
        assertNull(flow.takeOutcome(ready = true))
    }

    @Test fun noSecondLinkWhileOneIsOpeningOrWaitingToBeShown() {
        gate = CompletableDeferred()
        flow.pending.offer("https://home.example.org/c/$token")
        flow.start(ready = true)
        flow.pending.offer("https://home.example.org/c/ZZZZZZZZZZZZZZZZZZZZZZ")
        flow.start(ready = true) // first still opening
        assertEquals(listOf(token), redeemed)
        gate!!.complete(Unit); settle()
        flow.start(ready = true) // first's confirmation not shown yet
        assertEquals(listOf(token), redeemed)
        flow.takeOutcome(true)
        flow.start(ready = true); settle()
        assertEquals(listOf(token, "ZZZZZZZZZZZZZZZZZZZZZZ"), redeemed)
    }

    @Test fun accountEndKeepsTheWaitingLinkButDropsTheOldOutcome() {
        flow.pending.offer("https://home.example.org/u/alice")
        flow.start(ready = true); settle()
        flow.pending.offer("https://home.example.org/u/carol")
        flow.accountEnded()
        assertNull(flow.outcome.value)
        flow.start(ready = true); settle()
        assertEquals(LinkOutcome.Confirm("carol", null), flow.outcome.value)
    }
}
