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
    /// An attachment ID that is not safe to use in a file path.
    case invalidAttachmentID
    /// A queued upload whose folder or source file is gone.
    case pendingMissing
    /// A photo that cannot be read as an image.
    case cannotPrepare
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
        // The ID becomes part of a file path below: check it before anything is built from it.
        guard AttachmentID.isValid(uploaded.id) else { throw AttachmentServiceError.invalidAttachmentID }

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

    /// The attachment row a picked file has while it waits for its upload, so the sender sees it at once.
    public static func placeholderAttachmentID(_ messageID: String) -> String { "pending-\(messageID)" }

    /// Uploads the attachment waiting in `pendingDir` for the (already stored, still unsent) message `messageID`,
    /// then, in one transaction, puts the real envelope into the row, queues it and records the attachment. Only
    /// after that commit does the plaintext move to its final place. If the message was deleted meanwhile,
    /// nothing is queued and nothing is left behind.
    /// Throws `pendingMissing` and `cannotPrepare` for failures that can never succeed; anything else may be retried.
    /// `progress` is told how far the preparing and uploading have come (0...1); the upload itself is one request.
    public func uploadPending(messageID: String, pendingDir: URL, progress: (@Sendable (Double) -> Void)? = nil) async throws {
        let fm = FileManager.default
        guard let meta = PendingUpload.read(from: pendingDir) else { throw AttachmentServiceError.pendingMissing }
        let source = pendingDir.appendingPathComponent(meta.sourceName)
        guard fm.fileExists(atPath: source.path) else { throw AttachmentServiceError.pendingMissing }
        guard let record = try deps.repo.message(id: messageID), !record.deleted else {
            discard(messageID: messageID, pendingDir: pendingDir)
            return
        }

        var file = source
        var width = 0, height = 0
        var thumbnail: Data?
        var prepared = false
        #if canImport(ImageIO)
        if meta.kind == "photo" {
            let scratch = pendingDir.appendingPathComponent("prepared.jpg")
            do {
                let result = try MediaPreparation.preparePhoto(source, to: scratch)
                file = scratch
                (width, height, thumbnail) = (result.w, result.h, result.thumb)
                prepared = true
            } catch { throw AttachmentServiceError.cannotPrepare }
        }
        #endif
        try Task.checkCancellation()
        progress?(0.1)

        let temp = pendingDir.appendingPathComponent("upload.enc")
        defer { try? fm.removeItem(at: temp) }
        let sealed = try deps.attachments.encrypt(file: file, to: temp)
        progress?(0.3)
        let uploaded = try await deps.server.uploadAttachment(try Data(contentsOf: temp))
        guard AttachmentID.isValid(uploaded.id) else { throw AttachmentServiceError.invalidAttachmentID }
        try Task.checkCancellation()
        progress?(0.95)

        let att = Att(id: uploaded.id, key: sealed.key, dg: sealed.digest, sz: sealed.size, csz: sealed.cipherSize)
        let body: ContentEnvelope.Body
        switch meta.kind {
        case "photo": body = .photo(att, w: width, h: height, thumb: thumbnail, replyTo: nil)
        case "voice": body = .voice(att, duration: meta.duration, replyTo: nil)
        default: body = .file(att, name: meta.name, mime: meta.mime, replyTo: nil)
        }
        let envelope = ContentEnvelope(
            id: messageID, at: Int64((record.sentAt.timeIntervalSince1970 * 1000).rounded()), body: body)
        let ext = Self.fileExtension(kind: meta.kind, name: prepared ? nil : meta.name)
        let local = deps.mediaDirectory.appendingPathComponent("\(att.id).\(ext)")
        let data = envelope.encoded()
        let repo = deps.repo
        let me = deps.me.accountID
        let now = deps.now()
        let committed = try repo.transaction { () -> Bool in
            guard try repo.replaceUnsentEnvelope(
                id: messageID, sender: me, envelope: data, folded: meta.kind == "file" ? fold(meta.name) : "") else { return false }
            try repo.enqueue(OutboxRecord(messageID: messageID, chatID: meta.chatID, envelope: data, attempts: 0, nextAttemptAt: now))
            try repo.deleteAttachment(id: Self.placeholderAttachmentID(messageID))
            try repo.upsertAttachment(AttachmentRecord(
                id: att.id, messageID: messageID, kind: meta.kind, key: att.key, digest: att.dg, size: att.sz,
                cipherSize: att.csz, state: "local", downloaded: att.csz, localPath: local.path))
            return true
        }
        guard committed else {
            // Deleted while it was uploading: nothing is queued, no record, no file.
            discard(messageID: messageID, pendingDir: pendingDir)
            return
        }
        // Only now does the plaintext go to its final place. If that fails the download path can fetch the upload.
        do {
            try? fm.removeItem(at: local)
            try fm.moveItem(at: file, to: local)
            try applyProtection(to: local, .complete)
        } catch {
            try? fm.removeItem(at: local)
            try? repo.setAttachmentState(id: att.id, state: "failed", downloaded: 0, localPath: nil)
        }
        PendingUpload.removeFolder(pendingDir)
        onChange()
    }

    /// Forgets a queued upload: its placeholder attachment row and its folder.
    public func discard(messageID: String, pendingDir: URL) {
        try? deps.repo.deleteAttachment(id: Self.placeholderAttachmentID(messageID))
        PendingUpload.removeFolder(pendingDir)
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
        guard AttachmentID.isValid(attachmentID) else { throw AttachmentServiceError.invalidAttachmentID }
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
        guard AttachmentID.isValid(id) else { throw AttachmentServiceError.invalidAttachmentID }
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
            // No unprotected plaintext: if the protection cannot be set, the file goes and the download fails.
            do { try applyProtection(to: dest, .complete) } catch { try? fm.removeItem(at: dest); throw error }
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

    /// The extension of a stored file. A name's extension must be 1 to 12 lower-case letters or digits (it came from
    /// the sender), otherwise the file gets `bin`.
    static func fileExtension(kind: String, name: String?) -> String {
        if let name, let dot = name.lastIndex(of: "."), dot != name.startIndex, name.index(after: dot) != name.endIndex {
            return AttachmentID.safeExtension(of: name) ?? "bin"
        }
        switch kind {
        case "photo": return "jpg"
        case "voice": return MediaPlan.Voice.fileExtension
        default: return "bin"
        }
    }
}

/// What a picked attachment needs to be uploaded later: kept next to the staged source file in
/// `<media>/pending/<message id>/`. The folder is removed with the rest of `media/` when signing out.
public struct PendingUpload: Codable, Sendable, Equatable {
    public var kind: String          // "photo", "voice" or "file"
    public var chatID: String
    /// The cleaned file name.
    public var name: String
    /// The staged source file inside the folder.
    public var sourceName: String
    public var mime: String
    public var duration: Double

    public static let metaFileName = "meta.json"

    public init(kind: String, chatID: String, name: String, sourceName: String, mime: String, duration: Double) {
        self.kind = kind; self.chatID = chatID; self.name = name; self.sourceName = sourceName
        self.mime = mime; self.duration = duration
    }

    public static func root(in mediaDirectory: URL) -> URL { mediaDirectory.appendingPathComponent("pending", isDirectory: true) }

    /// The folder of one message; `messageID` is one of our own IDs, still checked.
    public static func folder(in mediaDirectory: URL, messageID: String) -> URL? {
        AttachmentID.isValid(messageID) ? root(in: mediaDirectory).appendingPathComponent(messageID, isDirectory: true) : nil
    }

    public static func read(from folder: URL) -> PendingUpload? {
        guard let data = try? Data(contentsOf: folder.appendingPathComponent(metaFileName)) else { return nil }
        return try? JSONDecoder().decode(PendingUpload.self, from: data)
    }

    public func write(to folder: URL) throws {
        try JSONEncoder().encode(self).write(to: folder.appendingPathComponent(Self.metaFileName), options: .atomic)
    }

    /// Removes the folder, and the `pending` folder above it when that is empty.
    public static func removeFolder(_ folder: URL) {
        let fm = FileManager.default
        try? fm.removeItem(at: folder)
        let parent = folder.deletingLastPathComponent()
        if parent.lastPathComponent == "pending", ((try? fm.contentsOfDirectory(atPath: parent.path)) ?? []).isEmpty {
            try? fm.removeItem(at: parent)
        }
    }
}
