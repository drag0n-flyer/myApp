package org.example.app.ui.qr

/** What the camera permission screen shows. */
enum class CameraGate {
    /** Permission is held: show the camera. */
    Granted,
    /** Show the reason and the "Allow camera" button; the system prompt can still appear. */
    Ask,
    /** Denied with "don't ask again": the system prompt will not appear, so send the person to Settings. */
    OpenSettings,
}

/**
 * [requested]: the system prompt has been shown at least once. [canAskAgain]: the system would
 * show it again (`shouldShowRequestPermissionRationale`). Before the first request that value is
 * false too, which is why [requested] decides first.
 */
fun cameraGate(granted: Boolean, requested: Boolean, canAskAgain: Boolean): CameraGate = when {
    granted -> CameraGate.Granted
    !requested || canAskAgain -> CameraGate.Ask
    else -> CameraGate.OpenSettings
}
