// A stand-in for the notification service extension, run as a separate process by
// `NSEProcessTests`: mls-nse-sim <storage dir> <identity> <hex padded payload>
// The signing key comes from the environment: MLS_TEST_KEY=<hex public key>:<hex secret key>.
// Prints `OK <plaintext>` or `ERR <MLSError>`, then `RSS <peak resident bytes>`.
import Foundation
import SecureMessaging
import Darwin

func hexData(_ text: String) -> Data? {
    guard text.count % 2 == 0 else { return nil }
    var data = Data()
    var index = text.startIndex
    while index < text.endIndex {
        let next = text.index(index, offsetBy: 2)
        guard let byte = UInt8(text[index..<next], radix: 16) else { return nil }
        data.append(byte)
        index = next
    }
    return data
}

func peakResidentBytes() -> Int {
    var usage = rusage()
    getrusage(RUSAGE_SELF, &usage)
    return Int(usage.ru_maxrss)   // bytes on Darwin
}

func finish(_ result: String) -> Never {
    print(result)
    print("RSS \(peakResidentBytes())")
    exit(0)
}

let arguments = CommandLine.arguments
guard arguments.count == 4,
      let padded = hexData(arguments[3]),
      let keyText = ProcessInfo.processInfo.environment["MLS_TEST_KEY"],
      case let parts = keyText.split(separator: ":").map(String.init), parts.count == 2,
      let publicKey = hexData(parts[0]), let secretKey = hexData(parts[1]) else {
    finish("ERR usage")
}
let identity = arguments[2]

do {
    guard let message = try PushPayload.unpad(padded) else { finish("ERR fetchInbox") }
    let keys = InMemorySigningKeyStore()
    try keys.save(identity: identity, publicKey: publicKey, secretKey: secretKey)
    let engine = try LiveMLSEngine(identity: identity, storage: URL(fileURLWithPath: arguments[1]), keys: keys)
    switch try await engine.process(message) {
    case .application(_, _, let plaintext, _): finish("OK " + String(decoding: plaintext, as: UTF8.self))
    case .commit: finish("OK (commit)")
    case .joined: finish("OK (joined)")
    }
} catch {
    finish("ERR \(error)")
}
