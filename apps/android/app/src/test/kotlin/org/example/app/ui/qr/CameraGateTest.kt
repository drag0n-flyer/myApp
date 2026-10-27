package org.example.app.ui.qr

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraGateTest {
    @Test fun grantedWins() = assertEquals(CameraGate.Granted, cameraGate(granted = true, requested = true, canAskAgain = false))
    @Test fun firstTimeShowsTheReasonAndAllowButton() = assertEquals(CameraGate.Ask, cameraGate(false, requested = false, canAskAgain = false))
    @Test fun deniedOnceCanAskAgain() = assertEquals(CameraGate.Ask, cameraGate(false, requested = true, canAskAgain = true))
    @Test fun permanentDenialOffersSettings() = assertEquals(CameraGate.OpenSettings, cameraGate(false, requested = true, canAskAgain = false))
    @Test fun grantMadeInSettingsOpensTheGate() {
        // requested earlier and permanently denied, then the person grants it in Settings and returns.
        assertEquals(CameraGate.OpenSettings, cameraGate(false, true, false))
        assertEquals(CameraGate.Granted, cameraGate(true, true, false))
    }
}
