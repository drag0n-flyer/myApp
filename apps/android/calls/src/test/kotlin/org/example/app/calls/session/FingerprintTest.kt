package org.example.app.calls.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FingerprintTest {
    /** The iPhone's FingerprintCheckTests rules. */
    @Test fun rules() {
        assertTrue(Fingerprint.isValid(sdp("sha-256 AA"), "sha-256 AA"))
        assertFalse(Fingerprint.isValid(sdp("sha-256 AA"), "sha-256 BB"))
        assertFalse(Fingerprint.isValid(sdp("sha-256 AA"), null))
        assertFalse(Fingerprint.isValid(null, "sha-256 AA"))
        assertFalse(Fingerprint.isValid("v=0\r\n", "sha-256 AA"))
        assertTrue(Fingerprint.isValid(sdp("sha-256 AA"), "sha-256 AA", pinned = "sha-256 AA"))
        assertFalse(Fingerprint.isValid(sdp("sha-256 CC"), "sha-256 CC", pinned = "sha-256 AA"))
        assertFalse(Fingerprint.isValid(sdp("sha-256 AA"), "sha-256 AA", pinned = null, requirePin = true))
    }

    /** calls.md: comparison lowercases the hash name and uppercases the hex. */
    @Test fun normalisesCaseAndSpacing() {
        assertEquals("sha-256 AB:CD:EF", Fingerprint.normalise("SHA-256 ab:cd:ef"))
        assertEquals("sha-256 AB:CD:EF", Fingerprint.normalise("  sha-256 \t AB:cd:EF \r"))
        assertTrue(Fingerprint.isValid(sdp("SHA-256 ab:cd:ef"), "sha-256 AB:CD:EF"))
        assertTrue(Fingerprint.isValid(sdp("sha-256 AB:CD:EF"), "sha-256 ab:cd:ef", pinned = "SHA-256 AB:CD:EF"))
        assertTrue(Fingerprint.isValid("v=0\na=fingerprint:sha-256 AB:CD\n", "sha-256 AB:CD"))   // bare LF
    }

    @Test fun rejectsMalformed() {
        assertNull(Fingerprint.normalise(null))
        assertNull(Fingerprint.normalise(""))
        assertNull(Fingerprint.normalise("sha-256"))
        assertNull(Fingerprint.normalise("sha-256 AB:CD extra"))
        assertNull(Fingerprint.normalise("sha-256 ABCD"))
        assertNull(Fingerprint.normalise("sha-256 AB:"))
        assertNull(Fingerprint.normalise("sha-256 AB::CD"))
        assertNull(Fingerprint.normalise("sha-256 GG:00"))
        assertNull(Fingerprint.normalise("sha 256 AB"))
        assertFalse(Fingerprint.isValid(sdp("sha-256 ABCD"), "sha-256 ABCD"))
    }

    /** calls.md: every `a=fingerprint` line must equal the claimed one. */
    @Test fun everyLineMustMatch() {
        val two = "v=0\r\na=fingerprint:sha-256 AA\r\nm=video 9 X 96\r\na=fingerprint:sha-256 BB\r\n"
        assertFalse(Fingerprint.isValid(two, "sha-256 AA"))
        assertFalse(Fingerprint.isValid(two, "sha-256 BB"))
        val same = "v=0\r\na=fingerprint:sha-256 AA\r\nm=video 9 X 96\r\na=fingerprint:SHA-256 aa\r\n"
        assertTrue(Fingerprint.isValid(same, "sha-256 AA"))
    }

    /** What we send is copied from our own SDP as is, for receivers that compare exactly (the iPhone). */
    @Test fun localCopiesTheLineAndRefusesInconsistentSdp() {
        assertEquals("sha-256 ab:CD", Fingerprint.local(sdp("sha-256 ab:CD")))
        assertNull(Fingerprint.local("v=0\r\n"))
        assertNull(Fingerprint.local("v=0\r\na=fingerprint:sha-256 AA\r\na=fingerprint:sha-256 BB\r\n"))
        assertNull(Fingerprint.local(sdp("sha-256 nothex")))
    }
}
