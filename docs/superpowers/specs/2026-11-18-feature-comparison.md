# Feature Comparison: Our Apps, Tinode, and SimpleX

Date: 2026-11-18. Our code at `main` f9685c3.

## 1. What this is

A side-by-side comparison of our iPhone/Mac and Android apps against two mature open-source messengers, [Tinode](https://github.com/tinode) and [SimpleX Chat](https://github.com/simplex-chat/simplex-chat), to decide which specs to write next.

**How it was made.** Four agents each read one codebase against the same 14-area checklist. Nothing was built or run, so every statement is a reading of source code, not observed behaviour. The serious claims about our own apps were then checked by hand in the code (section 3). The full inventories, with source file paths, are in [research/2026-11-18-inventories/](../research/2026-11-18-inventories/).

**What we may take from each project.**

| Project | License | What we may take |
|---|---|---|
| Tinode iPhone and Android apps | Apache-2.0 | Ideas, behaviour, and code (keep their copyright notices and credit them) |
| Tinode server | GPL-3.0 | Ideas and behaviour only |
| SimpleX apps and server | AGPL-3.0 | Ideas and behaviour only. Never copy code, and do not paste its code into our specs |

Tinode's apps are UIKit (iPhone) and Java (Android), so even where copying is allowed the code needs rewriting for SwiftUI and Compose. In practice both projects are sources of behaviour to specify, not code to import.

## 2. Main finding

**Our engine is well ahead of our screens.** On both platforms the layers under the UI are real and tested: end-to-end encryption, local database, offline send queue with retry, reconnect, inbox sync, push, device linking. But the stores already implement reply, react, edit, delete, attachment download, loading older messages, and message search, and almost no screen calls them.

So most of the gap to a "basic, workable" app is connecting existing screens to existing features, not building new machinery. The exceptions, which need real new work, are groups, calls media on iPhone/Mac, video rendering, and media viewing.

Approximate status counts from the inventories:

| | Working | Partial | Fake, sample, or does nothing | Absent |
|---|---|---|---|---|
| iPhone/Mac | 42 | 24 | 20 | 45 |
| Android | 54 | 20 | 15 | 45 |

## 3. Claims about our apps confirmed by hand

- **iPhone/Mac calls carry no audio or video.** `LiveMedia.make()` returns `UnavailableMediaSession` (`apps/apple/App/Calls/MediaSeam.swift:15`), and the media packages are not in `project.yml`.
- **Fake status is hard-coded on iPhone/Mac.** "online" in the chat header (`ChatView.swift:33`); green "Connected" in settings (`SettingsView.swift:141`).
- **Sample data is reachable in the live iPhone app.** "Scan QR code" fills in the user "neha" (`AddContactView.swift:34`); the invite link uses the sample invite code (`ContactsView.swift:41`).
- **iPhone/Mac screens never call** the message context menu, the older-messages load, or the attachment download.
- **Android's new-chat button does nothing** (`AppRoot.kt:158`).
- **Android settings are held in memory only** and reset on restart (`SettingsStore.kt:10`).

Everything else in section 4 about our apps comes from the inventories and has not been checked by hand.

## 4. Comparison by area

"Ours" describes the live build, not sample mode. "Both" means iPhone/Mac and Android.

### 4.1 Onboarding and account

| Feature | Ours | Tinode | SimpleX |
|---|---|---|---|
| Sign-up, email code, passkey, server choice | Working on both | Sign-up and login with password; iPhone picks the server in the iOS Settings app | Profile only, no account |
| Link a second device | Working on both (Mac cannot scan) | Not needed (server holds history) | Device-to-device migration with retry at each step |
| Delete account | iPhone/Mac working; **Android has no button** | Present | Present |
| Sign out | **Absent on both** | Present | Not applicable |
| Edit own name and photo | **Absent on both** | Present | Present |
| Backup or history on a new device | Absent (recovery starts empty) | Server holds history | Export, import, and migration |

### 4.2 Chat list

| Feature | Ours | Tinode | SimpleX |
|---|---|---|---|
| Rows, unread badge, folders | Working on both | Working; no folders | Filters (Unread, Favorites, Groups) plus user-made lists |
| Search | iPhone/Mac working; **Android has none** | None in the chat list; people search only | Names only, not message text |
| Swipe actions, long-press menu | **Absent on both** | iPhone swipe to delete or archive; Android long-press multi-select | Read/unread, mute cycle, add to list |
| Pin, archive, mute a chat | **Absent on both** | Archive and mute; pin on Android | Favorites and mute (all, mentions, none) |
| Connection status | **Always shows green on both** | "Working offline" bar on Android | Status shown |
| Typing and last seen | Absent (header says "online" for everyone on iPhone) | Online dot, typing pulse, last seen; typing and read receipts can be switched off | Online dot on contacts; no typing indicator recorded |
| Draft shown in the row | Absent | Absent (drafts lost when the app closes) | Present, survives restart |
| New chat | iPhone working; **Android button does nothing**; Mac has no button | Present | Present |
| Empty states | Mostly absent | Present | Present |

### 4.3 Chat screen

| Feature | Ours | Tinode | SimpleX |
|---|---|---|---|
| Long-press menu on a message | **Absent on both** | Popup (iPhone), multi-select bar (Android) | Present |
| Reply | Quotes display; **no way to start one** (store supports it) | Preview bar; tap quote to jump and flash | Swipe to reply with haptic; jump to original |
| Edit, delete | **No entry point** (store supports both) | Edit own text; delete for me or for everyone | Both within 1 day; edit history kept |
| Reactions | Display only (store supports adding) | Absent | Present |
| Copy, forward, select several | Absent | Present | Present |
| Older messages on scroll | **Absent: only the latest 50 per chat** (store supports paging) | Pages of 20 to 24, fetches only missing ranges | Loads the part of history needed for a jump |
| Date separators, unread marker, scroll-to-bottom | Absent | Date separators; no unread marker | Opens at first unread; button shows unread count |
| Delivery marks | Working; delivered and read look the same on iPhone | Clock, failed, sent, delivered, read | Sent, delivered, error with a reason |
| Failed message and retry | iPhone/Mac working; **Android shows failed as "sending"** | Failed mark | Automatic retry |
| Tappable links, link previews, formatting | Absent; links are plain text | Formatting; no previews | Previews and formatting |
| Search inside a chat | Absent (store has full-text search) | Absent | Present |
| Pinned messages | Absent | Up to 5 per chat | Absent |
| Disappearing messages | Setting exists but does nothing | Absent | Per chat and per message |

### 4.4 Composer, attachments, media

| Feature | Ours | Tinode | SimpleX |
|---|---|---|---|
| Send photo or file | iPhone/Mac working, no preview or caption; **Android buttons do nothing** (pipeline exists) | Present, with preview | Present |
| See a received photo | **Not on either platform: grey box only** | Present | Full-screen viewer with zoom and swipe |
| Camera | Does nothing | Present | Present |
| Video | Absent | Present | Present |
| Voice message record | iPhone/Mac tap to record; **Android button does nothing** (recorder exists) | Hold to record, slide to cancel, slide up to lock, live waveform, preview | Hold to record, preview, tap for hands-free on Android |
| Voice message playback | **Absent on both** | Present | Present with seek |
| Upload and download progress, cancel | Download ring on Android only | In the bubble, with cancel | Present |
| Save, share, media gallery | Absent | Save and share; no gallery | Save and share; in-chat filter by photos, videos, voice, files, links |
| Location | iPhone sends fake text "Shared location" | Absent | Absent |

### 4.5 Contacts, groups, chat info

| Feature | Ours | Tinode | SimpleX |
|---|---|---|---|
| Add by username | Working on both | Search by tags | Not applicable |
| Add by QR code | **iPhone button adds a sample user**; Android absent | "My Code" and scan | One-time invite QR and link |
| Invite link | iPhone shares a sample code; Android hidden | Generic "try this app" share text only | One-time links, with clear errors for expired or own links |
| Message requests | iPhone/Mac working; **Android has no Accept button** | Accept, Ignore, Block | Contact requests |
| Block, report | Working on both; **no unblock** | Block list with unblock; block and report | Group members only |
| Verify contact (safety number) | Present; depends on an engine flag not yet confirmed | Absent | Security code compare and scan |
| Groups | **Absent entirely** | Roles, permissions, channels, saved-messages chat | Roles, group links, moderation |
| Chat info: mute, search, media | Buttons present, none work | Present | Present |

### 4.6 Calls

| Feature | Ours | Tinode | SimpleX |
|---|---|---|---|
| Audio call | **iPhone/Mac: no media, call fails**. Android: engine wired, never verified by running | Working 1:1 | Working 1:1 |
| Video picture | **Placeholder on both**; Android sends camera but shows nothing | Working | Working, picture-in-picture |
| System call screen | Built on both (CallKit; Android Telecom) | CallKit; Android full-screen | CallKit; Android full-screen |
| Flip camera | Does nothing | Not recorded in the inventory | Present |
| Data mode and noise suppression | Settings and policy built on both; Enhanced unavailable on iPhone | None | None |
| Recovery on network change | Logic built; Android never observes network changes | None: ends the call | "Reconnecting", ends after 30 s |
| Group calls | Absent (Phase 2) | Absent | Absent |

Low-data calling and noise suppression are our intended advantage. Neither reference app has them, and neither helps here; this area follows our own call plans.

### 4.7 Notifications, settings, polish

| Feature | Ours | Tinode | SimpleX |
|---|---|---|---|
| Message push with sender and text | Built on both | Present | Present, with preview levels |
| Tap opens the chat | **Android opens the chat list instead** | Present | Present |
| Reply or mark read from notification | Absent | Absent | Accept request from notification |
| App icon badge | Absent | iPhone | Present |
| Per-chat mute | Absent | Present | Present |
| Notification settings | iPhone toggles do nothing; Android choice does nothing | Present | Instant, periodic, off, with battery explanations |
| Settings survive restart | iPhone not verified; **Android no** | Yes | Yes |
| Data and storage numbers | **iPhone shows made-up figures** | Not applicable | Present |
| App lock | Absent | Absent | Lock delay, self-destruct passcode |
| Themes, wallpapers | System light/dark only | Android only | Themes and wallpapers |
| Profile photos | Initials only | Photos plus coloured letter tiles | Photos |
| Translations | English only (strings are ready for translation) | Not recorded in the inventory | 17 languages on iPhone, about 40 on Android |
| iPad, tablet, landscape | Absent | Rotates; runs on iPad without a split view | Landscape allowed; Android single pane |
| VoiceOver and TalkBack labels | Good baseline on both | Partial | Thin on iPhone, good on Android |

## 5. Where we are already ahead

- End-to-end encryption on every chat with per-device keys and device linking. Tinode has none.
- Usernames with end-to-end encryption. SimpleX has no usernames by design.
- Passkey sign-in and recovery.
- Low-data call policy, noise suppression, and network-change logic (once media runs).
- Reactions and full-text message search exist in our engine; Tinode has neither.
- Accessibility labels are more complete than SimpleX on iPhone.

## 6. Proposed specs, in order

Each is its own spec and plan. Order is a proposal for the owner to change.

### Spec A: Honest and connected (both platforms)

Remove everything fake and connect screens to features the engine already has. No new protocol work.

- Remove fake status, sample users, sample invite code, fake location, and made-up storage numbers from live builds.
- Show the real connection state (connecting, offline, connected).
- Long-press menu on messages: reply, copy, react, edit, delete, forward.
- Load older messages on scroll; date separators; unread marker; scroll-to-bottom button; tappable links.
- Show received photos; open and save files; play voice messages.
- Android: attach, camera, and voice buttons; new-chat button; Accept and Decline on requests; search; saved settings; notification tap opens the chat; failed state with retry; delete account.
- Both: sign out, unblock, settings that do something or are removed.

Behaviour to borrow: Tinode's reply bar and jump-to-quote, its delete-for-me versus for-everyone rules, and its fetch-only-missing-ranges paging; SimpleX's open-at-first-unread and unread count on the scroll button.

### Spec B: Calls that carry sound and picture

- Link the media engine into the iPhone/Mac app.
- Video rendering on both platforms; flip camera; camera permission.
- Android observes network changes.
- Real-device call tests (the open L10 gate).

This touches the WebRTC/Opus build, which needs owner review under the project rules. It is independent of Spec A and can run alongside it.

### Spec C: Chat polish

- Voice notes: hold to record, slide to cancel, slide up to lock, waveform, preview (Tinode's design).
- Send preview with caption; upload progress and cancel; video; full-screen viewer and per-chat gallery (SimpleX's viewer behaviour).
- Chat list: swipe actions, pin, mute, archive, empty states, draft in the row with drafts that survive restart.
- Notifications: per-chat mute, badge count, reply and mark read, grouping.
- Link previews and text formatting.

### Spec D: Profile and finding people

- Edit name and photo; profile photos with coloured letter tiles as fallback.
- Real contact QR codes and one-time invite links, with clear messages for expired, own, or already-connected links (SimpleX's edge cases).
- App lock.

### Spec E: Groups

Already Phase 2 in GOAL.md. Needs server, protocol, and encryption work, so it needs owner review. Tinode's roles and permission model and SimpleX's group links and moderation are the references.

### Spec F: Reach

iPad, tablets, landscape, Mac menu commands and shortcuts, translations.

## 7. Decisions for the owner

These change what gets specified and are not settled by GOAL.md:

1. **Typing indicators and last seen.** Tinode has them, with off switches for typing and read receipts and an incognito mode. They expose activity to the server. Include, include with off switches, or leave out?
2. **Disappearing messages.** Our setting exists and does nothing. Build it in Spec C, or remove the setting until later?
3. **History on a new device.** Recovery starts with empty history and the help text mentions a backup that does not exist. Build backup or migration (SimpleX's approach), or correct the text for now?
4. **Order of A and B.** Run both at once, or calls first because calling is the product's main strength?

## 8. Limits of this comparison

- Source reading only. Our Android call engine in particular is wired but was not run.
- Counts in section 2 are approximate tallies by the inventory agents.
- SimpleX's own in-repo summaries were wrong in three places; items marked "(unverified)" in its inventory rest on them.
- Tinode and SimpleX were read at their latest commit on 2026-11-18, shallow clones kept outside the repo at `~/.cache/myapp-reference`.
