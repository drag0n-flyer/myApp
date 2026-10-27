import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import ServerKit

/// An in-memory server for tests: the delivery API of server design 7.2 (key packages,
/// conversations, commits, messages, inbox) plus users, blocks, reports, attachments (with
/// `Range`), `GET /v1/conversations/{id}` and `GET /v1/devices`.
///
/// Authentication: the bearer token is `test.<accountID>.<deviceID>` and the device must have
/// been registered (`makeDevice` does that). Everything is deterministic: IDs count up and time
/// comes from the injected clock.
public actor FakeServer {
    public static let host = "fake.test"

    /// The mode new conversations get. Tests set it to `server_readable` to exercise escrow.
    public var defaultMode = "e2ee"

    private struct Account {
        var username: String
        var displayName: String
        var devices: [String] = []
    }

    private struct Conversation {
        var id: String
        var mode: String
        var epoch: UInt64
        var accounts: Set<String>
        var members: Set<String>   // device IDs
        var nextSeq: Int64 = 1
    }

    private var accounts: [String: Account] = [:]
    private var deviceAccount: [String: String] = [:]
    private var keyPackages: [String: [Data]] = [:]            // device -> packages
    private var conversations: [String: Conversation] = [:]
    private var inboxes: [String: [InboxItem]] = [:]            // device -> items
    private var nextCursor: Int64 = 1
    private var blocks: [String: Set<String>] = [:]             // account -> blocked accounts
    private var reports: [[String: String]] = []
    private var attachments: [String: Data] = [:]
    private var messageSeqs: [String: Int64] = [:]              // "device|conv|clientMsgID" -> seq
    private var subscribers: [String: [UUID: AsyncStream<ServerEvent>.Continuation]] = [:]
    private var messageLog: [String: [StoredMessage]] = [:]      // conversation -> messages in seq order
    private var pendingCommitRejections = 0
    private var commitResponsesToLose = 0
    private var responseLost = false
    private var messageFailures: [Int] = []   // statuses for the next message posts
    private var counter = 0
    private let now: @Sendable () -> Date

    public init(now: @escaping @Sendable () -> Date = { Date(timeIntervalSince1970: 1_700_000_000) }) {
        self.now = now
    }

    public func setDefaultMode(_ mode: String) { defaultMode = mode }

    /// A message as the server stores it (what the sender posted).
    public struct StoredMessage: Sendable, Equatable {
        public let seq: Int64
        public let epoch: UInt64
        public let senderDeviceID: String
        public let ciphertext: Data
        public let escrow: Data?
    }

    /// The next `count` commit requests fail with `409 stale_epoch` (the epoch does not change).
    public func rejectNextCommits(_ count: Int) { pendingCommitRejections = count }

    /// The next `count` commits are applied, but the client never sees the reply (the transport throws).
    public func loseNextCommitResponses(_ count: Int) { commitResponsesToLose = count }

    /// The next message posts fail with these statuses, in order, without storing anything.
    public func failNextMessages(statuses: [Int]) { messageFailures = statuses }

    fileprivate func takeLostResponse() -> Bool {
        defer { responseLost = false }
        return responseLost
    }

    /// Messages stored for a conversation, in `seq` order.
    public func storedMessages(in conversationID: String) -> [StoredMessage] { messageLog[conversationID] ?? [] }

    /// Device IDs that are members of the conversation, sorted.
    public func memberDevices(of conversationID: String) -> [String] { (conversations[conversationID]?.members ?? []).sorted() }

    /// All conversation IDs, sorted.
    public func conversationIDs() -> [String] { conversations.keys.sorted() }
    /// The escrow public key `GET /v1/server` reports (32 bytes; the fake never uses the private half).
    public var escrowKey = Data((0..<32).map { UInt8(0x40 + $0) })

    // MARK: - Test hooks

    /// Adds a device to `account`, creating the account (with `username`) on first use.
    public func registerDevice(account: String, username: String) -> String {
        if accounts[account] == nil { accounts[account] = Account(username: username, displayName: username.capitalized) }
        counter += 1
        let deviceID = "dev-\(counter)"
        accounts[account]?.devices.append(deviceID)
        deviceAccount[deviceID] = account
        return deviceID
    }

    public func transport(accountID: String, deviceID: String) -> HTTPTransport {
        FakeTransport(server: self)
    }

    public func transport(for device: TestDevice) -> HTTPTransport {
        transport(accountID: device.accountID, deviceID: device.deviceID)
    }

    /// Server-pushed events for one device (what the WebSocket would carry). Only inbox items.
    public func events(for deviceID: String) -> AsyncStream<ServerEvent> {
        let id = UUID()
        let (stream, continuation) = AsyncStream<ServerEvent>.makeStream()
        subscribers[deviceID, default: [:]][id] = continuation
        continuation.onTermination = { [weak self] _ in
            Task { await self?.removeSubscriber(deviceID, id) }
        }
        return stream
    }

    public func submittedReports() -> [[String: String]] { reports }

    private func removeSubscriber(_ deviceID: String, _ id: UUID) {
        subscribers[deviceID]?[id] = nil
    }

    // MARK: - Request handling

    fileprivate func handle(_ request: URLRequest) -> (Data, HTTPURLResponse) {
        let method = request.httpMethod ?? "GET"
        guard let url = request.url, let comps = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return failure(400, "bad_request")
        }
        let path = comps.path
        if method == "GET", path == "/v1/server" { return serverInfo() }
        let query = Dictionary((comps.queryItems ?? []).compactMap { item in item.value.map { (item.name, $0) } },
                               uniquingKeysWith: { first, _ in first })
        guard let token = request.value(forHTTPHeaderField: "Authorization"), token.hasPrefix("Bearer test."),
              let (accountID, deviceID) = Self.parse(token: String(token.dropFirst("Bearer ".count))),
              deviceAccount[deviceID] == accountID else {
            return failure(401, "unauthorized")
        }
        let body = request.httpBody ?? Data()
        let parts = path.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        guard parts.first == "v1" else { return failure(404, "not_found") }
        let rest = Array(parts.dropFirst())
        let me = Caller(account: accountID, device: deviceID)

        var shape = rest
        if shape.count == 2, ["conversations", "users", "blocks", "attachments"].contains(shape[0]) { shape[1] = "{}" }
        if shape.count == 3, shape[0] == "conversations" { shape[1] = "{}" }
        let id = rest.count > 1 ? rest[1] : ""
        switch "\(method) /\(shape.joined(separator: "/"))" {
        case "POST /keypackages": return uploadKeyPackages(me, body)
        case "GET /keypackages/count": return ok(Count(count: keyPackages[deviceID, default: []].count))
        case "POST /keypackages/claim": return claim(me, body)
        case "POST /conversations": return createConversation(me, body)
        case "GET /conversations/{}": return getConversation(me, id)
        case "POST /conversations/{}/commits": return postCommit(me, id, body)
        case "POST /conversations/{}/messages": return postMessage(me, id, body)
        case "GET /inbox": return inbox(me, query)
        case "POST /inbox/ack": return ack(me, body)
        case "GET /users/{}": return user(id)
        case "PUT /blocks/{}": blocks[accountID, default: []].insert(id); return noContent()
        case "DELETE /blocks/{}": blocks[accountID, default: []].remove(id); return noContent()
        case "GET /blocks": return ok(BlockList(accountIDs: blocks[accountID, default: []].sorted()))
        case "POST /reports": return report(body)
        case "POST /attachments": return upload(body)
        case "GET /attachments/{}": return download(id, range: request.value(forHTTPHeaderField: "Range"))
        case "GET /devices": return devices(me)
        default: return failure(404, "not_found")
        }
    }

    private func serverInfo() -> (Data, HTTPURLResponse) {
        struct Policy: Encodable { let defaultMode = "auto", maxMode = "high", defaultIPHiding = "nonContacts" }
        struct Encryption: Encodable { let newConversations: String; let escrowKey: String? }
        struct Limits: Encodable { let attachmentBytes = 104_857_600 }
        struct Info: Encodable {
            let `protocol` = 1, host = FakeServer.host, requiresInvite = false
            let policy = Policy()
            let encryption: Encryption
            let features: [String] = []
            let federation = false
            let limits = Limits()
        }
        return ok(Info(encryption: Encryption(
            newConversations: defaultMode,
            escrowKey: defaultMode == "server_readable" ? Base64URLData(escrowKey).encoded : nil)))
    }

    private struct Count: Encodable { let count: Int }
    private struct Seq: Encodable { let seq: Int64 }
    private struct EpochOut: Encodable { let epoch: UInt64 }
    private struct BlockList: Encodable { let accountIDs: [String] }
    private struct ConversationOut: Encodable { let conversationID: String; let mode: String; let epoch: UInt64 }
    private struct ClaimEntry: Encodable { let deviceID: String; let keyPackage: Base64URLData }
    private struct ClaimOut: Encodable { let keyPackages: [ClaimEntry] }

    private struct Caller { let account: String; let device: String }

    private static func parse(token: String) -> (String, String)? {
        let pieces = token.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard pieces.count == 3, pieces[0] == "test" else { return nil }
        return (pieces[1], pieces[2])
    }

    // MARK: - Delivery

    private struct KeyPackagesBody: Decodable { let keyPackages: [Base64URLData] }
    private struct ClaimBody: Decodable { let accountID: String }
    private struct MembersBody: Decodable { let members: [String] }
    private struct CommitBody: Decodable {
        let epoch: UInt64
        let commit: Base64URLData
        let welcome: Base64URLData?
        let addDevices: [String]?
        let removeDevices: [String]?
    }
    private struct MessageBody: Decodable {
        let epoch: UInt64
        let ciphertext: Base64URLData
        let escrow: Base64URLData?
        let clientMsgID: String
    }
    private struct AckBody: Decodable { let upTo: Int64 }

    private func uploadKeyPackages(_ me: Caller, _ body: Data) -> (Data, HTTPURLResponse) {
        guard let b: KeyPackagesBody = decode(body) else { return failure(400, "bad_request") }
        var list = keyPackages[me.device, default: []]
        list.append(contentsOf: b.keyPackages.map(\.data))
        if list.count > 100 { list.removeFirst(list.count - 100) }
        keyPackages[me.device] = list
        return ok(Count(count: list.count))
    }

    private func claim(_ me: Caller, _ body: Data) -> (Data, HTTPURLResponse) {
        guard let b: ClaimBody = decode(body) else { return failure(400, "bad_request") }
        guard let target = accounts[b.accountID], !blocks[b.accountID, default: []].contains(me.account) else {
            return failure(404, "not_found")
        }
        var entries: [ClaimEntry] = []
        for device in target.devices {
            guard var list = keyPackages[device], let first = list.first else { continue }
            // A device's last KeyPackage is handed out without being removed.
            if list.count > 1 { list.removeFirst(); keyPackages[device] = list }
            entries.append(ClaimEntry(deviceID: device, keyPackage: Base64URLData(first)))
        }
        return ok(ClaimOut(keyPackages: entries))
    }

    private func createConversation(_ me: Caller, _ body: Data) -> (Data, HTTPURLResponse) {
        guard let b: MembersBody = decode(body), b.members.count == 1, let other = b.members.first,
              other != me.account, accounts[other] != nil else { return failure(400, "bad_request") }
        counter += 1
        let id = "conv-\(counter)"
        conversations[id] = Conversation(id: id, mode: defaultMode, epoch: 0, accounts: [me.account, other], members: [me.device])
        return ok(ConversationOut(conversationID: id, mode: defaultMode, epoch: 0))
    }

    private func getConversation(_ me: Caller, _ id: String) -> (Data, HTTPURLResponse) {
        guard let c = conversations[id], c.members.contains(me.device) else { return failure(404, "not_found") }
        return ok(ConversationOut(conversationID: c.id, mode: c.mode, epoch: c.epoch))
    }

    private func postCommit(_ me: Caller, _ id: String, _ body: Data) -> (Data, HTTPURLResponse) {
        guard var c = conversations[id], c.members.contains(me.device) else { return failure(404, "not_found") }
        guard let b: CommitBody = decode(body) else { return failure(400, "bad_request") }
        if pendingCommitRejections > 0 {
            pendingCommitRejections -= 1
            return failure(409, "stale_epoch", details: ["currentEpoch": Int(c.epoch)])
        }
        guard b.epoch == c.epoch else { return failure(409, "stale_epoch", details: ["currentEpoch": Int(c.epoch)]) }
        let added = b.addDevices ?? []
        let removed = b.removeDevices ?? []
        for device in added {
            guard let account = deviceAccount[device], c.accounts.contains(account) else { return failure(400, "bad_request") }
        }
        let before = c.members
        let newEpoch = c.epoch + 1
        // The commit goes to every device present before it, including the sender.
        for device in before.sorted() {
            deliver(to: device, kind: "commit", conversation: c.id, seq: nil, epoch: Int64(c.epoch), from: me, payload: b.commit.data)
        }
        if let welcome = b.welcome {
            for device in added.sorted() {
                guard let account = deviceAccount[device], !blocks[account, default: []].contains(me.account) else { continue }
                deliver(to: device, kind: "welcome", conversation: c.id, seq: nil, epoch: Int64(newEpoch), from: me, payload: welcome.data)
            }
        }
        for device in added {
            if let account = deviceAccount[device], !blocks[account, default: []].contains(me.account) { c.members.insert(device) }
        }
        for device in removed { c.members.remove(device) }
        c.epoch = newEpoch
        conversations[id] = c
        if commitResponsesToLose > 0 { commitResponsesToLose -= 1; responseLost = true }
        return ok(EpochOut(epoch: newEpoch))
    }

    private func postMessage(_ me: Caller, _ id: String, _ body: Data) -> (Data, HTTPURLResponse) {
        guard var c = conversations[id], c.members.contains(me.device) else { return failure(404, "not_found") }
        guard let b: MessageBody = decode(body) else { return failure(400, "bad_request") }
        if !messageFailures.isEmpty { return failure(messageFailures.removeFirst(), "unavailable") }
        let key = "\(me.device)|\(id)|\(b.clientMsgID)"
        if let seq = messageSeqs[key] { return ok(Seq(seq: seq)) }
        guard b.epoch <= c.epoch else { return failure(400, "bad_request") }
        if c.mode == "e2ee", b.escrow != nil { return failure(400, "escrow_forbidden") }
        if c.mode == "server_readable", b.escrow == nil { return failure(400, "escrow_required") }
        let seq = c.nextSeq
        c.nextSeq += 1
        conversations[id] = c
        messageSeqs[key] = seq
        messageLog[id, default: []].append(StoredMessage(
            seq: seq, epoch: b.epoch, senderDeviceID: me.device, ciphertext: b.ciphertext.data, escrow: b.escrow?.data))
        for device in c.members.sorted() where device != me.device {
            guard let account = deviceAccount[device], !blocks[account, default: []].contains(me.account) else { continue }
            deliver(to: device, kind: "message", conversation: id, seq: seq, epoch: Int64(b.epoch), from: me, payload: b.ciphertext.data)
        }
        return ok(Seq(seq: seq))
    }

    private func deliver(to device: String, kind: String, conversation: String, seq: Int64?, epoch: Int64, from: Caller, payload: Data) {
        let item = InboxItem(cursor: nextCursor, kind: kind, conversationID: conversation, seq: seq, epoch: epoch,
                             senderAccountID: from.account, senderDeviceID: from.device, payload: payload, sentAt: now())
        nextCursor += 1
        inboxes[device, default: []].append(item)
        for continuation in subscribers[device, default: [:]].values { continuation.yield(.inbox(item)) }
    }

    private func inbox(_ me: Caller, _ query: [String: String]) -> (Data, HTTPURLResponse) {
        let after = query["after"].flatMap { Int64($0) } ?? 0
        let limit = min(max(query["limit"].flatMap { Int($0) } ?? 100, 1), 500)
        let all = inboxes[me.device, default: []].filter { $0.cursor > after }.sorted { $0.cursor < $1.cursor }
        let page = Array(all.prefix(limit))
        struct Page: Encodable { let items: [InboxItem]; let more: Bool }
        return json(200, Page(items: page, more: all.count > page.count))
    }

    private func ack(_ me: Caller, _ body: Data) -> (Data, HTTPURLResponse) {
        guard let b: AckBody = decode(body) else { return failure(400, "bad_request") }
        inboxes[me.device] = inboxes[me.device, default: []].filter { $0.cursor > b.upTo }
        return noContent()
    }

    // MARK: - Users, blocks, reports, attachments, devices

    private func user(_ username: String) -> (Data, HTTPURLResponse) {
        guard let (id, a) = accounts.first(where: { $0.value.username == username }) else { return failure(404, "not_found") }
        return json(200, UserSummaryOut(accountID: id, username: a.username, displayName: a.displayName))
    }

    private struct UserSummaryOut: Encodable { let accountID: String; let username: String; let displayName: String }

    private func report(_ body: Data) -> (Data, HTTPURLResponse) {
        guard let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else { return failure(400, "bad_request") }
        reports.append(["accountID": object["accountID"] as? String ?? "", "reason": object["reason"] as? String ?? ""])
        return noContent()
    }

    private func upload(_ body: Data) -> (Data, HTTPURLResponse) {
        counter += 1
        let id = "att-\(counter)"
        attachments[id] = body
        struct Out: Encodable { let id: String; let expiresAt: Date }
        return json(200, Out(id: id, expiresAt: now().addingTimeInterval(30 * 86_400)))
    }

    private func download(_ id: String, range: String?) -> (Data, HTTPURLResponse) {
        guard let data = attachments[id] else { return failure(404, "not_found") }
        guard let range, range.hasPrefix("bytes=") else { return raw(200, data) }
        let bounds = range.dropFirst("bytes=".count).split(separator: "-", omittingEmptySubsequences: false)
        guard bounds.count == 2, let lower = Int(bounds[0]), lower < data.count else { return failure(416, "bad_request") }
        let upper = min(Int(bounds[1]) ?? (data.count - 1), data.count - 1)
        guard upper >= lower else { return failure(416, "bad_request") }
        return raw(206, data.subdata(in: lower..<(upper + 1)),
                   headers: ["Content-Range": "bytes \(lower)-\(upper)/\(data.count)"])
    }

    private func devices(_ me: Caller) -> (Data, HTTPURLResponse) {
        struct Out: Encodable {
            let id: String, name: String, platform: String, createdAt: Date, lastSeenAt: Date, current: Bool
        }
        let list = (accounts[me.account]?.devices ?? []).map {
            Out(id: $0, name: "Test device \($0)", platform: "ios", createdAt: now(), lastSeenAt: now(), current: $0 == me.device)
        }
        struct Reply: Encodable { let devices: [Out] }
        return json(200, Reply(devices: list))
    }

    // MARK: - Responses

    private func decode<T: Decodable>(_ data: Data) -> T? { try? JSONDecoder().decode(T.self, from: data) }

    private func encoder() -> JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }

    private func response(_ status: Int, _ data: Data, headers: [String: String] = [:]) -> (Data, HTTPURLResponse) {
        let url = URL(string: "https://\(Self.host)/")!
        return (data, HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!)
    }

    private func json<T: Encodable>(_ status: Int, _ value: T) -> (Data, HTTPURLResponse) {
        response(status, (try? encoder().encode(value)) ?? Data(), headers: ["Content-Type": "application/json"])
    }

    private func ok<T: Encodable>(_ value: T) -> (Data, HTTPURLResponse) { json(200, value) }
    private func noContent() -> (Data, HTTPURLResponse) { response(204, Data()) }
    private func raw(_ status: Int, _ data: Data, headers: [String: String] = [:]) -> (Data, HTTPURLResponse) {
        response(status, data, headers: headers)
    }

    private func failure(_ status: Int, _ code: String, details: [String: Int]? = nil) -> (Data, HTTPURLResponse) {
        struct Body: Encodable { let code: String; let message: String; let details: [String: Int]? }
        struct Envelope: Encodable { let error: Body }
        return json(status, Envelope(error: Body(code: code, message: code, details: details)))
    }
}

private struct FakeTransport: HTTPTransport {
    let server: FakeServer
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let reply = await server.handle(request)
        if await server.takeLostResponse() { throw URLError(.networkConnectionLost) }
        return reply
    }
}
