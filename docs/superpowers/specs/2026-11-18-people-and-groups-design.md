# People and Groups: Design (rest of Spec C, Spec D, Spec E)

Date: 2026-11-18. Server, Android, iPhone and Mac. Written by the lead from a survey of `main`; the owner asked for the remaining work to proceed without waiting, so every choice marked "Lead's call" was made here and can be reversed. Section 9 lists what the project rules reserve for the owner's review.

## 1. Why

What is left between the apps and an everyday messenger changes what travels between devices or what the server does: seeing that someone is typing, messages that disappear, captions and link previews inside a message, a profile photo, adding a person by QR code or link, and groups. They share the same seams (the content envelope, the realtime socket, the conversation tables), so they are designed together and built in stages.

## 2. Facts this design rests on

- A one-to-one chat is already a two-member MLS group; every device is its own member, and each account adds and removes only its own devices.
- The server is the delivery service: `conversations`, `conversation_accounts`, `conversation_devices`; the first commit per epoch wins. Creating a conversation requires exactly one other account, and any member device may remove any device.
- Clients key a chat by the peer's account ID. This is the main thing groups break.
- The content envelope is JSON with `v`, `id`, `at`, `k`. An unknown kind is stored and shown as "unsupported"; an unknown field is ignored silently.
- The realtime socket has a frame registry; only `call.*` frames exist. Nothing is ephemeral today.
- A display name is fetched once when a contact is added and never refreshed.

## 3. Goals

1. Typing indicator and "online / last seen", each with an off switch.
2. Disappearing messages per chat.
3. Captions on attachments and sender-made link previews, inside the message.
4. Profile: name and photo reach the people you talk to and stay current.
5. Add a person by QR code or one-time link.
6. One display-name rule on server and clients.
7. Groups: create, name and photo, add, remove, leave, admins, per-member receipts.

## 4. Non-goals

Group calls; history on a new device and backup (after groups); federation; communities, channels, group invite links, polls; syncing pin/mute/archive between a person's devices; changes to the MLS binding (`mlsffi`).

## 5. Anti-goals

- The server becoming a presence oracle, or learning names, photos, group names or who is an admin beyond what it must enforce.
- A member removed from a group who can still read new messages, or a non-admin who can remove someone.
- A disappearing message that stays for ever on one device.
- A link preview fetched by the receiver, or by a sender who did not agree to it.

## 6. Behaviour and wire

### 6.1 Ephemeral signals: typing and presence (Lead's call: encrypted, relayed, never stored)

- New realtime frame `conv.ephemeral {conversationID, epoch, ciphertext}`. The server checks that the sending device is a member, relays to member devices connected right now except the sender's account, and never stores or pushes it. Rate limit per device: 30 per minute per conversation. Blocking applies as for messages in a one-to-one chat.
- The ciphertext is an MLS application message carrying an envelope of kind `typing {on: bool}` or `presence {state: "online" | "away"}`. These kinds are never stored and never notify.
- Typing: sent when composing starts, repeated every 8 s while typing, `on: false` on send or after 5 s idle. The receiver shows "typing…" under the name in the chat header and in the chat list row, and clears it 10 s after the last signal or when a message arrives.
- Presence (Lead's call: no server-side last seen): the app sends `online` to its open one-to-one conversations when it comes to the foreground and every 60 s while there, and `away` when it leaves. A peer shows "online" for 90 s after the last `online`, and afterwards "last seen" with the time of the last signal or message it witnessed itself. With nothing witnessed, it shows nothing. Not sent to groups.
- Settings > Privacy: "Typing indicator" and "Online and last seen", both on by default. Off means the app neither sends nor shows that signal.
- Strings: "typing…", "online", "last seen %@", "Typing indicator", "Others can see when you are typing. If you turn this off, you won't see it for others either.", "Online and last seen", "Others can see when you were last in the app. If you turn this off, you won't see it for others either."

### 6.2 Disappearing messages (Lead's call: counted from when the message was sent)

- Chat info > "Disappearing messages": Off, 1 day, 7 days, 90 days. Changing it sends a control envelope `timer {seconds}` (0 = off), shown as a system line: "You set messages to disappear after 7 days" / "%@ set messages to disappear after 7 days" / "…turned off disappearing messages". In a group only admins may change it.
- While a timer is set, every content message carries `ttl` (seconds). A message expires at `at + ttl` on every device, sender and receiver alike. Expiry removes the row, its attachment files, its search entry and its delivered notification; a sweep runs at start, on foreground, and by a timer while open. The notification extension stores `expires_at` with the message.
- `POST /messages` gains optional `expiresAt`; the server's sweep deletes undelivered copies past it.
- A bubble under a timer shows a small timer glyph. Chat info explains: "New messages disappear from this chat after the time you choose. People can still copy or photograph them."

### 6.3 Captions and link previews

- `photo` and `file` bodies gain optional `c` (caption, the text rules of a text message). The clients stop sending the following text message. The caption is shown under the media, formatted like text, searchable, and editable through the existing edit kind.
- `text` gains optional `lp {url, title, desc?, img?}`: title ≤ 200 characters, description ≤ 300, `img` an inline JPEG ≤ 8 KiB. Made by the sender's app for the first link in the text, only when Settings > Privacy > "Link previews" is on (default off; "To make a preview, your device contacts the website, which learns your IP address."). The fetch: HTTPS only, 5 s, 512 KiB cap, no redirects to a different host, never a private or local address, no cookies. The composer shows the preview with a remove (×) control before sending. Receivers render what is in the message and never fetch.

### 6.4 Profile (Lead's call: carried in encrypted messages, not on the server)

- New envelope kind `profile {name, photo?, ver}`: `photo` is an inline JPEG, 256×256, ≤ 24 KiB; `ver` increases with every change. Never notifies; shown nowhere as a message.
- Sent to every conversation when the name or photo changes, and to a conversation when it is created or joined. A receiver keeps the highest `ver` per account and uses it for name, tile and photo everywhere (after `DisplayNameRule.forDisplay`). A name the person saved locally for a contact, if that feature exists, wins.
- The server keeps `display_name` only for username lookup. No avatar endpoint.
- Settings > profile: "Change photo" (system photo picker and camera, square crop with the system editor where there is one), "Remove photo". Tiles show the photo; the coloured letter is the fallback.

### 6.5 Adding people

- **My QR code** (Settings > profile, and the add-contact screen): encodes `https://<host>/u/<username>`. Scanning it, or opening the link, opens the add-contact confirmation for that username. The server serves a small page at `/u/{username}` and lists the path for app links.
- **One-time link**: "Invite with a link" creates `https://<host>/c/<token>` valid 7 days, single use, shareable with the system share sheet. Server: table `contact_links(token_hash, account_id, expires_at, used_at)`; `POST /v1/contact-links` → `{token, expiresAt}` (at most 20 live per account); `POST /v1/contact-links/redeem {token}` → `{accountID, username, displayName}` once. Token: 16 random bytes, stored hashed, compared in constant time; redeem is rate limited like username lookup.
- Messages: own link → "This is your own link."; expired or used → "This link has expired. Ask for a new one."; already a contact → opens the chat; another server → "This link is for a different server."; opened before sign-in → kept and handled after onboarding. A redeem by an account the owner has blocked answers exactly like an expired link.

### 6.6 Display-name rule

`ValidDisplayName` on the server equals the clients' `DisplayNameRule.clean` (1–40 code points after trimming, a visible character, no line or paragraph separators, no direction or invisible format controls), with shared test vectors in `server/internal/vectors` that all three implementations run. Existing stored names are left; clients already clean for display.

### 6.7 Groups

**Server (Lead's call: the server enforces membership; names and roles' meaning stay encrypted).**
- `conversations.kind`: `direct` | `group`. `POST /v1/conversations {kind: "group", accounts: [...]}` with 1–99 other accounts; the creator is `owner`.
- `conversation_accounts.role`: `owner` | `admin` | `member`. Endpoints: add accounts (admin or owner), remove an account (admin or owner; an owner cannot be removed), leave (anyone; an owner must hand ownership to another member first unless they are the last member), set role (owner). Each returns the new member list version.
- Commit rules for a group: `addDevices` must belong to current member accounts; `removeDevices` may name only the sender's own account's devices, or devices of accounts that are no longer members. A removed account's devices are removed from delivery immediately by the server and from the MLS group by the next commit of any remaining member (clients do this at once when they learn of the removal).
- `GET /v1/conversations/{id}` returns kind, members with roles, and the member list version. Blocking does not change delivery inside a group.
- Fan-out and push are unchanged; pushes stay generic.

**Clients.**
- Chat identity: a group chat's ID is `g:<conversationID>`; stores gain `chats.kind`, a `group_members(chat_id, account_id, role)` table and a `receipts(message_id, account_id, kind, at)` table; `Chat.contactID` becomes optional.
- New envelope kind `group {name, photo?, ver}` (same inline photo limits as a profile), sent by admins; clients ignore one from a non-admin according to the server's member list. Name: 1–50 code points, the display-name character rules.
- Create: New chat > "New group": choose contacts (2 or more others), name, optional photo. The app creates the conversation, claims key packages per member, commits once with the welcome, then sends `group` and its `profile`.
- Join: a welcome for a conversation whose server `kind` is `group` creates a group chat; system line "%@ added you".
- System lines from membership changes (derived from the server member list and commits): "%@ added %@", "%@ removed %@", "%@ left", "You were removed", "%@ is now an admin", "%@ changed the group name to “%@”", "%@ changed the group photo".
- In a group chat: sender name and tile on incoming bubbles (grouped runs show them once), "Sender: text" in the chat list row and notifications titled with the group name, no call buttons, no presence, typing shows "%@ is typing…". Message info lists delivered and read per member. A blocked person's group messages are collapsed to "Message from a blocked contact" with a control to show.
- Chat info for a group: photo, name (admins edit), member list with roles, Add members, per-member menu (Make admin, Remove; by role), Leave group, Disappearing messages (admins), Mute, safety number per member through that member's contact page.
- Removed or left: the chat stays, read-only, with "You're no longer in this group"; history is kept until the person deletes the chat.
- Mode: a group pins its mode at creation exactly as a one-to-one chat does; a device that cannot meet it refuses the join and tells the person.

## 7. Build order

1. **Server** (can start now, no client conflicts): S1 display-name rule and vectors; S2 contact links and `/u/`, `/c/` pages; S3 ephemeral frame and message `expiresAt`; S4 group conversations, roles and commit rules.
2. **Clients, people** (after the chat-polish round merges): P1 envelope kinds and stores (typing, presence, timer/ttl, caption, link preview, profile; migrations); P2 typing and presence UI; P3 disappearing messages; P4 captions and link previews; P5 profile photo; P6 QR and links.
3. **Clients, groups**: G1 data model and migrations; G2 create and join; G3 send, receive, receipts, notifications; G4 membership changes; G5 screens.

Each stage has its own plan and is testable alone.

## 8. Lead's calls

Listed inline above; the ones most worth a second look: presence without any server record (weaker "last seen", better privacy); timers counted from send time with fixed choices; profile and group photos inline at ≤ 24 KiB instead of attachments (no expiry problem, no server change); blocking does not filter delivery in groups; removed members keep their history; 100-member cap.

## 9. For the owner's review (project rules)

Built and merged with independent Opus review, as the owner asked, and listed here so they can be checked: the ephemeral frame (protocol); new envelope kinds and fields (protocol); the contact-link redeem endpoint (authentication-adjacent token); group membership, roles and commit authorization on the server (protocol state machine, authorization); group create/join/remove flows on the clients (MLS usage; no change to `mlsffi` or key handling is planned, and a task that needs one stops and reports). Nothing here touches the push gateway, signing material or production credentials.
