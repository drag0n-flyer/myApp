// JNI bridge for org.example.app.calls.android.media.RnnoiseJni.
#include <jni.h>
#include <stdint.h>
#include <stdatomic.h>
#include <stddef.h>
#include <string.h>

#include "rnnoise.h"

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

typedef struct HookContext {
    atomic_int enabled;
    atomic_long denoised_frames;
    atomic_int last_frame_size;
    DenoiseState *state;
} HookContext;

static HookContext g_hook;
static AppAudioCaptureProcessor g_processor;

static void hook_initialize(void *context, int sample_rate_hz, int num_channels) {
    (void)sample_rate_hz;
    (void)num_channels;
    HookContext *h = (HookContext *)context;
    if (h->state == NULL) h->state = rnnoise_create(NULL);
}

static void hook_process(void *context, float *const *channels, size_t num_frames, size_t num_channels) {
    HookContext *h = (HookContext *)context;
    atomic_store(&h->last_frame_size, (int)num_frames);
    if (!atomic_load(&h->enabled) || h->state == NULL || num_frames != FRAME_SAMPLES || num_channels < 1) return;
    /* WebRTC passes samples in [-1, 1]; RNNoise works in 16-bit scale. */
    float frame[FRAME_SAMPLES];
    for (size_t i = 0; i < FRAME_SAMPLES; i++) frame[i] = channels[0][i] * 32768.0f;
    rnnoise_process_frame(h->state, frame, frame);
    for (size_t c = 0; c < num_channels; c++) {
        for (size_t i = 0; i < FRAME_SAMPLES; i++) {
            float v = frame[i] / 32768.0f;
            channels[c][i] = v > 1.0f ? 1.0f : (v < -1.0f ? -1.0f : v);
        }
    }
    atomic_fetch_add(&h->denoised_frames, 1);
}

static void hook_reset(void *context) {
    HookContext *h = (HookContext *)context;
    if (h->state != NULL) rnnoise_destroy(h->state);
    h->state = rnnoise_create(NULL);
}

static void hook_destroy(void *context) {
    HookContext *h = (HookContext *)context;
    if (h->state != NULL) rnnoise_destroy(h->state);
    h->state = NULL;
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
