import Foundation
import Testing
@testable import AppCore

/// The vectors shared with the server and Android: server/internal/vectors/display_names.json.
@Test func displayNameRuleMatchesSharedVectors() throws {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<7 { url.deleteLastPathComponent() }   // file, AppCoreTests, Tests, AppCore, Packages, apple, apps
    url.appendPathComponent("server/internal/vectors/display_names.json")
    let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    let cases = object["cases"] as! [[String: Any]]
    #expect(cases.count >= 50)
    for (i, c) in cases.enumerated() {
        let input = c["input"] as! String
        let want = c["clean"] as? String
        // Compare scalars: String `==` uses canonical equivalence and would hide a changed code point sequence.
        let got = DisplayNameRule.clean(input).map { Array($0.unicodeScalars) }
        #expect(got == want.map { Array($0.unicodeScalars) }, "case \(i): \(input.unicodeScalars.map { String($0.value, radix: 16) })")
    }
}
