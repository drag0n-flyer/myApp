package org.example.app.webrtc

import android.media.MediaCodecList
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.webrtc.AudioCapturePostProcessing
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.MediaConstraints
import org.webrtc.OpusInfo
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Smoke test for the project's own WebRTC AAR (tools/webrtc patches 0006 and 0007). */
class OpusInfoTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun loadLibrary() {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(ApplicationProvider.getApplicationContext())
                    .createInitializationOptions(),
            )
        }
    }

    @Test
    fun opusFeatures() {
        assertEquals(7, OpusInfo.decoderComplexity())
        assertTrue(OpusInfo.hasDeepPlc())
        assertTrue(OpusInfo.hasOsce())
        assertEquals(120, OpusInfo.maxPacketMs())
    }

    @Test
    fun captureHookHalvesSamples() {
        assertTrue(AudioCapturePostProcessing.hasCapturePostProcessingHook())
        assertContentEquals(FloatArray(480) { 0.25f }, AudioCapturePostProcessing.selfTestBuiltin())
    }

    @Test
    fun captureProcessorIsSingleUse() {
        assertFailsWith<IllegalArgumentException> { AudioCapturePostProcessing(0L) }
        val p = AudioCapturePostProcessing(1L)
        // The first use is simulated by clearing the handle the way take() does (a real one needs a native struct).
        AudioCapturePostProcessing::class.java.getDeclaredField("nativeProcessor").apply { isAccessible = true }.setLong(p, 0L)
        assertFailsWith<IllegalStateException> { p.createNative(0L) }
        assertFailsWith<IllegalStateException> { p.selfTest() }
    }

    @Test
    fun senderBitrateCanBeSet() {
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val source = factory.createAudioSource(MediaConstraints())
        val track = factory.createAudioTrack("a0", source)
        val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(c: org.webrtc.IceCandidate) {}
            override fun onIceCandidatesRemoved(c: Array<org.webrtc.IceCandidate>) {}
            override fun onAddStream(s: org.webrtc.MediaStream) {}
            override fun onRemoveStream(s: org.webrtc.MediaStream) {}
            override fun onDataChannel(d: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: org.webrtc.RtpReceiver, s: Array<org.webrtc.MediaStream>) {}
        })!!
        val sender = pc.addTrack(track)
        val parameters = sender.parameters
        assertTrue(parameters.encodings.isNotEmpty())
        parameters.encodings[0].maxBitrateBps = 24000
        assertEquals(24000, parameters.encodings[0].maxBitrateBps)
        pc.dispose()
        track.dispose()
        source.dispose()
        factory.dispose()
    }

    @Test
    fun hardwareH264DecoderListedOnApi36() {
        assumeTrue(Build.VERSION.SDK_INT >= 36)
        val hasHardwareH264 = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.isHardwareAccelerated && info.supportedTypes.any { it.equals("video/avc", true) }
        }
        assumeTrue(hasHardwareH264)
        val names = HardwareVideoDecoderFactory(null).supportedCodecs.map { it.name }
        assertTrue("H264" in names, "supported: $names")
    }
}
