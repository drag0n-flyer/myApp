#ifndef APP_OPUS_PROBE_H
#define APP_OPUS_PROBE_H

#include <opus.h>

/* The probe is linked into the WebRTC framework, which must not export plain C
 * symbols of ours (see check-symbols.sh), so every function is hidden. */
#if defined(__GNUC__) || defined(__clang__)
#define APP_OPUS_PROBE_API __attribute__((visibility("hidden")))
#else
#define APP_OPUS_PROBE_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* opus_get_version_string() */
APP_OPUS_PROBE_API const char *app_opus_version(void);

/* Each probe returns 1 (feature changes the decoded audio), 0 (it does not),
 * or -1 on an internal error. */
APP_OPUS_PROBE_API int app_opus_deep_plc_active(void);
APP_OPUS_PROBE_API int app_opus_osce_active(void);
APP_OPUS_PROBE_API int app_opus_bwe_active(void);

#ifdef __cplusplus
}
#endif

#endif /* APP_OPUS_PROBE_H */
