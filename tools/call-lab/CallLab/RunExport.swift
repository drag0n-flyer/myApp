import CallLabKit
import Foundation
import UniformTypeIdentifiers
#if os(macOS)
import AppKit
#endif

private final class ErrorBox: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: Error?
    var error: Error? { lock.withLock { stored } }
    func set(_ e: Error) { lock.withLock { stored = e } }
}

/// The headless `--out` folder, behind a lock (set once at launch, read when a run starts).
private final class OutputDirBox: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: URL?
    var value: URL? {
        get { lock.withLock { stored } }
        set { lock.withLock { stored = newValue } }
    }
}

enum RunExport {
    private static let output = OutputDirBox()

    /// Headless runs (`--out <dir>`, L10a) write straight into this folder instead of Documents/runs/<run_id>/.
    static var outputDir: URL? {
        get { output.value }
        set { output.value = newValue }
    }

    /// `runs/<run_id>/` inside Documents (the sandbox container on the Mac), or `outputDir` when set.
    static func makeRunDir(runID: String) throws -> URL {
        if let out = outputDir {
            try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
            return out
        }
        let docs = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let dir = docs.appendingPathComponent("runs", isDirectory: true).appendingPathComponent(runID, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Zips the run folder to a temporary `<run_id>.zip` using the system's "for uploading" coordination.
    static func zip(runDir: URL) throws -> URL {
        let runID = runDir.lastPathComponent
        let dest = FileManager.default.temporaryDirectory.appendingPathComponent(RunNaming.zipName(runID: runID))
        try? FileManager.default.removeItem(at: dest)
        var coordError: NSError?
        let copyError = ErrorBox()   // the accessor block may be @Sendable: no captured var mutation
        NSFileCoordinator().coordinate(readingItemAt: runDir, options: .forUploading, error: &coordError) { tmpZip in
            do { try FileManager.default.copyItem(at: tmpZip, to: dest) } catch { copyError.set(error) }
        }
        if let e = coordError ?? copyError.error { throw e }
        return dest
    }

    #if os(macOS)
    @MainActor static func saveWithPanel(zip: URL) {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = zip.lastPathComponent
        panel.allowedContentTypes = [.zip]
        if panel.runModal() == .OK, let url = panel.url {
            try? FileManager.default.removeItem(at: url)
            try? FileManager.default.copyItem(at: zip, to: url)
        }
    }
    #endif
}
