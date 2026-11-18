package org.example.app.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals

class PaneLayoutTest {
    @Test fun belowTheThresholdIsOnePane() {
        assertEquals(PaneLayout.Single, PaneLayout.forWidth(839))
        assertEquals(PaneLayout.Single, PaneLayout.forWidth(411))
        assertEquals(PaneLayout.Single, PaneLayout.forWidth(0))
    }

    @Test fun atAndAboveTheThresholdIsTwoPanes() {
        assertEquals(PaneLayout.TwoPane, PaneLayout.forWidth(840))
        assertEquals(PaneLayout.TwoPane, PaneLayout.forWidth(1280))
    }

    @Test fun theThresholdAndListWidthAreTheSpecs() {
        assertEquals(840, PaneLayout.TWO_PANE_MIN_DP)
        assertEquals(360, PaneLayout.LIST_PANE_DP)
    }
}
