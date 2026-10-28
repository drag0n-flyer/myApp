# Android device checklist

What the owner checks by hand on real devices before the Android private beta: a OnePlus 5 (Android 10, API 29, the oldest phone we support) and an iPhone, both signed in to the same test server. CI cannot check these. The protocol between the two clients is already checked on every change by the interop run (`scripts/interop.sh`, workflow `interop`): sign-up, contacts, text, reply, reaction, edit, receipts, photos, escrow copies, safety numbers, call signaling and device linking.

How to record a result: put a date and your initials in the **Result** column (`👤 2026-11-05 JD`), or `fail` with a short note and an issue link. A row passes only when it passes in both directions where it says so.

## Before you start

- [ ] Both phones run the build under test (Android: the release build from the `android` workflow; iPhone: the current TestFlight build). Write both version numbers here: Android ____, iPhone ____.
- [ ] Both are signed in to accounts on the same server, are each other's contacts and have chatted at least once.
- [ ] The OnePlus 5 is charged to 100 %, has battery optimisation set to the default, and has only the test app installed besides the system apps.
- [ ] A Bluetooth headset is paired with each phone.

## Calls

The modes are the five the app shows: Auto, Ultra-low, Data saver, Balanced and High quality.

| # | Check | Result |
|---|---|---|
| 1 | Audio call Android → iPhone in each mode (5 calls): rings, connects within 3 s of answering, both hear each other clearly for 1 minute | |
| 2 | Audio call iPhone → Android in each mode (5 calls): the same | |
| 3 | Video call Android → iPhone, then iPhone → Android: both videos show, sound in sync | |
| 4 | Mode change in the middle of a call, each direction: the other side follows within a few seconds, the call never drops | |
| 5 | Wi-Fi → mobile data, then mobile data → Wi-Fi in the middle of a call (turn Wi-Fi off, then on): the call recovers within 5 s, both directions | |
| 6 | Bluetooth headset: answer from the headset, audio stays on the headset, switch to the speaker and back from the call screen | |
| 7 | Ring when the app is killed, FCM: swipe the app away on the OnePlus 5, call it from the iPhone: it rings with the full-screen call screen | |
| 8 | Ring when the app is killed, UnifiedPush (a distributor app installed and chosen in Settings): the same as row 7 | |
| 9 | A stranger's call does not ring: a third account that is not a contact calls the OnePlus 5: no ring, a missed-call line appears in the message requests | |
| 10 | Push while locked: lock both phones, send a message each way: the notification shows the sender and the text within 10 s | |

## Performance

| # | Check | Target | Result |
|---|---|---|---|
| 11 | One hour idle on the OnePlus 5: signed in, screen off, on Wi-Fi, no calls. Note the battery % before and after (Settings → Battery) | at most 2 % per hour ([GOAL.md](../../GOAL.md) section 9) | |
| 12 | Cold start: force-stop the app, open it, time until the chat list shows (three runs, take the median) | at most 2 s | |
| 13 | A chat with 10 000 messages (seeded on the test server): scroll from the newest to the oldest and back; no visible stutter, no blank rows | smooth | |

## SDP fixtures for Task E3

The interop run seals a recorded SDP offer from each platform: `apps/android/interop/fixtures/chrome-offer.sdp` stands in for the Android build (the same libwebrtc), `ios-webrtc-offer.sdp` is the iPhone build's. Record the real ones during row 1:

- [ ] From the Android build: the first offer of an audio call and of a video call (a debug build; copy it from the log). Save them as `android-audio-offer.sdp` and `android-video-offer.sdp` in `apps/android/calls/src/test/resources/fixtures/`.
- [ ] From the iPhone build: the same two offers. Save them as `ios-audio-offer.sdp` and `ios-video-offer.sdp` in `apps/apple/Packages/CallCore/Tests/CallCoreTests/Fixtures/` and in the Android fixtures folder.
- [ ] Remove the ICE candidates' IP addresses from every file before you commit it (replace them with `192.0.2.1`).

## Sign-off

- [ ] Every row above has a result. Failed rows have an issue.
- Owner: ____ Date: ____
