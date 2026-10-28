// JNI bridge for org.example.app.calls.android.media.RnnoiseJni.
#include <jni.h>
#include <stdint.h>
#include <stdatomic.h>
#include <stddef.h>
#include <string.h>

#include "rnnoise.h"

#ifdef APP_CAPTURE_TEST_TONE
#include <math.h>
#endif

#define FRAME_SAMPLES 480

JNIEXPORT jlong JNICALL
Java_org_example_app_calls_android_media_RnnoiseJni_nativeCreate(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    if (rnnoise_get_frame_size() != FRAME_SAMPLES) return 0;
    return (jlong)(intptr_t)rnnoise_create(NULL);
}

JNIEXPORT jfloat JNICALL
Java_org_example_app_calls_android_media_RnnoiseJni_nativeProcess(JNIEnv *env, jclass cls, jlong handle, jfloatArray frame) {
    (void)cls;
    DenoiseState *st = (DenoiseState *)(intptr_t)handle;
    if (st == NULL || (*env)->GetArrayLength(env, frame) != FRAME_SAMPLES) return -1.0f;
    float buffer[FRAME_SAMPLES];
    (*env)->GetFloatArrayRegion(env, frame, 0, FRAME_SAMPLES, buffer);
    float vad = rnnoise_process_frame(st, buffer, buffer);
    (*env)->SetFloatArrayRegion(env, frame, 0, FRAME_SAMPLES, buffer);
    return vad;
}

JNIEXPORT void JNICALL
Java_org_example_app_calls_android_media_RnnoiseJni_nativeDestroy(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    DenoiseState *st = (DenoiseState *)(intptr_t)handle;
    if (st != NULL) rnnoise_destroy(st);
}

/*
 * The WebRTC capture hook (tools/webrtc/patches/0007, sdk/android/src/jni/app/audio_capture_processor.h).
 * The struct is declared again here because that header is not shipped in the AAR; it is plain C and
 * versioned by abi_version. One hook per process: the PeerConnectionFactory is a process-wide singleton.
 * RNNoise runs only while `enabled` (Enhanced) and only on 480-sample (10 ms at 48 kHz) mono frames.
 */

#define APP_AUDIO_CAPTURE_PROCESSOR_ABI_VERSION 1

typedef struct AppAudioCaptureProcessor {
    uint32_t abi_version;
    void *context;
    void (*initialize)(void *context, int sample_rate_hz, int num_channels);
    void (*process)(void *context, float *const *channels, size_t num_frames, size_t num_channels);
    void (*reset)(void *context);
    void (*destroy)(void *context);
} AppAudioCaptureProcessor;

/*
 * Threads: WebRTC calls initialize, process and reset under its capture lock, and destroy only when the audio
 * processing module is freed. This code does not rely on that: the state pointer is atomic and created once
 * (compare-and-swap), a reset is a request flag that the audio thread carries out in place (rnnoise_init, no
 * allocation), and the Java side only touches atomics. destroy alone assumes no frame is in flight, which holds
 * because WebRTC frees the module after its last frame.
 */
typedef struct HookContext {
    atomic_int enabled;
    atomic_int reset_pending;
    atomic_long denoised_frames;
    atomic_int last_frame_size;
    _Atomic(DenoiseState *) state;
    atomic_int sample_rate_hz;
#ifdef APP_CAPTURE_TEST_TONE
    /* Test stimulus (debug builds only): a 440 Hz tone written into the capture, so loopback tests send real audio. */
    atomic_int tone;
    atomic_long tone_frames;
    double tone_phase; /* audio thread only */
#endif
} HookContext;

static HookContext g_hook;
static AppAudioCaptureProcessor g_processor;

static void hook_initialize(void *context, int sample_rate_hz, int num_channels) {
    (void)num_channels;
    HookContext *h = (HookContext *)context;
    atomic_store(&h->sample_rate_hz, sample_rate_hz);
    if (atomic_load(&h->state) != NULL) return;
    DenoiseState *fresh = rnnoise_create(NULL);
    DenoiseState *expected = NULL;
    if (fresh != NULL && !atomic_compare_exchange_strong(&h->state, &expected, fresh)) rnnoise_destroy(fresh);
}

#ifdef APP_CAPTURE_TEST_TONE
static void write_tone(HookContext *h, float *const *channels, size_t num_frames, size_t num_channels) {
    int rate = atomic_load(&h->sample_rate_hz);
    double step = 2.0 * 3.14159265358979323846 * 440.0 / (rate > 0 ? rate : 48000);
    for (size_t i = 0; i < num_frames; i++) {
        float v = (float)(0.3 * sin(h->tone_phase));
        h->tone_phase += step;
        if (h->tone_phase > 2.0 * 3.14159265358979323846) h->tone_phase -= 2.0 * 3.14159265358979323846;
        for (size_t c = 0; c < num_channels; c++) channels[c][i] = v;
    }
    atomic_fetch_add(&h->tone_frames, 1);
}
#endif

static void hook_process(void *context, float *const *channels, size_t num_frames, size_t num_channels) {
    HookContext *h = (HookContext *)context;
    atomic_store(&h->last_frame_size, (int)num_frames);
    DenoiseState *st = atomic_load(&h->state);
    if (st != NULL && atomic_exchange(&h->reset_pending, 0)) rnnoise_init(st, NULL);
    if (atomic_load(&h->enabled) && st != NULL && num_frames == FRAME_SAMPLES && num_channels >= 1) {
        /* WebRTC passes samples in [-1, 1]; RNNoise works in 16-bit scale. */
        float frame[FRAME_SAMPLES];
        for (size_t i = 0; i < FRAME_SAMPLES; i++) frame[i] = channels[0][i] * 32768.0f;
        rnnoise_process_frame(st, frame, frame);
        for (size_t c = 0; c < num_channels; c++) {
            for (size_t i = 0; i < FRAME_SAMPLES; i++) {
                float v = frame[i] / 32768.0f;
                channels[c][i] = v > 1.0f ? 1.0f : (v < -1.0f ? -1.0f : v);
            }
        }
        atomic_fetch_add(&h->denoised_frames, 1);
    }
#ifdef APP_CAPTURE_TEST_TONE
    if (atomic_load(&h->tone)) write_tone(h, channels, num_frames, num_channels);
#endif
}

static void hook_reset(void *context) {
    atomic_store(&((HookContext *)context)->reset_pending, 1);
}

static void hook_destroy(void *context) {
    HookContext *h = (HookContext *)context;
    DenoiseState *st = atomic_exchange(&h->state, NULL);
    if (st != NULL) rnnoise_destroy(st);
}

JNIEXPORT jlong JNICALL
Java_org_example_app_calls_android_media_CaptureHookJni_nativeProcessor(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    if (rnnoise_get_frame_size() != FRAME_SAMPLES) return 0;
    memset(&g_processor, 0, sizeof g_processor);
    g_processor.abi_version = APP_AUDIO_CAPTURE_PROCESSOR_ABI_VERSION;
    g_processor.context = &g_hook;
    g_processor.initialize = hook_initialize;
    g_processor.process = hook_process;
    g_processor.reset = hook_reset;
    g_processor.destroy = hook_destroy;
    return (jlong)(intptr_t)&g_processor;
}

JNIEXPORT void JNICALL
Java_org_example_app_calls_android_media_CaptureHookJni_nativeSetEnabled(JNIEnv *env, jclass cls, jboolean on) {
    (void)env;
    (void)cls;
    atomic_store(&g_hook.enabled, on ? 1 : 0);
}

JNIEXPORT jlong JNICALL
Java_org_example_app_calls_android_media_CaptureHookJni_nativeDenoisedFrames(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return (jlong)atomic_load(&g_hook.denoised_frames);
}

JNIEXPORT jint JNICALL
Java_org_example_app_calls_android_media_CaptureHookJni_nativeLastFrameSize(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return (jint)atomic_load(&g_hook.last_frame_size);
}

JNIEXPORT void JNICALL
Java_org_example_app_calls_android_media_CaptureHookJni_nativeResetForCall(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    atomic_store(&g_hook.reset_pending, 1);
}

#ifdef APP_CAPTURE_TEST_TONE
/* Test hooks: compiled into debug builds only (CMake defines APP_CAPTURE_TEST_TONE for Debug). */
JNIEXPORT void JNICALL
Java_org_example_app_calls_android_media_CaptureHookJni_nativeSetTone(JNIEnv *env, jclass cls, jboolean on) {
    (void)env;
    (void)cls;
    atomic_store(&g_hook.tone, on ? 1 : 0);
}

JNIEXPORT jlong JNICALL
Java_org_example_app_calls_android_media_CaptureHookJni_nativeToneFrames(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return (jlong)atomic_load(&g_hook.tone_frames);
}
#endif
