package org.example.app.ui.chat

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.app.core.calls.NetworkType
import org.example.app.core.chats.Chat
import org.example.app.core.chats.ChatEncryption
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.DeliveryStatus
import org.example.app.core.chats.DraftKeeper
import org.example.app.core.chats.HistoryPager
import org.example.app.core.chats.Message
import org.example.app.core.chats.MessageContent
import org.example.app.core.chats.MessageHit
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.core.settings.AutoDownloadSettings
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelActionsTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private val base = Instant.parse("2020-01-01T10:00:00Z")

    private fun message(
        n: Int, sender: String = "riya", status: DeliveryStatus = DeliveryStatus.Read,
        content: MessageContent = MessageContent.Text("message $n"),
    ) = Message("m$n", sender, base.plusSeconds(n.toLong()), content, status, null, emptyList())

    /** The sample store holds only [loaded] in the chat; `messages(before:)` serves [all], like a database behind a cache. */
    private class PagedStore(
        val inner: SampleChatStore,
        private val all: List<Message>,
        private val whileLoading: () -> Unit = {},
    ) : ChatStore by inner {
        var pageCalls = 0
        val actions = mutableListOf<String>()

        override fun messages(chatId: String, before: String?, limit: Int): List<Message> {
            pageCalls++
            whileLoading()
            val end = if (before == null) all.size else all.indexOfFirst { it.id == before }.let { if (it < 0) return emptyList() else it }
            return all.subList(maxOf(0, end - limit), end)
        }

        override fun react(emoji: String, messageId: String, chatId: String) { actions += "react:$emoji:$messageId" }
        override fun edit(messageId: String, chatId: String, text: String) { actions += "edit:$messageId:$text" }
        override fun delete(messageId: String, chatId: String) { actions += "delete:$messageId" }
        override fun retrySend(messageId: String) { actions += "retry:$messageId" }
        override fun send(content: MessageContent, chatId: String, replyTo: String?) { actions += "send:$chatId:${(content as? MessageContent.Text)?.text}:$replyTo" }
    }

    private fun sampleWith(loaded: List<Message>, unread: Int = 0): SampleChatStore {
        val data = SampleData.make(Instant.now())
        val chat = Chat("chat-riya", "riya", ChatEncryption.EndToEnd, emptySet(), unread, loaded, false)
        return SampleChatStore(data.copy(chats = listOf(chat)))
    }

    private fun vm(
        store: ChatStore, drafts: DraftKeeper = DraftKeeper(), now: () -> Long = { 0L },
    ) = ChatViewModel(store, "chat-riya", NetworkType.Wifi, drafts, Dispatchers.Unconfined, now) { AutoDownloadSettings() }

    /** 180 messages, the newest 50 cached. */
    private fun pagedChat(): PagedStore {
        val all = (0 until 180).map { message(it) }
        return PagedStore(sampleWith(all.takeLast(50)), all)
    }

    // Review focus 1: a target older than the loaded page.

    @Test
    fun aMessageOlderThanThePageIsFoundByLoadingOlderPages() = runTest {
        val store = pagedChat()
        val vm = vm(store)
        assertEquals(50, vm.state.value.messages.size)
        assertTrue(vm.ensureLoaded("m10"))
        val ids = vm.state.value.messages.map { it.id }
        assertTrue("m10" in ids)
        assertEquals(ids.distinct(), ids)
        assertEquals(ids.sortedBy { it.removePrefix("m").toInt() }, ids)
        // Found after three pages (50 + 3 * 50 reaches m0..m9 only on the fourth), and it stops once found.
        assertEquals(3, store.pageCalls)
    }

    @Test
    fun anAlreadyLoadedMessageNeedsNoLoad() = runTest {
        val store = pagedChat()
        val vm = vm(store)
        assertTrue(vm.ensureLoaded("m170"))
        assertEquals(0, store.pageCalls)
    }

    @Test
    fun whenHistoryEndsFirstNothingCrashesAndLoadingStops() = runTest {
        val store = pagedChat()
        val vm = vm(store)
        assertFalse(vm.ensureLoaded("no-such-message"))
        // Everything that exists is loaded, once, in order; asking again does not hit the store.
        assertEquals((0 until 180).map { "m$it" }, vm.state.value.messages.map { it.id })
        val calls = store.pageCalls
        assertFalse(vm.ensureLoaded("still-missing"))
        assertEquals(calls, store.pageCalls)
        assertFalse(vm.loadOlder())
    }

    @Test
    fun aStoreThatKeepsReturningTheSamePageDoesNotLoopForever() = runTest {
        val loaded = (100 until 150).map { message(it) }
        val stuck = object : ChatStore by sampleWith(loaded) {
            // A full page every time, always the same one.
            override fun messages(chatId: String, before: String?, limit: Int) = loaded
        }
        val vm = vm(stuck)
        assertFalse(vm.ensureLoaded("missing"))
        assertEquals(50, vm.state.value.messages.size)
    }

    // Review focus 2: messages arriving while an older page loads or the user reads old messages.

    @Test
    fun aMessageArrivingDuringAPageLoadIsNotDuplicatedOrLost() = runTest {
        val all = (0 until 180).map { message(it) }
        lateinit var store: PagedStore
        var arrived = false
        store = PagedStore(sampleWith(all.takeLast(50)), all, whileLoading = {
            if (!arrived) {
                arrived = true
                store.inner.receive(MessageContent.Text("fresh"), store.inner.contact("riya")!!)
            }
        })
        val vm = vm(store)
        assertTrue(vm.loadOlder())
        val messages = vm.state.value.messages
        assertEquals(50 + 50 + 1, messages.size)
        assertEquals(messages.map { it.id }.distinct(), messages.map { it.id })
        assertEquals("fresh", (messages.last().content as MessageContent.Text).text)
        assertEquals("m80", messages.first().id)
    }

    @Test
    fun aNewMessageAfterPagingKeepsEveryLoadedMessage() = runTest {
        val store = pagedChat()
        val vm = vm(store)
        vm.loadOlder()
        store.inner.receive(MessageContent.Text("later"), store.inner.contact("riya")!!)
        assertEquals(101, vm.state.value.messages.size)
        assertEquals("m80", vm.state.value.messages.first().id)
    }

    @Test
    fun anEditedMessageReplacesItsOlderLoadedCopy() = runTest {
        val all = (0 until 120).map { message(it, sender = "me") }
        val store = PagedStore(sampleWith(all.takeLast(50)), all)
        val vm = vm(store)
        vm.loadOlder()
        // The cache copy changes (edit from another device) and overlaps what the page already holds.
        store.inner.edit("m100", "chat-riya", "changed")
        val m100 = vm.state.value.messages.single { it.id == "m100" }
        assertEquals(MessageContent.Text("changed"), m100.content)
    }

    @Test
    fun scrollingNearTheOldestMessageLoadsOnePageAtATime() = runTest {
        val store = pagedChat()
        val vm = vm(store)
        vm.maybeLoadOlder(oldestVisibleIndex = 30)
        assertEquals(0, store.pageCalls)
        vm.maybeLoadOlder(oldestVisibleIndex = 3)
        assertEquals(1, store.pageCalls)
        assertEquals(100, vm.state.value.messages.size)
        assertEquals(HistoryPager.PAGE_SIZE, 50)
    }

    // Review focus 3: acting on a message that vanished or changed under the menu.

    @Test
    fun anActionOnAVanishedMessageIsDropped() = runTest {
        val store = pagedChat()
        val vm = vm(store)
        assertFalse(vm.react("👍", "gone"))
        assertFalse(vm.delete("gone"))
        assertFalse(vm.edit("gone", "x"))
        assertFalse(vm.retry("gone"))
        assertEquals(emptyList(), store.actions)
    }

    @Test
    fun anActionOnAMessageDeletedMeanwhileIsDropped() = runTest {
        val own = message(1, "me", DeliveryStatus.Sent, MessageContent.Deleted)
        val store = PagedStore(sampleWith(listOf(own)), listOf(own))
        val vm = vm(store)
        assertFalse(vm.react("👍", "m1"))
        assertFalse(vm.edit("m1", "x"))
        assertFalse(vm.delete("m1"))
        assertEquals(emptyList(), store.actions)
    }

    @Test
    fun anEditOfAMessageThatFailedMeanwhileIsDroppedAndNothingIsSent() = runTest {
        val failed = message(1, "me", DeliveryStatus.Failed)
        val store = PagedStore(sampleWith(listOf(failed)), listOf(failed))
        val vm = vm(store)
        assertFalse(vm.edit("m1", "new text"))
        assertEquals(emptyList(), store.actions)
        // It can be retried, which is the way out.
        assertTrue(vm.retry("m1"))
        assertEquals(listOf("retry:m1"), store.actions)
    }

    @Test
    fun retryIsOnlyForFailedOwnMessages() = runTest {
        val sent = message(1, "me", DeliveryStatus.Sent)
        val theirs = message(2, "riya")
        val store = PagedStore(sampleWith(listOf(sent, theirs)), listOf(sent, theirs))
        val vm = vm(store)
        assertFalse(vm.retry("m1"))
        assertFalse(vm.retry("m2"))
        assertEquals(emptyList(), store.actions)
    }

    @Test
    fun reactingTwiceQuicklyIsAppliedOnce() = runTest {
        val m = message(1, "riya")
        val store = PagedStore(sampleWith(listOf(m)), listOf(m))
        var now = 1_000L
        val vm = vm(store, now = { now })
        assertTrue(vm.react("👍", "m1"))
        now += 100
        assertFalse(vm.react("👍", "m1"))
        assertFalse(vm.react("❤️", "m1"))
        now += 1_000
        assertTrue(vm.react("👍", "m1"))
        assertEquals(listOf("react:👍:m1", "react:👍:m1"), store.actions)
    }

    @Test
    fun editSendsTheNewTextOnceAndAnUnchangedOrBlankEditSendsNothing() = runTest {
        val own = message(1, "me", DeliveryStatus.Sent)
        val store = PagedStore(sampleWith(listOf(own)), listOf(own))
        val vm = vm(store)
        assertTrue(vm.edit("m1", "  better  "))
        assertTrue(vm.edit("m1", "message 1"))
        assertFalse(vm.edit("m1", "   "))
        assertEquals(listOf("edit:m1:better"), store.actions)
    }

    @Test
    fun forwardOpensTheTargetChatAndSendsTheText() = runTest {
        val store = pagedChat()
        val vm = vm(store)
        val target = vm.forward("hello", "riya")
        assertEquals("chat-riya", target)
        assertEquals(listOf("send:chat-riya:hello:null"), store.actions)
        assertNull(vm.forward("   ", "riya"))
    }

    // Drafts and the unread line.

    @Test
    fun aDraftSurvivesLeavingAndReturning() = runTest {
        val keeper = DraftKeeper()
        val first = vm(pagedChat(), keeper)
        first.setDraft("half a thought")
        val second = vm(pagedChat(), keeper)
        assertEquals("half a thought", second.draft())
        second.setDraft("")
        assertEquals("", vm(pagedChat(), keeper).draft())
    }

    @Test
    fun theUnreadLineIsFixedWhenTheChatOpensEvenThoughOpeningMarksItRead() = runTest {
        val all = (0 until 10).map { message(it) }
        val store = sampleWith(all, unread = 3)
        val vm = vm(store)
        assertEquals("m7", vm.firstUnreadId)
        assertEquals(0, store.chat("chat-riya")!!.unreadCount)
        assertEquals("m7", vm.firstUnreadId)
    }

    @Test
    fun searchIsLimitedToThisChat() = runTest {
        val m = message(1, "riya")
        val inner = sampleWith(listOf(m))
        val store = object : ChatStore by inner {
            override fun searchMessages(query: String, limit: Int) = listOf(
                MessageHit("chat-riya", "m1", "here", base), MessageHit("chat-other", "x", "elsewhere", base),
            )
        }
        val vm = vm(store)
        assertEquals(listOf("m1"), vm.search("h").map { it.messageId })
        assertEquals(emptyList(), vm.search("   "))
    }

    // The store's cached page is the newest 50: older messages slide out of it as new ones arrive.

    private class WindowStore(inner: SampleChatStore, val all: MutableList<Message>) : ChatStore by inner {
        private val base = inner.chat("chat-riya")!!
        private val flow = MutableStateFlow(listOf(base.copy(messages = all.takeLast(50))))
        var reads = 0
        override val chats: StateFlow<List<Chat>> get() = flow
        override fun chat(id: String): Chat? = flow.value.firstOrNull { it.id == id }
        override fun messages(chatId: String, before: String?, limit: Int): List<Message> {
            reads++
            val end = if (before == null) all.size else all.indexOfFirst { it.id == before }.let { if (it < 0) return emptyList() else it }
            return all.subList(maxOf(0, end - limit), end).toList()
        }
        fun publish() { flow.value = listOf(base.copy(messages = all.takeLast(50))) }
        /** The chat changes but its messages do not (an unread count, say). */
        fun publishUnread(n: Int) { flow.value = listOf(base.copy(messages = all.takeLast(50), unreadCount = n)) }

        // Like the real store: a change to a message outside the cached page does not make the chat flow emit.
        private fun change(id: String, f: (Message) -> Message) {
            val i = all.indexOfFirst { it.id == id }
            if (i >= 0) all[i] = f(all[i])
            if (all.takeLast(50).any { it.id == id }) publish()
        }
        override fun react(emoji: String, messageId: String, chatId: String) = change(messageId) {
            it.copy(reactions = if (emoji in it.reactions) it.reactions - emoji else it.reactions + emoji)
        }
        override fun edit(messageId: String, chatId: String, text: String) = change(messageId) {
            it.copy(content = MessageContent.Text(text), editedAt = Instant.now())
        }
        override fun delete(messageId: String, chatId: String) = change(messageId) { it.copy(content = MessageContent.Deleted, reactions = emptyList()) }
    }

    private fun windowed(count: Int, sender: String = "riya"): WindowStore {
        val all = (0 until count).map { message(it, sender = sender) }.toMutableList()
        return WindowStore(sampleWith(all.takeLast(50)), all)
    }

    private fun WindowStore.arrive(n: Int) {
        all += message(1000 + n)
        publish()
    }

    @Test
    fun sixtyArrivalsLeaveNoGapAndTheUnseenCountKeepsRising() = runTest {
        val store = windowed(60)
        val vm = vm(store)
        val lastSeen = vm.state.value.messages.last().id
        repeat(60) { store.arrive(it) }
        val ids = vm.state.value.messages.map { it.id }
        assertEquals(store.all.drop(10).map { it.id }, ids)
        assertEquals(60, org.example.app.core.chats.ChatTimeline.unseenCount(vm.state.value.messages, lastSeen))
    }

    @Test
    fun sixtyArrivalsWithOlderPagesLoadedLeaveNoGapEither() = runTest {
        val store = windowed(180)
        val vm = vm(store)
        vm.loadOlder()
        repeat(60) { store.arrive(it) }
        assertEquals(store.all.drop(80).map { it.id }, vm.state.value.messages.map { it.id })
    }

    @Test
    fun aDeletedOlderMessageStopsShowingItsTextOnTheNextChange() = runTest {
        val store = windowed(180)
        val vm = vm(store)
        vm.loadOlder()
        val i = store.all.indexOfFirst { it.id == "m100" }
        store.all[i] = store.all[i].copy(content = MessageContent.Deleted)
        store.arrive(0)
        assertEquals(MessageContent.Deleted, vm.state.value.messages.single { it.id == "m100" }.content)
    }

    @Test
    fun anEmptyNewestPageEndsTheJumpWithNotFound() = runTest {
        val empty = object : ChatStore by sampleWith(emptyList()) {
            override fun messages(chatId: String, before: String?, limit: Int) = emptyList<Message>()
        }
        assertFalse(vm(empty).ensureLoaded("anything"))
    }

    @Test
    fun searchWidensTheLimitWhenOtherChatsFillTheTopHits() = runTest {
        val others = (0 until 250).map { MessageHit("chat-other", "o$it", "needle $it", base) }
        val mine = (0 until 3).map { MessageHit("chat-riya", "r$it", "needle mine $it", base) }
        val everything = others + mine
        val inner = sampleWith(listOf(message(1)))
        val store = object : ChatStore by inner {
            override fun searchMessages(query: String, limit: Int) = everything.take(limit)
        }
        assertEquals(listOf("r0", "r1", "r2"), vm(store).search("needle").map { it.messageId })
    }

    // Own actions on a message outside the cached page show at once, though the chat flow does not emit.

    @Test
    fun deletingAnOldMessageShowsAtOnce() = runTest {
        val store = windowed(180, sender = "me")
        val vm = vm(store)
        vm.loadOlder()
        assertTrue(vm.delete("m100"))
        assertEquals(MessageContent.Deleted, vm.state.value.messages.single { it.id == "m100" }.content)
    }

    @Test
    fun reactingToAnOldMessageShowsAtOnceAndASecondTapTakesItBack() = runTest {
        val store = windowed(180)
        var now = 0L
        val vm = vm(store, now = { now })
        vm.loadOlder()
        assertTrue(vm.react("❤️", "m100"))
        assertEquals(listOf("❤️"), vm.state.value.messages.single { it.id == "m100" }.reactions)
        now += 1_000
        assertTrue(vm.react("❤️", "m100"))
        assertEquals(emptyList(), vm.state.value.messages.single { it.id == "m100" }.reactions)
    }

    @Test
    fun editingAnOldMessageShowsAtOnce() = runTest {
        val store = windowed(180, sender = "me")
        val vm = vm(store)
        vm.loadOlder()
        assertTrue(vm.edit("m100", "better words"))
        val m = vm.state.value.messages.single { it.id == "m100" }
        assertEquals(MessageContent.Text("better words"), m.content)
        assertTrue(m.editedAt != null)
    }

    @Test
    fun theReReadWaitsForTheStoresWritesToLand() = runTest {
        val order = mutableListOf<String>()
        val inner = windowed(180, sender = "me")
        val store = object : ChatStore by inner {
            override fun delete(messageId: String, chatId: String) { order += "delete"; inner.delete(messageId, chatId) }
            override suspend fun awaitWrites() { order += "await" }
            override fun messages(chatId: String, before: String?, limit: Int): List<Message> { order += "read"; return inner.messages(chatId, before, limit) }
        }
        val vm = vm(store)
        vm.loadOlder()
        order.clear()
        vm.delete("m100")
        assertEquals(listOf("delete", "await", "read"), order)
    }

    // Refreshing the loaded older range

    @Test
    fun tenEmissionsWithUnchangedMessagesDoNoReads() = runTest {
        val store = windowed(180)
        val vm = vm(store)
        vm.loadOlder()
        val before = store.reads
        repeat(10) { store.publishUnread(it + 1) }
        assertEquals(before, store.reads)
        store.arrive(0)
        assertEquals(before + 1, store.reads)
        assertEquals(181 - 80, vm.state.value.messages.size)
    }

    @Test
    fun theLoadedOlderRangeIsTrimmedToTheNewest500AndReloadsOnDemand() = runTest {
        val store = windowed(1_500)
        val vm = vm(store)
        repeat(20) { vm.loadOlder() }
        assertEquals(50 + 1_000, vm.state.value.messages.size)
        vm.loadOlder()
        val ids = vm.state.value.messages.map { it.id.removePrefix("m").toInt() }
        assertEquals(50 + 500, ids.size)
        assertEquals((950 until 1_500).toList(), ids)
        // The trimmed pages come back on demand.
        vm.loadOlder()
        assertEquals((900 until 1_500).toList(), vm.state.value.messages.map { it.id.removePrefix("m").toInt() })
    }

    @Test
    fun aJumpIsNotTrimmedWhileItLoads() = runTest {
        val store = windowed(1_500)
        val vm = vm(store)
        assertTrue(vm.ensureLoaded("m3"))
        assertTrue(vm.state.value.messages.size > 1_400)
    }

    // Search

    private class CountingSearch(val perPass: (Int) -> Int, inner: SampleChatStore) : ChatStore by inner {
        val limits = mutableListOf<Int>()
        override fun searchMessages(query: String, limit: Int): List<MessageHit> {
            limits += limit
            return (0 until perPass(limit)).map { MessageHit("chat-other", "o$it", "x", null) }
        }
    }

    @Test
    fun searchStopsWideningWhenAPassReturnsFewerHitsThanItsLimit() = runTest {
        val store = CountingSearch({ 10 }, sampleWith(listOf(message(1))))
        vm(store).search("x")
        assertEquals(listOf(200), store.limits)
    }

    @Test
    fun searchNeverAsksForMoreThan1000() = runTest {
        val store = CountingSearch({ it }, sampleWith(listOf(message(1))))
        vm(store).search("x")
        assertEquals(listOf(200, 1_000), store.limits)
    }

    @Test
    fun searchesRunOneAtATime() {
        val active = java.util.concurrent.atomic.AtomicInteger()
        val most = java.util.concurrent.atomic.AtomicInteger()
        val inner = sampleWith(listOf(message(1)))
        val store = object : ChatStore by inner {
            override fun searchMessages(query: String, limit: Int): List<MessageHit> {
                most.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
                Thread.sleep(60)
                active.decrementAndGet()
                return emptyList()
            }
        }
        val vm = ChatViewModel(store, "chat-riya", NetworkType.Wifi, DraftKeeper(), Dispatchers.Default, { 0L }) { AutoDownloadSettings() }
        kotlinx.coroutines.runBlocking {
            (1..4).map { n -> this.async { vm.search("q$n") } }.forEach { it.await() }
        }
        assertEquals(1, most.get())
    }

    // Highlight, request bar, drafts

    @Test
    fun aHighlightIsConsumedOnce() = runTest {
        val vm = vm(pagedChat())
        assertTrue(vm.takeHighlight("m1"))
        assertFalse(vm.takeHighlight("m1"))
        assertTrue(vm.takeHighlight("m2"))
    }

    @Test
    fun blockTargetIsNullWhenTheChatCannotBeResolved() = runTest {
        val inner = sampleWith(listOf(message(1)))
        assertEquals("riya", vm(inner).blockTarget())
        val gone = object : ChatStore by inner { override fun chat(id: String): Chat? = null }
        assertNull(vm(gone).blockTarget())
    }

    @Test
    fun draftsAreKeyedBySignedInAccountThroughTheDefaultWiring() {
        fun store(account: String): ChatStore {
            val inner = sampleWith(listOf(message(1)))
            return object : ChatStore by inner { override val me = org.example.app.core.chats.Contact(account, account, account) }
        }
        fun open(account: String) = ChatViewModel(store(account), "chat-riya", NetworkType.Wifi) { AutoDownloadSettings() }
        open("acc-draft-a").setDraft("secret")
        assertEquals("secret", open("acc-draft-a").draft())
        assertEquals("", open("acc-draft-b").draft())
        // The first account's drafts are gone for good once another signed in.
        assertEquals("", open("acc-draft-a").draft())
    }
}
