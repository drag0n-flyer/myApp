package org.example.app.core.chats

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaBubbleStateTest {
    private fun state(phase: MediaState.Phase?, exists: Boolean = true): MediaBubbleState =
        MediaBubbleState(phase?.let { MediaState("a1", it) }) { exists }

    @Test fun noMediaIsNotDownloaded() = assertEquals(MediaBubbleState.NotDownloaded, state(null))

    @Test fun remoteIsNotDownloaded() = assertEquals(MediaBubbleState.NotDownloaded, state(MediaState.Phase.Remote))

    @Test fun downloadingKeepsItsFraction() = assertEquals(MediaBubbleState.Downloading(0.4), state(MediaState.Phase.Downloading(0.4)))

    @Test fun downloadingFractionStaysBetweenZeroAndOne() {
        assertEquals(MediaBubbleState.Downloading(1.0), state(MediaState.Phase.Downloading(7.0)))
        assertEquals(MediaBubbleState.Downloading(0.0), state(MediaState.Phase.Downloading(-1.0)))
        assertEquals(MediaBubbleState.Downloading(0.0), state(MediaState.Phase.Downloading(Double.NaN)))
    }

    @Test fun localWithTheFileIsReady() = assertEquals(MediaBubbleState.Ready("/x/a.enc"), state(MediaState.Phase.Local("/x/a.enc")))

    @Test fun localWhoseFileIsGoneFailed() = assertEquals(MediaBubbleState.Failed, state(MediaState.Phase.Local("/x/a.enc"), exists = false))

    @Test fun failedIsFailed() = assertEquals(MediaBubbleState.Failed, state(MediaState.Phase.Failed))

    @Test fun theFileIsLookedUpByItsOwnPath() {
        val asked = mutableListOf<String>()
        MediaBubbleState(MediaState("a1", MediaState.Phase.Local("/x/a.enc"))) { asked += it; true }
        assertEquals(listOf("/x/a.enc"), asked)
    }

    @Test fun theFileIsNotLookedUpForOtherPhases() {
        var asked = 0
        for (p in listOf(MediaState.Phase.Remote, MediaState.Phase.Failed, MediaState.Phase.Downloading(0.5))) {
            MediaBubbleState(MediaState("a1", p)) { asked++; true }
        }
        assertEquals(0, asked)
    }
}
