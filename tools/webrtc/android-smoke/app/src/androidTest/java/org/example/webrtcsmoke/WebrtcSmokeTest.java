package org.example.webrtcsmoke;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.Field;
import org.junit.BeforeClass;
import org.junit.Test;
import org.webrtc.AudioCapturePostProcessing;
import org.webrtc.OpusInfo;
import org.webrtc.OpusVoiceEncoder;
import org.webrtc.PeerConnectionFactory;

/** Smoke test for the AAR built by tools/webrtc/build-android.sh (patches 0005 and 0006). */
public class WebrtcSmokeTest {
  @BeforeClass
  public static void loadLibrary() {
    Context context = ApplicationProvider.getApplicationContext();
    PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions());
  }

  @Test
  public void opusInfo() {
    assertEquals(7, OpusInfo.decoderComplexity());
    assertTrue(OpusInfo.hasDeepPlc());
    assertTrue(OpusInfo.hasOsce());
    assertEquals(120, OpusInfo.maxPacketMs());
  }

  /** Patch 0008: 50 frames of silence encode to non-empty packets; pre-skip is 312. */
  @Test
  public void opusVoiceEncoder() {
    try (OpusVoiceEncoder encoder = new OpusVoiceEncoder()) {
      assertEquals(312, encoder.preSkip());
      byte[] frame = new byte[OpusVoiceEncoder.FRAME_BYTES];
      for (int i = 0; i < 50; i++) {
        assertTrue(encoder.encode(frame, 0).length > 0);
      }
    }
  }

  @Test
  public void captureHookHalvesSamples() {
    assertTrue(AudioCapturePostProcessing.hasCapturePostProcessingHook());
    float[] out = AudioCapturePostProcessing.selfTestBuiltin();
    assertEquals(480, out.length);
    float[] expected = new float[480];
    java.util.Arrays.fill(expected, 0.25f);
    assertArrayEquals(expected, out, 1e-4f);
  }

  /**
   * A real second createNative needs a native AppAudioCaptureProcessor struct for the first one,
   * which this project does not have. So the first use is simulated by zeroing the private handle
   * the way take() does, and the second use must throw.
   */
  @Test
  public void secondUseThrows() throws Exception {
    AudioCapturePostProcessing p = new AudioCapturePostProcessing(1L);
    Field f = AudioCapturePostProcessing.class.getDeclaredField("nativeProcessor");
    f.setAccessible(true);
    f.setLong(p, 0L);
    try {
      p.createNative(0L);
      fail("expected IllegalStateException");
    } catch (IllegalStateException expected) {
    }
    try {
      p.selfTest();
      fail("expected IllegalStateException");
    } catch (IllegalStateException expected) {
    }
  }

  @Test
  public void zeroProcessorRejected() {
    try {
      new AudioCapturePostProcessing(0L);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void factoryCreatesAndSenderBitrateSettable() {
    PeerConnectionFactory factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
    org.webrtc.AudioSource source = factory.createAudioSource(new org.webrtc.MediaConstraints());
    org.webrtc.AudioTrack track = factory.createAudioTrack("a0", source);
    org.webrtc.PeerConnection.RTCConfiguration config =
        new org.webrtc.PeerConnection.RTCConfiguration(new java.util.ArrayList<>());
    config.sdpSemantics = org.webrtc.PeerConnection.SdpSemantics.UNIFIED_PLAN;
    org.webrtc.PeerConnection pc =
        factory.createPeerConnection(config, new org.webrtc.PeerConnection.Observer() {
          public void onSignalingChange(org.webrtc.PeerConnection.SignalingState s) {}
          public void onIceConnectionChange(org.webrtc.PeerConnection.IceConnectionState s) {}
          public void onIceConnectionReceivingChange(boolean b) {}
          public void onIceGatheringChange(org.webrtc.PeerConnection.IceGatheringState s) {}
          public void onIceCandidate(org.webrtc.IceCandidate c) {}
          public void onIceCandidatesRemoved(org.webrtc.IceCandidate[] c) {}
          public void onAddStream(org.webrtc.MediaStream s) {}
          public void onRemoveStream(org.webrtc.MediaStream s) {}
          public void onDataChannel(org.webrtc.DataChannel d) {}
          public void onRenegotiationNeeded() {}
          public void onAddTrack(org.webrtc.RtpReceiver r, org.webrtc.MediaStream[] s) {}
        });
    org.webrtc.RtpSender sender = pc.addTrack(track);
    org.webrtc.RtpParameters parameters = sender.getParameters();
    assertTrue(parameters.encodings.size() > 0);
    parameters.encodings.get(0).maxBitrateBps = 24000;
    assertEquals(Integer.valueOf(24000), parameters.encodings.get(0).maxBitrateBps);
    pc.dispose();
    track.dispose();
    source.dispose();
    factory.dispose();
  }
}
