import AppCore
import SwiftUI
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// A request to scroll to a message and highlight it. The token makes repeat requests for one message distinct.
private struct JumpRequest: Equatable {
    let messageID: String
    /// False when the scroll only puts the chat back where it was (no flash).
    var flash = true
    let token = UUID()
}

private struct ForwardItem: Identifiable {
    let id = UUID()
    let text: String
}

/// The scroll place to return to when the search bar closes.
private struct SearchAnchor {
    /// The message at the bottom of the screen, or nil when the chat was at its end.
    let messageID: String?
    let newestID: String?
}

/// A conversation. Request chats show Accept / Block / Report instead of the composer.
struct ChatView: View {
    let chatID: String
    @Bindable var model: AppModel
    let open: (ChatRoute) -> Void
    @State private var draft = ""
    /// The draft set aside while the edit bar fills the composer.
    @State private var draftBeforeEdit = ""
    /// Set while the composer is emptied by a send: that change is not saved as a new draft.
    @State private var sentClear = false
    /// Messages whose download the person started; older loaded pages are re-read until each one finishes.
    @State private var downloading: Set<String> = []
    @State private var voice = VoicePlayer()
    @State private var preview = MediaPreview()
    /// The photo and video viewer over this chat's media; set when a local photo or video is tapped.
    @State private var viewer: ViewerRequest?
    @Environment(\.scenePhase) private var scenePhase
    @State private var history = ChatHistory()
    /// The store's newest messages as of the last sync, so ones that slide out of that window are kept.
    @State private var lastNewest: [Message] = []
    /// Set when this screen reacts to, edits or deletes a message, which may be older than the newest window.
    @State private var needsOlderRefresh = false
    @State private var scrollTarget = ScrollTarget()
    /// Bumped after a programmatic change of `scrollTarget` so the view reads it again.
    @State private var scrollTick = 0
    /// How many times the scroll-to-newest button ran (shown to UI tests in debug builds only).
    @State private var scrollTaps = 0
    @State private var hits: [MessageHit] = []
    /// The query `hits` answers; "No results" shows only once it matches what is typed.
    @State private var searchedQuery = ""
    /// Index into `hits` (newest first) of the result the search bar is on.
    @State private var currentHit: Int?
    /// Where the chat was when the search bar opened, so closing the bar can return there.
    @State private var searchAnchor: SearchAnchor?
    @State private var tracker = NewestTracker()
    @State private var firstUnreadID: String?
    @State private var capturedUnread = false
    @State private var highlightID: String?
    @State private var jump: JumpRequest?
    @State private var replyingTo: Message?
    @State private var editing: Message?
    @State private var deleteTarget: Message?
    @State private var forwardItem: ForwardItem?
    @State private var searching = false
    @State private var query = ""
    @FocusState private var searchFocused: Bool

    private var chat: Chat? { model.chat(id: chatID) }
    private var contact: Contact? { chat.flatMap { model.contact(id: $0.contactID) } }

    var body: some View {
        let searchRequest = model.chatSearchRequest
        Group {
            if let chat {
                messages(chat)
                    .safeAreaInset(edge: .top, spacing: 0) {
                        if searching { searchBar }
                    }
                    .safeAreaInset(edge: .bottom) {
                        if chat.isRequest {
                            RequestActionsBar(chat: chat, model: model)
                        } else {
                            ComposerView(text: $draft, send: { send(chat) }, sendSample: sendSample,
                                         sendAttachment: { model.sendAttachment($0, in: chatID) },
                                         sendMedia: { model.sendMedia($0, caption: $1, in: chatID) },
                                         attachmentLimit: model.attachmentLimitBytes,
                                         sampleMedia: model.isSampleData,
                                         replyingTo: replyingTo,
                                         replyingToName: replyingTo.map { senderName($0.senderID) } ?? "",
                                         editing: editing,
                                         cancelBar: cancelBar,
                                         sampleMode: model.isSampleData,
                                         callActive: model.activeCall?.isEnded == false)
                        }
                    }
            } else {
                ContentUnavailableView("This chat was removed", systemImage: "bubble.left.and.exclamationmark.bubble.right")
            }
        }
        .toolbar {
            ToolbarItem(placement: .principal) {
                ChatHeaderView(name: contact?.displayName ?? "", colorKey: contact?.id) {
                    open(.info(chatID: chatID))
                }
            }
            if chat?.isRequest == false, let contactID = chat?.contactID {
                ToolbarItemGroup(placement: .primaryAction) {
                    Button { model.startCall(contactID: contactID, video: false) } label: { Image(systemName: "phone") }
                        .accessibilityLabel("Audio call")
                        .accessibilityIdentifier("chat.call.audio")
                    Button { model.startCall(contactID: contactID, video: true) } label: { Image(systemName: "video") }
                        .accessibilityLabel("Video call")
                        .accessibilityIdentifier("chat.call.video")
                }
            }
        }
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        #endif
        .onAppear {
            // Also for a chat that has no row yet: its pending text is in the store.
            if !capturedUnread { draft = model.draft(for: chatID) }
            if !capturedUnread, let chat {
                // Before markRead clears the count.
                capturedUnread = true
                lastNewest = chat.messages
                firstUnreadID = ChatTimeline.firstUnreadID(messages: chat.messages, unreadCount: chat.unreadCount, myID: model.me.id)
            }
            model.markRead(chatID: chatID)
            model.chatAppeared(chatID)
            openSearchIfRequested()
        }
        .onAppear {
            MediaPreview.sweepStale()
            voice.isInCall = { [weak model] in model?.activeCall?.isEnded == false }
            voice.callStateChanged(inCall: model.activeCall?.isEnded == false)
        }
        .onChange(of: model.activeCall?.isEnded == false) { _, inCall in voice.callStateChanged(inCall: inCall) }
        .onDisappear { voice.stop(); preview.close(); model.flushDrafts(); model.chatDisappeared(chatID) }
        .onChange(of: scenePhase) { _, phase in if phase != .active { voice.stop(); model.flushDrafts() } }
        .mediaPreview(preview)
        .mediaViewer($viewer)
        .onChange(of: searchRequest) { _, _ in openSearchIfRequested() }
        .onChange(of: draft) { _, text in
            // The clearing after a send is the store's job (it clears the draft only once the message is queued).
            if sentClear { sentClear = false; return }
            if editing == nil { model.setDraft(text, for: chatID) }
        }
        .onChange(of: chat?.messages) { _, _ in
            closeBarsForChangedMessages()
            // A message that came, went or changed while the search bar is open: read the hits again.
            if searching, !trimmedQuery.isEmpty, searchedQuery == trimmedQuery { runSearch(trimmedQuery, jumpToFirst: false) }
        }
        .onChange(of: model.revision) { _, _ in syncHistory() }
        .task { await model.refreshAttachmentLimit() }
        .confirmationDialog("Delete this message for everyone?",
                            isPresented: Binding(get: { deleteTarget != nil }, set: { if !$0 { deleteTarget = nil } }),
                            titleVisibility: .visible) {
            Button("Delete", role: .destructive) { confirmDelete() }
            Button("Cancel", role: .cancel) { deleteTarget = nil }
        }
        .sheet(item: $forwardItem) { item in
            ForwardPickerView(model: model) { contactID in
                forwardItem = nil
                let target = model.forward(text: item.text, to: contactID)
                model.openChatID = target
            }
        }
    }

    // MARK: - Timeline

    private static let bottomID = "chat-bottom"

    /// Holds the id `scrollPosition(id:)` reads and writes. A plain class, so the system writing it during
    /// ordinary scrolling does not redraw the chat; `scrollTick` is bumped to make a programmatic change take effect.
    private final class ScrollTarget {
        var id: String?
    }

    /// Scrolls to the end of the chat. A far-away row of a lazy stack is only sized by estimate the first time,
    /// so the scroll is repeated once it has been laid out.
    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        // Two independent mechanisms: the scroll position binding and the proxy.
        scrollTarget.id = nil
        scrollTick += 1
        Task { @MainActor in
            scrollTarget.id = Self.bottomID
            scrollTick += 1
            proxy.scrollTo(Self.bottomID, anchor: .bottom)
            try? await Task.sleep(for: .milliseconds(200))
            proxy.scrollTo(Self.bottomID, anchor: .bottom)
        }
    }

    private func messages(_ chat: Chat) -> some View {
        let all = history.messages(newest: chat.messages)
        let rows = ChatTimeline.rows(messages: all, firstUnreadID: firstUnreadID, calendar: .current)
        let indexByID = Dictionary(uniqueKeysWithValues: all.enumerated().map { ($1.id, $0) })
        let byID = Dictionary(uniqueKeysWithValues: all.map { ($0.id, $0) })
        let newestID = all.last?.id
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 6) {
                    ForEach(rows) { row in
                        rowView(row, all: all, indexByID: indexByID, byID: byID, newestID: newestID, chat: chat, proxy: proxy)
                            .id(row.id)
                    }
                    // Always the last child: the one target for every "go to the bottom".
                    Color.clear.frame(height: 1).id(Self.bottomID)
                }
                .scrollTargetLayout()
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
            }
            .scrollPosition(id: Binding(get: { _ = scrollTick; return scrollTarget.id }, set: { scrollTarget.id = $0 }),
                            anchor: .bottom)
            .defaultScrollAnchor(.bottom)
            .background(Color.groupedBackground)
            .overlay {
                if all.isEmpty {
                    Text("No messages yet")
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("chat.empty")
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if !tracker.atBottom, newestID != nil {
                    scrollButton(count: tracker.unseenCount(in: all)) {
                        // The button goes away when the newest row appears, not on tap, so a scroll that
                        // lands short leaves it there to try again.
                        scrollTaps += 1
                        scrollToBottom(proxy)
                    }
                }
            }
            #if DEBUG
            .overlay(alignment: .topLeading) {
                // Lets UI tests see that the button's action ran. Not in release builds.
                Color.clear
                    .frame(width: 1, height: 1)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Scroll button taps")
                    .accessibilityValue("\(scrollTaps)")
                    .accessibilityIdentifier("chat.debug.scrollTaps")
            }
            #endif
            .onChange(of: newestID) { _, new in
                if tracker.messagesChanged(newestID: new) {
                    scrollToBottom(proxy)
                }
            }
            .onChange(of: firstUnreadID) { _, new in
                guard new != nil else { return }
                Task { @MainActor in proxy.scrollTo(ChatRow.unreadMarker.id, anchor: .top) }
            }
            .onChange(of: jump) { _, request in
                guard let request else { return }
                jump = nil
                if request.messageID == Self.bottomID {
                    scrollToBottom(proxy)
                } else {
                    jumpToMessage(request.messageID, chat: chat, proxy: proxy, flash: request.flash)
                }
            }
        }
    }

    @ViewBuilder
    private func rowView(_ row: ChatRow, all: [Message], indexByID: [String: Int], byID: [String: Message], newestID: String?,
                         chat: Chat, proxy: ScrollViewProxy) -> some View {
        switch row {
        case .date(let day):
            Text(dayTitle(day))
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(Color.secondaryBackground, in: Capsule())
                .frame(maxWidth: .infinity)
                .padding(.vertical, 4)
                .accessibilityAddTraits(.isHeader)
        case .unreadMarker:
            Text("Unread messages")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.tint)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
                .background(Color.accentColor.opacity(0.12))
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("chat.unreadMarker")
        case .message(let message):
            let mine = isMine(message)
            MessageBubbleView(
                message: message,
                isMine: mine,
                reply: message.replyToID.flatMap { byID[$0] },
                replySenderName: senderName,
                mediaState: MediaBubbleState(media: message.media, fileExists: model.mediaFileIsUsable),
                download: { downloading.insert(message.id); model.downloadMedia(messageID: message.id, in: chatID) },
                openMedia: { url, name, isPhoto in
                    if isPhoto || ChatMedia.isVideo(name: name ?? ""), let request = model.viewerRequest(chatID: chatID, startingAt: message.id) {
                        viewer = request
                    } else {
                        preview.open(source: url, mediaDirectory: model.mediaDirectory, name: name, isPhoto: isPhoto)
                    }
                },
                cancelUpload: { model.cancelUpload(messageID: message.id, in: chatID) },
                voice: voice,
                canDownload: !model.isSampleData,
                retry: { model.retrySend(messageID: message.id) },
                actions: MessageActions.available(for: message, isMine: mine),
                perform: { perform($0, on: message, all: all, indexByID: indexByID) },
                react: { needsOlderRefresh = true; model.react($0, to: message.id, in: chatID, followedBy: nextID(after: message.id, all: all, indexByID: indexByID)) },
                isHighlighted: highlightID == message.id,
                quoteTapped: { if let id = message.replyToID { jump = JumpRequest(messageID: id) } }
            )
            .onAppear {
                if message.id == newestID {
                    tracker.setAtBottom(true, newestID: newestID)
                    // Back at the bottom: now it is safe to drop far-away loaded pages.
                    history.trimIfNeeded()
                }
                if let index = indexByID[message.id], history.pager.shouldLoad(oldestVisibleIndex: index) {
                    loadOlder(chat: chat, keeping: message.id, proxy: proxy)
                }
            }
            .onDisappear {
                if message.id == newestID { tracker.setAtBottom(false, newestID: newestID) }
            }
        }
    }

    private func scrollButton(count: Int, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: "chevron.down")
                .font(.headline)
                .frame(width: 44, height: 44)
                .contentShape(Circle())
                .glassBackground(in: Circle())
                .overlay(alignment: .topTrailing) {
                    if count > 0 {
                        Text("\(count)")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(Color.accentColor))
                            .offset(x: 6, y: -6)
                    }
                }
        }
        .buttonStyle(.plain)
        .padding(.trailing, 14)
        .padding(.bottom, 10)
        .accessibilityLabel("Scroll to newest")
        .accessibilityValue(count > 0 ? Text("\(count)") : Text(verbatim: ""))
        .accessibilityIdentifier("chat.scrollToNewest")
    }

    private func dayTitle(_ day: Date) -> String {
        switch ChatTimeline.daySpan(for: day, now: Date(), calendar: .current) {
        case .today: String(localized: "Today")
        case .yesterday: String(localized: "Yesterday")
        case .other: ChatTimeline.dateLabel(for: day, now: Date(), calendar: .current)
        }
    }

    // MARK: - History

    /// After a store change: keep messages that left the newest window, fill gaps, refresh loaded older ones.
    private func syncHistory() {
        guard let chat else { return }
        // Store changes in other chats (receipts and so on) cost this screen nothing.
        let changed = chat.messages != lastNewest
        guard changed || needsOlderRefresh || !downloading.isEmpty else { return }
        var next = history
        next.sync(previousNewest: lastNewest, newest: chat.messages, refreshOlder: true,
                  allowTrim: tracker.atBottom) { before, limit in
            model.olderMessages(in: chatID, before: before, limit: limit)
        }
        history = next
        lastNewest = chat.messages
        needsOlderRefresh = false
        if !downloading.isEmpty {
            let loaded = history.messages(newest: chat.messages)
            downloading = downloading.filter { id in
                switch loaded.first(where: { $0.id == id })?.media?.phase {
                case .remote, .downloading: true
                default: false
                }
            }
        }
    }

    /// Loads the next older page and keeps `anchorID` (the row that triggered it) where it was on screen.
    private func loadOlder(chat: Chat, keeping anchorID: String, proxy: ScrollViewProxy) {
        var next = history
        let added = next.loadPage(newest: chat.messages) { before, _ in model.olderMessages(in: chatID, before: before) }
        history = next
        if added > 0 {
            Task { @MainActor in proxy.scrollTo(anchorID, anchor: .top) }
        }
    }

    /// Scrolls to a message and highlights it, loading older pages as needed. Does nothing if history ends first.
    private func jumpToMessage(_ id: String, chat: Chat, proxy: ScrollViewProxy, flash: Bool = true) {
        var trial = history
        guard trial.load(until: id, newest: chat.messages, loader: { before, _ in model.olderMessages(in: chatID, before: before) }) else { return }
        history = trial
        // We are about to scroll away from the bottom; no trimming until the person is back there.
        if !chat.messages.contains(where: { $0.id == id }) {
            tracker.setAtBottom(false, newestID: chat.messages.last?.id)
        }
        if flash { highlightID = id }
        Task { @MainActor in withAnimation { proxy.scrollTo(id, anchor: flash ? .center : .bottom) } }
        guard flash else { return }
        Task {
            try? await Task.sleep(for: .seconds(1.5))
            if highlightID == id { highlightID = nil }
        }
    }

    // MARK: - Actions

    private func isMine(_ message: Message) -> Bool { message.senderID == model.me.id }

    private func senderName(_ id: String) -> String {
        id == model.me.id ? String(localized: "You") : model.contact(id: id)?.displayName ?? ""
    }

    private func nextID(after id: String, all: [Message], indexByID: [String: Int]) -> String? {
        guard let index = indexByID[id], index + 1 < all.count else { return nil }
        return all[index + 1].id
    }

    /// The ID of the message after `id` in what is loaded now, for reading `id` back from the store.
    private func nextLoadedID(after id: String) -> String? {
        let all = history.messages(newest: chat?.messages ?? [])
        guard let index = all.firstIndex(where: { $0.id == id }), index + 1 < all.count else { return nil }
        return all[index + 1].id
    }

    private func perform(_ action: MessageAction, on message: Message, all: [Message], indexByID: [String: Int]) {
        // The menu was built earlier; the message may have changed or vanished since.
        let current = model.currentMessage(id: message.id, in: chatID, followedBy: nextID(after: message.id, all: all, indexByID: indexByID))
        guard let current, MessageActions.isStillApplicable(action, current: current, isMine: isMine(current)) else { return }
        switch action {
        case .reply:
            if editing != nil { closeEditBar() }
            replyingTo = current
        case .react:
            break
        case .copy:
            if case .text(let text) = current.content { copy(text) }
        case .forward:
            if case .text(let text) = current.content { forwardItem = ForwardItem(text: text) }
        case .edit:
            beginEdit(current)
        case .delete:
            deleteTarget = current
        }
    }

    private func copy(_ text: String) {
        #if canImport(UIKit)
        UIPasteboard.general.string = text
        #elseif canImport(AppKit)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
    }

    private func beginEdit(_ message: Message) {
        guard case .text(let text) = message.content else { return }
        if editing == nil { draftBeforeEdit = draft }
        replyingTo = nil
        editing = message
        draft = text
    }

    private func closeEditBar() {
        guard editing != nil else { return }
        editing = nil
        draft = draftBeforeEdit
        draftBeforeEdit = ""
    }

    private func cancelBar() {
        if editing != nil { closeEditBar() } else { replyingTo = nil }
    }

    /// Closes the reply or edit bar when its message was deleted, vanished, or can no longer be edited.
    private func closeBarsForChangedMessages() {
        if let editing, !stillApplicable(.edit, editing) { closeEditBar() }
        if let replyingTo, !stillApplicable(.reply, replyingTo) { self.replyingTo = nil }
    }

    private func stillApplicable(_ action: MessageAction, _ message: Message) -> Bool {
        let current = model.currentMessage(id: message.id, in: chatID, followedBy: nextLoadedID(after: message.id))
        return MessageActions.isStillApplicable(action, current: current, isMine: isMine(message))
    }

    private func confirmDelete() {
        guard let target = deleteTarget else { return }
        deleteTarget = nil
        needsOlderRefresh = true
        model.delete(messageID: target.id, in: chatID, followedBy: nextLoadedID(after: target.id))
    }

    private func send(_ chat: Chat) {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        if let editing {
            // If the message changed under the edit, the edit is dropped; the bar closes either way.
            needsOlderRefresh = true
            model.edit(messageID: editing.id, in: chatID, text: text, followedBy: nextLoadedID(after: editing.id))
            closeEditBar()
            return
        }
        // Your own message always scrolls into view.
        tracker.setAtBottom(true, newestID: history.messages(newest: chat.messages).last?.id)
        model.send(.text(text), in: chatID, replyTo: replyingTo?.id)
        replyingTo = nil
        sentClear = true
        draft = ""
    }

    private func sendSample(_ content: MessageContent) {
        model.send(content, in: chatID)
    }

    // MARK: - Search

    private func openSearchIfRequested() {
        guard model.chatSearchRequest == chatID else { return }
        model.chatSearchRequest = nil
        if !searching {
            let newest = history.messages(newest: chat?.messages ?? []).last?.id
            let place = scrollTarget.id
            searchAnchor = SearchAnchor(messageID: tracker.atBottom || place == Self.bottomID ? nil : place, newestID: newest)
        }
        searching = true
        Task { @MainActor in searchFocused = true }
    }

    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// True once the results on screen answer what is typed.
    private var searchAnswered: Bool { !trimmedQuery.isEmpty && searchedQuery == trimmedQuery }

    private func closeSearch() {
        // Back to where the person was; a message that came or went meanwhile means the newest.
        let newest = history.messages(newest: chat?.messages ?? []).last?.id
        if let anchor = searchAnchor {
            if let id = anchor.messageID, anchor.newestID == newest {
                jump = JumpRequest(messageID: id, flash: false)
            } else {
                jump = JumpRequest(messageID: Self.bottomID, flash: false)
            }
        }
        searchAnchor = nil
        searching = false
        query = ""
        hits = []
        searchedQuery = ""
        currentHit = nil
    }

    /// Reads the hits for `text` and keeps the ones whose message can be reached: the pages the jump would load
    /// are loaded here, once, after the typing pause, and a hit beyond the page cap is left out of the count.
    /// With `jumpToFirst` false (the chat changed under the open bar) the current hit stays if it still exists.
    private func runSearch(_ text: String, jumpToFirst: Bool) {
        guard let chat else { return }
        let found = model.searchMessages(text, in: chatID)
        var trial = history
        if let oldest = found.last {
            _ = trial.load(until: oldest.messageID, newest: chat.messages,
                           loader: { before, limit in model.olderMessages(in: chatID, before: before, limit: limit) })
        }
        let loaded = Set(trial.messages(newest: chat.messages).map(\.id))
        let kept = currentHit.flatMap { hits.indices.contains($0) ? hits[$0].messageID : nil }
        hits = found.filter { loaded.contains($0.messageID) }
        searchedQuery = text
        if !jumpToFirst, let kept, let same = hits.firstIndex(where: { $0.messageID == kept }) {
            currentHit = same
            return
        }
        currentHit = ChatSearch.step(hits, current: nil, direction: .next)
        if jumpToFirst, let first = currentHit { jump = JumpRequest(messageID: hits[first].messageID) }
    }

    private func moveSearch(_ direction: ChatSearch.Direction) {
        guard let next = ChatSearch.step(hits, current: currentHit, direction: direction) else { return }
        currentHit = next
        jump = JumpRequest(messageID: hits[next].messageID)
    }

    private var searchBar: some View {
        HStack(spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary).accessibilityHidden(true)
                TextField("Search in chat", text: $query)
                    .focused($searchFocused)
                    .submitLabel(.search)
                    .onSubmit { moveSearch(.next) }
                    .autocorrectionDisabled()
                    .accessibilityLabel("Search in chat")
                    .accessibilityIdentifier("chat.search.field")
                if searchAnswered {
                    if hits.isEmpty {
                        Text("No results")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .accessibilityIdentifier("chat.search.empty")
                    } else if let currentHit {
                        Text("\(currentHit + 1) of \(hits.count)")
                            .font(.footnote)
                            .monospacedDigit()
                            .foregroundStyle(.secondary)
                            .accessibilityIdentifier("chat.search.count")
                    }
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(Color.secondaryBackground, in: RoundedRectangle(cornerRadius: 10))
            Button { moveSearch(.previous) } label: { Image(systemName: "chevron.up") }
                .disabled(hits.isEmpty || !searchAnswered)
                .accessibilityLabel("Previous result")
                .accessibilityIdentifier("chat.search.previous")
            Button { moveSearch(.next) } label: { Image(systemName: "chevron.down") }
                .disabled(hits.isEmpty || !searchAnswered)
                .accessibilityLabel("Next result")
                .accessibilityIdentifier("chat.search.next")
            Button("Cancel") { closeSearch() }
                .accessibilityIdentifier("chat.search.cancel")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
        .task(id: trimmedQuery) {
            // Debounced, and kept in state so typing does not search on every redraw.
            let text = trimmedQuery
            guard !text.isEmpty else {
                hits = []
                searchedQuery = ""
                currentHit = nil
                return
            }
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            runSearch(text, jumpToFirst: true)
        }
    }
}

/// Pick a contact to forward a message to.
private struct ForwardPickerView: View {
    let model: AppModel
    let choose: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(model.contacts.sorted { $0.displayName.localizedCompare($1.displayName) == .orderedAscending }) { person in
                Button { choose(person.id) } label: {
                    HStack(spacing: 12) {
                        Avatar(name: person.displayName, colorKey: person.id, size: 36)
                        VStack(alignment: .leading) {
                            Text(person.displayName).foregroundStyle(.primary)
                            Text("@\(person.username)").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                .accessibilityIdentifier("forward.contact.\(person.id)")
            }
            .navigationTitle("Forward to…")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
    }
}

/// Accept, Block, or Report a message request.
struct RequestActionsBar: View {
    let chat: Chat
    let model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmReport = false

    var body: some View {
        VStack(spacing: 10) {
            Text("This person isn't in your contacts. Their calls won't ring until you accept.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            HStack(spacing: 10) {
                Button(role: .destructive) {
                    model.block(contactID: chat.contactID)
                    dismiss()
                } label: { Text("Block").frame(maxWidth: .infinity) }
                    .accessibilityIdentifier("requests.block")
                Button {
                    confirmReport = true
                } label: { Text("Report").frame(maxWidth: .infinity) }
                    .accessibilityIdentifier("requests.report")
                Button {
                    model.accept(requestID: chat.id)
                } label: { Text("Accept").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("requests.accept")
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
        }
        .confirmationDialog("Report this chat?", isPresented: $confirmReport, titleVisibility: .visible) {
            Button("Report", role: .destructive) { model.report(chatID: chat.id) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The last 10 messages will be sent to the server admin.")
        }
        .padding()
        .glassBackground(in: RoundedRectangle(cornerRadius: 24))
        .padding(.horizontal, 10)
        .padding(.bottom, 6)
    }
}
