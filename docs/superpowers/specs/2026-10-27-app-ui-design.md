# App UI Design: iPhone and Mac Clients

Date: 2026-10-27
Status: draft for review
Related: [GOAL.md](../../../GOAL.md), mockups in [mockups/](mockups/)

## 1. Intent

The app should look and feel like **WhatsApp and Telegram**, built with **Apple's own iOS design language**, and be **easy to understand** for non-technical people. The product's strengths (low data use, clear calls, your own server) should show up as small, plain-language touches, not as technical screens.

Calls and chats should feel familiar on day one. A person who has used WhatsApp or Telegram should not need to learn anything new.

## 2. Goals, non-goals, anti-goals

**Goals**
- Familiar messenger structure: tab bar, chat list with folders, chat screen, call screen.
- Apple look: SwiftUI system components, SF fonts, SF Symbols, system colors, light and dark mode, Liquid Glass on iOS 26+.
- Data use visible where it matters: in calls (mode, data used) and in Settings (auto-download, monthly usage).
- Plain words everywhere ("Hide my IP address in calls", not "TURN relay policy").

**Non-goals**
- A custom visual identity that departs from Apple's design language.
- A web client (excluded in GOAL.md §3).
- Brand name, logo, and app icon: decided later; the UI uses the placeholder "[App]".

**Anti-goals** (would be a failure even if it works)
- Showing technical terms (codec, kbps, relay, MLS, E2EE) in normal screens.
- Encryption banners, warnings, or badges in the chat list, chat, or call screens (decision in §5).
- Media downloading on mobile data without the user asking.

## 3. Constraints

- **Platforms:** iPhone on iOS 17 and later; macOS (minimum version set alongside iOS 17). SwiftUI first, UIKit/AppKit only where SwiftUI is missing something.
- **Glass:** use Apple's Liquid Glass APIs on iOS 26+ / macOS 26+. On iOS 17–25 fall back to the standard system materials (`.ultraThinMaterial`/`.regularMaterial`) with the same layout. Use only system components so both paths come for free; no hand-made blur effects.
- **Accessibility and languages:** Dynamic Type, VoiceOver labels on every control, right-to-left layouts, Indic scripts (see GOAL.md §16).
- **Calls on iPhone** use CallKit for incoming and outgoing calls. The in-app call screen (§6.5) is shown when the app is open. **Calls on the Mac** ring through a notification, because macOS has no CallKit (GOAL.md §3).
- Mockups use emoji as icon placeholders. The real app uses SF Symbols.

## 4. Navigation

**iPhone:** a floating glass **tab bar** at the bottom with four tabs, plus a separate round glass **search button** to its right (Apple's iOS 26 pattern):

| Tab | Content |
|---|---|
| Contacts | People you can message or call |
| Calls | Call history |
| **Chats** (opens by default) | Chat list with folders |
| Settings | Profile, server, devices, settings |

The tab order follows Telegram (Contacts, Calls, Chats, Settings), as in the approved mockup.

Search is global: chats, messages, contacts.

**iOS 17–25:** use the standard system tab bar (full width, with materials) and put search as a search field at the top of the Chats and Contacts lists. Do not hand-build a floating tab bar. The floating capsule with the separate search button appears only on iOS 26+, where the system provides it.

**Mac:** see §6.9.

**Groups:** group chats are Phase 2 (GOAL.md §11). The group parts of this design (sender names, member list, admin tools, the group mockup) are a reference for Phase 2. In Phase 1 there is no "New group" option and no group UI.

## 5. Encryption display (decision)

Following Telegram's approach, **the app does not label encryption** in the chat list, chat screen, or call screen. No lock icons, no "encrypted" text, no "not encrypted" warnings.

- The **chat info page** (opened by tapping the chat name) shows the chat's encryption type in one line: "End-to-end encrypted" or "Not end-to-end encrypted: the server admin can read this chat". This is where contact/device verification also lives.
- Server-readable chats look identical to other chats everywhere else.
- **The server admin decides** which chats are server-readable (server policy, e.g. "all chats on this server are server-readable"). Users never choose an encryption type when starting a chat, so the new-chat flow has no encryption option.
- If a chat's encryption type ever changes, the existing GOAL.md rule still applies: an explicit, authenticated change with a notice in the chat. Strict E2EE chats are never downgraded.

This replaces the earlier "persistent security indicator" requirement; GOAL.md §5 is updated to match.

## 6. Screens

### 6.1 Chat list (mockup 01, approved)
- Large title "Chats"; round glass buttons: menu (•••, left) and new chat (✎, right).
- **Folder chips** under the title: All, Unread (with count), Personal, Work. Users can create, rename, reorder, and delete folders in Settings → Chat folders. "All" and "Unread" are built in.
- A thin glass **status strip**: server name with a connection dot, and the mode a new call would start with on the current network (e.g. "● Data saver" on mobile data with the default limit).
- Rows: photo, name, last message preview, time, blue unread badge. Previews use short labels with icons: voice message with duration, photo, missed audio call, video call with duration.
- The list scrolls under the glass tab bar.

### 6.2 Chat screen (mockup 02, approved)
- **Header:** glass back button; glass pill with photo, name, and status ("online", or member names for groups); glass audio-call and video-call buttons. When groups arrive (Phase 2), group chats show no call buttons until group calls exist.
- **Bubbles:** own messages blue (right), others white/grey (left); time inside the bubble; ✓ sent, ✓✓ delivered/read. Groups show colored sender names.
- **Message types shown:** text, reply quote, emoji reaction, voice message with waveform and duration, photo/file.
- **Low data:** on mobile data, photos and files show a blurred preview with the size ("Photo · 240 KB") and a download button. Voice messages download automatically. Rules come from Settings → Data & Storage.
- **Composer:** floating glass bar with ＋ (photo, camera, file, location), message field with emoji button, 🎤 for voice messages. The 🎤 becomes a send arrow when there is text.

### 6.3 Chat info page (not drafted)
Opened by tapping the header pill. Photo, name, username; buttons for audio call, video call, mute, search; media/files/links; **encryption type line** (§5) and **Verify** (safety code / QR); disappearing messages; block/report; for groups, members and admin tools.

### 6.4 Contacts and Calls tabs (not drafted; builder follows the chat list style)
- **Contacts:** search, "Invite people" row (share invite link/QR), alphabetical list with photos. Tapping a contact opens their chat.
- **Calls:** history rows with photo, name, call type icon, direction (incoming/outgoing/missed in red), time; a call-back button on each row; "Missed" filter chip.

### 6.5 Call screens (mockup 03, approved)
1. **Audio call:** dark gradient background, large photo, name, timer. Glass pills: **network quality** (bars + "Good"/"Weak") and **current data mode** ("Auto · Data saver"); a pill with **data used so far** ("≈ 1.2 MB used"). Floating glass control bar: Speaker, Mute, Video, **Data**, End.
2. **Data sheet:** tapping "Data" opens a glass sheet "Data for this call" with Auto, Ultra-low, Data saver, Balanced, High quality. Each shows estimated data per 10 minutes (audio, both directions). The change applies to this call only. Modes above the server maximum are shown disabled with "Limited by your server". Modes above the user's **Limit on mobile data** stay selectable on mobile data, marked "Above your mobile data limit"; choosing one lifts the limit for this call only.
- **Order of limits:** server maximum (hard), then the in-call choice if any, otherwise the user's default mode capped by the mobile-data limit when on mobile data, then what the network can sustain.
3. **Video call on a weak network:** full-screen remote video, small self-view top-right, glass banner explaining what the app did. Banner sequence as conditions worsen: "Weak network: video lowered to save your voice quality" → "Switched to audio only" → "Reconnecting…" (for up to ~30 s) → "Call ended: connection lost".
- The estimates in the sheet (~2, ~3, ~5, ~8 MB per 10 min) are placeholders from research; they are replaced with Phase 0 measurements.
- Incoming calls on iPhone use the system CallKit screen; this design covers the in-app screen after answering.

### 6.6 Settings (mockup 04, approved)
1. **Main:** profile card (name, @username); **Server** (address, connection dot); **Linked devices** (count); then Privacy, Data & Storage, Notifications, Chat folders, Calls & Audio, Appearance; Backup & recovery key; Help.
2. **Data & Storage:**
   - Calls: Call data mode (default Auto), **Limit on mobile data** (default Data saver), "Start video calls with video off on mobile" (default on).
   - Footer line with the server default and server maximum.
   - Auto-download media: on Wi-Fi All; on mobile data None; voice messages Always.
   - This month: mobile data used, split into calls / media / messages; storage used on this device.
3. **Privacy:**
   - **"Hide my IP address in calls":** Never / With people not in my contacts (recommended; default comes from the server admin) / Always.
   - Who can find me: by username (on), by email (off). No phone option until phone discovery ships after the beta.
   - Read receipts, disappearing messages (default off), blocked list.
- **Calls & Audio** (not drafted): noise suppression Standard / Enhanced / Off; a hint to turn on Apple's Voice Isolation with a button that opens the system mic-mode picker.
- **Linked devices** (not drafted): list of devices with name, type, last active; "Link a device" (opens the camera to scan the QR code shown on the new device, see §6.8); remove device.

### 6.7 Welcome and sign-up (mockup 05, approved)
1. **Welcome:** app icon, one-line pitch, three points (uses very little data, private by default, your own server); "Get started" and "I already have an account".
2. **Join a server:**
   - Top, highlighted and marked "Recommended": **[App] server**, run by the project, with "Invite code needed during the beta". The app picks the nearest region automatically.
   - Below, "Or join another server": scan invite QR code, paste invite link, enter server address.
   - Opening an invite link from outside the app skips this step.
3. **Invite code:** when the chosen server needs one (the [App] server during the beta), a single field "Invite code". Skipped when the user arrived through an invite link or QR code, which already carries the code.
4. **Create account:** shows the server being joined; Name, Username (live availability check), Email with the help text "Only used to recover your account. Nobody can find you by email unless you turn it on."
5. **Verify email:** enter the 6-digit code sent by email.
6. **Create a passkey:** "Use Face ID to sign in" (Touch ID on Mac) with the system passkey prompt. No password. An email code is the backup way to sign in when no passkey is available.
7. **Recovery key (Phase 2):** shown in sign-up only once encrypted backups exist (GOAL.md §11 Phase 2). Mockup 05 step 4 is the design for that phase: the key in groups; "Save to Passwords" (iCloud Keychain) or "I wrote it down"; warning that email recovers the account but not old messages. Skippable, with a reminder in Settings → Backup & recovery key until it's done.
8. **Notifications and microphone permission** prompts come after sign-up, each with a one-line reason shown before the system prompt.

**Lost all devices:** "I already have an account" → "I don't have my old device" → sign in with a passkey synced through iCloud Keychain, or enter username or email and the email code → signed in on the new device with an empty history (Phase 1). The screen says plainly that old messages can't be restored without a backup.

### 6.8 Linking a device ("I already have an account")
- **iPhone is the primary device.** Accounts are created on iPhone only. The Mac is link-only in Phase 1, like Signal Desktop.
- The new device (usually the Mac) shows a QR code; the user scans it with the iPhone from Settings → Linked devices. The new device then syncs. (Not drafted.)

### 6.10 Contacts and message requests (not drafted; my call)
- A **contact** is someone you added in the app: by username, by scanning their QR code, or by accepting their invite. Phase 1 does not read the iPhone address book (phone discovery comes after the beta).
- The first message from someone who is not a contact goes to **Message requests**, shown as a row at the top of the chat list ("Message requests · 2"), not inside folders or unread counts. Accept, Block, or Report. Accepting adds them as a contact.
- Calls from people who are not contacts do not ring. They appear as a missed call inside the message request.
- "With people not in my contacts" in the IP setting uses this definition.

### 6.11 What syncs across linked devices (my call)
- **Synced (per account):** chat folders and their chats, contacts, blocked list, privacy settings, call data mode and mobile-data limit, read state, disappearing-message settings. Synced end-to-end encrypted between the user's devices.
- **Per device:** auto-download rules, storage, noise suppression mode, notification sounds, appearance.

### 6.9 Mac layout (not drafted; my call)
- `NavigationSplitView` with three columns: **sidebar** (Chats with folders, Calls, Contacts, Settings), **chat list**, **conversation**.
- Glass toolbar on macOS 26+, standard materials on older versions.
- Calls open in a separate, resizable window with the same controls as the iPhone call screen.
- Incoming calls appear as a notification with Accept/Decline (no CallKit on macOS).
- Settings in the standard macOS Settings window.

## 7. Visual style

- **Colors:** Apple system colors. Accent: system blue (outgoing bubbles, selected chips, badges). Green for good network and data mode, yellow/orange for weak network banners, red for End and missed calls.
- **Type:** SF Pro via system text styles (Large Title, Headline, Body, Caption) so Dynamic Type works.
- **Shapes:** round glass buttons, capsule tab bar and pills, 18 pt bubble radius with a smaller corner on the tail side.
- **Dark mode:** full support from the start; call screens are always dark.
- **Motion:** system defaults only; respect Reduce Motion and Reduce Transparency (Reduce Transparency switches glass to solid backgrounds).

## 8. Decided vs left to the builder

**Decided (by the project owner in review)**
- Layout B: tab bar + chat folders, Telegram tab order.
- Apple glass look, light and dark.
- No encryption labels except on the chat info page.
- The default [App] server is offered first, marked recommended, invite-only in the beta.
- Call data mode visible during calls and changeable from a sheet.
- Sign in with a passkey (Face ID / Touch ID), with an email code as backup; no passwords.
- The server admin decides which chats are server-readable; users don't choose.
- Build the UI on sample data in parallel with Phase 0, then connect it to the real app (§9).

**My calls** (made by the design author; the project owner can override)
- Photo/file auto-download defaults (Wi-Fi all, mobile none, voice always).
- "Limit on mobile data" default Data saver; video starts off on mobile.
- Recovery key step skippable with a reminder; it appears in Phase 2 with backups.
- Chat info, Contacts, Calls tab, Linked devices, Calls & Audio, and Mac layouts as described in §6.3, §6.4, §6.6, §6.8, §6.9.
- iOS 17–25 use the standard tab bar; the floating glass tab bar is iOS 26+ only.
- iPhone is the primary device; the Mac is link-only in Phase 1.
- Contacts are added in the app; strangers go to Message requests; strangers' calls don't ring.
- Mobile-data limit can be lifted per call from the Data sheet.
- Synced vs per-device settings as in §6.11.
- Invite code step, email verification, and the lost-all-devices flow as in §6.7.

**Left to the builder**
- Exact SF Symbols, spacing, and animation details within Apple's Human Interface Guidelines.
- Empty states, loading states, and error messages, written in the same plain style.
- Chat wallpaper (default: plain system background).

## 9. Build order (decided)
1. **UI on sample data**, built in parallel with GOAL.md Phase 0. All screens in this document work and are clickable, driven by sample data behind interfaces (chat store, call engine, account service, settings sync) that the real implementations replace later. Call screens are driven by a simulated call engine that can play the network states (good, weak, audio-only, reconnecting, ended) and data counters.
2. **The real app**: after Phase 0 proves calls work, replace the sample implementations with the real server, MLS, push, and WebRTC call engine, without redesigning the screens.

## 10. Open items
- App name, icon, and brand colors: decided later; "[App]" is a placeholder.
- Real per-mode data estimates: from Phase 0 measurements.
- Server regions shown on the default-server card: depend on where servers are hosted.
