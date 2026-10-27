import CallLabKit
import Foundation
#if os(macOS)
import AppKit
#endif

enum RunExport {
    /// `runs/<run_id>/` inside Documents (the sandbox container on the Mac).
    static func makeRunDir(runID: String) throws -> URL {
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
        var copyError: Error?
        NSFileCoordinator().coordinate(readingItemAt: runDir, options: .forUploading, error: &coordError) { tmpZip in
            do { try FileManager.default.copyItem(at: tmpZip, to: dest) } catch { copyError = error }
        }
        if let e = coordError ?? copyError { throw e }
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
