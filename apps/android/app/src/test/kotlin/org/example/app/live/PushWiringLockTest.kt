package org.example.app.live

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.example.app.AppGraph
import org.example.app.contracts.NotificationOutcome
import org.example.app.push.MessageNotifier
import org.example.app.push.PushRuntime
import org.example.app.ui.lock.LockSwitch
import org.example.app.ui.settings.SettingsStore
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** App lock reaching the notifications through the real `PushWiring.install` hook. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PushWiringLockTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val graph by lazy { AppGraph(context, sampleData = true) }

    @BeforeTest
    fun setUp() {
        shadowOf(context as Application).grantPermissions("android.permission.POST_NOTIFICATIONS")
    }

    @AfterTest
    fun tearDown() {
        PushRuntime.appLockOn = { false }
        nm.cancelAll()
    }

    private var sequence = 0

    // A different text each time: the notifier drops a message it has just shown.
    private fun post() = MessageNotifier(context).show(NotificationOutcome.Message("c1-${++sequence}".take(2), "Ana", "secret ${System.nanoTime()}"))

    private fun everythingShown(): String {
        val n = nm.activeNotifications.single { it.tag == "chat:c1" }.notification
        val extras = n.extras.keySet().mapNotNull { n.extras.get(it)?.toString() }.joinToString("|")
        return extras + "|" + n.actions.orEmpty().joinToString("|") { it.title }
    }

    @Test fun withTheLockOnANewMessageShowsNewMessageWithNoSenderTextOrReply() {
        val lock = LockSwitch().also { it.set(true) }
        PushWiring.install(context, lock, storedLock = { error("already known") }) { graph }
        post()
        val shown = everythingShown()
        assertTrue("New message" in shown, shown)
        assertFalse("Ana" in shown || "secret" in shown || "Reply" in shown, shown)
    }

    @Test fun withTheLockOffTheSenderAndTextShow() {
        val lock = LockSwitch().also { it.set(false) }
        PushWiring.install(context, lock, storedLock = { error("already known") }) { graph }
        post()
        val shown = everythingShown()
        assertTrue("Ana" in shown && "secret" in shown, shown)
    }

    @Test fun switchingTheLockOnInTheStoreHidesTheNextNotification() {
        val lock = LockSwitch()
        val store = SettingsStore(null, org.example.app.core.settings.UserSettings(), lock)
        PushWiring.install(context, lock, storedLock = { error("the store has set it") }) { graph }
        post()
        assertTrue("secret" in everythingShown())
        store.update { it.copy(appLock = true) }
        post()
        assertFalse("secret" in everythingShown())
        store.reset(org.example.app.core.settings.UserSettings())
        assertFalse(lock.on)
    }

    @Test fun aColdStartWithTheSettingNotLoadedHidesContentUntilItIsLoaded() = runBlocking {
        val lock = LockSwitch()
        var reads = 0
        PushWiring.install(context, lock, storedLock = { reads++; true }) { graph }
        assertTrue(PushRuntime.appLockOn(), "not loaded: treated as on")
        post()
        assertFalse("secret" in everythingShown(), "hidden before the setting is known")
        // A push or the inbox fetch loads the stored setting before anything is posted.
        PushRuntime.inboxFetcher!!.fetch()
        assertEquals(1, reads)
        assertTrue(lock.loaded)
        assertTrue(PushRuntime.appLockOn(), "stored as on: stays hidden")
        PushRuntime.inboxFetcher!!.fetch()
        assertEquals(1, reads, "loaded once")
    }

    @Test fun aColdStartWithTheSettingStoredOffShowsContentAfterTheLoad() = runBlocking {
        val lock = LockSwitch()
        PushWiring.install(context, lock, storedLock = { false }) { graph }
        PushRuntime.inboxFetcher!!.fetch()
        assertFalse(PushRuntime.appLockOn())
        post()
        assertTrue("secret" in everythingShown())
    }

    @Test fun anUnreadableStoredSettingCountsAsOn() = runBlocking {
        val lock = LockSwitch()
        PushWiring.install(context, lock, storedLock = { throw java.io.IOException("disk") }) { graph }
        PushRuntime.inboxFetcher!!.fetch()
        assertTrue(PushRuntime.appLockOn())
    }

    @Test fun theStoredSettingIsReadFromThePreferencesFile() {
        val prefs = context.getSharedPreferences("lock-wiring-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertFalse(SettingsStore.storedAppLock(prefs))
        SettingsStore(prefs).update { it.copy(appLock = true) }
        assertTrue(SettingsStore.storedAppLock(prefs))
        prefs.edit().putString("settings", "not json").commit()
        assertFalse(SettingsStore.storedAppLock(prefs)) // unreadable settings load as the defaults, as the store does
    }
}
