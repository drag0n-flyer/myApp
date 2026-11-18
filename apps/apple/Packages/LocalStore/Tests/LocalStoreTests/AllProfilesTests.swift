import Foundation
import Testing
@testable import LocalStore

@Test func testProfilesListsEveryStoredProfile() throws {
    let db = try Database.inMemory()
    try migrate(db)
    let repo = Repository(db: db, now: { Date(timeIntervalSince1970: 1000) })
    #expect(try repo.profiles().isEmpty)
    try repo.saveProfile(accountID: "bob", name: "Bob", photo: Data([0xFF, 0xD8]), ver: 1)
    try repo.saveProfile(accountID: "ana", name: "Ana", photo: nil, ver: 2)
    let all = try repo.profiles().sorted { $0.accountID < $1.accountID }
    #expect(all == [
        ProfileRecord(accountID: "ana", name: "Ana", photo: nil, ver: 2),
        ProfileRecord(accountID: "bob", name: "Bob", photo: Data([0xFF, 0xD8]), ver: 1),
    ])
}
