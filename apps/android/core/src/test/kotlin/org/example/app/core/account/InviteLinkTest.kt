package org.example.app.core.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InviteLinkTest {
    @Test fun parsesCodeFromAlphabet() {
        val link = InviteLink.parse("ABCDE-FGH23")
        assertEquals(InviteLink(host = null, code = "ABCDE-FGH23"), link)
    }

    @Test fun rejectsLettersAndDigitsOutsideAlphabet() {
        assertNull(InviteLink.parse("ABCDE-FGHI0"))
        assertNull(InviteLink.parse("ABCDE-FGHO1"))
    }

    @Test fun rejectsWrongShape() {
        assertNull(InviteLink.parse("ABCDEFGH23"))
        assertNull(InviteLink.parse("ABCD-FGH23"))
        assertNull(InviteLink.parse("ABCDE-FGH234"))
        assertNull(InviteLink.parse(""))
    }

    @Test fun trimsAndUppercasesTypedCodes() {
        assertEquals("ABCDE-FGH23", InviteLink.parse("  abcde-fgh23 ")?.code)
    }

    @Test fun parsesAppLink() {
        assertEquals(InviteLink("home.example.org", "ABCDE-FGH23"), InviteLink.parse("https://home.example.org/invite/ABCDE-FGH23"))
    }

    @Test fun rejectsBadAppLinks() {
        assertNull(InviteLink.parse("http://home.example.org/invite/ABCDE-FGH23"))
        assertNull(InviteLink.parse("https://home.example.org/link/ABCDE-FGH23"))
        assertNull(InviteLink.parse("https://home.example.org/invite/ABCDE-FGHI0"))
        assertNull(InviteLink.parse("https:///invite/ABCDE-FGH23"))
    }

    @Test fun linkKeepsThePort() {
        assertEquals(InviteLink("home.example.org:8443", "ABCDE-FGH23"), InviteLink.parse("https://home.example.org:8443/invite/ABCDE-FGH23"))
    }
}
