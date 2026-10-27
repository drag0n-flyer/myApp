package org.example.app.core.calls

import org.example.app.core.settings.CallSettings
import org.example.app.core.settings.IPHiding
import org.example.app.core.settings.ServerPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataModePolicyTest {
    private val server = ServerPolicy(ModePreference.Auto, DataMode.High, IPHiding.NonContacts, true, "home.example.org")
    private val s = CallSettings()

    private fun ceiling(sv: ServerPolicy = server, st: CallSettings = s, n: NetworkType, inCall: ModePreference? = null) =
        DataModePolicy.ceiling(sv, st, n, inCall)

    @Test fun autoOnWifiIsHigh() = assertEquals(DataMode.High, ceiling(n = NetworkType.Wifi))

    @Test fun mobileLimitApplies() = assertEquals(DataMode.DataSaver, ceiling(n = NetworkType.Mobile))

    @Test fun inCallFixedLiftsMobileLimit() =
        assertEquals(DataMode.High, ceiling(n = NetworkType.Mobile, inCall = ModePreference.Fixed(DataMode.High)))

    @Test fun inCallAutoKeepsMobileLimit() =
        assertEquals(DataMode.DataSaver, ceiling(n = NetworkType.Mobile, inCall = ModePreference.Auto))

    @Test fun serverMaximumCapsUserChoice() {
        val capped = server.copy(maximum = DataMode.Balanced)
        val st = CallSettings(callDataMode = ModePreference.Fixed(DataMode.High))
        assertEquals(DataMode.Balanced, ceiling(capped, st, NetworkType.Wifi))
        assertEquals(DataMode.Balanced, ceiling(capped, st, NetworkType.Wifi, ModePreference.Fixed(DataMode.High)))
        assertEquals("mode_balanced", DataModePolicy.stripLabelKey(capped, st, NetworkType.Wifi))
    }

    @Test fun wifiToMobileKeepsInCallChoice() {
        assertEquals(DataMode.High, ceiling(n = NetworkType.Wifi))
        assertEquals(DataMode.DataSaver, ceiling(n = NetworkType.Mobile))
        assertEquals(DataMode.Balanced, ceiling(n = NetworkType.Mobile, inCall = ModePreference.Fixed(DataMode.Balanced)))
    }

    @Test fun effectiveUsesNetwork() = assertEquals(
        DataMode.UltraLow,
        DataModePolicy.effective(server, s, NetworkType.Wifi, null, DataMode.UltraLow),
    )

    @Test fun sheetOnMobileWithServerCap() {
        val capped = server.copy(maximum = DataMode.Balanced)
        val o = DataModePolicy.sheetOptions(capped, s, NetworkType.Mobile, null)
        assertEquals(listOf("mode_auto", "mode_ultra_low", "mode_data_saver", "mode_balanced", "mode_high"), o.map { it.titleKey })
        assertTrue(o[0].isSelected)
        assertNull(o[0].estimate)
        assertEquals("note_above_mobile_limit", o[3].noteKey)
        assertTrue(o[3].isEnabled)
        assertEquals("note_limited_by_server", o[4].noteKey)
        assertFalse(o[4].isEnabled)
        assertNull(o[2].noteKey)
        assertEquals("~3 MB per 10 min", o[2].estimate)
    }

    @Test fun stripLabels() {
        assertEquals("mode_auto", DataModePolicy.stripLabelKey(server, s, NetworkType.Wifi))
        assertEquals("mode_data_saver", DataModePolicy.stripLabelKey(server, s, NetworkType.Mobile))
    }
}
