package org.example.app.live

import android.content.Context
import android.util.Log
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.app.AppGraph
import org.example.app.contracts.NotificationOutcome
import org.example.app.core.chats.ChatStore
import org.example.app.messaging.LiveChatStore
import org.example.app.core.notify.NotificationPlan
import org.example.app.push.MessageNotifier
import org.example.app.ui.lock.LockSwitch
import org.example.app.ui.settings.SettingsStore
import org.example.app.SETTINGS_PREFS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.app.contracts.PushPayloadHandler
import org.example.app.contracts.RingOutcome
import org.example.app.net.ServerClient
import org.example.app.net.api.PushTokenApi
import org.example.app.net.api.ServerInfoApi
import org.example.app.push.AndroidPushEffects
import org.example.app.push.BuildConfig
import org.example.app.push.FirebaseInit
import org.example.app.push.IncomingCallSink
import org.example.app.push.InboxFetcher
import org.example.app.push.MissedCallRecorder
import org.example.app.push.PlayServices
import org.example.app.push.PushApi
import org.example.app.push.PushDispatcher
import org.example.app.push.PushRegistrar
import org.example.app.push.PushRegistration
import org.example.app.push.PushRuntime
import org.example.app.push.PushTransport
import org.example.app.push.RegistrationSink
import org.example.app.push.UnifiedPushSetup

private const val TAG = "PushWiring"

/**
 * Connects the push entry points (`PushRuntime`) to the live graph. Every hook opens the signed-in services on use,
 * so a push that starts a killed process works; while signed out they show nothing and register nothing.
 */
object PushWiring {
    fun install(
        context: Context,
        /** App lock's in-memory switch; the live settings store keeps it current. ON until loaded. */
        lock: LockSwitch = LockSwitch.shared,
        /** The stored setting, for a process no screen started. Reads the disk: only called off the main thread, outside any notifier lock. */
        storedLock: () -> Boolean = { SettingsStore.storedAppLock(context.applicationContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)) },
        graph: () -> AppGraph,
    ) {
        val app = context.applicationContext
        // Called by every entry point that can post a notification, before it does: a push that starts a killed process
        // has no settings store yet, and until the lock setting is known the notifications hide their content.
        suspend fun lockLoaded() { if (!lock.loaded) withContext(Dispatchers.IO) { lock.ensureLoaded(storedLock) } }
        val handler = object : PushPayloadHandler {
            override suspend fun onMessagePush(padded: ByteArray): NotificationOutcome =
                lockLoaded().let { graph() }.openSession()?.notifications?.onMessagePush(padded) ?: NotificationOutcome.Nothing

            override suspend fun onCallPush(padded: ByteArray): RingOutcome =
                lockLoaded().let { graph() }.openSession()?.notifications?.onCallPush(padded) ?: RingOutcome.Drop
        }
        // A contact's ring goes to the session's LiveCallEngine, which asks `call.status` and rings through Telecom.
        val ring = object : IncomingCallSink {
            override fun ring(outcome: RingOutcome.Ring) = graph().ring(outcome)
        }
        val missed = object : MissedCallRecorder {
            override suspend fun recordMissedCall(callId: String, fromAccountId: String) {
                graph().openSession()?.chatStore?.recordStrangerMissedCall(callId, fromAccountId)
            }
        }
        // The two switches the notifications read. Wired here and nowhere else. Both are plain in-memory reads (the notifier calls them with its lock held).
        // App lock is ON until loaded. The mute list is the current session's, already loaded because every entry point opens the session first.
        wireSwitches { graph().chatStore }
        PushRuntime.appLockOn = { lock.on }
        PushRuntime.notificationActions = LiveNotificationActions(
            store = { graph().openSession()?.chatStore?.let(::SessionNotificationStore) },
        )
        PushRuntime.dispatcher = PushDispatcher(handler, ring, AndroidPushEffects(app, missed))
        PushRuntime.inboxFetcher = object : InboxFetcher {
            override suspend fun fetch(): List<NotificationOutcome> = lockLoaded().let { graph() }.openSession()?.notifications?.fetchOutcomes() ?: emptyList()
        }
        PushRuntime.registrationSink = object : RegistrationSink {
            override suspend fun onRegistration(registration: PushRegistration) {
                val transport = if (registration is PushRegistration.Fcm) PushTransport.Fcm else PushTransport.UnifiedPush
                registrar(graph())?.register(transport, registration)
            }

            override suspend fun onFcmToken(token: String) {
                registrar(graph())?.register(PushTransport.Fcm, PushRegistration.Fcm(token))
            }
        }
    }

    /**
     * The mute switch. [store] is the current session's store, or an empty one while no session is open (it never opens
     * anything and never waits). A plain in-memory read: no disk, no blocking, safe under the notifier's lock.
     */
    internal fun wireSwitches(store: () -> ChatStore) {
        PushRuntime.mutedChatIds = { store().mutedChatIds.value }
    }

    /**
     * Cancels a chat's notifications when it was read (its unread count fell to zero, in the app or from the notification),
     * left the chat list, or was blocked.
     */
    suspend fun cancelReadChats(context: Context, store: ChatStore) {
        val notifier = MessageNotifier(context.applicationContext)
        var before = emptyMap<String, Int>()
        var blockedBefore = emptySet<String>()
        kotlinx.coroutines.flow.combine(store.chats, store.blockedIds) { chats, blocked -> chats to blocked }.collect { (chats, blocked) ->
            val after = chats.associate { it.id to it.unreadCount }
            val gone = NotificationPlan.readChats(before, after) + NotificationPlan.leftChats(before.keys, after.keys, blockedBefore, blocked)
            gone.forEach(notifier::cancelChat)
            before = after
            blockedBefore = blocked
        }
    }

    /**
     * After sign-in: starts UnifiedPush when that is the transport (its receiver then reports the endpoint). FCM reports
     * its token through `AppFirebaseService.onNewToken` once Firebase is configured.
     */
    suspend fun registerAfterSignIn(context: Context, client: ServerClient) {
        val up = UnifiedPushSetup(context)
        val transport = PushTransport.choose(
            playServices = PlayServices.available(context), distributorCount = up.distributors().size,
            distributorChosen = up.distributorChosen(), fcmConfigured = FirebaseInit.init(context),
        )
        if (transport == PushTransport.UnifiedPush) {
            ServerInfoApi(client).serverInfo().push.vapidKey?.let { up.register(it) }
        }
    }

    private suspend fun registrar(graph: AppGraph): PushRegistrar? {
        val client = graph.openSession()?.client ?: return null
        val key = BuildConfig.PUSH_GATEWAY_KEY.takeIf { it.isNotEmpty() }?.let { Base64.getUrlDecoder().decode(it) } ?: ByteArray(0)
        return PushRegistrar(ServerPushApi(client), graph.hpke, key, BuildConfig.PUSH_GATEWAY_URL, BuildConfig.APP_ID)
    }

    /** `PushApi` over `/v1/server` and `/v1/push/tokens`; binary fields are base64url without padding. */
    private class ServerPushApi(private val client: ServerClient) : PushApi {
        private val tokens = PushTokenApi(client)
        private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        override suspend fun serverGatewayUrl(): String? = ServerInfoApi(client).serverInfo().push.gateway
        override suspend fun serverVapidKey(): String? = ServerInfoApi(client).serverInfo().push.vapidKey
        override suspend fun putFcm(sealed: ByteArray) = tokens.putPushToken("fcm", buildJsonObject { put("sealed", b64(sealed)) })
        override suspend fun putUnifiedPush(endpoint: String, p256dh: ByteArray, auth: ByteArray) =
            tokens.putPushToken("unifiedpush", buildJsonObject { put("endpoint", endpoint); put("p256dh", b64(p256dh)); put("auth", b64(auth)) })
        override suspend fun delete(kind: String) = tokens.deletePushToken(kind)
    }
}

/** Adds [text] to the chat's unsent draft, after what is already typed there, never replacing it. */
internal suspend fun saveReplyDraft(store: NotificationStore, chatId: String, text: String) {
    val typed = store.chat(chatId)?.draft.orEmpty()
    store.setDraft(chatId, if (typed.isBlank()) text else typed + "\n" + text)
}

/** The live store as the notification buttons see it. */
private class SessionNotificationStore(private val store: LiveChatStore) : NotificationStore {
    override val meId: String get() = store.me.id
    override fun chat(chatId: String) = store.chat(chatId)
    override fun blocked(): Set<String> = store.blockedIds.value
    override suspend fun setDraft(chatId: String, text: String?) {
        store.setDraft(chatId, text)
        store.awaitWrites()
    }
    override suspend fun markRead(chatId: String) {
        store.markRead(chatId)
        store.awaitWrites()
    }
    override suspend fun sendReturningId(chatId: String, text: String, waitMs: Long): String? = store.sendReturningId(text, chatId, waitMs = waitMs)
    override suspend fun settle() = store.settle()
}
