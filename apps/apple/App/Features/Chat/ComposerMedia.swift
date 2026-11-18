import AppCore
import Foundation
import Messaging
import SwiftUI
import UniformTypeIdentifiers

/// What the composer hands to the app when the person attaches or records something.
enum ComposerAttachment {
    case photo(URL)
    case file(URL)
    case voice(URL, duration: Double)
}

/// The app's microphone for `VoiceSession`: `VoiceRecorder` (Opus, 16 kHz mono, Ogg file) behind `VoiceCapture`.
@MainActor
final class VoiceRecordingController: VoiceCapture {
    private let recorder = VoiceRecorder()

    var permission: VoicePermission {
        switch VoiceRecorder.permissionStatus {
        case .granted: .granted
        case .denied: .denied
        case .undetermined: .undetermined
        }
    }

    func requestPermission() async -> Bool { await VoiceRecorder.requestPermission() }

    func start() throws { try recorder.start() }

    var level: Float { VoiceLevel.normalized(decibels: recorder.averagePowerDecibels) }

    var elapsed: Double { recorder.elapsed }

    func stop() throws -> (url: URL, duration: Double) {
        let result = try recorder.stop()
        return (result.0, result.1)
    }

    func cancel() { recorder.cancel() }
}

/// A video chosen in the photo picker, copied into the staging folder.
struct PickedMovie: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let folder = MediaPreview.stagingFolder
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let ext = received.file.pathExtension.lowercased()
            let safe = !ext.isEmpty && ext.count <= 5 && ext.allSatisfy { $0.isLetter || $0.isNumber } ? ext : "mov"
            let destination = folder.appendingPathComponent("video-\(UUID().uuidString.lowercased()).\(safe)")
            try FileManager.default.copyItem(at: received.file, to: destination)
            return PickedMovie(url: destination)
        }
    }
}
