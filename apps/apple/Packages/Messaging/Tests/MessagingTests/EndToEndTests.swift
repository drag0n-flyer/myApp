import AppCore
import Foundation
#if canImport(ImageIO)
import ImageIO
import UniformTypeIdentifiers
#endif
import LocalStore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

/// Writes a photo for `sendAttachment`. Where ImageIO exists the photo is re-encoded before sending, so it must be a
/// real image; elsewhere the bytes are sent as they are.
private func writeTestPhoto(to url: URL) throws {
    #if canImport(ImageIO)
    let (w, h) = (400, 300)
    let ctx = try #require(CGContext(
        data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
        space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
    for i in 0..<40 {
        ctx.setFillColor(red: CGFloat(i % 7) / 7, green: CGFloat(i % 5) / 5, blue: CGFloat(i % 3) / 3, alpha: 1)
        ctx.fill(CGRect(x: (i * 97) % w, y: (i * 53) % h, width: w / 6, height: h / 6))
    }
    let image = try #require(ctx.makeImage())
    let dest = try #require(CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil))
    CGImageDestinationAddImage(dest, image, nil)
    #expect(CGImageDestinationFinalize(dest))
    #else
    try Data(repeating: 0xAB, count: 70_000).write(to: url)
    #endif
}

/// A clock that moves one second on every reading, so messages sort in the order they were made.
private final class TickingClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current = testEpochDate
    func now() -> Date { lock.withLock { current = current.addingTimeInterval(1); return current } }
}

/// One device with its whole graph, built the way the app builds it (`LiveMessaging.make`): a `LiveChatStore`
/// on an inbox processor, an outbox, an attachment service and device sync, all on one `FakeServer`.
@MainActor
private struct Node {
    let device: TestDevice
    let store: LiveChatStore
    let sync: DeviceSync
    let inbox: InboxProcessor
    var repo: Repository { device.deps.repo }
    var accountID: String { device.accountID }

    init(_ device: TestDevice, network: NetworkType = .wifi) {
        self.device = device
        let deps = device.deps
        let keys = EscrowKeyProvider(server: deps.server)
        let manager = ConversationManager(deps: deps, escrowKey: keys.closure)
        let outbox = Outbox(deps: deps, conversations: manager, keys: keys)
        inbox = InboxProcessor(
            deps: deps, conversations: manager, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }),
            controls: outbox)
        let attachments = AttachmentService(deps: deps, outbox: outbox, network: { network }, settings: { AutoDownloadSettings() })
        store = LiveChatStore(deps: deps, outbox: outbox, inbox: inbox, conversations: manager, attachments: attachments)
        sync = DeviceSync(deps: deps, conversations: manager, wipeMLS: {})
    }

    /// Fetches the inbox and waits for everything it started (receipts, downloads).
    func catchUp() async {
        await store.syncInbox()
        await store.settle()
    }

    func texts(in chatID: String) -> [String] {
        store.messages(in: chatID, before: nil, limit: 50).compactMap {
            if case .text(let t) = $0.content { return t }
            return nil
        }
    }
}

@MainActor
private struct World {
    let server: FakeServer
    let ana1: Node, ana2: Node, bob1: Node, bob2: Node

    let clock: TickingClock

    static func make(mode: String = "e2ee") async throws -> World {
        let server = FakeServer()
        let clock = TickingClock()
        await server.setDefaultMode(mode)
        var nodes: [Node] = []
        for (account, name) in [("acc-ana", "ana"), ("acc-ana", "ana"), ("acc-bob", "bob"), ("acc-bob", "bob")] {
            let device = await makeDevice(server, account: account, username: name, now: clock.now)
            _ = try await device.deps.server.uploadKeyPackages(try await device.deps.mls.makeKeyPackages(count: 5))
            nodes.append(Node(device))
        }
        return World(server: server, clock: clock, nodes: nodes)
    }

    private init(server: FakeServer, clock: TickingClock, nodes: [Node]) {
        self.server = server
        self.clock = clock
        ana1 = nodes[0]; ana2 = nodes[1]; bob1 = nodes[2]; bob2 = nodes[3]
    }

    /// Ana's first device adds bob and sends the first message, which also creates the conversation.
    func anaSays(_ text: String) async {
        _ = await ana1.store.addContact(username: "bob")
        let chat = ana1.store.ensureChat(with: "acc-bob")
        ana1.store.send(.text(text), in: chat)
        await ana1.store.settle()
    }
}

@Suite("TestEndToEnd") @MainActor struct TestEndToEnd {
    @Test func testConversationAcrossFourDevices() async throws {
        let w = try await World.make()
        await w.anaSays("hi")
        for node in [w.bob1, w.bob2, w.ana2] { await node.catchUp() }

        #expect(w.bob1.texts(in: "acc-ana") == ["hi"])
        #expect(w.bob2.texts(in: "acc-ana") == ["hi"])
        #expect(w.ana2.texts(in: "acc-bob") == ["hi"])

        // bob2 answers with a photo; ana1 downloads it on its own because it is on Wi-Fi.
        let photo = w.bob2.device.directory.appendingPathComponent("beach.jpg")
        try writeTestPhoto(to: photo)
        w.bob2.store.sendAttachment(photo, kind: .photo, in: "acc-ana")
        await w.bob2.store.settle()
        await w.ana1.catchUp()
        let received = try #require(w.ana1.store.messages(in: "acc-bob", before: nil, limit: 50).last)
        guard case .photo = received.content else { Issue.record("expected a photo, got \(received.content)"); return }
        let media = try #require(received.media)
        guard case .local(let url) = media.phase else { Issue.record("not downloaded: \(media.phase)"); return }
        #expect(try Data(contentsOf: url).count > 0)

        // bob1 opens the chat: ana's "hi" becomes read on ana's side.
        let hi = try #require(w.ana1.store.messages(in: "acc-bob", before: nil, limit: 50).first)
        #expect(hi.status != .read)
        // Ana is a stranger to bob until he accepts her request; only then does his phone send read receipts.
        w.bob1.store.accept(requestID: "acc-ana")
        w.bob1.store.markRead(chatID: "acc-ana")
        await w.bob1.store.settle()
        await w.ana1.catchUp()
        let after = try #require(w.ana1.store.messages(in: "acc-bob", before: nil, limit: 50).first { $0.id == hi.id })
        #expect(after.status == .read)
    }

    @Test func testRevokedDeviceGetsNothing() async throws {
        let w = try await World.make()
        await w.anaSays("before")
        for node in [w.bob1, w.bob2] { await node.catchUp() }
        #expect(w.bob2.texts(in: "acc-ana") == ["before"])

        await w.server.revokeDevice(w.bob2.device.deviceID)
        try await w.bob1.sync.handleRevoked(deviceID: w.bob2.device.deviceID)
        await w.ana1.catchUp()   // processes the remove commit
        w.ana1.store.send(.text("after"), in: "acc-bob")
        await w.ana1.store.settle()

        await w.bob1.catchUp()
        await w.bob2.catchUp()
        #expect(w.bob1.texts(in: "acc-ana") == ["before", "after"])
        #expect(w.bob2.texts(in: "acc-ana") == ["before"])
    }

    @Test func testLinkedDeviceJoins() async throws {
        let w = try await World.make()
        await w.anaSays("one")
        await w.bob1.catchUp()

        // ana3 is linked: it gets the snapshot, then ana1 adds it to the conversations.
        let device = await makeDevice(w.server, account: "acc-ana", username: "ana", now: w.clock.now)
        _ = try await device.deps.server.uploadKeyPackages(try await device.deps.mls.makeKeyPackages(count: 5))
        let ana3 = Node(device)
        try await ana3.sync.importSnapshot(try await w.ana1.sync.exportSnapshot())
        try await w.ana1.sync.reconcileOwnDevices()

        w.ana1.store.send(.text("two"), in: "acc-bob")
        await w.ana1.store.settle()
        await ana3.catchUp()

        #expect(ana3.texts(in: "acc-bob") == ["two"])   // history is not transferred
        #expect(ana3.store.contacts.map(\.id).sorted() == w.ana1.store.contacts.map(\.id).sorted())
        #expect(ana3.store.contacts.contains { $0.id == "acc-bob" })
    }

    @Test func testServerReadableEndToEnd() async throws {
        let w = try await World.make(mode: "server_readable")
        await w.anaSays("hello")
        await w.bob1.catchUp()
        w.bob1.store.send(.text("hello back"), in: "acc-ana")
        await w.bob1.store.settle()

        let conversationIDs = await w.server.conversationIDs()
        let conversation = try #require(conversationIDs.first)
        let stored = await w.server.storedMessages(in: conversation)
        #expect(stored.count >= 2)
        #expect(stored.allSatisfy { $0.escrow != nil })
        #expect(w.ana1.store.chat(id: "acc-bob")?.encryption == .serverReadable)
        await w.ana1.catchUp()
        #expect(w.bob1.store.chat(id: "acc-ana")?.encryption == .serverReadable)
    }

    @Test func testVoiceAndFileCarryTheirDetails() async throws {
        let w = try await World.make()
        await w.anaSays("hi")
        let voice = w.ana1.device.directory.appendingPathComponent("note.caf")
        let file = w.ana1.device.directory.appendingPathComponent("notes.txt")
        try Data(repeating: 1, count: 3_000).write(to: voice)
        try Data("plain notes".utf8).write(to: file)
        w.ana1.store.sendVoice(voice, duration: 4.5, in: "acc-bob")
        w.ana1.store.sendAttachment(file, kind: .file, in: "acc-bob")
        await w.ana1.store.settle()
        await w.bob1.catchUp()
        let contents = w.bob1.store.messages(in: "acc-ana", before: nil, limit: 50).map(\.content)
        #expect(contents.contains(.voice(duration: 4.5)))
        #expect(contents.contains(.file(name: "notes.txt", bytes: 11)))
    }
}
