package org.example.app.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.example.app.R
import org.example.app.calls.android.media.VideoFeed
import org.example.app.ui.Avatar
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * The call's video: the other person full screen, you in a corner tile. Before the first remote frame, and again when
 * the other side turns video off ([remoteVideoOn] false), the contact's avatar is shown instead (as on iPhone).
 * [remote] and [local] are null where there is no media session (sample data): the avatar and a plain tile show.
 * Each renderer is initialised with the session's [egl] context and released when it leaves the screen.
 */
@Composable
fun VideoStage(
    remote: VideoFeed?,
    local: VideoFeed?,
    avatarName: String,
    modifier: Modifier = Modifier,
    egl: EglBase.Context? = null,
    remoteVideoOn: Boolean = remote != null,
    mirrorLocal: Boolean = true,
) {
    val remoteLabel = stringResource(R.string.call_remote_video)
    val selfLabel = stringResource(R.string.call_self_video)
    Box(modifier.fillMaxSize()) {
        if (remote != null && egl != null && remoteVideoOn) {
            FeedView(remote, egl, mirror = false, overlay = false, Modifier.fillMaxSize().semantics { contentDescription = remoteLabel })
        } else {
            Avatar(avatarName, size = 160.dp, modifier = Modifier.align(Alignment.Center))
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = 48.dp).size(width = 84.dp, height = 120.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(Color.Gray, Color(0x663B6FD8))))
                .semantics { contentDescription = selfLabel },
        ) {
            if (local != null && egl != null) FeedView(local, egl, mirror = mirrorLocal, overlay = true, Modifier.fillMaxSize())
        }
    }
}

/**
 * One [SurfaceViewRenderer] on a [VideoFeed]. The renderer is made once per feed, inside the view's factory (so what is
 * on screen is what is attached), and detached and released when the view is released: the screen goes, the call ends,
 * or the composition is abandoned. A new [egl] object on a recomposition does not make another renderer.
 */
@Composable
private fun FeedView(feed: VideoFeed, egl: EglBase.Context, mirror: Boolean, overlay: Boolean, modifier: Modifier) {
    key(feed) {
        AndroidView(
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    init(egl, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    setEnableHardwareScaler(true)
                    setZOrderMediaOverlay(overlay)
                    feed.attach(this)
                }
            },
            modifier = modifier,
            onRelease = {
                feed.detach(it)
                it.release()
            },
            update = { it.setMirror(mirror) },
        )
    }
}
