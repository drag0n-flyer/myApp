package org.example.app.calls.android.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.EglBase
import org.webrtc.VideoSink

/** One video picture (the other person's, or the camera's) that renderers attach to. */
interface VideoFeed {
    /** False when the picture is gone (the call's media is closed): the caller must not render it. */
    fun attach(sink: VideoSink): Boolean

    /** Safe at any time, including after the call's media closed. */
    fun detach(sink: VideoSink)
}

/**
 * What the call screen needs from the live media session: the two pictures, whether the other side is sending video,
 * and the camera controls. One exists per call that has video; it is gone when the call's media closes.
 */
interface CallVideoSurface {
    /** For `SurfaceViewRenderer.init`: the EGL context the session's encoder and decoder share. */
    val eglContext: EglBase.Context?
    val remote: VideoFeed
    val local: VideoFeed

    /** True while the other person's video frames arrive; false before the first one and after they turn video off. */
    val remoteVideoOn: StateFlow<Boolean>
    val cameraCount: Int

    /** The camera in use faces the user (the picture is mirrored). */
    val frontCamera: StateFlow<Boolean>

    /** The camera could not start, was refused, or stopped (for example its permission was revoked). */
    val cameraProblem: StateFlow<Boolean>

    fun flipCamera()

    /** The camera permission was just granted: starts the camera if video was waiting for it. */
    fun cameraPermissionGranted()
}

/** The video surface of the call in progress, if it has video. The call screen reads it; the media session sets it. */
object CallVideo {
    private val current = MutableStateFlow<CallVideoSurface?>(null)
    val surface: StateFlow<CallVideoSurface?> = current.asStateFlow()

    internal fun publish(surface: CallVideoSurface) {
        current.value = surface
    }

    /** Only the surface that is current goes: a late close of an earlier call cannot remove the next call's. */
    internal fun clear(surface: CallVideoSurface) {
        current.compareAndSet(surface, null)
    }
}

/**
 * The renderers attached to one picture. Once [closeAll] ran, nothing attaches any more and every sink was handed to
 * its remover exactly once, so a renderer that outlives the track is never left registered on a disposed track.
 */
internal class SinkSet<T : Any> {
    private val items = LinkedHashSet<T>()
    private var closed = false

    val size: Int get() = items.size

    fun add(item: T): Boolean {
        if (closed) return false
        items.add(item)
        return true
    }

    /** True if [item] was attached (and so should be removed from the track). */
    fun remove(item: T): Boolean = items.remove(item)

    fun closeAll(removeFromTrack: (T) -> Unit) {
        if (closed) return
        closed = true
        items.forEach(removeFromTrack)
        items.clear()
    }
}
