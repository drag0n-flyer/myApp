# [App] App Store Readiness Checklist

This list covers what must be true before the beta reaches TestFlight and before App Store submission. Items marked 👤 need the owner or the lawyer. Items marked "agent" are done in the repository. Sources were read on 2026-10-28. Apple changes its rules, so check each linked source before you submit.

Run the automatic check any time:

```
python3 scripts/check-release-config.py --allow-missing --placeholder-brand-ok --filtering-pending
```

It lists every requirement in the table below as `PASS`, `MISSING` or `PENDING-OWNER`. Drop `--allow-missing` for the real release: then any `MISSING` makes it fail. Drop `--placeholder-brand-ok` once the brand and bundle ID are chosen, and drop `--filtering-pending` once Apple grants the filtering entitlement.

## 1. Account

- [ ] 👤 Enroll in the Apple Developer Program **as an organization** (D-U-N-S number, legal entity) before App Store submission. An individual account is not enough for this app (GOAL §17; App Review Guideline 5.1.1(ix)). Individual accounts can still upload to TestFlight for internal testing.
- [ ] 👤 Create the App Store Connect app record with the final bundle IDs. Never put credentials, team IDs or certificates in this repository.

## 2. Entitlements and capabilities

`scripts/check-release-config.py` checks these from `apps/apple/project.yml` and the `*.entitlements` files.

| Target | Requirement |
|---|---|
| iOS app | `aps-environment`; `UIBackgroundModes` containing `voip`, `remote-notification` and `audio`; associated domains with `applinks:` and `webcredentials:`; app group `group.org.example.app`; `keychain-access-groups`; `ITSAppUsesNonExemptEncryption`; non-empty `NSMicrophoneUsageDescription` and `NSCameraUsageDescription` |
| iOS NSE | the app's app group and keychain group; `com.apple.developer.usernotifications.filtering` (only after Apple grants it) |
| Mac app | `com.apple.developer.aps-environment`; app sandbox; network client; device audio input and camera; the app group; the keychain group; associated domains |
| Mac NSE | the app group and the keychain group |
| All | a real bundle ID (not `org.example.app`); a non-empty app icon set |

- [ ] agent: `ITSAppUsesNonExemptEncryption = YES` is set in `project.yml` for both apps and both NSEs. Without it, every TestFlight upload stops at the export compliance question. See section 8.
- [ ] 👤 Register the app group, the keychain sharing group, the push notification capability, associated domains and the VoIP background mode on the real App IDs in the developer portal.
- [ ] 👤 Request the **notification-filtering entitlement** (`com.apple.developer.usernotifications.filtering`) from Apple, using this justification text:

  > E2EE messenger; the NSE must silently drop duplicate pushes and pushes for removed devices.

  The repository already has `NSE-iOS-filtering.entitlements`. The default build uses the plain file, so builds do not fail before Apple answers. When Apple grants it, set `NSE_FILTERING: 'YES'` and drop `--filtering-pending` from the check.
- [ ] agent: **CallKit and PushKit rule.** Every VoIP push must be reported to CallKit, with no exception (plan 8). Apple stops delivering VoIP pushes to apps that receive one and do not report a call. Keep this rule in the review notes (section 7).
- [ ] 👤 Add the Mac camera entitlement (`com.apple.security.device.camera`) before a Mac build goes out. The checker reports it as `MISSING` until then.

## 3. Guideline 1.2 (user-generated content)

Apps with user-generated content need four things ([App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/) §1.2):

- [ ] A method for filtering objectionable material. Message requests from strangers (plan 1 UI) and blocking provide it.
- [ ] A mechanism to report content, with timely responses. The in-app report (plan 2 E2), the abuse contact, and an operator response target of 24 hours provide it. 👤 The operator commits to the 24 hour target.
- [ ] The ability to block abusive users from the service (plan 2 E2 and plan 1 UI).
- [ ] Published contact information so users can reach the operator. The server info document ([server design §7.5](../superpowers/specs/2026-10-27-server-design.md)) and the [privacy policy](../legal/privacy-policy.md) carry it. 👤 Fill in the real contact address before release.

## 4. Guideline 5.1.1(v): account deletion

- [ ] agent: In-app account deletion exists: plan 2 A4 (`DELETE /v1/me`) on the server, and the plan 6 UI in the app.
- [ ] agent: Deletion is immediate, deletes user content, and a self-hoster cannot disable it ([Apple: offering account deletion](https://developer.apple.com/support/offering-account-deletion-in-your-app/)).
- [ ] 👤 Tell the reviewer where to find it in the review notes (section 7).

## 5. Privacy nutrition labels

These are the App Store Connect "App Privacy" answers. They come from section 2 of the [privacy policy](../legal/privacy-policy.md). Keep them in step with that policy.

| Apple category | Answer | Notes |
|---|---|---|
| Contact Info → Email Address | Collected | Used for App Functionality. Linked to the user's identity. Not used for tracking. |
| User Content → messages, photos and audio | **Not collected** in end-to-end encrypted chats | Apple's definition covers data the developer can access, and the operator cannot read these. |
| Identifiers → User ID | Collected | Used for App Functionality. Linked to the user's identity. Not used for tracking. |
| Diagnostics | None | Stays "none" until crash reporting is chosen. Change this answer when that happens. |

- [ ] 👤 **Lawyer decision:** server-readable mode lets the server read messages, so for that mode User Content becomes "collected" for the operator. Decide how to answer, and record the decision in this file.
- [ ] 👤 Answer "No" to tracking. The app has no advertising or cross-app tracking.
- [ ] agent: Privacy manifest (`PrivacyInfo.xcprivacy`) for each app target. Declare no tracking, no tracking domains, the collected data types above, and a reason for each "required reason" API the code calls (for example file timestamps, user defaults and disk space). Build the list from the code, because Apple rejects uploads that use these APIs without a declared reason ([Apple: privacy manifest files](https://developer.apple.com/documentation/bundleresources/privacy-manifest-files)). This file is not in the repository yet. It goes in with the app UI work, and the checker does not look for it.

## 6. Brand and assets

All of these wait on the owner choosing a brand. 👤

- [ ] App name (replaces the placeholder `[App]`).
- [ ] Bundle IDs (replace `org.example.app`), app group and keychain group names.
- [ ] App icon at 1024 × 1024 px. The checker reports an empty icon set as `MISSING`.
- [ ] Screenshots for each device size Apple asks for.
- [ ] Privacy policy URL: `https://<host>/legal/privacy`.
- [ ] Support URL and marketing text.

## 7. TestFlight

Internal testing (up to 100 people on your App Store Connect team) comes first and needs no review. External testing needs Beta App Review, so prepare review notes from [review-notes-template.md](review-notes-template.md).

- [ ] 👤 Set up a reviewer server and give its URL in the notes.
- [ ] 👤 Create two demo accounts on it. Enter the credentials in App Store Connect (App Information, "Sign-in information"). **Never write them in the repository.**
- [ ] 👤 Explain how to receive an invite, how to make a call between the two accounts, and why VoIP push is used. The template has the wording.
- [ ] 👤 Give the encryption export answers from the [export compliance memo](../legal/export-compliance.md).
- [ ] 👤 Start with one internal build, then submit for external testing.

## 8. Export compliance

See the [export compliance memo](../legal/export-compliance.md) (Task 9).

- [ ] agent: `ITSAppUsesNonExemptEncryption = YES` in every app and extension target.
- [ ] 👤 Answer the export compliance questions in App Store Connect as the memo says.
- [ ] 👤 Add the `ITSEncryptionExportComplianceCode` to `apps/apple/project.yml` once Apple issues it.
