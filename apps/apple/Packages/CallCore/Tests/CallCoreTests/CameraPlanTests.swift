import Testing
import AppCore
@testable import CallCore

private let bob = Contact(id: "bob", displayName: "Bob", username: "bob")

@Suite struct CameraPlanTests {
    @Test func answeringNeverWaitsForThePrompt() {
        #expect(CameraPlan.plan(for: .answer(video: true), status: .notDetermined) == .answerAsAudioThenAsk)
        #expect(CameraPlan.plan(for: .answer(video: true), status: .authorized) == .handleNow)
        #expect(CameraPlan.plan(for: .answer(video: true), status: .refused) == .answerAsAudio)
        #expect(CameraPlan.plan(for: .answer(video: false), status: .notDetermined) == .handleNow)
    }

    @Test func onlyStartAndVideoOnMayWait() {
        let start = CallInput.start(contact: bob, video: true, callID: "c1")
        #expect(CameraPlan.plan(for: start, status: .notDetermined) == .askThenHandle)
        #expect(CameraPlan.plan(for: .setVideo(true), status: .notDetermined) == .askThenHandle)
        #expect(CameraPlan.plan(for: start, status: .authorized) == .handleNow)
        #expect(CameraPlan.plan(for: start, status: .refused) == .handleNow)
        #expect(CameraPlan.plan(for: .setVideo(true), status: .refused) == .handleNow)
    }

    /// Once the status is decided every input is synchronous, so a later hang-up or video-off cannot overtake one.
    @Test func everythingElseIsImmediate() {
        for status in [CameraStatus.notDetermined, .authorized, .refused] {
            #expect(CameraPlan.plan(for: .setVideo(false), status: status) == .handleNow)
            #expect(CameraPlan.plan(for: .hangup, status: status) == .handleNow)
            #expect(CameraPlan.plan(for: .decline, status: status) == .handleNow)
        }
        let audio = CallInput.start(contact: bob, video: false, callID: "c1")
        #expect(CameraPlan.plan(for: audio, status: .notDetermined) == .handleNow)
    }
}
