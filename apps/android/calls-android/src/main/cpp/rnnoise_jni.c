// JNI bridge for org.example.app.calls.android.media.Rnnoise.
#include <jni.h>
#include <stdint.h>

#include "rnnoise.h"

#define FRAME_SAMPLES 480

JNIEXPORT jlong JNICALL
Java_org_example_app_calls_android_media_Rnnoise_nativeCreate(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    if (rnnoise_get_frame_size() != FRAME_SAMPLES) return 0;
    return (jlong)(intptr_t)rnnoise_create(NULL);
}

JNIEXPORT jfloat JNICALL
Java_org_example_app_calls_android_media_Rnnoise_nativeProcess(JNIEnv *env, jclass cls, jlong handle, jfloatArray frame) {
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
Java_org_example_app_calls_android_media_Rnnoise_nativeDestroy(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    DenoiseState *st = (DenoiseState *)(intptr_t)handle;
    if (st != NULL) rnnoise_destroy(st);
}
