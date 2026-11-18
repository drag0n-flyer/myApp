package org.example.app.ui.call

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraPolicyTest {
    private fun step(wanted: Boolean = true, granted: Boolean = false, refused: Boolean = false, failed: Boolean = false) =
        CameraPolicy.next(wanted, granted, refused, failed)

    @Test fun audioCallNeverAsks() = assertEquals(CameraStep.Ready, step(wanted = false))

    @Test fun videoWithoutPermissionAsksOnce() = assertEquals(CameraStep.Ask, step())

    @Test fun refusalContinuesAsAudioWithTheMessage() = assertEquals(CameraStep.AudioOnlyNoPermission, step(refused = true))

    @Test fun grantedVideoIsReady() = assertEquals(CameraStep.Ready, step(granted = true))

    @Test fun revokedMidCallFallsBackToAudio() {
        // Granted at first, then revoked in system settings: the resume check marks it refused.
        assertEquals(CameraStep.Ready, step(granted = true))
        assertEquals(CameraStep.AudioOnlyNoPermission, step(granted = false, refused = true))
    }

    @Test fun aCameraThatFailsWithThePermissionHeldFallsBackToAudio() =
        assertEquals(CameraStep.AudioOnlyUnavailable, step(granted = true, failed = true))

    @Test fun aMissingPermissionIsNotReportedAsAFailedCamera() =
        assertEquals(CameraStep.Ask, step(granted = false, failed = true))
}
