# Android app

Build settings live in the root `build.gradle.kts`, versions only in `gradle/libs.versions.toml`, and the application ID only in `app-id.properties` (`./gradlew checkAppId` fails if it is copied anywhere else; package names and namespaces stay `org.example.app...`).

Before building the app (`assembleDebug`, `bundleRelease`), run `scripts/android-fetch-rnnoise-model.sh` once per checkout: it fetches the RNNoise model weights, which are not committed (see `calls-android/src/main/cpp/rnnoise/VENDORED.md`). The native build needs the Android SDK's CMake 3.22.1; without it, set `cmake.dir` in `local.properties`.

## Push setup

- Push is battery-optimised: no foreground service for push, and the app never asks for the battery-optimisation exemption.
- Application ID: only `app-id.properties`; `:push` reads it into `BuildConfig.APP_ID`.
- Gateway: `push-gateway.properties` (committed, public values) holds `gatewayUrl` and `gatewayKey` (X25519 public key, base64url). With an empty key the app registers no FCM token.
- Firebase (FCM): the owner creates `apps/android/firebase.properties` (gitignored) with `projectId`, `appId`, `apiKey` and `senderId`, copied from the Firebase console. No `google-services.json` is used or committed. Without the file, `FirebaseInit.init` does nothing and the app picks UnifiedPush when a distributor is installed.
