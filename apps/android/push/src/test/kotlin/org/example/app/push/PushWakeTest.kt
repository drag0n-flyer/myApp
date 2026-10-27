package org.example.app.push

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.app.contracts.NotificationOutcome
import org.example.app.contracts.PushPadding
import org.example.app.contracts.PushPayloadHandler
import org.example.app.contracts.RingOutcome
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private fun pad(payload: ByteArray): ByteArray {
    val size = PushPadding.BUCKETS.first { it >= payload.size + 2 }
    val out = ByteArray(size)
    out[0] = (payload.size shr 8).toByte()
    out[1] = payload.size.toByte()
    payload.copyInto(out, 2)
    return out
}

private class FakeHandler : PushPayloadHandler {
    var message: NotificationOutcome = NotificationOutcome.Nothing
    var call: RingOutcome = RingOutcome.Drop
    var delayMs = 0L
    val messagePayloads = mutableListOf<ByteArray>()
    val callPayloads = mutableListOf<ByteArray>()

    override suspend fun onMessagePush(padded: ByteArray): NotificationOutcome {
        if (delayMs > 0) delay(delayMs)
        messagePayloads += padded
        return message
    }

    override suspend fun onCallPush(padded: ByteArray): RingOutcome {
        callPayloads += padded
        return call
    }
}

private class FakeRing : IncomingCallSink {
    val rung = mutableListOf<RingOutcome.Ring>()
    override fun ring(outcome: RingOutcome.Ring) { rung += outcome }
}

private class FakeEffects : PushEffects {
    val shown = mutableListOf<NotificationOutcome>()
    var fetches = 0
    val fetchPriorities = mutableListOf<Boolean>()
    val missedShown = mutableListOf<Pair<String, String>>()
    var strangerShown = 0
    val missed = mutableListOf<Pair<String, String>>()
    val pushes = mutableListOf<Pair<Boolean, Boolean>>()
    override fun show(outcome: NotificationOutcome) { shown += outcome }
    override fun enqueueInboxFetch(highPriority: Boolean) { fetches++; fetchPriorities += highPriority }
    override fun showMissedCall(chatId: String, callerName: String) { missedShown += chatId to callerName }
    override fun showStrangerMissedCall() { strangerShown++ }
    override suspend fun recordMissedCall(callId: String, fromAccountId: String) { missed += callId to fromAccountId }
    override fun pushHandled(highPriority: Boolean, showedSomething: Boolean) { pushes += highPriority to showedSomething }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PushWakeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val handler = FakeHandler()
    private val ring = FakeRing()
    private val effects = FakeEffects()
    private fun dispatcher(budgetMs: Long = 8_000) = PushDispatcher(handler, ring, effects, budgetMs)
    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun unpadsEverySharedVector() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/protocol/test-vectors/push-padding.json").exists()) dir = dir.parentFile
        val root = Json.parseToJsonElement(File(checkNotNull(dir), "docs/protocol/test-vectors/push-padding.json").readText()).jsonObject
        val cases = root["cases"]!!.jsonArray
        assertTrue(cases.size >= 7)
        for (c in cases) {
            val o = c.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            // The expected payload is what the padded bytes' own 2-byte length prefix says (an over-limit payload
            // is padded as length 0, whatever payloadHex holds), so it does not depend on the case name.
            val padded = hex(o["paddedHex"]!!.jsonPrimitive.content)
            val length = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
            val expected = if (length == 0) ByteArray(0) else hex(o["payloadHex"]!!.jsonPrimitive.content)
            assertEquals(length, expected.size, "$name: payloadHex length must match the prefix")
            assertContentEquals(expected, PushPadding.unpad(hex(o["paddedHex"]!!.jsonPrimitive.content)), o["name"].toString())
        }
        for (n in root["negative"]!!.jsonArray) {
            assertNull(PushPadding.unpad(hex(n.jsonObject["paddedHex"]!!.jsonPrimitive.content)), n.jsonObject["name"].toString())
        }
    }

    @Test
    fun messageGoesToHandlerAndShows() = runTest {
        val outcome = NotificationOutcome.Message("c1", "Ana", "hi")
        handler.message = outcome
        val padded = pad("x".encodeToByteArray())
        dispatcher().onFcm(mapOf("k" to "message", "d" to b64(padded)), highPriority = true)
        assertContentEquals(padded, handler.messagePayloads.single())
        assertEquals(listOf<NotificationOutcome>(outcome), effects.shown)
        assertEquals(listOf(true to true), effects.pushes)
    }

    @Test
    fun nothingShowsNothing() = runTest {
        handler.message = NotificationOutcome.Nothing
        dispatcher().onFcm(mapOf("k" to "message", "d" to b64(pad("x".encodeToByteArray()))), highPriority = true)
        assertTrue(effects.shown.isEmpty())
        assertEquals(0, effects.fetches)
        assertEquals(listOf(true to false), effects.pushes)
    }

    @Test
    fun emptyEnqueuesExpeditedFetch() = runTest {
        dispatcher().onFcm(mapOf("k" to "message", "d" to b64(pad(ByteArray(0)))), highPriority = true)
        assertEquals(1, effects.fetches)
        assertEquals(listOf(true), effects.fetchPriorities)
        assertTrue(handler.messagePayloads.isEmpty())
        assertTrue(effects.shown.isEmpty())
        assertTrue(effects.pushes.isEmpty(), "counted when the fetch ends, not at hand-off")
    }

    @Test
    fun malformedPaddingShowsGeneric() = runTest {
        dispatcher().onFcm(mapOf("k" to "message", "d" to b64(ByteArray(100))), highPriority = true)
        assertTrue(handler.messagePayloads.isEmpty())
        assertTrue(effects.shown.single() === NotificationOutcome.Generic)
    }

    @Test
    fun badBase64IsGeneric() = runTest {
        dispatcher().onFcm(mapOf("k" to "message", "d" to "!!!"), highPriority = true)
        assertTrue(effects.shown.single() === NotificationOutcome.Generic)
    }

    @Test
    fun unknownKindShowsNothingAndCountsQuiet() = runTest {
        dispatcher().onFcm(mapOf("k" to "other", "d" to b64(pad(ByteArray(1)))), highPriority = true)
        dispatcher().onFcm(mapOf("d" to b64(pad(ByteArray(1)))), highPriority = true)
        assertTrue(effects.shown.isEmpty() && handler.messagePayloads.isEmpty() && handler.callPayloads.isEmpty())
        assertEquals(listOf(true to false, true to false), effects.pushes)
    }

    @Test
    fun handlerFailureShowsGeneric() = runTest {
        val failing = object : PushPayloadHandler {
            override suspend fun onMessagePush(padded: ByteArray): NotificationOutcome = error("boom")
            override suspend fun onCallPush(padded: ByteArray): RingOutcome = error("boom")
        }
        PushDispatcher(failing, ring, effects).onFcm(mapOf("k" to "message", "d" to b64(pad(ByteArray(1)))), highPriority = true)
        assertTrue(effects.shown.single() === NotificationOutcome.Generic)
        PushDispatcher(failing, ring, effects).onFcm(mapOf("k" to "call", "d" to b64(pad(ByteArray(1)))), highPriority = true)
        assertTrue(ring.rung.isEmpty())
        assertEquals(2, effects.shown.size, "an undecryptable ring falls back to the generic notice")
        assertTrue(effects.shown.all { it === NotificationOutcome.Generic })
    }

    @Test
    fun slowHandlerFallsBackToFetchWithinBudget() = runTest {
        handler.message = NotificationOutcome.Message("c", "Ana", "hi")
        handler.delayMs = 60_000
        dispatcher(budgetMs = 8_000).onFcm(mapOf("k" to "message", "d" to b64(pad(ByteArray(1)))), highPriority = true)
        assertEquals(1, effects.fetches)
        assertTrue(effects.shown.single() === NotificationOutcome.Generic, "the budget ran out: post the generic notice")
        assertEquals(listOf(true to true), effects.pushes)
        assertEquals(8_000, testScheduler.currentTime)
    }

    @Test
    fun handlerFetchInboxOutcomeEnqueuesFetch() = runTest {
        handler.message = NotificationOutcome.FetchInbox
        dispatcher().onFcm(mapOf("k" to "message", "d" to b64(pad(ByteArray(1)))), highPriority = false)
        assertEquals(1, effects.fetches)
        assertEquals(listOf(false), effects.fetchPriorities)
        assertTrue(effects.shown.isEmpty())
        assertTrue(effects.pushes.isEmpty())
    }

    @Test
    fun contactCallRings() = runTest {
        val r = RingOutcome.Ring("call1", "acct", "Ana", video = true)
        handler.call = r
        dispatcher().onFcm(mapOf("k" to "call", "d" to b64(pad(ByteArray(1)))), highPriority = true)
        assertTrue(ring.rung.single() === r)
        assertTrue(effects.missed.isEmpty())
    }

    @Test
    fun strangerCallDoesNotRing() = runTest {
        handler.call = RingOutcome.SilentMissed("call2", "stranger")
        dispatcher().onFcm(mapOf("k" to "call", "d" to b64(pad(ByteArray(1)))), highPriority = true)
        assertTrue(ring.rung.isEmpty())
        assertEquals(listOf("call2" to "stranger"), effects.missed)
        assertEquals(1, effects.strangerShown)
        assertEquals(listOf(true to true), effects.pushes)
    }

    @Test
    fun callEndedBeforeAnswerPostsMissedCallForThatChat() = runTest {
        handler.call = RingOutcome.MissedBeforeAnswer("call3", "chat7", "Ana")
        dispatcher().onFcm(mapOf("k" to "call", "d" to b64(pad(ByteArray(1)))), highPriority = true)
        assertTrue(ring.rung.isEmpty())
        assertEquals(listOf("chat7" to "Ana"), effects.missedShown)
        assertEquals(listOf(true to true), effects.pushes)
    }

    @Test
    fun droppedAndEmptyCallFallBackToGeneric() = runTest {
        handler.call = RingOutcome.Drop
        dispatcher().onFcm(mapOf("k" to "call", "d" to b64(pad(ByteArray(1)))), highPriority = true)
        dispatcher().onFcm(mapOf("k" to "call", "d" to b64(pad(ByteArray(0)))), highPriority = true)
        assertTrue(ring.rung.isEmpty() && effects.missed.isEmpty())
        assertTrue(handler.callPayloads.size == 1)
        assertEquals(2, effects.shown.size)
        assertTrue(effects.shown.all { it === NotificationOutcome.Generic })
    }

    @Test
    fun unifiedPushKindByte() = runTest {
        val padded = pad("x".encodeToByteArray())
        handler.message = NotificationOutcome.Request("c1")
        dispatcher().onUnifiedPush(byteArrayOf(0x01) + padded)
        assertContentEquals(padded, handler.messagePayloads.single())
        assertTrue(effects.shown.single() is NotificationOutcome.Request)

        handler.call = RingOutcome.Ring("c", "a", "Ana", false)
        dispatcher().onUnifiedPush(byteArrayOf(0x02) + padded)
        assertContentEquals(padded, handler.callPayloads.single())
        assertEquals(1, ring.rung.size)

        val before = effects.pushes.size
        dispatcher().onUnifiedPush(byteArrayOf(0x07) + padded)
        dispatcher().onUnifiedPush(ByteArray(0))
        assertEquals(1, handler.messagePayloads.size)
        assertEquals(1, handler.callPayloads.size)
        assertEquals(listOf(true to false, true to false), effects.pushes.drop(before))
    }

    @Test
    fun handlerRunsUnderMlsGate() = runTest {
        var heldInside = false
        val probing = object : PushPayloadHandler {
            override suspend fun onMessagePush(padded: ByteArray): NotificationOutcome {
                // The gate is not re-entrant and a held gate cannot be taken twice.
                heldInside = kotlinx.coroutines.withTimeoutOrNull(50) { org.example.app.mls.MlsGate.withLock { } } == null
                return NotificationOutcome.Nothing
            }
            override suspend fun onCallPush(padded: ByteArray): RingOutcome = RingOutcome.Drop
        }
        PushDispatcher(probing, ring, effects).onFcm(mapOf("k" to "message", "d" to b64(pad(ByteArray(1)))), highPriority = true)
        assertTrue(heldInside)
    }

    @Test
    fun quietPushCounted() {
        val monitor = MissedPushMonitor(context)
        monitor.pushHandled(highPriority = true, showedSomething = false)
        monitor.pushHandled(highPriority = true, showedSomething = false)
        monitor.pushHandled(highPriority = true, showedSomething = true)
        monitor.pushHandled(highPriority = false, showedSomething = false)
        assertEquals(2, monitor.quietHighPriorityCount())
        assertEquals(3, monitor.highPriorityCount())
        // survives a new instance (stored on disk)
        assertEquals(2, MissedPushMonitor(context).quietHighPriorityCount())
    }

    @Test
    fun missedPushDetectedWhenInboxItemHadNoPush() {
        var now = 1_000_000L
        val monitor = MissedPushMonitor(context) { now }
        monitor.pushHandled(highPriority = true, showedSomething = true) // push at t=1_000_000
        // an item that arrived with a push nearby: fine
        monitor.noteInboxItem(arrivedAtMs = 1_000_000L - 5_000)
        assertFalse(monitor.shouldShowBatteryHint())
        // an item that arrived long before any push: missed
        now += 10 * 60_000
        monitor.noteInboxItem(arrivedAtMs = 1_000_000L + 5 * 60_000)
        assertTrue(monitor.shouldShowBatteryHint())
        monitor.markHintShown()
        assertFalse(monitor.shouldShowBatteryHint())
        monitor.noteInboxItem(arrivedAtMs = 1_000_000L + 6 * 60_000)
        assertFalse(monitor.shouldShowBatteryHint(), "one-time hint")
    }

    @Test
    fun recentItemIsNotYetMissed() {
        val monitor = MissedPushMonitor(context) { 1_000_000L }
        monitor.noteInboxItem(arrivedAtMs = 1_000_000L - 1_000)
        assertFalse(monitor.shouldShowBatteryHint())
    }

    @Test
    fun notificationsPermissionAskedAfterSignUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // API 33+, signed up, not granted, not asked yet: ask once
        assertFalse(NotificationPermission.shouldAsk(app, signedUp = false, sdkInt = 33))
        assertTrue(NotificationPermission.shouldAsk(app, signedUp = true, sdkInt = 33))
        NotificationPermission.markAsked(app)
        assertFalse(NotificationPermission.shouldAsk(app, signedUp = true, sdkInt = 33))
    }

    @Test
    fun noPermissionAskBeforeApi33OrWhenGranted() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(NotificationPermission.shouldAsk(app, signedUp = true, sdkInt = 32))
        shadowOf(app).grantPermissions("android.permission.POST_NOTIFICATIONS")
        assertFalse(NotificationPermission.shouldAsk(app, signedUp = true, sdkInt = 33))
    }

    // ---- notifications

    private fun active() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    @Test
    fun channelsExist() {
        NotificationChannels.ensure(context)
        val ids = context.getSystemService(NotificationManager::class.java).notificationChannels.map { it.id }.toSet()
        assertEquals(setOf("messages", "requests", "calls", "devices", "service"), ids)
        val nm = context.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel("service").importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel("messages").importance)
    }

    @Test
    fun messageOutcomeShowsNotification() {
        shadowOf(context as Application).grantPermissions("android.permission.POST_NOTIFICATIONS")
        MessageNotifier(context).show(NotificationOutcome.Message("c1", "Ana", "hello there"))
        val n = active().single()
        assertEquals("messages", n.channelId)
        assertEquals("Ana", n.extras.getString("android.title"))
        assertEquals("hello there", n.extras.getString("android.text"))
    }

    @Test
    fun requestUsesRequestsChannel() {
        MessageNotifier(context).show(NotificationOutcome.Request("c9"))
        val n = active().single()
        assertEquals("requests", n.channelId)
        assertEquals("Message request", n.extras.getString("android.text"))
    }

    @Test
    fun genericUsesBrandAndNewMessage() {
        MessageNotifier(context).show(NotificationOutcome.Generic)
        val n = active().single()
        assertEquals("messages", n.channelId)
        assertEquals("New message", n.extras.getString("android.text"))
    }

    @Test
    fun strangerMissedCallIsQuietOnRequestsChannelAndNamesNobody() {
        MessageNotifier(context).showStrangerMissedCall()
        val n = active().single()
        assertEquals("requests", n.channelId)
        assertEquals("Missed call", n.extras.getString("android.text"))
        assertEquals(context.applicationInfo.loadLabel(context.packageManager).toString(), n.extras.getString("android.title"))
        @Suppress("DEPRECATION") val low = n.priority <= androidx.core.app.NotificationCompat.PRIORITY_LOW
        assertTrue(low)
    }

    @Test
    fun missedCallReplacesTheChatsNotification() {
        val notifier = MessageNotifier(context)
        notifier.show(NotificationOutcome.Message("c1", "Ana", "hello"))
        notifier.showMissedCall("c1", "Ana")
        val n = active().single()
        assertEquals("messages", n.channelId)
        assertEquals("Ana", n.extras.getString("android.title"))
        assertEquals("Missed call", n.extras.getString("android.text"))
    }

    @Test
    fun newDeviceUsesDevicesChannel() {
        MessageNotifier(context).show(NotificationOutcome.NewOwnDevice("Pixel"))
        val n = active().single()
        assertEquals("devices", n.channelId)
        assertTrue(n.extras.getString("android.text")!!.contains("Pixel"))
    }

    @Test
    fun nothingAndFetchInboxShowNothing() {
        MessageNotifier(context).show(NotificationOutcome.Nothing)
        MessageNotifier(context).show(NotificationOutcome.FetchInbox)
        assertTrue(active().isEmpty())
    }

    @Test
    fun noEncryptionWordsInNotificationStrings() {
        val words = listOf("encrypt", "e2ee", "mls", "codec", "kbps", "relay")
        for (id in listOf(
            R.string.push_channel_messages, R.string.push_channel_requests, R.string.push_channel_calls,
            R.string.push_channel_devices, R.string.push_channel_service, R.string.push_generic_body,
            R.string.push_request_body, R.string.push_new_device_body, R.string.push_new_device_body_unnamed,
            R.string.push_fetch_title, R.string.push_missed_call_body,
        )) {
            val text = context.getString(id, "x").lowercase()
            for (w in words) assertFalse(text.contains(w), "$text has $w")
        }
    }

    // ---- worker and receivers

    @Test
    fun expeditedFetchRequestIsExpeditedAndUnique() {
        runCatching {
            WorkManager.initialize(
                context,
                androidx.work.Configuration.Builder().setExecutor(java.util.concurrent.Executor { it.run() }).build(),
            )
        }
        assertTrue(InboxFetchWorker.request().workSpec.expedited)
        InboxFetchWorker.enqueue(context)
        InboxFetchWorker.enqueue(context)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(InboxFetchWorker.UNIQUE_NAME).get()
        assertEquals(1, infos.size)
    }

    @Test
    fun runnerShowsFetchedOutcomes() = runTest {
        PushRuntime.inboxFetcher = object : InboxFetcher {
            override suspend fun fetch() = listOf<NotificationOutcome>(NotificationOutcome.Message("c", "Bo", "yo"), NotificationOutcome.Nothing)
        }
        try {
            InboxFetchRunner.run(context)
        } finally {
            PushRuntime.inboxFetcher = null
        }
        assertEquals("Bo", active().single().extras.getString("android.title"))
    }

    private fun fetcherReturning(vararg o: NotificationOutcome) = object : InboxFetcher {
        override suspend fun fetch() = o.toList()
    }

    @Test
    fun runnerCountsQueuedPushesAfterTheOutcomeIsKnown() = runTest {
        val monitor = MissedPushMonitor(context)
        monitor.fetchQueued(highPriority = true)
        monitor.fetchQueued(highPriority = true)
        monitor.fetchQueued(highPriority = false)
        assertEquals(0, monitor.highPriorityCount(), "not counted at hand-off")
        PushRuntime.inboxFetcher = fetcherReturning(NotificationOutcome.Message("c", "Bo", "yo"))
        try {
            InboxFetchRunner.run(context)
        } finally {
            PushRuntime.inboxFetcher = null
        }
        assertEquals(2, monitor.highPriorityCount())
        assertEquals(0, monitor.quietHighPriorityCount())
    }

    @Test
    fun runnerPostsGenericWhenFetchShowsNothingAndCountsItAsShown() = runTest {
        val monitor = MissedPushMonitor(context)
        monitor.fetchQueued(highPriority = true)
        PushRuntime.inboxFetcher = fetcherReturning(NotificationOutcome.Nothing)
        try {
            assertTrue(InboxFetchRunner.run(context))
        } finally {
            PushRuntime.inboxFetcher = null
        }
        val n = active().single()
        assertEquals("messages", n.channelId)
        assertEquals("New message", n.extras.getString("android.text"))
        assertEquals(1, monitor.highPriorityCount())
        assertEquals(0, monitor.quietHighPriorityCount())
    }

    @Test
    fun runnerPostsGenericWhenFetchFails() = runTest {
        val monitor = MissedPushMonitor(context)
        monitor.fetchQueued(highPriority = true)
        PushRuntime.inboxFetcher = object : InboxFetcher { override suspend fun fetch(): List<NotificationOutcome> = error("offline") }
        try {
            assertFalse(InboxFetchRunner.run(context))
        } finally {
            PushRuntime.inboxFetcher = null
        }
        assertEquals("New message", active().single().extras.getString("android.text"))
        assertEquals(1, monitor.highPriorityCount())
    }

    @Test
    fun realNotificationReplacesEarlierGeneric() = runTest {
        MessageNotifier(context).show(NotificationOutcome.Generic)
        PushRuntime.inboxFetcher = fetcherReturning(NotificationOutcome.Message("c", "Bo", "yo"))
        try {
            InboxFetchRunner.run(context)
        } finally {
            PushRuntime.inboxFetcher = null
        }
        assertEquals("Bo", active().single().extras.getString("android.title"))
    }

    @Test
    fun runnerWithoutFetcherIsQuiet() = runTest {
        PushRuntime.inboxFetcher = null
        InboxFetchRunner.run(context)
        assertTrue(active().isEmpty())
    }

    @Test
    fun runnerFailureRetriesAndAnnounces() = runTest {
        PushRuntime.inboxFetcher = object : InboxFetcher { override suspend fun fetch(): List<NotificationOutcome> = error("offline") }
        try {
            assertFalse(InboxFetchRunner.run(context))
        } finally {
            PushRuntime.inboxFetcher = null
        }
        assertEquals("New message", active().single().extras.getString("android.text"), "a failed fetch with a push behind it still announces")
    }

    @Test
    fun workerHasForegroundNotificationForOldApis() {
        val n = InboxFetchWorker.foregroundNotification(context)
        assertEquals("service", n.channelId)
    }

    @Test
    fun manifestDeclaresReceiversAndNoProcessSplit() {
        val pm = context.packageManager
        val info = pm.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_ACTIVITIES,
        )
        val all = info.services.orEmpty().map { it.name to it.processName } +
            info.receivers.orEmpty().map { it.name to it.processName }
        for ((name, process) in all) assertEquals(context.packageName, process, "$name runs in $process")
        assertTrue(info.services.orEmpty().any { it.name == AppFirebaseService::class.java.name })
        assertTrue(info.receivers.orEmpty().any { it.name == AppUnifiedPushReceiver::class.java.name })
        val fcm = pm.queryIntentServices(Intent("com.google.firebase.MESSAGING_EVENT").setPackage(context.packageName), 0)
        assertTrue(fcm.any { it.serviceInfo.name == AppFirebaseService::class.java.name })
        val up = pm.queryBroadcastReceivers(Intent("org.unifiedpush.android.connector.MESSAGE").setPackage(context.packageName), 0)
        assertTrue(up.any { it.activityInfo.name == AppUnifiedPushReceiver::class.java.name })
    }

    @Test
    fun raiseToForegroundServiceIsAPlainBoundServiceNotAForegroundService() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
        val raise = info.services.orEmpty().single { it.name.endsWith("RaiseToForegroundService") }
        assertEquals(0, raise.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST, "no foreground type")
        val intent = Intent("org.unifiedpush.android.connector.RAISE_TO_FOREGROUND").setPackage(context.packageName)
        val controller = org.robolectric.Robolectric.buildService(Class.forName(raise.name).asSubclass(android.app.Service::class.java), intent)
        val service = controller.create().get()
        assertNotNull(service.onBind(intent))
        assertTrue(shadowOf(service).isForegroundStopped || shadowOf(service).lastForegroundNotification == null, "never calls startForeground")
        val perms = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(perms.any { it.startsWith("android.permission.FOREGROUND_SERVICE") && it != "android.permission.FOREGROUND_SERVICE" && it != "android.permission.FOREGROUND_SERVICE_DATA_SYNC" }, "$perms")
    }
}
