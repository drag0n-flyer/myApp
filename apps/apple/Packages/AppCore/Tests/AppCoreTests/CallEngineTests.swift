import Testing
@testable import AppCore

func engine(_ net: NetworkType = .mobile) -> SimulatedCallEngine {
    .init(server: .init(defaultPreference: .auto, maximum: .high, defaultIPHiding: .nonContacts, requiresInvite: true, host: "h"),
          settings: .init(), network: net)
}

let riya = Contact(id: "riya", displayName: "Riya", username: "riya")

@Test func dataCounterMatchesMockup() {
    let e = engine()
    e.start(with: riya, video: false)
    e.advance(by: 1)
    e.advance(by: 239)
    #expect(e.state?.effectiveMode == .dataSaver)
    #expect(e.state?.dataUsedText == "≈ 1.2 MB used")
    #expect(e.state?.elapsedText == "04:00")
}

@Test func weakThenWorse() {
    let e = engine(.wifi)
    e.start(with: riya, video: true)
    e.advance(by: 1)
    e.simulate(.weak)
    #expect(e.state?.banner == .videoLowered && e.state?.quality == .weak)
    e.simulate(.veryWeak)
    #expect(e.state?.banner == .audioOnly && e.state?.isVideoOn == false && e.state?.effectiveMode == .ultraLow)
    e.simulate(.lost)
    #expect(e.state?.phase == .reconnecting && e.state?.banner?.text == "Reconnecting…")
    e.advance(by: 29)
    #expect(e.state?.phase == .reconnecting)
    e.advance(by: 1)
    #expect(e.state?.phase == .ended(.connectionLost) && e.state?.banner?.text == "Call ended: connection lost")
}

@Test func bannerTexts() {
    #expect(CallBanner.videoLowered.text == "Weak network: video lowered to save your voice quality")
    #expect(CallBanner.audioOnly.text == "Switched to audio only")
}

@Test func recoversWithin30s() {
    let e = engine()
    e.start(with: riya, video: false)
    e.advance(by: 1)
    e.simulate(.lost)
    e.advance(by: 10)
    e.simulate(.good)
    #expect(e.state?.phase == .active && e.state?.banner == nil)
}

@Test func noBytesWhileReconnecting() {
    let e = engine()
    e.start(with: riya, video: false)
    e.advance(by: 1)
    let before = e.state!.bytesUsed
    e.simulate(.lost)
    e.advance(by: 10)
    #expect(e.state!.bytesUsed == before)
}

@Test func videoStartsOffOnMobile() {
    let e = engine(.mobile)
    e.start(with: riya, video: true)
    #expect(e.state?.isVideoOn == false)
}

@Test func inCallChoiceSurvivesNetworkSwitch() {
    let e = engine(.wifi)
    e.start(with: riya, video: false)
    e.advance(by: 1)
    e.setPreference(.fixed(.balanced))
    e.setNetwork(.mobile)
    #expect(e.state?.effectiveMode == .balanced)
}

@Test func endCall() {
    let e = engine()
    e.start(with: riya, video: false)
    e.advance(by: 1)
    e.end()
    #expect(e.state?.phase == .ended(.hungUp))
}

@Test func longCallClock() {
    let e = engine()
    e.start(with: riya, video: false)
    e.advance(by: 1)
    e.advance(by: 3_724)
    #expect(e.state?.elapsedText == "1:02:05")
}
