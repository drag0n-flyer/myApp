# Daily Use (Android first): Design

Date: 2026-11-18. Follows [usable-app design](2026-11-18-usable-app-design.md) (Specs A and B). Source of scope: Specs C and D in the [feature comparison](2026-11-18-feature-comparison.md).

## 1. Why

After Specs A and B the Android app tells the truth and its chat and calls work. It still lacks the small things people use every day in a chat app: tidying the chat list, acting on a notification, locking the app, and looking like themselves. These four pieces need no server, protocol or encryption change, and they touch different files from the media and call work still in review, so they can be built now, in parallel.

The iPhone and Mac versions of the same four pieces follow once Tasks 3 and 7 of the usable-app plan are on `main`; this design is written so they can mirror it.

## 2. Goals

1. Chat list: pin, mute and archive a chat by swipe or long press; a draft shows in its row; drafts survive an app restart.
2. Notifications: one group per chat, Reply and Mark as read actions, an unread badge, and no sound for muted chats.
3. App lock: the app asks for the phone's fingerprint, face or screen lock before showing anything.
4. Profile: edit the display name; every person gets a coloured letter tile.

## 3. Non-goals

- Profile photos, real contact QR codes, one-time invite links (need server and identity work: separate design, owner review).
- Voice-note gestures, video, gallery, link previews, text formatting (need mockups first: Spec C, part 2).
- Typing indicators, last seen, disappearing messages, history backup (owner decisions 1–3 of the comparison, still open).
- Tablets, landscape, translations (Spec F).
- Syncing pin, mute or archive across a person's devices. Lead's call: they are local to each device for now; syncing needs a protocol message.

## 4. Anti-goals

- A lock that can be bypassed by a notification, a share intent, the recents screen or a call screen showing chat content.
- A notification Reply that silently fails to send.
- A muted or archived chat whose messages are lost or marked read.

## 5. Approaches

- **Pin/mute/archive storage.** (a) New columns on the chat row in the Room database with a migration; (b) a separate preferences file. Chosen: (a). The chat list already reads chats from the database in one query, and the wipe already covers it. This needs the database's first migration (version 1 to 2), written and tested.
- **Drafts across restart.** (a) Database table; (b) keep memory only. Chosen: (a), one `drafts` table keyed by chat, in the same migration. It replaces the usable-app ruling "drafts in memory only". Drafts stay on the device and are wiped with the account.
- **App lock mechanism.** (a) Platform `BiometricPrompt` with device-credential fallback; (b) our own PIN. Chosen: (a). No secret of ours to store, no new dependency (`android.hardware.biometrics.BiometricPrompt`, API 29+ which is our minimum), and it is the system's own sheet. The lock gates the screen only; it does not re-encrypt data.
- **Notification Reply.** (a) Send through the same background session the push worker opens; (b) open the app. Chosen: (a) if the existing worker path can send safely; otherwise the Reply action is left out and only Mark as read ships. A reply that cannot be sent must show a "Not sent" notification that opens the chat with the text as a draft.

## 6. Behaviour

### 6.1 Chat list (Task N4)

- Swipe a row from the end edge: **Archive** (or **Unarchive** in the archive). Swipe from the start edge: **Pin** / **Unpin**. Long press opens a menu: Pin/Unpin, Mute/Unmute, Archive/Unarchive, Mark as read.
- Pinned chats sort first, by pin time; then the rest by last message. A pin icon shows on the row. At most 5 pinned chats; a sixth shows "You can pin up to 5 chats."
- A muted chat shows a muted icon; its unread count is shown in a neutral colour; it does not count toward the launcher badge.
- Archived chats leave the list. A row "Archived" with the count appears at the top when there is at least one; it opens the archive. A new message does not unarchive a chat (lead's call, as on other chat apps' "keep archived" setting); its unread count shows on the Archived row.
- A chat with a draft shows "Draft: <text>" in place of the last message, the word Draft in the error colour.
- Empty archive: "No archived chats".

### 6.2 Notifications (Task N5)

- One notification per chat using `MessagingStyle`, with up to the last 6 unread messages, grouped under a summary when two or more chats have notifications.
- Actions: **Reply** (inline text) and **Mark as read**. Mark as read marks the chat read locally and sends the read receipt if the person's setting allows it, then removes the notification.
- Reading a chat in the app removes its notification.
- Muted chats (when N4 is merged; until then a `mutedChatIds` lookup that returns empty): notification posted silently in a low-importance channel "Muted chats". Missed calls are never muted.
- Launcher badge: number of unread, unmuted chats, through the notification's `setNumber` on the summary.
- With app lock on (N6): notifications show "New message" without sender or text, and Reply is not offered.

### 6.3 App lock (Task N6)

- Settings, Privacy: switch "App lock", subtitle "Ask for your fingerprint, face or screen lock to open [App]". Turning it on requires passing the prompt once. If the phone has no screen lock: the switch is disabled with "Set a screen lock in your phone's settings first."
- "Lock after": Immediately, 1 minute (default), 5 minutes, 1 hour.
- While locked, a full-screen cover shows the app name and a button "Unlock". Nothing behind it is composed. The prompt appears automatically once; cancelling leaves the cover.
- The window is marked secure while app lock is on, so the recents screen and screenshots show nothing.
- Incoming calls still ring and can be answered while locked; the call screen shows the caller's name only. Leaving the call screen returns to the cover.
- A notification tap or share intent is remembered and followed after unlock.
- The setting is per account and reset by sign-out like the other settings.

### 6.4 Profile (Task N7)

- Profile screen: tapping the name opens "Edit name" with a text field (1 to 40 characters after trimming, no line breaks or direction-override characters), Save and Cancel. It saves through the existing server call that updates the display name. Failure: "Couldn't save your name. Try again." and the old name stays.
- Letter tiles: the avatar's background is one of 8 colours chosen from the person's account ID (stable across devices and platforms: `colorIndex = fnv1a32(accountId) mod 8`), with the first letter in white. Colours are defined as theme tokens with a contrast ratio of at least 4.5 to 1 against white in light and dark themes. Applies everywhere `Avatar` is used.

## 7. Exact UI strings

Pin · Unpin · Mute · Unmute · Archive · Unarchive · Mark as read · Archived · No archived chats · You can pin up to 5 chats. · Draft: · Reply · New message · Not sent · Muted chats · App lock · Ask for your fingerprint, face or screen lock to open [App] · Set a screen lock in your phone's settings first. · Lock after · Immediately · 1 minute · 5 minutes · 1 hour · Unlock · Unlock [App] · Edit name · Save · Couldn't save your name. Try again.

`[App]` is `AppBrand.name`. Reuse an existing string where one with the same text exists.

## 8. Constraints

All of AGENTS.md. No new dependencies. No change to cryptography, keys, MLS, the push gateway, the server, or WebRTC. App lock is device authentication in front of the UI; it is listed here for the owner's attention because AGENTS.md names authentication for review.

## 9. Lead's calls

- Pin, mute, archive are per device.
- New messages do not unarchive.
- Maximum 5 pins.
- Drafts move to the database (replaces "memory only").
- App lock default delay 1 minute; notifications hide content while it is on.
- Profile photos wait for a server design.
