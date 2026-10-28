# [App] Android: Google Play release steps

This is the owner's checklist for the first Play release. Items marked "OWNER TO CONFIRM" are decisions or actions only the owner can take. Nothing here is automated. CI builds an unsigned bundle, and the owner signs and uploads it. Never put a keystore, a password or Play credentials in this repository.

Sources to re-read before you submit, because Google changes its rules: Play Console Help on app signing, on foreground service types, on the full-screen intent declaration, on the Data safety form, and on account deletion.

## 1. Developer account and app record

- [ ] OWNER TO CONFIRM: create a Google Play Developer account. An organization account needs a D-U-N-S number. A new personal account may need a closed test before production, so check the current rule.
- [ ] OWNER TO CONFIRM: create the app record in Play Console with the final application ID. The ID is set only in `apps/android/app-id.properties`. It is still the placeholder, so choose the real one before the first upload, because it cannot change later.
- [ ] OWNER TO CONFIRM: the app name shown on Play. The in-app name is the placeholder `[App]` (`AppBrand.name`) until the owner chooses one.

## 2. Play App Signing and the upload key

Use Play App Signing. Google holds the app signing key. The owner makes and keeps only the upload key.

1. OWNER TO CONFIRM: make an upload keystore on a machine you trust, for example with `keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000`. Keep the keystore and its passwords in a password manager, with a backup. If the upload key is lost, Play support can reset it, but that takes time.
2. Enroll in Play App Signing when you upload the first bundle.
3. Build the signed bundle on your own machine. The release build reads signing settings only from Gradle properties or environment variables, and signs only when all four are set:

   | Name | Meaning |
   |---|---|
   | `APP_UPLOAD_STORE_FILE` | path to the upload keystore |
   | `APP_UPLOAD_STORE_PASSWORD` | keystore password |
   | `APP_UPLOAD_KEY_ALIAS` | key alias |
   | `APP_UPLOAD_KEY_PASSWORD` | key password |

   ```
   cd apps/android
   APP_UPLOAD_STORE_FILE=/path/upload.jks APP_UPLOAD_STORE_PASSWORD=... \
   APP_UPLOAD_KEY_ALIAS=upload APP_UPLOAD_KEY_PASSWORD=... ./gradlew :app:bundleRelease
   ```

   Without them the bundle is unsigned. The `android` workflow's `release-bundle` job builds exactly that and fails if the bundle turns out to be signed.
4. Upload `apps/android/app/build/outputs/bundle/release/app-release.aab` in Play Console.

## 3. What the `release-bundle` job already checks

It runs on `main` and by hand (workflow dispatch), and `android-ci` requires it.

- The bundle's native ABIs are exactly `arm64-v8a`, `armeabi-v7a` and `x86_64`, and `libmlsffi.so` is in each.
- Every 64-bit `.so` in the bundle is aligned to 16 KB (Play requires this for new apps and updates that target Android 15 and later). 32-bit `armeabi-v7a` libraries are exempt.
- R8 stays on. `checkR8KeepRules` and `checkNoComponentProcess` run in the `assemble` job.
- Every Gradle runtime dependency has an allowed license, or an owner-approved row in `docs/security/license-allowlist.md` (section gradle-libraries). Copyleft licenses fail. JNA counts as Apache-2.0.
- `apps/android/app/src/main/assets/oss-notices.json`, the list on Settings, Open-source licenses, is up to date. Regenerate it with `scripts/generate-oss-notices.py --android-output apps/android/app/src/main/assets/oss-notices.json` after `./gradlew :app:exportRuntimeDependencies`.
- A tagged release (`v*` tag push) fails the notices check until the SQLCipher, WebRTC and Opus notice texts are filled in (`--release` makes a pending notice an error). On main and in pull requests the check only prints a WARNING listing each pending notice.
- `gitleaks` finds no secret in `apps/android`.

## 4. Foreground service declarations

Play Console asks for each foreground service type the app declares, with a short description and a video or steps to see it.

| Type | Where | Why | Status |
|---|---|---|---|
| `dataSync` | WorkManager's `SystemForegroundService`, Android 10 and 11 only | Expedited work for fetching a message after a push runs as a short foreground service on those versions. On Android 12 and later it is a plain job and no foreground service starts. | Declared in `push/src/main/AndroidManifest.xml` |
| `phoneCall`, `microphone`, `camera` | `CallService` during an answered call | Keeps a call running while the app is in the background. Only while a call is in progress. | Not in the manifest yet. It lands with the calls work, then declare it here. OWNER TO CONFIRM the exact types. |

The app never asks for the battery-optimization exemption and keeps no always-connected service.

## 5. Full-screen intent declaration

The app declares `USE_FULL_SCREEN_INTENT` to show the incoming-call screen over the lock screen. Play treats this as a restricted permission for apps whose core function is calling or alarms.

- [ ] OWNER TO CONFIRM: in Play Console, App content, declare the full-screen intent use: "Incoming call screen for end-to-end encrypted voice and video calls." Core function: calling.
- The app checks `canUseFullScreenIntent` and, when Android has turned it off, Settings, Notifications explains it and opens the system page.

## 6. Other declarations in App content

- [ ] OWNER TO CONFIRM: Data safety form. Use [android-data-safety.md](android-data-safety.md).
- [ ] OWNER TO CONFIRM: privacy policy URL. The policy text is [the privacy policy](../legal/privacy-policy.md). It is a draft that needs lawyer review, and it must be published at a public URL first.
- [ ] OWNER TO CONFIRM: account deletion. See section 7.
- [ ] OWNER TO CONFIRM: content rating questionnaire, target audience and content (the minimum age is still a lawyer decision), ads declaration (no ads), government apps (no), financial features (none).
- [ ] OWNER TO CONFIRM: permissions declarations. The app asks for camera, microphone and notifications at the moment a feature needs them.
- [ ] OWNER TO CONFIRM: export compliance and encryption. The app uses end-to-end encryption. See [export compliance](../legal/export-compliance.md).
- [ ] OWNER TO CONFIRM: testing track. Start with internal testing, then closed testing, before production.

## 7. Account deletion web page

Play requires a web page where a person can ask for account deletion without installing the app, and the in-app path. The in-app path exists (the privacy policy, section 7). The web page does not exist yet.

- URL: PLACEHOLDER, OWNER TO CONFIRM (not yet chosen or built).
- What the page must say: the app name and developer name as shown on Play, the steps to delete the account, what is deleted and what is kept and for how long (see the privacy policy, section 6), and a contact address.
- Where it is hosted: PLACEHOLDER, OWNER TO CONFIRM.

## 8. Crash reports

The privacy policy says crash reports are sent to the operator. How they are sent, who receives them and how long they are kept are still open owner items (privacy policy, section 2). The Android app has no crash reporter yet. Play Console's own Android vitals need no code.

- Crash report handling: PLACEHOLDER, OWNER TO CONFIRM.

## 9. Before each release

- [ ] Raise `versionCode` in `apps/android/build.gradle.kts`.
- [ ] Green `android` workflow on the commit you release, including `release-bundle`.
- [ ] Upload the signed bundle to the internal track first.
