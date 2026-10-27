import Testing
import Foundation
@testable import CallCore

@Suite struct CallNotificationTextTests {
    @Test func incomingTexts() {
        let audio = CallNotificationText.content(callerName: "Riya", video: false, ringAge: 0)
        #expect(audio.title == "Riya")
        #expect(audio.body == "Incoming call")
        #expect(audio.isCall)
        let video = CallNotificationText.content(callerName: "Riya", video: true, ringAge: 10)
        #expect(video.body == "Incoming video call")
        #expect(video.isCall)
    }
    @Test func staleRingIsMissed() {
        let t = CallNotificationText.content(callerName: "Riya", video: true, ringAge: 45)
        #expect(t.body == "Missed call")
        #expect(!t.isCall)
        #expect(CallNotificationText.content(callerName: "Riya", video: false, ringAge: 44.9).isCall)
    }
}
