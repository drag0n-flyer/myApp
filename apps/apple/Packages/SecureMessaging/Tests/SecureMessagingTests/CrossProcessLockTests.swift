import Dispatch
import Foundation
import Testing
import SecureMessaging

private func tempLockURL() -> URL {
    FileManager.default.temporaryDirectory
        .appendingPathComponent("mls-lock-\(UUID().uuidString)")
        .appendingPathExtension("lock")
}

private struct Boom: Error {}

@Suite struct CrossProcessLockTests {
    @Test func testSecondHolderWaitsThenFails() throws {
        let url = tempLockURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let holder = CrossProcessLock(url: url)
        let acquired = DispatchSemaphore(value: 0)
        let released = DispatchSemaphore(value: 0)
        DispatchQueue.global().async {
            try? holder.withLock {
                acquired.signal()
                Thread.sleep(forTimeInterval: 0.3)
            }
            released.signal()
        }
        acquired.wait()

        let impatient = CrossProcessLock(url: url, timeout: .milliseconds(100))
        #expect(throws: MLSError.busy) { try impatient.withLock { 1 } }

        let patient = CrossProcessLock(url: url, timeout: .seconds(1))
        #expect(try patient.withLock { 42 } == 42)
        released.wait()
    }

    @Test func testLockReleasedAfterThrow() throws {
        let url = tempLockURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let lock = CrossProcessLock(url: url, timeout: .milliseconds(50))
        #expect(throws: Boom.self) { try lock.withLock { throw Boom() } }
        #expect(try lock.withLock { "ok" } == "ok")
    }

    @Test func testLockFileCreated() throws {
        let url = tempLockURL()
        defer { try? FileManager.default.removeItem(at: url) }
        #expect(!FileManager.default.fileExists(atPath: url.path))
        try CrossProcessLock(url: url).withLock {}
        #expect(FileManager.default.fileExists(atPath: url.path))
    }

    @Test func testUnopenableLockFileIsStorageError() {
        let url = URL(fileURLWithPath: "/nonexistent-dir-\(UUID().uuidString)/mls.lock")
        let lock = CrossProcessLock(url: url)
        do {
            try lock.withLock {}
            Issue.record("expected a throw")
        } catch let error as MLSError {
            guard case .storage = error else { Issue.record("expected .storage, got \(error)"); return }
        } catch {
            Issue.record("unexpected error \(error)")
        }
    }
}
