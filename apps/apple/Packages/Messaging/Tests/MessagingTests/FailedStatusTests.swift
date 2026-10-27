import AppCore
import Foundation
import Testing
@testable import Messaging

@Suite("TestFailedStatus") struct TestFailedStatus {
    @Test func failedRowsShowAsFailed() {
        #expect(ChatMapping.status("failed") == .failed)
        #expect(ChatMapping.status("sending") == .sending)
        #expect(ChatMapping.status("sent") == .sent)
        #expect(ChatMapping.status("delivered") == .delivered)
        #expect(ChatMapping.status("read") == .read)
    }
}
