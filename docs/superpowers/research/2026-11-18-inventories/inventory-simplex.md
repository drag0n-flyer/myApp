# SimpleX Chat mobile apps: feature inventory (behaviour only, no code copied)

Source: shallow clone of simplex-chat (v6.5 / 7.0 era strings). Read-only study.

Path shorthand (all relative to repo root):
- `iOS/`  = `apps/ios/Shared/Views/`   (SwiftUI; model in `apps/ios/Shared/Model/`, shared types in `apps/ios/SimpleXChat/`)
- `MP/`   = `apps/multiplatform/common/src/commonMain/kotlin/chat/simplex/common/` (Kotlin/Compose, Android + desktop; `views/` below it)
- `AND/`  = `apps/multiplatform/android/src/main/java/chat/simplex/app/`
- `STR`   = `apps/multiplatform/common/src/commonMain/moko-resources/base/strings.xml` (all Android/desktop user-visible text; best single place to read every message, alert and label)
- Core limits/rules (edit window, etc.) live in Haskell `src/Simplex/Chat/*.hs`; only cited where they shape the UI.

Verification notes: the repo ships LLM-written summaries (`apps/ios/product/*`, `apps/ios/spec/*`). I used them to locate things and confirmed against code. Where they were WRONG: (a) no pinned chats exist (only Favorites); (b) voice messages have a seek slider, NOT a waveform; (c) chat-list search matches names/aliases only, not message text. Items marked (unverified) rest only on those summaries or on strings.

---

## 1. Onboarding

- Stages (enum): intro -> create profile -> (Android/desktop only: set database passphrase) -> server operators / network commitments -> done. Old stages "create address" and "set notifications mode" are deprecated and skipped. `iOS/Onboarding/OnboardingView.swift`, `MP/views/onboarding/OnboardingView.kt`
- Intro screen: tagline "Be free in your network", "Private and secure messaging", one primary button "Get started", link "Why SimpleX is built" (long manifesto page), secondary "Migrate from another device". `iOS/Onboarding/SimpleXInfo.swift`, `MP/views/onboarding/SimpleXInfo.kt`, `HowItWorks.*`
- Profile creation: ONE field (display name; no spaces allowed, auto-corrected via "Correct name to X?" prompt), optional avatar (camera / library), optional short bio (160 bytes). Copy stresses: "No account. No phone. No email. No ID", profile stored on device, shared only with contacts. Errors: duplicate name, invalid name. `iOS/Onboarding/CreateProfile.swift`, `MP/views/WelcomeView.kt`
- Server step: "Network commitments" page (operators promise independence/minimal metadata/open-source; user promises legal content in public groups, no spam) + single Accept. Operator choice (SimpleX Chat + Flux, toggles) is behind "Configure operators"; explains that using several operators means none can see who talks to whom. `iOS/Onboarding/ChooseServerOperators.swift`, `YourNetwork.swift`, `MP/views/onboarding/ChooseServerOperators.kt`, `YourNetwork.kt`
- Final "Your network" page has two optional buttons: "Setup routers" and "Setup notifications", then Continue. Notification mode can be set here (Instant / Periodic / When app is running) with plain battery notes: Instant = "uses more battery", Periodic = checks every 10 min, may miss calls. `iOS/Onboarding/SetNotificationsMode.swift`, `MP/views/onboarding/SetNotificationsMode.kt`
- Android/desktop: dedicated "Setup database passphrase" step. Default = random passphrase stored in the OS keystore (button "Use random passphrase"); alternative = user passphrase entered at every app start, with warning that it cannot be recovered. `MP/views/onboarding/SetupDatabasePassphrase.kt`. iOS: DB is also encrypted with a random key by default (flag "initial random DB passphrase"); user can change it later in Settings. `iOS/Database/DatabaseEncryptionView.swift`
- Desktop-only onboarding option "Link a mobile" (use phone as the account host). `MP/views/onboarding/LinkAMobileView.kt`
- Multiple profiles: "Your chat profiles" page via tapping the avatar in chat-list toolbar (user picker). Add profile (needs local auth if lock on), switch (tap), per-profile unread badge, per-profile mute ("muted when inactive": still get calls/notifications while that profile is active), hide, delete. `iOS/UserSettings/UserProfilesView.swift`, `iOS/ChatList/UserPicker.swift`, `MP/views/usersettings/UserProfilesView.kt`, `chatlist/UserPicker.kt`
- Hidden profiles: set a password; profile disappears from the list; to reveal, type the FULL password into the search field of "Your chat profiles" (the search box doubles as the password prompt). First-time notice explains this. Hidden profile + self-destruct code: see section 13. `iOS/UserSettings/HiddenProfileView.swift`, `MP/views/usersettings/HiddenProfileView.kt`
- Delete profile: two choices, "Profile and server connections" (tells servers/contacts) vs "Local profile data only"; can't delete the active profile (switch first); needs auth. Delete whole database is separate (Database settings). Strings: `users_delete_*` in STR.
- Incognito: toggle when connecting (1-time link, address, group join, accepting a request); uses a fresh random name+avatar per connection; shown with a mask icon in lists; a contact who got an incognito profile can't be invited to groups where main profile is used (explicit error). `iOS/UserSettings/IncognitoHelp.swift`, `MP/views/usersettings/IncognitoView.kt`
- Database passphrase management: encrypt, change, remove from keystore, restore backup after a failed change, wrong-passphrase screen with retry, upgrade/downgrade confirmation screens for DB schema. `iOS/Database/DatabaseView.swift`, `DatabaseErrorView.swift`, `MP/views/database/*`
- Migration to another device: old device: stop chat -> verify you remember DB passphrase -> "Archive and upload" (encrypted, chunked to file servers) -> shows QR + link; new device (from onboarding "Migrate from another device" or paste/scan link): download with progress, import, enter passphrase, confirm network settings; old device then "Finalize" and offers to delete DB. Retry buttons on every failed step. Hard-case warnings: never run same DB on two devices (breaks decryption); remove uploaded archive from servers when cancelling. `iOS/Migration/MigrateFromDevice.swift`, `MigrateToDevice.swift`, `MP/views/migration/*`
- Empty chat list after onboarding doubles as guidance: cards "Let someone connect to you / Connect via link or QR / Invite someone privately / Create your public address", plus a pre-made chat with the developers. `iOS/NewChat/OnboardingCards.swift`, `MP/views/newchat/OnboardingCards.kt`
- "What's new" sheet shown after update, re-openable in Settings > Help. `iOS/Onboarding/WhatsNewView.swift`

## 2. Chat list

- Row: avatar (online dot for contacts), name, last-message preview (markdown rendered, media shown as icon + caption, voice/file/call icons, group events text), relative timestamp, unread count badge, mute icon, incognito mask, delivery tick of last sent item, "@" badge when unread mention (mention-coloured), blue badge when muted-but-unread. `iOS/ChatList/ChatPreviewView.swift`, `MP/views/chatlist/ChatPreviewView.kt`
- Unsent DRAFT shows in the row in place of the last message (setting "Message draft"). Privacy setting "Show last messages" hides all previews.
- Special rows for non-ready chats: "Tap to connect" / "Open to connect" / "Open to accept" / "contact should accept…" / "connecting…", group invitations "You are invited to group" with Join / Join incognito, "reviewed by admins" (pending member), "rejected". `ChatPreviewView.swift` lines ~360-480; `iOS/ChatList/ContactRequestView.swift`, `ContactConnectionView.swift`
- Sorting: by recent activity only. NO pinning (verified: no pin code). "Favorites" is a filter, not a sort position. `iOS/Model/ChatModel.swift` (popChat)
- Filters/tabs (`TagListView`): All, Unread, Favorites, Groups (and Channels), Contacts, Business, Notes, Group reports (moderators, shows active report count), plus user-created LISTS (name + emoji, add chat to list via swipe "List"/context menu, reorder, edit, delete list). Empty-state texts per filter ("No unread chats", "No chats in list X"). `iOS/ChatList/TagListView.swift`, `MP/views/chatlist/TagListView.kt`
- Search: bar in the list; filters by name/alias/full name only (NOT message text; there is no cross-chat message search). Pasting a SimpleX link into the search box offers "Connect" / opens the existing chat for that link (detects "already connected"). `iOS/ChatList/ChatListView.swift` (`filteredChats`, `searchShowingSimplexLink`)
- Swipe (iOS): leading = Read/Unread, Favorite/Unfavorite (star); trailing = Mute cycle (all -> mentions-only -> none for groups), List, Clear, Leave / Delete ("More" menu); contact request rows: Accept, Accept incognito, Reject; group invite rows: Join. Android/desktop use long-press menu with same actions. `iOS/ChatList/ChatListNavLink.swift`, `MP/views/chatlist/ChatListNavLinkView.kt`
- Archiving: no archive state for chats. "Archived contacts" = deleted-but-kept conversations: when deleting a contact you choose "Keep conversation" / "Only delete conversation" / "Delete" (+ optional notify contact); conversation disappears from the main list but stays reachable from the Contacts list and you can still message them. `STR delete_contact_*`, `iOS/Chat/ChatInfoView.swift`
- Muting: per chat; groups get 3 states (all / mentions only / none) shown with different icons. Per-profile mute exists too.
- Connection status: toolbar indicator showing subscription progress; tap opens "Servers info" (per-server connected/errors/proxied, message stats, upload/download stats, "Reconnect all servers" with confirm because it costs traffic, reset statistics). Optional percentage display. `iOS/ChatList/ServersSummaryView.swift`, `MP/views/chatlist/ServersSummaryView.kt`
- Pull to refresh = "Reconnect servers?" confirm (forces delivery). Pending 1-time invitations and contact requests are list rows (invitation name editable; deleting warns the link will stop working).
- One-handed layout: "Reachable app toolbars" (iOS: toolbar + search at bottom, list flipped; Android: bottom bar) and separate "Reachable chat toolbar" setting; one-time intro card. `iOS/ChatList/OneHandUICard.swift`, Appearance settings
- Other banners: address-creation card, optional crowdfunding banner (region-limited, ignore), "Support SimpleX/badge" banner.
- Android share-target: sharing text/images/files from other apps opens a chat picker (`MP/views/chatlist/ShareListView.kt`); iOS has a Share Extension target `apps/ios/SimpleX SE`.

## 3. Chat screen

- Bubbles: classic framed bubbles (sent/received sides, optional "tail", corner radius slider), "Flat" and "Terminal" (full-width rows, no bubbles, sender line repeats after 7.5 min pause) layouts selectable per app/profile/chat; groups can force a layout (owner) or address can. `iOS/Chat/ChatItem/FramedItemView.swift`, `TerminalItemView.swift`, `MP/views/chat/item/FramedItemView.kt`
- Grouping: consecutive messages of same sender merge (avatar + name only on first; large vs small gaps); runs of group events or deleted items collapse into one expandable line ("N group events", "N messages deleted/blocked"); "A, B and N other members connected". `iOS/Chat/ChatItemsMerger.swift`, `MP/views/chat/ChatItemsMerger.kt`
- Date separators between days ("Mon, 12 Nov", year added when not current) and a floating date chip while scrolling away from bottom. `iOS/Chat/ChatView.swift` (`DateSeparator`)
- Message meta: time, delivery tick, pencil "edited", stopwatch for disappearing, "forwarded" label, via-proxy marker (optional), signature/verified marks (channels). `iOS/Chat/ChatItem/CIMetaView.swift`
- Delivery marks: one check = accepted by server ("sent"); two checks = delivered receipt; pale check = partial in groups; red x = error; orange triangle = warning; received-unread = coloured dot. There is NO separate "read" state (receipts mean delivered, user-controlled). Tap/info shows reason, e.g. "connection probably deleted", "capacity exceeded", "network issues - message expired after many attempts". `SimpleXChat/ChatTypes.swift` (CIStatus), STR `snd_error_*`
- No manual "retry send" button; sending retried automatically by core until expiry (messages expire in sending client after ~2 days, per skipped-message help text).
- Reply/quote: long-press menu "Reply", AND swipe a message toward leading edge (iOS threshold ~42 pt with medium haptic; Android swipe-to-dismiss 30dp; desktop none). Quote shows above compose; tapping a quote jumps to original anywhere in history. Not offered on deleted items, live drafts, notes, or when sending disabled. `iOS/Chat/ChatView.swift` (~2300), `MP/views/chat/ChatView.kt` (~2005)
- Forward: single or multi-select (up to 20); picker of target chats; plans first and warns about files not downloaded/failed/missing ("forward without files?"); forwarded items hide original source from recipient (shows only "forwarded"); info shows source for the forwarder. `iOS/Chat/ChatItemForwardingView.swift`
- Edit: only own, non-forwarded, non-voice messages within 1 day of sending (core rule `deletable'` with one day). Edit history kept and visible in Info. Recipients see "edited".
- Delete: confirm dialog "Delete for me" vs "Delete for everyone" (only within 1 day and for own messages). If the chat's "Delete for everyone" preference is off, button reads "Mark deleted for everyone" (recipient sees a "deleted" placeholder they can reveal). Multi-select delete up to 20 (iOS) / 200 (Android moderate). Moderators get "Moderate" on others' messages (placeholder says "moderated by X"). Channels: "Delete from history". `iOS/Chat/ChatView.swift` (~2333)
- Reactions: fixed set of 8 emoji (thumbs up/down, smile, laugh, sad, heart, rocket, check); long-press menu row; counts under bubble; tap to see who reacted; per-chat toggle in preferences (contact/group). `SimpleXChat/ChatTypes.swift` (MREmojiChar)
- Copy / Share / Save (image or file to gallery/Files) / Download / Cancel file transfer; Select -> multi-select toolbar (Delete, Forward; Android also bulk moderate/block members).
- Scroll-to-bottom floating button with unread count; scroll-to-first-unread button; chat opens on first unread item; jump to quoted/found/forwarded message loads the surrounding page (non-contiguous "splits" handled). `iOS/Chat/ChatScrollHelpers.swift`, `EndlessScrollView.swift`
- In-chat search: toolbar search; results filter list; content-type filter (images, videos, voice, files, links) acts as a per-chat media/links gallery (see 5). `ChatView.swift` (`searchToolbar`, `ContentFilter`)
- Drafts: unsent text + attachments + reply context kept per chat across app restarts (setting). `ComposeView.swift`
- Link previews: generated while typing; shown in compose with cancel; OPT-IN (first use asks "Enable link previews? may reveal your IP"); via SOCKS if enabled. Received links: setting "Open links from chat list" yes/no/ask; "Open full link / clean link" (strips tracking); hyperlink with different visible text is shown as raw to defeat phishing. `iOS/Chat/ComposeMessage/ComposeLinkView.swift`, `ChatItem/CILinkView.swift`
- Text formatting (markdown subset): `*bold*`-style bold, `_italic_`, `~strike~`, `` `code` ``, `!1 colored!` (6 colours), `#secret#` (hidden until tapped/revealed, copyable), links, emails, phone numbers, SimpleX links rendered as cards/labels ("simplex link mode": description / full / via browser), mention `@name`, bot `/commands`. Formatted view vs text view toggle for .md files. `iOS/UserSettings/MarkdownHelp.swift`, `MP/views/chat/item/MarkdownBlocks.kt`, `TextItemView.kt`
- Mentions (groups): type `@` -> autocomplete of members; max 3 per message (alert beyond); mentioned user gets notification even if group muted to "mentions only"; "@" badge in list. `iOS/Chat/Group/GroupMentions.swift`
- Emoji-only messages render large (size depends on count). `EmojiItemView.swift`
- Disappearing messages: per-contact/group preference with TTL; sender can also send ONE disappearing message via long-press on send (30 s / 1 min / 5 min / custom) even if feature off; countdown icon on bubble; per-chat TTL for deleting local history (never/day/week/month/year). Profile-level default TTL ("enable disappearing by default"). `ContactPreferencesView.swift`, `CustomTimePicker.swift`, `SendMessageView.swift`
- Message info sheet: tabs History (edit versions), In reply to, Forwarded, Delivery (per-recipient status in groups), timestamps (sent/received/deleted/disappears), file availability expiry. `iOS/Chat/ChatItemInfoView.swift`, `MP/views/chat/ChatItemInfoView.kt`
- Special items: call events (missed/rejected/ended + duration, "Answer call" button on pending), group invitation card, feature-change notices ("X offered voice messages", "Allow"/"Accept" inline), encryption/security-code-changed notices, undecryptable-message item with "Fix connection" help, integrity warnings ("3 skipped messages", bad hash/id/duplicate) with explanatory alerts. `ChatItem/CIRcvDecryptionError.swift`, `IntegrityErrorItemView.swift`, `CIFeaturePreferenceView.swift`
- Notes to self ("Private notes"): local-only chat with files; no delivery marks. Business chats show customer/business banners. Channels show relay status bar ("2/3 relays active").
- "Chat with admins" / member support chat and "reports" are secondary scoped chat views inside a group (see 7). `iOS/Chat/Group/SecondaryChatView.swift`

## 4. Composer and attachments

- Layout: auto-growing text field, attachment "+" button, send button that turns into mic when empty; context strip above field for reply/edit/forward/report; previews for link, image(s), file, voice. `iOS/Chat/ComposeMessage/*`, `MP/views/chat/ComposeView.kt`, `SendMsgView.kt`
- Attachment menu: Camera (photo/video), Gallery (images+video multi-select up to 10 in one go on iOS), File picker (any type). Android also drag-and-drop on desktop. `iOS/Helpers/ImagePicker.swift`, `MP/views/helpers/ChooseAttachmentView.kt`
- Many images/videos in one send become several messages (first carries the caption); preview strip lets you remove items before sending.
- Send options via LONG-PRESS on send button: "Send live message" (bolt) and "Disappearing message"; disabled when voice/multi-media/reply present.
- Live messages: first send pushes the text, then updates the recipient's copy every ~3 s while you type; send button pulses; first use shows explanation alert; cancel button; once sent final form replaces it. Edits of live items blocked in menu. `SendMessageView.swift` (`liveMsgInterval`)
- Voice recording: iOS = press and HOLD mic (needs at least 1 s hold, else discarded), release stops and shows a PREVIEW (play, slider, delete) which you then send; during record shows timer + progress bar toward the 5-minute cap (`MAX_VOICE_MESSAGE_LENGTH` 300 s); also a stop button variant. Android = hold to record, release ends; a quick TAP switches to hands-free recording with explicit Stop button (screen orientation locked while recording to avoid breaking the recording). Result is an m4a/aac file; permission-denied alert deep-links to settings; if contact disallows voice, mic opens "Allow voice messages?" prompt (or "ask your contact to enable"). Only one audio plays at a time; recording stops playback. `SendMessageView.swift`, `ComposeVoiceView.swift`, `MP/views/chat/SendMsgView.kt`, `ComposeVoiceView.kt`
- Voice playback bubble: play/pause + elapsed time + seek slider (NO waveform in code); voice files below ~510 KB auto-download. `iOS/Chat/ChatItem/CIVoiceView.swift`
- Limits (constants): inline image after compression <= 255 KB; auto-receive images/voice <= 510 KB, video <= ~1 MB; file/video up to 1 GB on file servers (free), 2 GB / 5 GB for "supporter"/"legend" badge holders (monetisation, ignore); voice 5 min; mentions 3/message; images per pick 10. Oversize file -> explicit "file too large" alert with the limit. `SimpleXChat/FileUtils.swift`, `MP/views/helpers/Utils.kt`
- Transfer UI: file bubble shows icon/name/size, download button, circular progress (upload and download), "waiting for sender/file", cancel (stop sending / stop receiving / revoke file for recipients), "file expired" (server keeps files a limited time; info row "file available until"), "temporary file error", per-error text (blocked by operator, file deleted). `iOS/Chat/ChatItem/CIFileView.swift`, STR `file_error_*`, `stop_*_file`, `revoke_file_*`
- Auto-accept images toggle; blur-media-until-tap setting; unknown file-server approval prompt ("Unknown servers! Your IP will be visible to these relays" with Download/Cancel) unless Tor/SOCKS; "Protect IP address" toggle. Privacy: image/voice file names use UTC time to avoid leaking timezone.
- Local file encryption at rest (toggle "Encrypt local files"). Received files stored in app storage; Storage screen shows count/size and "Delete all files".
- Send preview: images shown in compose with remove; caption = message text; reply-to-media allowed; forwarded media re-uses file.
- Camera: system camera picker (iOS) / in-app intent (Android); permissions explained with "Open settings".
- Disabled-composer states show the reason in place of the field ("connecting…", "you are observer/can't send", "member removed", "group deleted", "contact deleted" etc.).

## 5. Media viewing

- Tapping image/video opens full-screen (black background): pinch zoom, double-tap zoom, swipe left/right to move to previous/next media in the same chat, swipe down to dismiss AND scroll chat to that message, tap to toggle chrome. Animated GIF supported. `iOS/Chat/ChatItem/FullScreenMediaView.swift`, `AnimatedImageView.swift`, `MP/views/chat/item/ImageFullScreenView.kt`
- Videos: inline thumbnail with duration and play button; play inline or full-screen; chat-list voice/video playback while navigating ("play from the chat list"). `CIVideoView.swift`, `iOS/Helpers/VideoPlayerView.swift`
- Save to gallery/Files, share sheet, copy image from menu. Android Save via system file dialog.
- "Media gallery per chat": NOT a separate grid screen; instead in-chat search + content filter (images / videos / voice / files / links) lists those items inline. `ChatView.swift` (ContentFilter), STR `content_filter_*`, `placeholder_search_*`
- Blur radius setting (off/soft/medium/strong) hides received media until tapped. Link/image previews respect it.
- Remote file loading from linked phone on desktop shows "Loading the file" wait. (desktop-only)

## 6. Contacts and finding people

- No phone numbers/usernames. Ways to connect (New chat sheet / pencil button): (1) one-time invitation link + QR (single use; shown with short/full link toggle, share, copy, per-link incognito toggle, optional private name; on closing the sheet "Keep unused invitation?"), (2) scan QR / paste link (also by pasting into chat-list search), (3) public SimpleX address (reusable link; optional auto-accept with welcome message; optional business address), (4) group/channel links. `iOS/NewChat/NewChatView.swift`, `NewChatMenuButton.swift`, `QRCode.swift`, `MP/views/newchat/NewChatView.kt`, `NewChatSheet.kt`
- Address management: create/delete address, share via any channel, "share with contacts" (puts address in profile so contacts can pass it on), welcome message, auto-accept (incognito option), short vs full link ("Upgrade address"), SimpleX public names (beta, ignore). `iOS/UserSettings/UserAddressView.swift`, `MP/views/usersettings/UserAddressView.kt`
- Contact requests: appear as chat-list rows; Accept / Accept incognito / Reject (sender not told on reject); notification action "Accept" on Android. Auto-accept optional.
- Connection plan hard cases with explicit alerts: own link ("Connect to yourself?"), already connected (opens chat), already connecting, repeat request, group already joined, invalid/unsupported link, link needs newer app version, expired/removed invitation, "contact's device must be online to finish connecting". `MP/views/newchat/ConnectPlan.kt`, STR `connect_plan_*`, `connection_error_*`
- Faster connect (6.4): you can message immediately after tapping Connect (prepared chat; shows "Open to connect"/"contact should accept…").
- Links from outside: OS handlers for `simplex:`, `simplexchat:` and `https://simplex.chat/...` (Android manifest; iOS URL scheme / universal links).
- Contacts list (Contacts tab in New chat sheet): searchable; "Archived contacts"; filter "No filtered contacts". `MP/views/contacts/ContactListNavView.kt`, `iOS/Contacts/ContactListNavLink.swift`
- Security code verification: contact info > "Verify security code": shows 60-digit code + QR; scan contact's QR or compare by eye; "Mark verified"/"Clear verification"; verified shield on contact; incorrect-code alert; notice when code changes ("security code changed"). Intro text suggests comparing codes to detect a replaced link. `iOS/Chat/VerifyCodeView.swift`, `ScanCodeView.swift`, `MP/views/chat/VerifyCodeView.kt`
- Blocking: individual CONTACTS cannot be blocked (not present); you delete the contact instead. Group MEMBERS can be blocked locally (messages hidden, tap to reveal) and moderators can block for all. Operator can block links/files/groups ("Connection blocked", "File is blocked by server operator"). 
- Reporting: in groups members send private reports to moderators with reason (Spam, Inappropriate content, Community guidelines violation, Inappropriate profile, Other); moderators see a Reports queue (list tab "Group reports", per-chat reports view), can archive report for me / for all moderators. No report-to-app-developers flow for contacts. `iOS/Chat/Group/GroupReports*` via `ChatView.swift`, `MP/views/chat/group/GroupReportsView.kt`
- Member-to-member contact: from group member page "Send direct message" / "Connect directly?" creates a contact (can be auto-accepted by setting "contact requests in groups").
- Directory/bots: public "directory" bot docs (`docs/DIRECTORY.md`); chats with bots show command menu (`iOS/Chat/CommandsMenuView.swift`).

## 7. Groups (and public channels)

- Create: name, avatar, optional incognito; "Create secret group" (fully decentralized, visible only to members); then step "Invite members" (select contacts, choose initial role; can skip). `iOS/NewChat/AddGroupView.swift`, `MP/views/newchat/AddGroupView.kt`
- Roles (low to high): observer (read only), member (author), moderator (remove messages, block members), admin, owner. Hierarchy rule: only manage strictly lower roles; creator is owner. Role change notifies all; invited-not-yet-joined members get a new invitation. `iOS/Chat/Group/GroupMemberInfoView.swift`
- Add members: contacts list with multi-select; can't invite while using incognito in that group (explicit alert); "Invite prohibited" alert if shared an incognito profile.
- Remove member (with option to delete their messages), delete member's messages only, block/unblock (local), block/unblock for all (moderator+), leave (history kept), delete group (owner, for everyone). Android/desktop: multi-select members for bulk remove/block/role. `GroupMemberInfoView.swift`, `MP/views/chat/group/GroupMembersToolbar.kt`
- Group link: admin creates link/QR (anyone can join), set initial role, short vs full, delete link (members stay). Join flow for link holders: "Join group?" Join / Join incognito. `iOS/Chat/Group/GroupLinkView.swift`
- Group preferences (owner; each toggle with "who": all members / admins / moderators / owners): disappearing messages (+TTL), direct messages between members, delete-for-everyone (24 h), reactions, voice, files & media, SimpleX links, visible history (sends up to 100 last messages to new members), reports to moderators, chats with admins. Changing prompts "Save and notify group members". `iOS/Chat/Group/GroupPreferencesView.swift`, `MP/views/chat/group/GroupPreferences.kt`
- Member admission / "review members": owner can require review before admission ("knocking"): applicant sees "Please wait for moderators to review"; moderators open a private support chat with them and Accept as member/observer or Reject. `iOS/Chat/Group/MemberAdmissionView.swift`, `MemberSupportView.swift`
- "Chat with admins": any member can open a private scoped chat with admins; admins have a member-support inbox with unread badges. `MemberSupportChatToolbar.swift`
- Welcome message (owner; shown to new members), edit profile (name, image, description), per-group local alias, per-group chat theme/wallpaper, per-group TTL, receipts toggle (disabled with explanation when group > 20 members).
- Group info page lists members (role badge, online/connection status, search, "N members").
- Public channels (new): owner creates channel via 3-step wizard (profile -> relays connect with progress "x of y relays", cancel deletes -> share link); subscribers default to observer role; relay management UI (add/remove relays, last-relay warning, status bar), owners/contributors/subscribers lists; optional web preview page embed code. `iOS/NewChat/AddChannelView.swift`, `iOS/Chat/Group/Channel*.swift`, `MP/views/chat/group/Channel*.kt`. (Likely out of scope for us.)
- Incognito groups: create/join with random profile; welcome copy explains.

## 8. Chat info / profile pages

- Contact info (`iOS/Chat/ChatInfoView.swift`, `MP/views/chat/ChatInfoView.kt`): big avatar, name, full name, bio; editable LOCAL alias (private name); action row Message / Search / Call / Video / Mute; incognito indicator ("your random profile"); Verify security code; Contact preferences (what each side allows: disappearing msgs + time, delete-for-everyone, reactions, voice, files, calls); Send receipts (yes/no/default); Chat theme/wallpaper; auto-delete timer for this chat (device only); encryption type line (quantum-resistant vs standard) - the ONLY place encryption is named; address QR/share if contact has one; servers used (receive/send hosts), change receiving address / abort, subscription status; "Fix connection"/"Renegotiate encryption"; Clear chat; Delete contact (see 2). Everything disabled while contact not ready/active. Developer section: IDs, queue debug.
- Group info: see section 7; also Search, Mute, Add members/Link, theme, TTL, receipts.
- Member info page: avatar, name, role (editable if allowed), verify code, send direct message / message / call (if allowed), connection status (direct vs indirect), block, remove.
- Own profile page: edit name, full name, bio, avatar (camera/library), delete image; saves and notifies contacts; badge area (ignore); per-profile theme.
- Your SimpleX address page and "Your chat profiles" are reachable from the user picker too.

## 9. Calls

- Audio and video calls, 1:1 only (no group calls), WebRTC peer-to-peer; signalling sent as end-to-end encrypted chat messages (no signalling server). iOS WebRTC native + JS bridge; Android/desktop WebRTC inside a WebView. `iOS/Call/*`, `MP/views/call/*`, `apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/views/call/CallView.android.kt`
- Start: phone/video buttons in chat toolbar and contact info. Guard alerts: contact connecting ("please wait"), contact deleted, contact has calls disabled ("ask them to enable" / "allow calls?"), group member without contact ("send message first"), permissions missing (mic/cam) with open-settings.
- Incoming: iOS with CallKit enabled = system incoming-call screen, also on lock screen; without = in-app banner with Reject / Ignore / Accept and ringtone; accepting while another call active ends the old call. Android = high-priority "call" notification channel with full-screen intent, Accept/Reject actions, repeating ringtone + vibration; in-app alert when app open. `iOS/Call/IncomingCallView.swift`, `CallController.swift`, `AND/model/NtfManager.android.kt`, `MP/views/call/IncomingCallAlertView.kt`
- Settings "Audio & video calls": Always use relay (hides your IP, relay sees call duration) vs allow direct; custom ICE/STUN/TURN server list; iOS "Use iOS call interface", "Show calls in phone history", lock-screen call behaviour (accept without app auth vs require auth); Android lock-screen option (Show / Accept / Disable). `iOS/UserSettings/CallSettings.swift`, `MP/views/usersettings/CallSettings.kt`
- In-call controls: mute mic, speaker/earpiece/Bluetooth/headphones picker, flip camera, video on/off (switch audio<->video during call), hang up; local video preview floating; tap remote video toggles fill/fit; connection type shown (peer-to-peer vs via relay); "Call is not encrypted" warning if other side lacks e2e; screen kept awake; Android proximity sensor turns screen off for audio calls. 
- Background/PiP: iOS picture-in-picture for video; collapsible call view while using chat; Android PiP and an in-app top bar ("interactive area") to return to the call. If background restrictions block calls Android shows a warning button. `AND/views/call/CallActivity.kt`, `MP/App.kt` (`ActiveCallInteractiveArea`)
- Network change: call shows "reconnecting…" with connecting sound, callee restarts ICE every 10 s, call ends if not restored within 30 s; old clients without support end immediately. `iOS/Call/WebRTCClient.swift`
- States shown: calling, waiting for answer, connecting, connected, reconnecting, ended. Call history item in chat: missed (red), rejected, accepted, in progress, ended + duration; pending incoming shows "Answer call" button. `iOS/Chat/ChatItem/CICallItemView.swift`
- In-call sounds (connecting tone, end vibration).
- Region note: CallKit auto-disabled in China (in-app UI fallback).

## 10. Notifications

- Modes: iOS Instant (APNs -> Notification Service Extension that starts the core, decrypts on device and shows real content), Periodic (background refresh, roughly every 10-20 min), Off (only while app running). Android: Instant = persistent foreground service (permanent low-priority notification, restarts after kill/reboot via alarm, battery-optimisation exemption prompts incl. Xiaomi hint), Periodic = WorkManager job every 10 min for ~60 s, Off. Token status indicator on iOS (colour = registered/confirmed/active/expired) and "Test notifications". `iOS/UserSettings/NotificationsView.swift`, `apps/ios/SimpleX NSE/NotificationService.swift`, `AND/SimplexService.kt`, `MessagesFetcherWorker.kt`, `MP/views/usersettings/NotificationsSettingsView.kt`
- Preview options: Message text / Contact name only / Hidden ("SimpleX: new message"). iOS also links to system lock-screen preview setting.
- Grouping: Android notifications grouped under one summary with per-chat children; same-chat notifications within a short window are silent (rate-limit ~1 s on iOS); contact request notification has Accept action; call notifications have Accept/Reject. iOS thread-id per chat. `AND/model/NtfManager.android.kt`, `iOS/Model/NtfManager.swift`
- Mute options: per chat (all / mentions only / none), per profile, "Mute all" in groups; mentions override mute-to-mentions.
- Badge: iOS app-icon badge = total unread across profiles (updated on background); Android launcher badge via system; desktop tray tooltip "N unread". 
- Notification actions: tap opens exact chat (switching profile if needed); contact connected and new-contact-request notifications exist.
- Desktop: minimize-to-tray option so notifications continue.

## 11. Settings (full tree)

Top: avatar menu -> "Your settings". Sections (iOS `iOS/UserSettings/SettingsView.swift`, Android `MP/views/usersettings/SettingsView.kt`):
- Your profile / Your SimpleX address / Chat preferences (default per-contact feature prefs) / Your chat profiles / Use from desktop (iPhone/Android link to desktop app) / Migrate to another device
- Notifications: mode, preview.
- Network & servers: preset operators (accept conditions, per-operator toggles for messages/files/names), own SMP and XFTP servers (add by scan/paste, test servers, per-server "use for new connections"), chat relays (channels), SOCKS proxy (host/port/auth modes), .onion usage (no/when available/required), transport isolation (per profile/app session/server/connection), private message routing (mode + fallback), "show message status", web TCP port 443 option; Advanced: timeouts, ping interval/count, TCP keepalive, receive concurrency, reset to defaults, save & reconnect. `iOS/UserSettings/NetworkAndServers/*`
- Audio & video calls (section 9).
- Your privacy: SimpleX Lock (enable, mode System/Passcode, lock-after delay: immediately..30 min, change passcode, self-destruct passcode + its display name), Protect app screen (blur in app switcher / Android FLAG_SECURE blocks screenshots), Send link previews, Remove link tracking, Open links from list, Show last messages, Message draft, Verify names, Auto-accept images, Blur media, Encrypt local files, Protect IP address (ask before unknown file servers), Auto-accept contact requests from groups and group invitations, Delivery receipts (Contacts / small groups <=20, with "enable for all / keep overrides" prompts), Show encryption/signature (developer-ish), Share-to-SimpleX allow. `iOS/UserSettings/PrivacySettings.swift`
- Appearance: language (system/in-app; 17 iOS, ~40 Android/desktop), reachable toolbars, toolbar opacity/blur (iOS "toolbar opacity", Android "transparency/blur"), font size & zoom (Android/desktop), color mode (System/Light/Dark), dark theme variant (Dark / SimpleX / Black), custom accent/bubble colours, wallpapers (6 presets + own image, scale fill/fit/repeat, tint), per-profile and per-chat themes with "apply to light/dark/all", import/export theme as YAML, message shape (corner radius, tail), profile image shape (square-circle slider), app icon choice (light/dark), chat layout (classic/flat/terminal), minimize-to-tray (desktop). `iOS/UserSettings/AppearanceSettings.swift`, `MP/views/usersettings/Appearance.kt`
- Chat database section: Database passphrase & export (run/stop chat toggle, export archive, import archive with "will replace everything" confirm, delete database, encryption status line, Keystore vs plaintext storage explanation), Delete files, Auto-delete messages (all chats of profile: day/week/month/year/never), storage totals. `iOS/Database/DatabaseView.swift`, `MP/views/database/DatabaseView.kt`, `iOS/UserSettings/StorageView.swift`
- Help: How to use it (in-app guide), Markdown help, What's new, About, Send questions and ideas (opens chat with developers), Email, Follow network news, Terminal/Chat console (developer), app version/core version, check for app updates (Android/desktop: Stable/Beta/Disabled, download+install from GitHub), Reset all hints, Rate/Star/Contribute, donation/badge items (ignore).
- Developer tools: show IDs, internal errors, slow API calls, logs, confirm DB upgrades, terminal. `iOS/UserSettings/DeveloperView.swift`

## 12. Offline and reliability

- Local-first: all history in encrypted SQLite (SQLCipher; two files: chat + agent), messages persist locally, no server-side history. Chat can be STOPPED (to export/import/delete DB); banner "Chat is stopped" with start button; warning not to start the same DB on two devices. `spec/database.md` (apps/ios/spec), `iOS/Database/DatabaseView.swift`
- Send queue lives in core: messages marked "sending" (no tick), delivered when network returns; failure text explains cause; expire after ~2 days (client) / 30 days (server).
- Reconnect: pull-to-refresh "Reconnect servers?" and per-server reconnect in Servers info; subscription progress indicator; app foreground resubscribes; "Network status" per contact; "Change receiving address" per contact/member to move to another server (with abort).
- Delivery receipts: per-contact and per-small-group toggles; default prompt asks to enable on first launch of the feature ("Delivery receipts!" sheet).
- Ratchet/encryption problems: "Fix connection" (re-negotiate encryption) offered inline on decryption-error items and in contact info; also after restoring backups; shows progress item "agreeing encryption…".
- Integrity alerts: skipped messages, bad hash/ID, duplicates, "dropped after N attempts" with explanations of causes.
- Backup/export: Export database archive (requires chat stopped; shares zip with files; warns on files that could not be exported); import replaces everything; device-to-device migration (section 1); Android "App data backup" note; restore previous DB if passphrase change interrupted.
- Auto message expiry per chat/profile (local deletion), and "Delete files for all profiles".
- Pagination: newest page loaded first, older pages on scroll up (and newer on scroll down when jumped into the middle); trims in-memory items back to ~200; loader keeps "splits" so jumps to a found/quoted/unread message work without loading everything between. `iOS/Chat/ChatItemsLoader.swift`, `ChatItemsMerger.swift`, `MP/views/chat/ChatItemsLoader.kt`
- Multi-process care (iOS): notification extension and main app coordinate DB access (close/reopen store); Android foreground service shares process with app. DB init errors have dedicated screens ("Database error", "Keychain error", "wrong passphrase", migration).
- Crash handling: "app was crashed" notice and "another instance running" detection (desktop).

## 13. Look and polish

- Themes: system/light/dark; dark variants Dark, SimpleX, Black; custom colours for accent, backgrounds, bubbles (sent/received/quote), title, wallpaper tint; export/import YAML to share themes (`docs/THEMES.md`); theme per profile and per chat. `iOS/Theme/Theme.swift`, `ThemeManager.swift`, `MP/ui/theme`
- Wallpapers: six preset patterns (cats, flowers, hearts, kids, school, travel), custom image, scale modes, tint; preview with sample chat. `SimpleXChat/Theme/ChatWallpaperTypes.swift`, `iOS/Helpers/ChatWallpaper.swift`
- Avatars: user image or coloured initials/placeholder; shape slider; separate icon for channels/business/notes.
- Animations: spring swipe-to-reply, message list animated inserts, pulsing live-send button, animated GIFs.
- Accessibility: iOS has system text styles and Dynamic-Type-aware sizing (~70 refs) but very few explicit VoiceOver labels (about 2 in app views) -> relies on SwiftUI defaults; Android sets contentDescription widely (~135) and semantic roles. Good reference for what NOT to skip: we require labels on every control.
- Localisation: iOS 17 languages (string catalogue + Weblate), Android/desktop ~40; in-app language override; translations contributed via Weblate. `apps/ios/*.lproj`, `apps/multiplatform/common/src/commonMain/moko-resources/*`
- App lock ("SimpleX Lock"): system biometrics/passcode OR own app passcode; lock delay; lock re-engages when returning from background; self-destruct passcode wipes all data and opens an EMPTY profile with chosen name (plausible deniability); lock required for sensitive screens (profiles, database, console); share-extension toggle. `iOS/LocalAuth/*`, `MP/views/localauth/*`
- Screen protection: hides content in task switcher (iOS) / FLAG_SECURE (Android).
- Tablet/landscape: iOS iPhone-first, landscape allowed, no iPad split-view code found (unverified device-family setting). Android = single sliding pane (list <-> chat, handles display cutouts and rotate-in-chat); desktop app = 2/3-pane layout. 
- One-handed UI and reachable chat toolbar (section 2).
- Font scale/zoom (Android/desktop) with layout widths scaled by font size.

## 14. Anything else a user would miss

- Quantum-resistant encryption (post-quantum ratchet) on by default in direct chats; shown only on chat info page.
- Private message routing + SOCKS/Tor options protect IP from the recipient's server; "message sent via proxy" optional marker.
- Multiple operators for metadata privacy; Network summary page.
- Private notes (notes to self) with files.
- Business chats / business address: customer gets own chat with the business, team members can be added to conversations.
- Link mobile with desktop (remote access): desktop app drives the phone's account over local network with QR + session-code check, auto-discover, only one device active at a time. `iOS/RemoteAccess/ConnectDesktopView.swift`, `MP/views/remote/*`
- Bots with command menus; SimpleX directory service.
- Per-contact "chat preferences" negotiated between both sides (each feature shows you-allow / contact-allows; inline accept/ignore chat items).
- Message signing/verified marks, SimpleX names, supporter badges (paid files up to 5 GB): SimpleX-specific, skip.
- Terminal/chat console for debugging.
- In-app update check (Android/desktop), "What's new" per release, "Reset all hints".

---

## Most valuable behaviours for us (ranked)
1. One-time/ reusable invite links + QR with incognito toggle and "keep unused invitation?" prompt.
2. Hold-to-record voice with hands-free tap mode (Android), preview-before-send, 5-minute cap, auto-download of small voice files.
3. Swipe a message to reply, with threshold + haptic; tap quote jumps to original anywhere in history.
4. Open chat on first unread, scroll-to-bottom button with unread count, jump-to-message with non-contiguous page loading.
5. Chat list filters + user-made lists + favourites + swipe actions (read/unread, mute cycle, list) and one-handed bottom toolbar.
6. Delete for everyone limited to 1 day, "mark deleted" fallback, edit history, per-message info sheet with delivery detail.
7. Clear delivery marks (sent/delivered/partial/error) with explanatory reasons and automatic retry, no silent failures.
8. Disappearing messages: per-chat setting plus one-off per message via long-press on send; per-chat local auto-delete.
9. Local-only draft persistence including attachments and reply context; draft visible in chat list.
10. Passcode lock with delay + self-destruct passcode; screen-protect; hidden profiles unlocked by typing password in search.
11. Notifications: instant/periodic/off with plain battery explanations, preview levels, mute-to-mentions, per-profile mute, grouped Android notifications, accept-from-notification.
12. Full-screen media viewer: zoom, swipe to next media in chat, swipe down to close and land on the message; blur-until-tap; auto-accept toggle.
13. Calls: CallKit/lock-screen path, relay-only toggle, reconnect-for-30 s on network change, call events with duration and "Answer call" button, PiP/return-to-call bar.
14. Device-to-device migration with retry at each step, plus encrypted export/import and clear "never run on two devices" warnings.
15. Rich connection edge-case alerts (own link, already connected, already connecting, expired link, needs newer app) and per-feature "prohibited / ask contact to enable" prompts instead of dead buttons.
