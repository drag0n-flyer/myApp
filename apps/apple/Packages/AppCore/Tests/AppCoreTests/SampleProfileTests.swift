import Foundation
import Testing
@testable import AppCore

@Suite("Sample profiles") @MainActor
struct SampleProfileTests {
    @Test func theSampleContactHasADecodablePhotoWithinTheLimit() {
        let store = SampleChatStore()
        let photo = store.profilePhoto(for: "riya")
        #expect(photo != nil)
        #expect(ProfileDisplay.photo(profile: StoredProfile(accountID: "riya", name: "Riya", photo: photo, ver: 1)) == photo)
        #expect(store.profilePhoto(for: "arjun") == nil)
    }

    @Test func theOwnPhotoCanBeSetAndRemovedButOnlyAsAJPEG() {
        let store = SampleChatStore()
        #expect(store.myPhoto == nil)
        #expect(!store.setMyPhoto(Data([1, 2, 3, 4])))
        let photo = store.profilePhoto(for: "riya")!
        #expect(store.setMyPhoto(photo))
        #expect(store.myPhoto == photo && store.profilePhoto(for: store.me.id) == photo)
        #expect(store.setMyPhoto(nil))
        #expect(store.myPhoto == nil)
    }
}
