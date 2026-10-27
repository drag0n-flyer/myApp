package org.example.app.core.calls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataModeTest {
    @Test fun titleKeys() {
        assertEquals(
            listOf("mode_ultra_low", "mode_data_saver", "mode_balanced", "mode_high"),
            DataMode.entries.map(DataModeInfo::titleKey),
        )
        assertEquals("mode_ultra_low_subtitle", DataModeInfo.subtitleKey(DataMode.UltraLow))
        assertEquals("mode_data_saver_subtitle", DataModeInfo.subtitleKey(DataMode.DataSaver))
        assertNull(DataModeInfo.subtitleKey(DataMode.Balanced))
        assertNull(DataModeInfo.subtitleKey(DataMode.High))
        assertEquals("mode_auto", ModePreference.Auto.titleKey)
        assertEquals("mode_data_saver", ModePreference.Fixed(DataMode.DataSaver).titleKey)
    }

    @Test fun ordering() {
        assertTrue(DataMode.UltraLow < DataMode.DataSaver && DataMode.Balanced < DataMode.High)
    }

    @Test fun estimates() {
        assertEquals(listOf(2, 3, 5, 8), DataMode.entries.map { it.estimatedMBPer10Minutes })
        assertEquals(5_000, DataMode.DataSaver.bytesPerSecond)
    }

    @Test fun wireNames() {
        assertEquals(listOf("ultraLow", "dataSaver", "balanced", "high"), DataMode.entries.map { it.wireName })
    }
}
