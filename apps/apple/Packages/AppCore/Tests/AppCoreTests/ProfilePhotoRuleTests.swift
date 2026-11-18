import Foundation
import Testing
@testable import AppCore

/// The table shared with Android: server/internal/vectors/profile_photo.json.
private struct PhotoCase: Decodable {
    let name: String
    let hex: String
    let accept: Bool
    let width: Int?
    let height: Int?
    var data: Data {
        var out = Data()
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            out.append(UInt8(hex[index..<next], radix: 16)!)
            index = next
        }
        return out
    }
}

private func loadCases() throws -> [PhotoCase] {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<7 { url.deleteLastPathComponent() }   // file, AppCoreTests, Tests, AppCore, Packages, apple, apps
    url.appendPathComponent("server/internal/vectors/profile_photo.json")
    return try JSONDecoder().decode([PhotoCase].self, from: Data(contentsOf: url))
}

@Suite("Profile photo rule")
struct ProfilePhotoRuleTests {
    @Test func theSharedTableIsAllHonoured() throws {
        let cases = try loadCases()
        #expect(cases.count >= 20)
        for c in cases {
            let size = JPEGFrame.size(of: c.data)
            if c.accept {
                #expect(size?.width == c.width && size?.height == c.height, "\(c.name)")
            }
            #expect(ProfilePhotoRule.isAcceptable(c.data) == c.accept, "\(c.name)")
        }
    }

    @Test func aFrameHeaderIsReadEvenWhenTheSizeIsOutOfRange() {
        // The parser reports what is declared; the rule decides.
        let data = Data([0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x0B, 0x08, 0xFD, 0xE8, 0xFD, 0xE8, 0x01, 0x01, 0x11, 0x00])
        #expect(JPEGFrame.size(of: data)?.width == 65000)
        #expect(!ProfilePhotoRule.isAcceptable(data))
    }

    @Test func theByteLimitAndTheMinimumLength() {
        let header = Data([0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x01, 0x00, 0x01, 0x00, 0x01, 0x01, 0x11, 0x00])
        #expect(ProfilePhotoRule.isAcceptable(header + Data(count: 24_576 - header.count)))
        #expect(!ProfilePhotoRule.isAcceptable(header + Data(count: 24_577 - header.count)))
        #expect(!ProfilePhotoRule.isAcceptable(Data([0xFF, 0xD8, 0xFF])))
        #expect(!ProfilePhotoRule.isAcceptable(Data()))
    }

    @Test func displayRechecksAStoredPhoto() {
        let hostile = Data([0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x0B, 0x08, 0xFD, 0xE8, 0xFD, 0xE8, 0x01, 0x01, 0x11, 0x00])
        let stored = StoredProfile(accountID: "x", name: "A", photo: hostile, ver: 1)
        #expect(ProfileDisplay.photo(profile: stored) == nil)
    }
}
