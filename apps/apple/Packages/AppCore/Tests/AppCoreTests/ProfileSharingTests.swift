import Testing
import Foundation
@testable import AppCore

@Suite("Profile photo sizing")
struct ProfilePhotoSizingTests {
    @Test func centreCropIsTheLargestCentredSquare() {
        #expect(ProfilePhotoSizing.centreSquare(width: 400, height: 300) == .init(x: 50, y: 0, side: 300))
        #expect(ProfilePhotoSizing.centreSquare(width: 300, height: 401) == .init(x: 0, y: 50, side: 300))
        #expect(ProfilePhotoSizing.centreSquare(width: 256, height: 256) == .init(x: 0, y: 0, side: 256))
        #expect(ProfilePhotoSizing.centreSquare(width: 0, height: 10) == nil)
        #expect(ProfilePhotoSizing.centreSquare(width: 10, height: -1) == nil)
    }

    @Test func fitsAcceptsAtTheLimitOnly() {
        let first = ProfilePhotoSizing.first
        #expect(ProfilePhotoSizing.decision(after: first, bytes: 24_576) == .accept)
        #expect(ProfilePhotoSizing.decision(after: first, bytes: 1) == .accept)
        if case .retry = ProfilePhotoSizing.decision(after: first, bytes: 24_577) {} else { Issue.record("expected a retry") }
        #expect(ProfilePhotoSizing.decision(after: first, bytes: 0) == .giveUp)
    }

    @Test func startsAt256SquareAndEveryRetryIsSmallerOrLowerQuality() {
        #expect(ProfilePhotoSizing.first.side == 256)
        var attempt = ProfilePhotoSizing.first
        var seen = [attempt]
        for _ in 0..<100 {
            guard case .retry(let next) = ProfilePhotoSizing.decision(after: attempt, bytes: 1_000_000) else { break }
            #expect(next.side < attempt.side || (next.side == attempt.side && next.quality < attempt.quality))
            #expect(next.side <= 256 && next.side >= 64 && next.quality >= 0.2 && next.quality <= 0.9)
            seen.append(next)
            attempt = next
        }
        // Always ends: a photo that never fits is given up on.
        #expect(ProfilePhotoSizing.decision(after: attempt, bytes: 1_000_000) == .giveUp)
        #expect(seen.count > 3 && seen.count < 40)
        #expect(Set(seen).count == seen.count)
    }

    @Test func qualityDropsBeforeSize() {
        guard case .retry(let next) = ProfilePhotoSizing.decision(after: ProfilePhotoSizing.first, bytes: 30_000) else {
            Issue.record("expected a retry"); return
        }
        #expect(next.side == 256)
        #expect(next.quality < ProfilePhotoSizing.first.quality)
    }
}

@Suite("Profile sender")
struct ProfileSenderTests {
    @Test func threeChatsThreeSends() {
        let chats = [chat("a"), chat("b"), chat("c")]
        #expect(ProfileSender.pending(chats: chats, ownVer: 1, sent: [:]) == ["a", "b", "c"])
    }

    @Test func nothingChangedNothingSent() {
        let chats = [chat("a"), chat("b")]
        #expect(ProfileSender.pending(chats: chats, ownVer: 3, sent: ["a": 3, "b": 3]).isEmpty)
        #expect(ProfileSender.pending(chats: chats, ownVer: 3, sent: ["a": 4, "b": 9]).isEmpty)
    }

    @Test func aNewVersionGoesToEveryoneAndANewChatGetsIt() {
        let chats = [chat("a"), chat("b"), chat("new")]
        #expect(ProfileSender.pending(chats: chats, ownVer: 4, sent: ["a": 3, "b": 4]) == ["a", "new"])
    }

    @Test func blockedRequestsGroupsAndNoVersionGetNothing() {
        let chats = [chat("a", blocked: true), chat("b", request: true), chat("c"), chat("g:1", oneToOne: false)]
        #expect(ProfileSender.pending(chats: chats, ownVer: 2, sent: [:]) == ["c"])
        #expect(ProfileSender.pending(chats: chats, ownVer: 0, sent: [:]).isEmpty)
    }

    @Test func versionBumpsOnlyOnARealChange() {
        #expect(ProfileSender.nextVersion(current: nil, publishedName: nil, name: "Ana", photoChanged: false) == 1)
        #expect(ProfileSender.nextVersion(current: 2, publishedName: "Ana", name: "Ana", photoChanged: false) == nil)
        #expect(ProfileSender.nextVersion(current: 2, publishedName: "Ana", name: "Ana", photoChanged: true) == 3)
        #expect(ProfileSender.nextVersion(current: 2, publishedName: "Ana", name: "Anna", photoChanged: false) == 3)
        #expect(ProfileSender.nextVersion(current: 2, publishedName: "Ana", name: "", photoChanged: true) == nil)
    }

    private func chat(_ id: String, blocked: Bool = false, request: Bool = false, oneToOne: Bool = true) -> ProfileSender.Target {
        .init(chatID: id, blocked: blocked, isRequest: request, isOneToOne: oneToOne)
    }
}

@Suite("Profile display")
struct ProfileDisplayTests {
    private func profile(_ name: String, photo: Data? = nil) -> StoredProfile {
        StoredProfile(accountID: "x", name: name, photo: photo, ver: 1)
    }

    @Test func profileNameWinsOverLookupName() {
        #expect(ProfileDisplay.name(lookup: "ana99", profile: profile("Ana Lopez"), nickname: nil) == "Ana Lopez")
    }

    @Test func fallsBackToLookupWithoutAProfile() {
        #expect(ProfileDisplay.name(lookup: "ana99", profile: nil, nickname: nil) == "ana99")
    }

    @Test func aNicknameTheUserSetKeepsPriority() {
        #expect(ProfileDisplay.name(lookup: "ana99", profile: profile("Ana Lopez"), nickname: "Mum") == "Mum")
        #expect(ProfileDisplay.name(lookup: "ana99", profile: profile("Ana Lopez"), nickname: "  ") == "Ana Lopez")
    }

    @Test func aProfileNameThatCleansToNothingFallsBack() {
        #expect(ProfileDisplay.name(lookup: "ana99", profile: profile("\u{202E}"), nickname: nil) == "ana99")
    }

    @Test func photoOnlyWhenItIsAJPEGWithinTheLimit() {
        let good = Data([0xFF, 0xD8, 0xFF, 0xE0] + [UInt8](repeating: 1, count: 100))
        #expect(ProfileDisplay.photo(profile: profile("A", photo: good)) == good)
        #expect(ProfileDisplay.photo(profile: profile("A", photo: Data([1, 2, 3]))) == nil)
        let big = Data([0xFF, 0xD8] + [UInt8](repeating: 1, count: 24_576))
        #expect(ProfileDisplay.photo(profile: profile("A", photo: big)) == nil)
        #expect(ProfileDisplay.photo(profile: nil) == nil)
    }
}
