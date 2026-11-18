import Foundation
import Testing
@testable import AppCore

@Suite struct MediaFileNameTests {
    @Test func plainNameIsKept() {
        #expect(MediaFileName.clean("Report 2026.pdf") == "Report 2026.pdf")
    }

    @Test func pathPartsAreDropped() {
        #expect(MediaFileName.clean("../../etc/passwd") == "passwd")
        #expect(MediaFileName.clean("a/b\\c.txt") == "c.txt")
    }

    @Test func directionOverridesAndControlsAreRemoved() {
        #expect(MediaFileName.clean("photo\u{202E}gpj.exe") == "photogpj.exe")
        #expect(MediaFileName.clean("a\u{0}b\nc\u{7F}.txt") == "abc.txt")
        #expect(MediaFileName.clean("x\u{2066}y\u{2069}\u{200F}.txt") == "xy.txt")
    }

    @Test func emptyOrDotNamesFallBack() {
        #expect(MediaFileName.clean("") == MediaFileName.fallback)
        #expect(MediaFileName.clean("..") == MediaFileName.fallback)
        #expect(MediaFileName.clean("   ") == MediaFileName.fallback)
        #expect(MediaFileName.clean("/") == MediaFileName.fallback)
    }

    @Test func lengthIsCappedKeepingTheExtension() {
        let long = String(repeating: "a", count: 500) + ".pdf"
        let cleaned = MediaFileName.clean(long)
        #expect(cleaned.count <= MediaFileName.maxLength)
        #expect(cleaned.hasSuffix(".pdf"))
    }

    @Test func previewOfAFileMessageUsesTheCleanedName() {
        let message = Message(id: "m", senderID: "s", sentAt: Date(), content: .file(name: "x/evil\u{202E}.txt", bytes: 1),
                              status: .sent, replyToID: nil, reactions: [])
        #expect(message.previewText == "evil.txt")
    }
}

@Suite struct MediaPathGuardTests {
    private let dir = URL(fileURLWithPath: "/data/media", isDirectory: true)

    @Test func fileInsideIsAllowed() {
        #expect(MediaPathGuard.isInside(URL(fileURLWithPath: "/data/media/a.jpg"), directory: dir))
    }

    @Test func fileOutsideIsRefused() {
        #expect(!MediaPathGuard.isInside(URL(fileURLWithPath: "/data/other/a.jpg"), directory: dir))
        #expect(!MediaPathGuard.isInside(URL(fileURLWithPath: "/data/media-evil/a.jpg"), directory: dir))
    }

    @Test func dotDotEscapesAreRefused() {
        #expect(!MediaPathGuard.isInside(URL(fileURLWithPath: "/data/media/../secret"), directory: dir))
    }

    @Test func theDirectoryItselfIsRefused() {
        #expect(!MediaPathGuard.isInside(dir, directory: dir))
    }

    @Test func safeComponentRejectsSeparatorsAndDots() {
        #expect(MediaPathGuard.isSafeComponent("0f3a-bc"))
        #expect(!MediaPathGuard.isSafeComponent("../x"))
        #expect(!MediaPathGuard.isSafeComponent("a/b"))
        #expect(!MediaPathGuard.isSafeComponent(".."))
        #expect(!MediaPathGuard.isSafeComponent(""))
        #expect(!MediaPathGuard.isSafeComponent("a\u{0}b"))
    }
}

@Suite struct BoundedCacheTests {
    @Test func evictsLeastRecentlyUsed() {
        var cache = BoundedCache<String, Int>(capacity: 2)
        cache.set("a", 1)
        cache.set("b", 2)
        _ = cache.get("a")
        cache.set("c", 3)
        #expect(cache.get("b") == nil)
        #expect(cache.get("a") == 1)
        #expect(cache.get("c") == 3)
        #expect(cache.count == 2)
    }

    @Test func overwriteKeepsSize() {
        var cache = BoundedCache<String, Int>(capacity: 2)
        cache.set("a", 1)
        cache.set("a", 5)
        #expect(cache.count == 1)
        #expect(cache.get("a") == 5)
    }

    @Test func removeAllEmpties() {
        var cache = BoundedCache<String, Int>(capacity: 2)
        cache.set("a", 1)
        cache.removeAll()
        #expect(cache.count == 0)
    }
}

@Suite struct VoiceSelectionTests {
    @Test func startingOneStopsTheOther() {
        var s = VoiceSelection()
        #expect(s.toggle("a") == .play)
        #expect(s.playingID == "a")
        #expect(s.toggle("b") == .play)
        #expect(s.playingID == "b")
    }

    @Test func tappingThePlayingOnePausesAndAgainResumes() {
        var s = VoiceSelection()
        _ = s.toggle("a")
        #expect(!s.isPaused)
        #expect(s.toggle("a") == .pause)
        #expect(s.isPaused)
        #expect(s.playingID == "a")
        #expect(s.toggle("a") == .resume)
        #expect(!s.isPaused)
    }

    @Test func switchingAfterPauseStartsFresh() {
        var s = VoiceSelection()
        _ = s.toggle("a")
        _ = s.toggle("a")
        #expect(s.toggle("b") == .play)
        #expect(!s.isPaused)
        #expect(s.playingID == "b")
    }

    @Test func stopClearsEverything() {
        var s = VoiceSelection()
        _ = s.toggle("a")
        _ = s.toggle("a")
        s.stop()
        #expect(s.playingID == nil)
        #expect(!s.isPaused)
    }

    @Test func finishedClearsOnlyTheMatchingOne() {
        var s = VoiceSelection()
        _ = s.toggle("a")
        s.finished("b")
        #expect(s.playingID == "a")
        s.finished("a")
        #expect(s.playingID == nil)
    }

    @Test func elapsedTextAndClamp() {
        #expect(VoiceSelection.elapsedText(progress: 0.5, duration: 20) == "0:10")
        #expect(VoiceSelection.elapsedText(progress: 2, duration: 20) == "0:20")
        #expect(VoiceSelection.elapsedText(progress: 0.5, duration: 0) == "0:00")
        #expect(VoiceSelection.clamp(-1) == 0)
        #expect(VoiceSelection.clamp(.nan) == 0)
    }
}

@Suite struct MediaStagingTests {
    private func makeDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("staging-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    @Test func removesOnlyOldEntries() throws {
        let dir = try makeDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let old = dir.appendingPathComponent("old.jpg"), fresh = dir.appendingPathComponent("fresh.jpg")
        try Data("x".utf8).write(to: old)
        try Data("y".utf8).write(to: fresh)
        let now = Date()
        try FileManager.default.setAttributes([.modificationDate: now.addingTimeInterval(-7200)], ofItemAtPath: old.path)
        MediaStaging.sweep(directory: dir, olderThan: 3600, now: now)
        #expect(!FileManager.default.fileExists(atPath: old.path))
        #expect(FileManager.default.fileExists(atPath: fresh.path))
    }

    @Test func zeroAgeRemovesEverythingAndMissingDirectoryIsFine() throws {
        let dir = try makeDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        try Data("x".utf8).write(to: dir.appendingPathComponent("a"))
        MediaStaging.sweep(directory: dir, olderThan: -1, now: Date())
        #expect((try FileManager.default.contentsOfDirectory(atPath: dir.path)).isEmpty)
        MediaStaging.sweep(directory: dir.appendingPathComponent("missing"), olderThan: 0, now: Date())
    }
}

@Suite @MainActor struct MessageHitDateTests {
    @Test func hitsCarryTheMessageDate() {
        let date = Date(timeIntervalSince1970: 1000)
        let hit = MessageHit(chatID: "c", messageID: "m", preview: "p", sentAt: date)
        #expect(hit.sentAt == date)
    }

    @Test func sampleSearchHitsHaveTheirMessageDate() {
        let store = SampleChatStore()
        let hits = store.searchMessages("e", limit: 50)
        #expect(!hits.isEmpty)
        #expect(hits.allSatisfy { $0.sentAt != .distantPast })
    }
}
