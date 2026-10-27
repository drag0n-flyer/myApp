import Foundation
import Testing
@testable import Messaging

private func key(_ seed: UInt8) -> Data { Data((0..<32).map { seed &+ UInt8($0) }) }

private let members = [
    MemberKey(identity: "acc-ana/dev-1", signatureKey: key(1)),
    MemberKey(identity: "acc-ana/dev-2", signatureKey: key(40)),
    MemberKey(identity: "acc-bob/dev-3", signatureKey: key(80)),
]

private struct Vector: Decodable {
    struct Member: Decodable { let identity: String, signatureKeyHex: String }
    struct Case: Decodable {
        let name: String, accountA: String, accountB: String
        let members: [Member]
        let expected: [String]
    }
    let version: String
    let cases: [Case]
}

private func data(hex: String) -> Data {
    var bytes: [UInt8] = []
    var index = hex.startIndex
    while index < hex.endIndex {
        let next = hex.index(index, offsetBy: 2)
        bytes.append(UInt8(hex[index..<next], radix: 16)!)
        index = next
    }
    return Data(bytes)
}

@Suite("TestSafetyNumber") struct TestSafetyNumber {
    @Test func format() throws {
        let number = try SafetyNumberCalc.compute(accountA: "acc-ana", accountB: "acc-bob", members: members)
        #expect(number.count == 12)
        for group in number { #expect(group.range(of: "^[0-9]{5}$", options: .regularExpression) != nil) }
    }

    @Test func symmetric() throws {
        let ab = try SafetyNumberCalc.compute(accountA: "acc-ana", accountB: "acc-bob", members: members)
        let ba = try SafetyNumberCalc.compute(accountA: "acc-bob", accountB: "acc-ana", members: members)
        #expect(ab == ba)
        #expect(try SafetyNumberCalc.compute(accountA: "acc-ana", accountB: "acc-bob", members: members.reversed()) == ab)
    }

    @Test func thirdAccountIgnored() throws {
        let plain = try SafetyNumberCalc.compute(accountA: "acc-ana", accountB: "acc-bob", members: members)
        let withCarol = members + [MemberKey(identity: "acc-carol/dev-9", signatureKey: key(9))]
        #expect(try SafetyNumberCalc.compute(accountA: "acc-ana", accountB: "acc-bob", members: withCarol) == plain)
    }

    /// An account that is a prefix of another must not pick up the other's devices.
    @Test func accountPrefixIsNotAMatch() throws {
        let plain = try SafetyNumberCalc.compute(accountA: "acc-ana", accountB: "acc-bob", members: members)
        let longer = members + [MemberKey(identity: "acc-ana2/dev-9", signatureKey: key(9))]
        #expect(try SafetyNumberCalc.compute(accountA: "acc-ana", accountB: "acc-bob", members: longer) == plain)
    }

    @Test func changesWhenKeyDiffers() throws {
        let before = try SafetyNumberCalc.compute(accountA: "acc-ana", accountB: "acc-bob", members: members)
        var swapped = members
        var bytes = [UInt8](swapped[1].signatureKey)
        bytes[0] ^= 1
        swapped[1] = MemberKey(identity: swapped[1].identity, signatureKey: Data(bytes))
        let after = try SafetyNumberCalc.compute(accountA: "acc-ana", accountB: "acc-bob", members: swapped)
        #expect(after != before)
        // Only ana's side changed: bob's 6 groups are the same.
        #expect(Array(after[6...]) == Array(before[6...]))
        #expect(Array(after[..<6]) != Array(before[..<6]))
    }

    @Test func vector() throws {
        let url = try #require(Bundle.module.url(forResource: "safety-v1", withExtension: "json", subdirectory: "Resources"))
        let vector = try JSONDecoder().decode(Vector.self, from: Data(contentsOf: url))
        #expect(vector.version == SafetyNumberCalc.version)
        #expect(vector.cases.count >= 5)
        for c in vector.cases {
            let keys = c.members.map { MemberKey(identity: $0.identity, signatureKey: data(hex: $0.signatureKeyHex)) }
            #expect(try SafetyNumberCalc.compute(accountA: c.accountA, accountB: c.accountB, members: keys) == c.expected, "\(c.name)")
        }
    }

    /// Lengths that do not fit a u16 are rejected, never wrapped; 65 535 still works.
    @Test func oversizedInputRejected() throws {
        let big = Data(repeating: 1, count: 65_536)
        let ok = Data(repeating: 1, count: 65_535)
        #expect(throws: SafetyNumberCalc.Failure.inputTooLong) {
            try SafetyNumberCalc.compute(
                accountA: "acc-ana", accountB: "acc-bob", members: members + [MemberKey(identity: "acc-bob/dev-9", signatureKey: big)])
        }
        let longID = "acc-ana/" + String(repeating: "x", count: 65_536)
        #expect(throws: SafetyNumberCalc.Failure.inputTooLong) {
            try SafetyNumberCalc.compute(
                accountA: "acc-ana", accountB: "acc-bob", members: members + [MemberKey(identity: longID, signatureKey: key(1))])
        }
        _ = try SafetyNumberCalc.compute(
            accountA: "acc-ana", accountB: "acc-bob", members: members + [MemberKey(identity: "acc-bob/dev-9", signatureKey: ok)])
        // An oversized input of a third account is not part of either side, so it does not matter.
        _ = try SafetyNumberCalc.compute(
            accountA: "acc-ana", accountB: "acc-bob", members: members + [MemberKey(identity: "acc-carol/dev-9", signatureKey: big)])
    }

    /// Equal identities with different keys have one fixed order.
    @Test func duplicateIdentityOrderIndependent() throws {
        let a = MemberKey(identity: "acc-ana/dev-1", signatureKey: key(1)), b = MemberKey(identity: "acc-ana/dev-1", signatureKey: key(2))
        let bob = MemberKey(identity: "acc-bob/dev-3", signatureKey: key(80))
        #expect(try SafetyNumberCalc.compute(accountA: "acc-ana", accountB: "acc-bob", members: [a, b, bob])
            == SafetyNumberCalc.compute(accountA: "acc-ana", accountB: "acc-bob", members: [b, a, bob]))
    }
}
