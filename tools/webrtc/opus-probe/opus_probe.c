/* Feature probe for a libopus build: does Deep PLC / OSCE / OSCE BWE change the
 * decoded audio? Signal and settings are fixed, so every result is deterministic.
 * C99, public libopus API only (plus two private encoder constants, below). */
#include <math.h>
#include <stdint.h>
#include <string.h>

#include "opus_probe.h"

/* Private encoder control from libopus src/opus_private.h, not in <opus.h>. */
#ifndef OPUS_SET_FORCE_MODE_REQUEST
#define OPUS_SET_FORCE_MODE_REQUEST 11002
#endif
#define APP_MODE_SILK_ONLY 1000

#define SAMPLE_RATE 48000
#define FRAME_SIZE 960 /* 20 ms at 48 kHz */
#define NUM_FRAMES 30
#define MAX_PACKET 1276
#define TWO_PI 6.28318530717958647692

typedef struct {
  unsigned char data[MAX_PACKET];
  opus_int32 len;
} Packet;

typedef struct {
  opus_int16 pcm[NUM_FRAMES][FRAME_SIZE];
} Decoded;

/* Encoded test signal, built once on first use. */
static Packet g_packets[NUM_FRAMES];
static int g_packets_state; /* 0 not built, 1 ready, -1 failed */

static uint32_t lcg_next(uint32_t *state) {
  *state = *state * 1664525u + 1013904223u;
  return *state;
}

static int build_packets(void) {
  OpusEncoder *enc;
  uint32_t lcg = 12345u;
  int err = OPUS_OK;
  int frame;
  int ok = 1;

  if (g_packets_state != 0) return g_packets_state > 0;

  enc = opus_encoder_create(SAMPLE_RATE, 1, OPUS_APPLICATION_VOIP, &err);
  if (enc == NULL || err != OPUS_OK) {
    g_packets_state = -1;
    return 0;
  }
  if (opus_encoder_ctl(enc, OPUS_SET_BITRATE(16000)) != OPUS_OK ||
      opus_encoder_ctl(enc, OPUS_SET_BANDWIDTH(OPUS_BANDWIDTH_WIDEBAND)) != OPUS_OK ||
      opus_encoder_ctl(enc, OPUS_SET_FORCE_MODE_REQUEST, APP_MODE_SILK_ONLY) != OPUS_OK ||
      opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(10)) != OPUS_OK ||
      opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(0)) != OPUS_OK) {
    ok = 0;
  }

  for (frame = 0; ok && frame < NUM_FRAMES; frame++) {
    float pcm[FRAME_SIZE];
    int i;
    opus_int32 n;
    for (i = 0; i < FRAME_SIZE; i++) {
      double t = (double)(frame * FRAME_SIZE + i) / SAMPLE_RATE;
      double noise = ((double)(lcg_next(&lcg) >> 8) / 8388608.0 - 1.0) * 0.05;
      pcm[i] = (float)(0.3 * sin(TWO_PI * 220.0 * t) + 0.2 * sin(TWO_PI * 1250.0 * t) + noise);
    }
    n = opus_encode_float(enc, pcm, FRAME_SIZE, g_packets[frame].data, MAX_PACKET);
    if (n <= 0) {
      ok = 0;
    } else {
      g_packets[frame].len = n;
    }
  }
  opus_encoder_destroy(enc);
  g_packets_state = ok ? 1 : -1;
  return ok;
}

/* Decode packets [0, count) with a fresh decoder at `complexity`. If `osce_bwe`
 * is 0 or 1 it is set through OPUS_SET_OSCE_BWE; -1 leaves it alone. If
 * `lost_frames` > 0, that many frames of packet loss concealment follow.
 * Returns the number of frames written to `out`, or -1 on error.
 * *bwe_unimplemented is set when the BWE control is unknown to this build. */
static int decode_run(int complexity, int osce_bwe, int count, int lost_frames,
                      Decoded *out, int *bwe_unimplemented) {
  int err = OPUS_OK;
  int frame;
  int total = 0;
  OpusDecoder *dec = opus_decoder_create(SAMPLE_RATE, 1, &err);

  if (dec == NULL || err != OPUS_OK) return -1;
  if (opus_decoder_ctl(dec, OPUS_SET_COMPLEXITY(complexity)) != OPUS_OK) {
    opus_decoder_destroy(dec);
    return -1;
  }
  if (osce_bwe >= 0) {
    int r = opus_decoder_ctl(dec, OPUS_SET_OSCE_BWE(osce_bwe));
    if (r == OPUS_UNIMPLEMENTED && bwe_unimplemented != NULL) {
      *bwe_unimplemented = 1;
    } else if (r != OPUS_OK) {
      opus_decoder_destroy(dec);
      return -1;
    }
  }
  for (frame = 0; frame < count; frame++) {
    int n = opus_decode(dec, g_packets[frame].data, g_packets[frame].len,
                        out->pcm[total], FRAME_SIZE, 0);
    if (n != FRAME_SIZE) {
      opus_decoder_destroy(dec);
      return -1;
    }
    total++;
  }
  for (frame = 0; frame < lost_frames; frame++) {
    int n = opus_decode(dec, NULL, 0, out->pcm[total], FRAME_SIZE, 0);
    if (n != FRAME_SIZE) {
      opus_decoder_destroy(dec);
      return -1;
    }
    total++;
  }
  opus_decoder_destroy(dec);
  return total;
}

/* 1 if frames [first, first+count) differ in any sample. */
static int frames_differ(const Decoded *a, const Decoded *b, int first, int count) {
  return memcmp(a->pcm[first], b->pcm[first], (size_t)count * FRAME_SIZE * sizeof(opus_int16)) != 0;
}

/* Static: two decoded sets, so repeated calls reuse the same memory. */
static Decoded g_a, g_b;

APP_OPUS_PROBE_API const char *app_opus_version(void) { return opus_get_version_string(); }

APP_OPUS_PROBE_API int app_opus_deep_plc_active(void) {
  if (!build_packets()) return -1;
  /* Packets 0-19, then one concealed frame, at complexity 4 and 5. */
  if (decode_run(4, -1, 20, 1, &g_a, NULL) != 21) return -1;
  if (decode_run(5, -1, 20, 1, &g_b, NULL) != 21) return -1;
  return frames_differ(&g_a, &g_b, 20, 1);
}

APP_OPUS_PROBE_API int app_opus_osce_active(void) {
  if (!build_packets()) return -1;
  /* Packets 0-29 at complexity 5 and 7; compare the last 10 frames. */
  if (decode_run(5, -1, NUM_FRAMES, 0, &g_a, NULL) != NUM_FRAMES) return -1;
  if (decode_run(7, -1, NUM_FRAMES, 0, &g_b, NULL) != NUM_FRAMES) return -1;
  return frames_differ(&g_a, &g_b, NUM_FRAMES - 10, 10);
}

APP_OPUS_PROBE_API int app_opus_bwe_active(void) {
  int unimplemented = 0;
  if (!build_packets()) return -1;
  /* Packets 0-29 at complexity 7, BWE on and off; compare the last 10 frames. */
  if (decode_run(7, 1, NUM_FRAMES, 0, &g_a, &unimplemented) != NUM_FRAMES) return -1;
  if (unimplemented) return 0;
  if (decode_run(7, 0, NUM_FRAMES, 0, &g_b, &unimplemented) != NUM_FRAMES) return -1;
  if (unimplemented) return 0;
  return frames_differ(&g_a, &g_b, NUM_FRAMES - 10, 10);
}
