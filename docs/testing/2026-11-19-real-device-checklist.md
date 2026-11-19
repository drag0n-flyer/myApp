# Real-device check (v0.2.0)

Three devices: an Android phone (APK), an iPhone (unsigned .ipa, sideloaded), a Mac (zip).
Two accounts on chat.shimul.me: **A** (Android + Mac linked) and **B** (iPhone).
Unsigned iPhone/Mac builds sign in with the email code (no passkeys). Note anything that
looks wrong, is slow, or needs explaining; a screenshot per problem is enough.

## 1. Sign-up and linking
- [ ] Android: create account A (invite code, name, username, email code). Under 2 minutes, no confusing step.
- [ ] iPhone: create account B the same way.
- [ ] Mac: "I already have an account" → link to A by scanning the QR with the Android phone.
- [ ] Mac window: nothing cut off, buttons a normal Mac size.

## 2. Contacts and chat
- [ ] A finds B by username and starts a chat; B sees a message request and accepts.
- [ ] Text both ways; ticks go ✓ → ✓✓; the Mac shows the same chat as the Android phone.
- [ ] Swipe a message to reply (Android and iPhone); reply quote shows on the other side.
- [ ] React with an emoji; edit a message; delete one for everyone.
- [ ] Typing indicator appears and goes away.

## 3. Media
- [ ] Photo from each device; opens full screen on the others.
- [ ] Voice note from each device; plays on the others.
- [ ] A file (PDF) from the Mac.

## 4. Groups
- [ ] A creates a group with B; rename it; set a photo; B leaves and is re-added.

## 5. Calls
- [ ] Audio call Android → iPhone, 2 minutes, both directions audible.
- [ ] Video call iPhone → Android; switch camera; hang up from either side.
- [ ] Call from the Mac.
- [ ] Missed call shows in Calls and in the chat.

## 6. Bad network
- [ ] Turn Wi-Fi off on one phone, send messages to it, turn it back on: they arrive in order.
- [ ] Call on mobile data with weak signal: does it keep going?

## 7. Notifications (expected gaps)
- [ ] Android: message with the app closed → notification arrives.
- [ ] iPhone/Mac: none while closed (no Apple developer account yet). Confirm only.

## Report
For each failed box: device, what you did, what you saw, what you expected.
