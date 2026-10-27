import AppCore
import Foundation
import LocalStore
import ServerKit

/// What `AttachmentService` needs from the send queue. `Outbox` (lane B) conforms with
/// `extension Outbox: EnvelopeEnqueuing {}`; tests use a recorder.
public protocol EnvelopeEnqueuing: Sendable {
    func enqueue(_ envelope: ContentEnvelope, chatID: String) async throws
}

public enum AttachmentServiceError: Error, Equatable, Sendable {
    /// The server sent fewer bytes than requested; what arrived is kept and the next call resumes.
    case incomplete
    case unknownAttachment
    case unreadableResponse
}

/// Uploads, downloads and auto-downloads attachments (Design 7, 9).
///
/// Downloads fetch ciphertext in `Range` segments into `media/<id>.part`, keep `downloaded` in
/// the database after every segment, and so resume where they stopped. Only a complete file with
/// a matching digest is decrypted. Never logs keys, names or content, only IDs.
public actor AttachmentService {
    private let deps: MessagingDeps
    private let outbox: EnvelopeEnqueuing
    private let network: @Sendable () -> NetworkType
    private let settings: @Sendable () -> AutoDownloadSettings
    private let segmentSize: Int
    private var inFlight: [String: Task<Void, Error>] = [:]
    private var lastNotified: Date = .distantPast
    private var onChange: @Sendable () -> Void = {}

    /// `segmentSize` is the most ciphertext one request asks for.
    public init(
        deps: MessagingDeps, outbox: EnvelopeEnqueuing,
        network: @escaping @Sendable () -> NetworkType,
        settings: @escaping @Sendable () -> AutoDownloadSettings,
        segmentSize: Int = 1 << 20
    ) {
        self.deps = deps
        self.outbox = outbox
        self.network = network
        self.settings = settings
        self.segmentSize = max(segmentSize, 1)
    }

    /// Called when an attachment's state changes; progress calls come at most about 4 times a second.
    public func setOnChange(_ handler: @escaping @Sendable () -> Void) { onChange = handler }

    // MARK: - Send

    /// Encrypts `file`, uploads the ciphertext and enqueues the photo, voice or file envelope.
    /// Photo size, voice length and file name are optional because the caller (re-encoding,
    /// recording) knows them; `video` is sent as a file.
    public func send(
        file: URL, kind: MediaKind, chatID: String, replyTo: String?,
        width: Int = 0, height: Int = 0, thumbnail: Data? = nil,
        duration: Double = 0, name: String? = nil, mime: String = "application/octet-stream"
    ) async throws {
        let fm = FileManager.default
        let temp = deps.mediaDirectory.appendingPathComponent("\(UUID().uuidString.lowercased()).enc")
        defer { try? fm.removeItem(at: temp) }
        let sealed = try deps.attachments.encrypt(file: file, to: temp)
        let uploaded = try await deps.server.uploadAttachment(try Data(contentsOf: temp))

        let att = Att(id: uploaded.id, key: sealed.key, dg: sealed.digest, sz: sealed.size, csz: sealed.cipherSize)
        let fileName = name ?? file.lastPathComponent
        let body: ContentEnvelope.Body
        let kindName: String
        switch kind {
        case .photo: body = .photo(att, w: width, h: height, thumb: thumbnail, replyTo: replyTo); kindName = "photo"
        case .voice: body = .voice(att, duration: duration, replyTo: replyTo); kindName = "voice"
        case .file, .video: body = .file(att, name: fileName, mime: mime, replyTo: replyTo); kindName = "file"
        }
        let envelope = ContentEnvelope(
            id: UUID().uuidString.lowercased(), at: Int64((deps.now().timeIntervalSince1970 * 1000).rounded()), body: body)

        // The sender keeps the plaintext so the message shows at once.
        let ext = Self.fileExtension(kind: kindName, name: fileName)
        let local = deps.mediaDirectory.appendingPathComponent("\(att.id).\(ext)")
        try? fm.removeItem(at: local)
        try fm.copyItem(at: file, to: local)
        try applyProtection(to: local, .complete)

        try await outbox.enqueue(envelope, chatID: chatID)   // stores the message row first
        try deps.repo.upsertAttachment(AttachmentRecord(
            id: att.id, messageID: envelope.id, kind: kindName, key: att.key, digest: att.dg, size: att.sz,
            cipherSize: att.csz, state: "local", downloaded: att.csz, localPath: local.path))
        onChange()
    }

    // MARK: - Download

    /// The phase the UI shows for a message's attachment.
    public func mediaState(messageID: String) -> MediaState? {
        guard let record = try? deps.repo.attachment(messageID: messageID) else { return nil }
        return Self.mediaState(record)
    }

    static func mediaState(_ r: AttachmentRecord) -> MediaState {
        let phase: MediaState.Phase
        switch r.state {
        case "local": phase = r.localPath.map { .local(URL(fileURLWithPath: $0)) } ?? .failed
        case "downloading": phase = .downloading(r.cipherSize > 0 ? min(Double(r.downloaded) / Double(r.cipherSize), 1) : 0)
        case "failed": phase = .failed
        default: phase = .remote
        }
        return MediaState(attachmentID: r.id, phase: phase)
    }

    /// Downloads and decrypts one attachment, resuming from `downloaded`. Concurrent calls for the
    /// same attachment share one download. On failure the state is `failed` and the error is thrown;
    /// calling again resumes, except after a 404 or a digest failure, which start over.
    public func download(attachmentID: String) async throws {
        if let running = inFlight[attachmentID] { return try await running.value }
        guard let record = try deps.repo.attachment(id: attachmentID) else { throw AttachmentServiceError.unknownAttachment }
        if record.state == "local" { return }
        let task = Task { try await self.run(record) }
        inFlight[attachmentID] = task
        defer { inFlight[attachmentID] = nil }
        try await task.value
    }

    /// Stops a running download; the bytes already fetched are kept and the state returns to `remote`.
    public func cancelDownload(attachmentID: String) {
        inFlight[attachmentID]?.cancel()
    }

    /// Downloads the message's attachment if the policy allows it on the current network. Only a
    /// `remote` attachment qualifies: a failed one waits for an explicit `download`.
    public func autoDownload(for message: MessageRecord) async {
        guard message.senderAccount != deps.me.accountID, !message.deleted else { return }
        let kind: MediaKind
        switch ContentEnvelope.decode(message.envelope).body {
        case .photo: kind = .photo
        case .voice: kind = .voice
        case .file: kind = .file
        default: return
        }
        guard AutoDownloadPolicy.shouldDownload(kind, network: network(), settings: settings()),
              let record = try? deps.repo.attachment(messageID: message.id), record.state == "remote" else { return }
        try? await download(attachmentID: record.id)
    }

    private func part(_ id: String) -> URL { deps.mediaDirectory.appendingPathComponent("\(id).part") }

    private func run(_ initial: AttachmentRecord) async throws {
        let fm = FileManager.default
        let id = initial.id
        let partURL = part(id)
        if !fm.fileExists(atPath: partURL.path) { fm.createFile(atPath: partURL.path, contents: nil) }
        var offset = (try? fm.attributesOfItem(atPath: partURL.path)[.size] as? Int64) ?? 0
        if offset > initial.cipherSize { try Data().write(to: partURL); offset = 0 }

        do {
            while offset < initial.cipherSize {
                try Task.checkCancellation()
                let end = min(offset + Int64(segmentSize), initial.cipherSize) - 1
                try setState(id, "downloading", offset, notify: true)
                let data = try await deps.server.downloadAttachment(id: id, range: Int(offset)...Int(end))
                let wanted = Int(end - offset) + 1
                if data.count > wanted {
                    // The server ignored the range and sent the whole file: start over with it.
                    guard Int64(data.count) == initial.cipherSize else { throw AttachmentServiceError.unreadableResponse }
                    try data.write(to: partURL)
                    offset = initial.cipherSize
                    break
                }
                let handle = try FileHandle(forWritingTo: partURL)
                defer { try? handle.close() }
                try handle.seek(toOffset: UInt64(offset))
                try handle.write(contentsOf: data)
                offset += Int64(data.count)
                if data.count < wanted {
                    try setState(id, "downloading", offset, notify: false)
                    throw AttachmentServiceError.incomplete
                }
            }
            try setState(id, "downloading", offset, notify: true)

            let ext = Self.fileExtension(kind: initial.kind, name: fileName(messageID: initial.messageID))
            let dest = deps.mediaDirectory.appendingPathComponent("\(id).\(ext)")
            try deps.attachments.decrypt(file: partURL, to: dest, key: initial.key, digest: initial.digest)
            try? applyProtection(to: dest, .complete)
            try? fm.removeItem(at: partURL)
            try deps.repo.setAttachmentState(id: id, state: "local", downloaded: initial.cipherSize, localPath: dest.path)
            onChange()
        } catch is CancellationError {
            try? deps.repo.setAttachmentState(id: id, state: "remote", downloaded: offset, localPath: nil)
            onChange()
            throw CancellationError()
        } catch let error as ServerError where error.code == "cancelled" {
            try? deps.repo.setAttachmentState(id: id, state: "remote", downloaded: offset, localPath: nil)
            onChange()
            throw CancellationError()
        } catch {
            // Gone from the server (expired) or corrupt: nothing to resume from.
            let restart = (error as? ServerError)?.status == 404 || !(error is ServerError || error is AttachmentServiceError)
            if restart { try? fm.removeItem(at: partURL) }
            try? deps.repo.setAttachmentState(id: id, state: "failed", downloaded: restart ? 0 : offset, localPath: nil)
            onChange()
            throw error
        }
    }

    private func setState(_ id: String, _ state: String, _ downloaded: Int64, notify: Bool) throws {
        try deps.repo.setAttachmentState(id: id, state: state, downloaded: downloaded, localPath: nil)
        let now = deps.now()
        if notify, now.timeIntervalSince(lastNotified) >= 0.25 {
            lastNotified = now
            onChange()
        }
    }

    private func fileName(messageID: String) -> String? {
        guard let record = try? deps.repo.message(id: messageID),
              case .file(_, let name, _, _) = ContentEnvelope.decode(record.envelope).body else { return nil }
        return name
    }

    static func fileExtension(kind: String, name: String?) -> String {
        if let name, !URL(fileURLWithPath: name).pathExtension.isEmpty {
            return URL(fileURLWithPath: name).pathExtension.lowercased()
        }
        switch kind {
        case "photo": return "jpg"
        case "voice": return "caf"
        default: return "bin"
        }
    }
}
