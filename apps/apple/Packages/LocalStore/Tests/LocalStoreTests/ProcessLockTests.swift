import Foundation
import Testing
@testable import LocalStore

@Test func testProcessLockExclusive() throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("lock-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: url) }
    let a = ProcessLock(url: url)
    let b = ProcessLock(url: url)
    try a.withLock(timeout: .seconds(1)) {
        #expect(throws: LockTimeout.self) {
            try b.withLock(timeout: .milliseconds(100)) {}
        }
    }
    let r = try b.withLock(timeout: .milliseconds(100)) { 42 }
    #expect(r == 42)
}

@Test func testProcessLockOpenFailureIsNotTimeout() throws {
    let lock = ProcessLock(url: URL(fileURLWithPath: "/nonexistent-dir/x.lock"))
    #expect(throws: LockError.self) { try lock.withLock(timeout: .milliseconds(50)) {} }
}

@Test func testAsyncLockHeldAcrossAwait() async throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("lock-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: url) }
    let a = ProcessLock(url: url)
    let b = ProcessLock(url: url)
    try await a.withAsyncLock(timeout: .seconds(1)) {
        try await Task.sleep(for: .milliseconds(10))
        #expect(throws: LockTimeout.self) { try b.withLock(timeout: .milliseconds(50)) {} }
        await #expect(throws: LockTimeout.self) { try await b.withAsyncLock(timeout: .milliseconds(50)) {} }
    }
    #expect(try b.withLock(timeout: .milliseconds(100)) { 42 } == 42)
}
