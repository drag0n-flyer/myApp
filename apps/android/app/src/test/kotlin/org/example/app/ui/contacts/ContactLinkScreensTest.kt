package org.example.app.ui.contacts

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.app.MainActivity
import org.example.app.contactLinkFrom
import org.example.app.core.chats.SampleChatStore
import org.example.app.core.chats.SampleData
import org.example.app.core.links.LinkProblem
import org.example.app.ui.qr.QrCodes
import org.example.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class ContactLinkScreensTest {
    @get:Rule val rule = createComposeRule()

    @Test fun myQrShowsTheCodeAndTheUsername() {
        rule.setContent { AppTheme { MyQrScreen("neha", "home.example.org", onBack = {}) } }
        rule.onNodeWithContentDescription("Your QR code. Someone who scans it can add you.").assertIsDisplayed()
        rule.onNodeWithText("@neha").assertIsDisplayed()
        rule.onNodeWithText("Share").assertIsDisplayed()
    }

    @Test fun theCodeCarriesTheUserLink() {
        val pixels = QrCodes.render("https://home.example.org/u/neha")
        assertEquals("https://home.example.org/u/neha", QrCodes.decode(pixels.pixels, pixels.size, pixels.size))
    }

    @Test fun addContactOffersScanPasteMyCodeAndInviteLink() {
        var scans = 0; var pastes = 0; var mine = 0; var invites = 0
        rule.setContent {
            AppTheme {
                AddContactScreen(
                    SampleChatStore(SampleData.make(Instant.now())), onBack = {}, onAdded = {},
                    links = AddContactLinks({ scans++ }, { pastes++ }, { mine++ }, { invites++ }, makingLink = false),
                )
            }
        }
        rule.onNodeWithText("Scan QR code").performClick()
        rule.onNodeWithText("Paste link").performClick()
        rule.onNodeWithText("My QR code").performClick()
        rule.onNodeWithText("Invite with a link").performClick()
        assertEquals(listOf(1, 1, 1, 1), listOf(scans, pastes, mine, invites))
    }

    @Test fun addContactShowsTheTooManyLinksMessage() {
        rule.setContent {
            AppTheme {
                AddContactScreen(
                    SampleChatStore(SampleData.make(Instant.now())), onBack = {}, onAdded = {},
                    links = AddContactLinks({}, {}, {}, {}, makingLink = false, message = linkProblemMessage(LinkProblem.TooManyLinks)),
                )
            }
        }
        rule.onNodeWithText("You have too many unused links. Wait for one to expire.").assertIsDisplayed()
    }

    @Test fun pastedLinkIsHandedOverTrimmed() {
        var opened: String? = null
        rule.setContent { AppTheme { PasteLinkForm(onOpen = { opened = it }, onCancel = {}) } }
        rule.onNodeWithText("Link").performTextInput("  https://home.example.org/u/neha ")
        rule.onNodeWithText("Open").performClick()
        assertEquals("https://home.example.org/u/neha", opened)
    }

    @Test fun everyProblemHasItsOwnMessage() {
        rule.setContent { AppTheme { LinkProblemDialog(LinkProblem.Expired, onDismiss = {}) } }
        rule.onNodeWithText("This link has expired. Ask for a new one.").assertIsDisplayed()
        assertEquals(LinkProblem.entries.size, LinkProblem.entries.map { linkProblemMessage(it) }.toSet().size)
    }

    @Test fun confirmationAsksBeforeAdding() {
        var added = 0
        rule.setContent { AppTheme { ContactLinkConfirmScreen("neha", "Neha", onAdd = { added++; true }, onBack = {}) } }
        rule.onNodeWithText("Add Neha to your contacts?").assertIsDisplayed()
        assertEquals(0, added)
        rule.onNodeWithText("Add").performClick()
        rule.waitUntil(5_000) { added == 1 }
    }

    // ---- The activity: app links and sample mode

    @Test fun onlyAnHttpsViewIntentIsALink() {
        val link = "https://home.example.org/u/neha"
        assertEquals(link, contactLinkFrom(Intent(Intent.ACTION_VIEW, Uri.parse(link))))
        assertNull(contactLinkFrom(Intent(Intent.ACTION_VIEW, Uri.parse("http://home.example.org/u/neha"))))
        assertNull(contactLinkFrom(Intent(Intent.ACTION_VIEW, Uri.parse("content://x/u/neha"))))
        assertNull(contactLinkFrom(Intent(Intent.ACTION_SEND).setData(Uri.parse(link))))
        assertNull(contactLinkFrom(Intent(Intent.ACTION_VIEW)))
        assertNull(contactLinkFrom(null))
    }

    @Test fun theManifestHandlesBothLinkPathsOnTheConfiguredHost() {
        val pm = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager
        for (path in listOf("u/neha", "c/AAAAAAAAAAAAAAAAAAAAAA")) {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://home.example.org/$path")).addCategory(Intent.CATEGORY_BROWSABLE)
            assertTrue(pm.queryIntentActivities(view, 0).any { it.activityInfo.name == MainActivity::class.java.name }, path)
        }
        val other = Intent(Intent.ACTION_VIEW, Uri.parse("https://home.example.org/other/x")).addCategory(Intent.CATEGORY_BROWSABLE)
        assertTrue(pm.queryIntentActivities(other, 0).none { it.activityInfo.name == MainActivity::class.java.name })
    }

    @Test fun anIntentReopenedFromRecentsIsNotALinkAgain() {
        val link = "https://home.example.org/u/neha"
        val again = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(contactLinkFrom(again))
        assertNull(org.example.app.chatIdFrom(Intent().putExtra(org.example.app.push.MessageNotifier.EXTRA_CHAT_ID, "c1").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
    }

    @Test fun theMessageForANonLinkIsWorded() {
        rule.setContent { AppTheme { LinkProblemDialog(LinkProblem.NotALink, onDismiss = {}) } }
        rule.onNodeWithText("That isn't a link for a person.").assertIsDisplayed()
    }
}
