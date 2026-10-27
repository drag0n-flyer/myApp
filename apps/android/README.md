# Android app

Build settings live in the root `build.gradle.kts`, versions only in `gradle/libs.versions.toml`, and the application ID only in `app-id.properties` (`./gradlew checkAppId` fails if it is copied anywhere else; package names and namespaces stay `org.example.app...`).

## Push setup

- Push is battery-optimised: no foreground service for push, and the app never asks for the battery-optimisation exemption.
- Application ID: only `app-id.properties`; `:push` reads it into `BuildConfig.APP_ID`.
- Gateway: `push-gateway.properties` (committed, public values) holds `gatewayUrl` and `gatewayKey` (X25519 public key, base64url). With an empty key the app registers no FCM token.
- Firebase (FCM): the owner creates `apps/android/firebase.properties` (gitignored) with `projectId`, `appId`, `apiKey` and `senderId`, copied from the Firebase console. No `google-services.json` is used or committed. Without the file, `FirebaseInit.init` does nothing and the app picks UnifiedPush when a distributor is installed.
