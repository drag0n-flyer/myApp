package org.example.app.calls.policy

import org.example.app.contracts.DataMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun fixture(name: String): String =
    SdpMungerTest::class.java.getResource("/fixtures/$name.sdp")!!.readText()

private fun lines(s: String) = s.split("\r\n")
private fun section(sdp: String, kind: String): List<String> {
    val out = mutableListOf<String>()
    var on = false
    for (l in lines(sdp)) {
        if (l.startsWith("m=")) on = l.startsWith("m=$kind ")
        if (on) out += l
    }
    return out
}

class SdpMungerTest {
    private val fixtures = listOf("chrome-offer", "ios-webrtc-offer", "firefox-offer")

    @Test fun tableMatchesDesign() {
        data class Row(val m: DataMode, val br: Int, val pt: Int, val fec: Boolean, val dtx: Boolean, val rate: Int, val wire: Int)
        val rows = listOf(
            Row(DataMode.UltraLow, 10000, 60, false, true, 16000, 21000),
            Row(DataMode.DataSaver, 16000, 40, true, true, 16000, 30000),
            Row(DataMode.Balanced, 24000, 20, true, true, 48000, 51000),
            Row(DataMode.High, 40000, 20, true, true, 48000, 75000),
        )
        assertEquals(4, AudioProfile.table.size)
        for (r in rows) {
            assertEquals(AudioProfile(r.m, r.br, r.pt, r.rate, r.wire, r.fec, r.dtx), AudioProfile.forMode(r.m))
        }
    }

    @Test fun mungesOpusFmtpPerMode() {
        for (mode in DataMode.entries) {
            val p = AudioProfile.forMode(mode)
            val out = SdpMunger.local(fixture("chrome-offer"), mode)
            val fmtp = lines(out).first { it.startsWith("a=fmtp:111 ") }
            val want = "maxaveragebitrate=${p.maxAverageBitrate};useinbandfec=${if (p.inbandFEC) 1 else 0};" +
                "usedtx=${if (p.dtx) 1 else 0};maxplaybackrate=${p.maxPlaybackRate};stereo=0"
            assertTrue(fmtp.contains(want), fmtp)
            assertTrue(fmtp.contains("minptime=10"))
            assertEquals(2, fmtp.split("useinbandfec").size)
            assertEquals(1, section(out, "audio").count { it == "a=ptime:${p.ptime}" })
        }
        // remote() applies the send mode's profile.
        val r = SdpMunger.remote(fixture("chrome-offer"), DataMode.DataSaver)
        assertTrue(lines(r).first { it.startsWith("a=fmtp:111 ") }.contains("maxaveragebitrate=16000"))
    }

    @Test fun keepsOtherCodecsUntouched() {
        val src = fixture("chrome-offer")
        val out = SdpMunger.local(src, DataMode.DataSaver)
        assertEquals(section(src, "video"), section(out, "video"))
        val audioOther = { s: String -> section(s, "audio").filter { it.startsWith("a=rtpmap:") || it.startsWith("a=rtcp-fb:") } }
        assertEquals(audioOther(src), audioOther(out))
        val audio = section(out, "audio")
        assertEquals(2, audio.count { it.startsWith("a=ptime:") || it.startsWith("a=maxptime:") })
    }

    @Test fun nonDefaultPayloadType() {
        val out = SdpMunger.local(fixture("firefox-offer"), DataMode.UltraLow)
        val l = lines(out)
        val fmtp = l.first { it.startsWith("a=fmtp:109 ") }
        assertTrue(fmtp.contains("maxaveragebitrate=10000") && fmtp.contains("useinbandfec=0"))
        assertTrue(fmtp.contains("stereo=0") && !fmtp.contains("stereo=1"))
        assertTrue(fmtp.contains("maxplaybackrate=16000") && !fmtp.contains("maxplaybackrate=48000"))
        for (key in listOf("maxaveragebitrate", "useinbandfec", "usedtx", "maxplaybackrate", "stereo")) {
            assertEquals(2, fmtp.split("$key=").size, "$key duplicated")
        }
        assertEquals(1, l.count { it.startsWith("a=fmtp:109 ") })
        assertEquals(1, section(out, "audio").count { it == "a=ptime:60" })
    }

    @Test fun idempotent() {
        for (name in fixtures) {
            val once = SdpMunger.local(fixture(name), DataMode.Balanced)
            assertEquals(once, SdpMunger.local(once, DataMode.Balanced))
        }
    }

    @Test fun crlfPreservedAndLfBecomesCrlf() {
        val out = SdpMunger.local(fixture("chrome-offer"), DataMode.High)
        assertTrue(out.endsWith("\r\n"))
        assertFalse(out.replace("\r\n", "").contains("\n"))
        val lf = SdpMunger.local(fixture("chrome-offer").replace("\r\n", "\n"), DataMode.Balanced)
        assertFalse(lf.replace("\r\n", "").contains("\n"))
        assertTrue(lf.contains("\r\n"))
    }

    @Test fun extractsFingerprint() {
        assertTrue(SdpMunger.fingerprint(fixture("chrome-offer"))!!.startsWith("sha-256 AB:CD:EF:01"))
        assertNull(SdpMunger.fingerprint("v=0\r\n"))
    }

    @Test fun ptimeAfterLastPayloadAttribute() {
        for (name in fixtures) {
            val audio = section(SdpMunger.local(fixture(name), DataMode.DataSaver), "audio")
            val pi = audio.indexOfFirst { it.startsWith("a=ptime:") }
            assertTrue(pi >= 0)
            assertTrue(audio[pi + 1].startsWith("a=maxptime:"))
            val lastPayload = audio.indexOfLast {
                it.startsWith("a=rtpmap:") || it.startsWith("a=fmtp:") || it.startsWith("a=rtcp-fb:")
            }
            assertEquals(lastPayload + 1, pi)
        }
    }

    @Test fun acceptsIosSdpFixtures() {
        // The iPhone's SDP is accepted as a remote offer at every mode: Opus found, one fmtp, fingerprint intact.
        val ios = fixture("ios-webrtc-offer")
        for (mode in DataMode.entries) {
            val out = SdpMunger.remote(ios, mode)
            val p = AudioProfile.forMode(mode)
            val audio = section(out, "audio")
            assertEquals(1, audio.count { it.startsWith("a=fmtp:") && it.contains("maxaveragebitrate=${p.maxAverageBitrate}") })
            assertEquals(1, audio.count { it == "a=ptime:${p.ptime}" })
            assertEquals(section(ios, "video"), section(out, "video"))
            assertEquals(SdpMunger.fingerprint(ios), SdpMunger.fingerprint(out))
            assertNotNull(SdpMunger.fingerprint(out))
        }
    }
}
