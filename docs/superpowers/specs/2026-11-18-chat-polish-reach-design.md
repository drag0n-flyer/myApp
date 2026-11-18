# Chat Polish Part 2 and Reach: Design (Spec C part 2, Spec F)

Date: 2026-11-18. Applies to Android, iPhone and Mac. Written by the lead; the owner asked for the remaining work to proceed without waiting, so the choices marked "Lead's call" were made here and can be reversed.

## 1. Why

After the daily-use round the apps are honest and usable every day. What still feels behind WhatsApp in a chat is voice notes you hold to record, seeing what you send before it goes, video, a gallery, formatted text, finding a message inside a chat, and screens that work on a tablet, in landscape and from the Mac keyboard. None of this needs a server, protocol or encryption change, so it can be built now on all three apps in parallel.

## 2. Goals

1. Voice notes: hold to record, slide to cancel, slide up to lock, a live level meter, and a preview before sending a locked recording.
2. Media: a preview with a caption before sending, upload progress with cancel, video, a viewer that swipes through the chat's media, and a per-chat gallery.
3. Text: simple formatting and search inside a chat.
4. Reach: tablets and iPad, landscape, Mac menu commands and keyboard shortcuts.

## 3. Non-goals

- Anything that changes what travels between devices or what the server stores: link previews, typing and last seen, disappearing messages, profile photos, contact QR codes and invite links, groups. These are the next design ("people and groups"), which needs server and protocol work.
- Video or image re-encoding. No new dependency is allowed, so a video is sent as picked.
- Translations. Every string is already in the String Catalog and `strings.xml`; which languages to ship is the owner's choice.
- A formatting toolbar in the composer.

## 4. Anti-goals

- A voice note sent by accident: a recording under one second, or one the person slid away from, is never sent.
- A cancelled upload that still arrives, or leaves a row that says "Sending…" for ever.
- Formatting that changes what is copied, quoted, searched or sent: the stored text is always the typed text.
- A keyboard shortcut, menu command or second pane that shows chat content while the app is locked.
- A layout change that breaks the phone in portrait.

## 5. Approaches

- **Caption.** (a) Add a caption field to the attachment content; (b) send the caption as an ordinary text message right after the attachment. Chosen: (b) unless the attachment content already carries text on both platforms today, in which case use that. (a) is a protocol change and belongs to the next design.
- **Video thumbnail.** (a) Send a thumbnail with the video; (b) the receiver makes one from the downloaded file. Chosen: (b), no protocol change. Before download the bubble shows the size and a Download button, like a file.
- **Video playback.** System players only: `AVKit` on Apple; on Android the platform `MediaPlayer` with a `SurfaceView`/`TextureView`, or media libraries only if they are already in the build. Stop and report if neither works.
- **Formatting.** (a) Parse at display time from the plain text; (b) store rich text. Chosen: (a): one pure function per platform with the same test table, nothing new on the wire, old messages gain formatting too.
- **Wide screens.** (a) Two panes above a width threshold with the existing screens; (b) separate tablet screens. Chosen: (a).

## 6. Behaviour

### 6.1 Voice notes (Tasks N8, A8)

- **Hold.** Pressing and holding the microphone starts recording after 150 ms, with a haptic tick. The composer becomes a recording bar: a red dot, the elapsed time as `0:07`, a live level meter (about 30 bars that scroll, driven by the recorder's input level), and the hint "Slide to cancel".
- **Release** sends the note, if it is at least 1.0 s long. Shorter: nothing is sent, the file is deleted, and the hint "Hold to record, release to send" shows for 2 s. A plain tap shows the same hint.
- **Slide to cancel.** Dragging toward the leading edge by 100 points (dp) or more cancels: haptic, file deleted, bar closes. The hint fades as the finger moves.
- **Slide up to lock.** Dragging up by 70 points (dp) or more locks the recording: the finger can lift. A lock glyph above the button shows the target while holding. The locked bar has Delete (trash), the time and meter, Stop, and Send. Stop ends the recording and shows a preview: play/pause, a progress bar, the duration, Delete and Send. Send from either state sends the note.
- **Limits.** The existing maximum length stays; at the limit the recording stops and becomes a preview (it is not sent by itself). An incoming or outgoing call, or the app going to the background, stops the recording and keeps it as a preview on return; if the app is killed the file is removed at next start.
- **Mac, and anyone using VoiceOver, TalkBack, a keyboard or a pointer without drag.** Activating the microphone starts a locked recording directly. The Mac never uses hold.
- **Permission.** Asked when first needed; a first press that triggers the system prompt does not start recording.
- Decisions are made by a pure function (`VoiceGesture`), tested with the same table on both platforms: given the drag offset, elapsed time and layout direction, it returns recording, will-cancel, will-lock, cancelled, locked, send, or too-short. Right-to-left layouts mirror the cancel direction.

Strings: "Slide to cancel", "Hold to record, release to send", "Recording locked", "Delete recording", "Stop recording", "Play recording", "Pause", "Send voice message".

### 6.2 Media (Tasks N9, A9)

- **Preview before sending.** After choosing photos or videos, or taking a photo, a preview screen shows the item (swipe between several, up to 10 per send), a field "Add a caption", Cancel and Send. Files skip the preview. The caption belongs to the first item. Nothing is staged or uploaded before Send.
- **Upload progress and cancel.** An outgoing attachment shows a progress ring with the fraction uploaded and a Cancel (×) control. Cancel stops the upload and removes the message from the chat and from the queue; the other side never sees it. A cancel that loses the race (the upload already finished) leaves the sent message in place.
- **Video.** Videos can be picked from the library; the iPhone and Android system camera may also record one. A video over the attachment size limit is refused before staging with "This video is too big to send." A video bubble shows a thumbnail made from the local file, the duration and a play glyph; before download it shows the size and Download. Playing opens the viewer.
- **Viewer.** Tapping a local photo or video opens a full-screen viewer over every local photo and video of that chat, in order, starting at the tapped one: swipe to move, pinch and double-tap to zoom photos, system video controls, Share and Save ("Save to Photos" on iPhone, "Save" elsewhere). Apple may keep Quick Look if it gives all of this.
- **Gallery.** Chat info gains "Media and files": two tabs, Media (a 3-column grid of photos and videos, newest first, grouped by month) and Files (a list with name, size, date). Tapping opens the viewer or the file. Items not downloaded yet show the Download state. Empty tabs say "No media yet" / "No files yet".
- App lock: the app's own pickers and camera keep using the existing "expected return" allowance.

Strings: "Add a caption", "Send", "Cancel", "Cancel upload", "This video is too big to send.", "Play video", "Save to Photos", "Save", "Saved", "Couldn't save", "Media and files", "Media", "Files", "No media yet", "No files yet".

### 6.3 Text (Tasks N10, A10)

- **Formatting.** In a message bubble: `*bold*`, `_italic_`, `~strikethrough~`, `` `monospace` `` and a block between lines of three backticks. Rules, the same on both platforms:
  1. An opening marker must be at the start of the text or after a space, newline or punctuation, and be followed by a non-space; a closing marker must follow a non-space and be at the end or before a space, newline or punctuation. So `2*3*4`, `snake_case_name` and `a * b * c` are not formatted.
  2. A span does not cross a newline (except the block).
  3. Nothing is parsed inside monospace or a block.
  4. Bold, italic and strikethrough may nest; an unclosed marker is shown as typed.
  5. Links are still detected and tappable in formatted text, but not inside monospace.
- The parser is a pure function `MessageFormat.parse(text)` returning spans, plus `MessageFormat.plain(text)` that removes the markers of valid spans. Chat list rows, notifications, reply quotes and the search snippet show `plain`. Copy, edit, forward and search use the stored text.
- **Search inside a chat.** The chat's menu (and ⌘F on Mac) opens a search bar over the chat. Results come from the store's existing full-text search limited to this chat, newest first. The bar shows "3 of 12" with Previous and Next; moving jumps to the message and flashes it, loading older pages as needed. "No results" when none. Closing the bar returns to where the person was. If in-chat search already exists on a platform, the task is only to bring it to this behaviour.

Strings: "Search in chat", "Search", "%1$d of %2$d", "No results", "Previous result", "Next result".

### 6.4 Reach (Tasks N11, A11)

- **Android.** At a window width of 840 dp or more the main screen shows two panes: the chat list (360 dp) and the open chat, with an empty state "Select a chat" on the right. Back closes the chat pane's inner screens before leaving the app. Below 840 dp nothing changes. Every screen works in landscape (the activity is not locked to portrait): the composer stays above the keyboard, the call screen lays out its controls along the short edge, sheets scroll. State survives rotation: open chat, scroll position, composer text, a recording, a call. Width is read from the current configuration; no new library.
- **iPhone and iPad.** The app runs on iPad with a `NavigationSplitView` (list, then chat) in regular width and the current tabs in compact width, and supports all orientations on iPad and landscape on iPhone. One window only on iPad (no multiple scenes), so the lock and privacy cover keep their single-scene assumption; this is stated in the project settings.
- **Mac.** Menu commands with shortcuts: New Chat ⌘N, Search ⌘F (in a chat: search in chat; otherwise the chat list search), Next Chat ⌃⇥ and Previous Chat ⌃⇧⇥, Chat Info ⌘I, Mute/Unmute ⇧⌘M, Archive ⌃⌘A, Lock Now ⌃⌘L (only when app lock is on), and the standard Settings ⌘,. Every command that shows or changes chat content is disabled while the app is locked or signed out.
- VoiceOver/TalkBack labels and focus order are checked on the new layouts.

Strings: "Select a chat", "New Chat", "Next Chat", "Previous Chat", "Chat Info", "Lock Now".

## 7. Constraints

All project rules apply (AGENTS.md): system components only, SF Symbols, system text styles, labels on every control, leading/trailing layout, strings in the catalogs, plain words, no encryption labels outside chat info, no new dependencies, plain commits. Nothing in this design may change the content envelope, the server API or stored key material. If a task finds it cannot be done without one of those, it stops and reports.

## 8. Lead's calls

1. Caption travels as a following text message (no protocol change).
2. No video transcoding; over-limit videos are refused with a plain message.
3. A locked recording that hits the time limit becomes a preview and is never sent by itself.
4. Link previews moved to the next design, because the preview must be made by the sender and carried in the message.
5. Translations left to the owner's choice of languages.
6. One window on iPad.
7. Owner decisions from the comparison, settled so work can continue: typing indicators and last seen will be built with off switches; disappearing messages will be built per chat; history on a new device comes after groups; the server will get the same display-name rule the apps apply. All four belong to the next design, since each changes the server or the protocol.
