import Foundation

/// One member device of an MLS group and its signature public key (from the ratchet tree).
///
/// TODO(plan 4 memberKeys): this type belongs to `SecureMessagingCore` (cross-plan item 3). When
/// `MLSEngine.memberKeys(of:)` lands, delete this declaration and import the engine's.
public struct MemberKey: Sendable, Equatable {
    public let identity: String
    public let signatureKey: Data
    public init(identity: String, signatureKey: Data) {
        self.identity = identity
        self.signatureKey = signatureKey
    }
}

/// Safety numbers (Design 20, OPEN-6). Owner review: cryptography.
///
///     side(acct) = SHA-256( "safety-v1" || 0x00 || acct || 0x00 ||
///                           for each device of acct, sorted by identity UTF-8 bytes:
///                               u16be(len id) || id || u16be(len key) || key )
///
/// A device belongs to `acct` when its identity's part before the first `/` equals `acct`. The two sides are
/// ordered by account ID (UTF-8 bytes, ascending). Each side gives 6 groups: for `i` in 0..<6 the big-endian
/// integer of hash bytes `5i..<5i+5`, mod 100 000, as 5 digits. The number is the lower side's 6 groups, then the
/// higher side's: 12 groups, the same on both phones. Members of any other account are ignored.
///
/// SHA-256 is `PortableSHA256`, so Linux tests and devices run the same code. `safety-v1.json` pins it.
public enum SafetyNumberCalc {
    public static let version = "safety-v1"

    public static func compute(accountA: String, accountB: String, members: [MemberKey]) -> [String] {
        let (low, high) = Array(accountA.utf8).lexicographicallyPrecedes(Array(accountB.utf8))
            ? (accountA, accountB) : (accountB, accountA)
        return groups(of: side(low, members)) + groups(of: side(high, members))
    }

    static func account(of identity: String) -> Substring {
        identity.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false).first ?? ""
    }

    private static func side(_ account: String, _ members: [MemberKey]) -> Data {
        let mine = members
            .filter { Self.account(of: $0.identity) == account }
            .map { (id: Array($0.identity.utf8), key: [UInt8]($0.signatureKey)) }
            .sorted { $0.id.lexicographicallyPrecedes($1.id) }
        var input = [UInt8](Self.version.utf8)
        input.append(0)
        input += Array(account.utf8)
        input.append(0)
        for device in mine {
            input += u16be(device.id.count) + device.id + u16be(device.key.count) + device.key
        }
        return PortableSHA256.hash(Data(input))
    }

    private static func u16be(_ count: Int) -> [UInt8] {
        // Identities and keys are far below 64 KiB. A longer one (a hostile member) wraps instead of
        // crashing every device; the Go vector generator does the same.
        let value = UInt16(truncatingIfNeeded: count)
        return [UInt8(value >> 8), UInt8(value & 0xff)]
    }

    private static func groups(of hash: Data) -> [String] {
        let bytes = [UInt8](hash)
        return (0..<6).map { i in
            let value = bytes[(5 * i)..<(5 * i + 5)].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
            let digits = String(value % 100_000)
            return String(repeating: "0", count: 5 - digits.count) + digits
        }
    }
}
