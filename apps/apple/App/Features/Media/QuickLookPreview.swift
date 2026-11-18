import AppCore
import Foundation
import Observation
import QuickLook
import SwiftUI

/// Opens a downloaded photo or file in Quick Look (which has the system share action on iPhone and Mac).
///
/// Quick Look shows and shares the file's own name, and the stored file is named after its attachment ID, so the
/// file is first copied to a temporary folder under its cleaned name (received names are untrusted). The copy is
/// deleted when the preview closes, when the chat goes away, and at the next launch of a chat (older leftovers).
@MainActor @Observable
final class MediaPreview {
    /// The file Quick Look shows; setting it to nil (the sheet closing) deletes the copy.
    var url: URL? {
        didSet { if url == nil, let old = oldValue { Self.remove(old) } }
    }
    /// Set when nothing could be opened; the chat shows the "Couldn't download" alert.
    var failed = false

    private var openingToken = UUID()

    /// `source` must be inside `mediaDirectory` (when known) and non-empty. `name` is the received name or nil for photos.
    func open(source: URL, mediaDirectory: URL?, name: String?, isPhoto: Bool) {
        close()
        let token = UUID()
        openingToken = token
        if let mediaDirectory, !MediaPathGuard.isInside(source, directory: mediaDirectory) { failed = true; return }
        let cleaned = name.map(MediaFileName.clean)
        let shownName = Self.shownName(cleaned: cleaned, source: source, isPhoto: isPhoto)
        Task {
            let copy = await Task.detached(priority: .userInitiated) { Self.makeCopy(of: source, named: shownName) }.value
            // Closed, or another item opened, while copying: drop the copy.
            guard token == openingToken else { if let copy { Self.remove(copy) }; return }
            if let copy { url = copy } else { failed = true }
        }
    }

    func close() {
        openingToken = UUID()
        url = nil
    }

    // MARK: - Files

    nonisolated static var folder: URL { FileManager.default.temporaryDirectory.appendingPathComponent("media-preview", isDirectory: true) }

    private static var swept = false

    /// Removes leftovers from earlier runs (previews and picked files waiting to be sent); once per launch,
    /// before any chat of this run has made its own.
    static func sweepStale() {
        // Picked files may still be on their way to the engine: only the old ones go, except at launch.
        MediaStaging.sweep(directory: stagingFolder, olderThan: swept ? 3600 : 0, now: Date())
        guard !swept else { return }
        swept = true
        MediaStaging.sweep(directory: folder, olderThan: 0, now: Date())
    }

    /// Where photos and files picked in the composer are copied before the engine takes them.
    nonisolated static var stagingFolder: URL { FileManager.default.temporaryDirectory.appendingPathComponent("outgoing-media", isDirectory: true) }

    static func shownName(cleaned: String?, source: URL, isPhoto: Bool) -> String {
        let ext = source.pathExtension.lowercased()
        let safeExt = MediaPathGuard.isSafeComponent(ext) && ext.count <= 12 ? ext : ""
        if let cleaned {
            // A file keeps the sender's name (cleaned); give it the stored file's extension when it has none.
            if URL(fileURLWithPath: cleaned).pathExtension.isEmpty, !safeExt.isEmpty { return cleaned + "." + safeExt }
            return cleaned
        }
        let base = isPhoto ? "Photo" : MediaFileName.fallback
        return safeExt.isEmpty ? base : base + "." + safeExt
    }

    nonisolated private static func makeCopy(of source: URL, named name: String) -> URL? {
        let fm = FileManager.default
        let size = (try? source.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard size > 0 else { return nil }
        let dir = folder.appendingPathComponent(UUID().uuidString.lowercased(), isDirectory: true)
        let dest = dir.appendingPathComponent(name)
        do {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
            try fm.copyItem(at: source, to: dest)
            return dest
        } catch {
            try? fm.removeItem(at: dir)
            return nil
        }
    }

    nonisolated private static func remove(_ copy: URL) {
        // The copy lives in its own folder: <folder>/<uuid>/<name>.
        let dir = copy.deletingLastPathComponent()
        guard dir.deletingLastPathComponent().standardizedFileURL == folder.standardizedFileURL else { return }
        try? FileManager.default.removeItem(at: dir)
    }
}

extension View {
    /// Presents Quick Look for `preview.url` and the "Couldn't download" alert when nothing could be opened.
    func mediaPreview(_ preview: MediaPreview) -> some View {
        @Bindable var preview = preview
        return self
            .quickLookPreview($preview.url)
            .alert("Couldn't download", isPresented: $preview.failed) {
                Button("OK", role: .cancel) {}
            }
    }
}
