# Android WebRTC smoke project

A throwaway Gradle project that CI (`.github/workflows/webrtc-android.yml`, job `smoke`)
uses to run the instrumented smoke test against the freshly built AAR on an API 29
x86_64 emulator. It is not part of `apps/android`; the `:webrtc` module there replaces
it once the Gradle scaffold is on main. CI copies the AAR to `app/libs/libwebrtc.aar`.

Run by hand (needs /dev/kvm): `gradle :app:pixel6Api29DebugAndroidTest`.
