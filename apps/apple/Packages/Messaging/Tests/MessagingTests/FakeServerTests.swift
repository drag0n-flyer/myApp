import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

@Suite("TestFakeServerConversation") struct TestFakeServerConversation {
    @Test func claimCreateCommitThenStaleEpoch() async throws {
        let server = FakeServer()
        let alice = await makeDevice(server, account: "acc-alice", username: "alice")
        let bob = await makeDevice(server, account: "acc-bob", username: "bob")

        let bobPackages = try await bob.deps.mls.makeKeyPackages(count: 2)
        #expect(try await bob.deps.server.uploadKeyPackages(bobPackages) == 2)

        let claimed = try await alice.deps.server.claimKeyPackages(accountID: bob.accountID)
        #expect(claimed.map(\.deviceID) == [bob.deviceID])

        let conversation = try await alice.deps.server.createConversation(with: bob.accountID)
        #expect(conversation.epoch == 0 && conversation.mode == "e2ee")

        let group = try await alice.deps.mls.createGroup()
        let bundle = try await alice.deps.mls.add(keyPackages: claimed.map(\.keyPackage), to: group)
        let epoch = try await alice.deps.server.postCommit(
            conversationID: conversation.conversationID, epoch: 0, commit: bundle.commit,
            welcome: bundle.welcome, addDevices: [bob.deviceID], removeDevices: [])
        #expect(epoch == 1)

        do {
            _ = try await alice.deps.server.postCommit(
                conversationID: conversation.conversationID, epoch: 0, commit: Data("other".utf8),
                welcome: nil, addDevices: [], removeDevices: [])
            Issue.record("expected stale_epoch")
        } catch {
            #expect(error.status == 409 && error.code == "stale_epoch" && error.details?["currentEpoch"] == 1)
        }

        // Bob's inbox: the welcome. Alice's inbox: her own commit echoed back.
        let bobInbox = try await bob.deps.server.inbox(after: nil)
        #expect(bobInbox.items.map(\.kind) == ["welcome"])
        let aliceInbox = try await alice.deps.server.inbox(after: nil)
        #expect(aliceInbox.items.map(\.kind) == ["commit"])
        let joined = try await bob.deps.mls.process(bobInbox.items[0].payload)
        #expect(joined == .joined(group: group, epoch: 1))
    }

    @Test func messagesAreIdempotentAndFannedOut() async throws {
        let server = FakeServer()
        let alice = await makeDevice(server, account: "acc-alice", username: "alice")
        let bob = await makeDevice(server, account: "acc-bob", username: "bob")
        let conv = try await alice.deps.server.createConversation(with: bob.accountID)
        _ = try await alice.deps.server.postCommit(
            conversationID: conv.conversationID, epoch: 0, commit: Data("c".utf8), welcome: Data("w".utf8),
            addDevices: [bob.deviceID], removeDevices: [])
        let first = try await alice.deps.server.postMessage(
            conversationID: conv.conversationID, epoch: 1, ciphertext: Data("m".utf8), escrow: nil, clientMsgID: "id1")
        let again = try await alice.deps.server.postMessage(
            conversationID: conv.conversationID, epoch: 1, ciphertext: Data("m".utf8), escrow: nil, clientMsgID: "id1")
        #expect(first == again)
        let kinds = try await bob.deps.server.inbox(after: nil).items.map(\.kind)
        #expect(kinds == ["welcome", "message"])
        do {
            _ = try await alice.deps.server.postMessage(
                conversationID: conv.conversationID, epoch: 1, ciphertext: Data("m".utf8), escrow: Data("e".utf8), clientMsgID: "id2")
            Issue.record("expected escrow_forbidden")
        } catch {
            #expect(error.code == "escrow_forbidden")
        }
    }

    @Test func attachmentsSupportRange() async throws {
        let server = FakeServer()
        let alice = await makeDevice(server, account: "acc-alice", username: "alice")
        let up = try await alice.deps.server.uploadAttachment(Data("0123456789".utf8))
        #expect(try await alice.deps.server.downloadAttachment(id: up.id) == Data("0123456789".utf8))
        #expect(try await alice.deps.server.downloadAttachment(id: up.id, range: 2...4) == Data("234".utf8))
    }

    @Test func usersBlocksAndEvents() async throws {
        let server = FakeServer()
        let alice = await makeDevice(server, account: "acc-alice", username: "alice")
        let bob = await makeDevice(server, account: "acc-bob", username: "bob")
        let found = try await alice.deps.server.lookUpUser(username: "bob")
        #expect(found.accountID == "acc-bob")
        do { _ = try await alice.deps.server.lookUpUser(username: "nobody"); Issue.record("expected not_found") } catch {
            #expect(error.code == "not_found")
        }
        try await bob.deps.server.block(accountID: alice.accountID)
        #expect(try await bob.deps.server.blocks() == ["acc-alice"])
        do { _ = try await alice.deps.server.claimKeyPackages(accountID: bob.accountID); Issue.record("expected not_found") } catch {
            #expect(error.code == "not_found")
        }

        let stream = await server.events(for: bob.deviceID)
        let carol = await makeDevice(server, account: "acc-carol", username: "carol")
        let conv = try await carol.deps.server.createConversation(with: bob.accountID)
        _ = try await carol.deps.server.postCommit(
            conversationID: conv.conversationID, epoch: 0, commit: Data("c".utf8), welcome: Data("w".utf8),
            addDevices: [bob.deviceID], removeDevices: [])
        var iterator = stream.makeAsyncIterator()
        guard case .inbox(let item)? = await iterator.next() else { Issue.record("no event"); return }
        #expect(item.kind == "welcome" && item.conversationID == conv.conversationID)
    }
}

@Suite("TestKitCiphers") struct TestKitCiphers {
    @Test func attachmentCipherRoundTrips() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let plain = dir.appendingPathComponent("p"), enc = dir.appendingPathComponent("e"), out = dir.appendingPathComponent("o")
        try Data("hello attachment".utf8).write(to: plain)
        let cipher = FakeAttachmentCipher()
        let r = try cipher.encrypt(file: plain, to: enc)
        #expect(r.size == 16 && r.cipherSize == 16 && r.key.count == 32)
        #expect(try Data(contentsOf: enc) != Data("hello attachment".utf8))
        #expect(r.digest == PortableSHA256.hash(try Data(contentsOf: enc)))
        try cipher.decrypt(file: enc, to: out, key: r.key, digest: r.digest)
        #expect(try Data(contentsOf: out) == Data("hello attachment".utf8))
        #expect(throws: (any Error).self) { try cipher.decrypt(file: enc, to: out, key: r.key, digest: Data(count: 32)) }
    }

    @Test func escrowAndNotifier() async throws {
        #expect(try FakeEscrowSealer().seal(Data("x".utf8), to: Data()) == Data("ESCROW:x".utf8))
        let n = RecordingDeviceNotifier()
        await n.ownDeviceAdded(deviceID: "d", name: "iPad")
        #expect(await n.added.map(\.name) == ["iPad"])
    }
}
