import Foundation
import Testing
@testable import AppCore

@Suite struct ContactLinkTests {
    private let host = "chat.example.org"
    private let token = "AbCdEfGhIjKlMnOpQrStUv"   // 22 URL-safe characters

    private func parse(_ s: String) -> ContactLink { ContactLink.parse(URL(string: s)!, serverHost: host) }

    @Test func userLink() {
        #expect(parse("https://chat.example.org/u/alice_1") == .user("alice_1"))
    }

    @Test func inviteLink() {
        #expect(token.count == 22)
        #expect(parse("https://chat.example.org/c/\(token)") == .invite(token))
        #expect(parse("https://chat.example.org/c/AbCd-fGhIjKl_nOpQrStUv") == .invite("AbCd-fGhIjKl_nOpQrStUv"))
    }

    @Test func hostIgnoresCase() {
        #expect(parse("https://CHAT.Example.ORG/u/alice") == .user("alice"))
        #expect(ContactLink.parse(URL(string: "https://chat.example.org/u/alice")!, serverHost: "Chat.Example.Org") == .user("alice"))
    }

    @Test func defaultPortIsNoPort() {
        #expect(parse("https://chat.example.org:443/u/alice") == .user("alice"))
        #expect(ContactLink.parse(URL(string: "https://chat.example.org/u/alice")!, serverHost: "chat.example.org:443") == .user("alice"))
        #expect(parse("https://chat.example.org:8443/u/alice") == .otherServer)
        #expect(ContactLink.parse(URL(string: "https://chat.example.org:8443/u/alice")!, serverHost: "chat.example.org:8443") == .user("alice"))
        #expect(ContactLink.parse(URL(string: "https://chat.example.org/u/alice")!, serverHost: "chat.example.org:8443") == .otherServer)
    }

    @Test func otherHost() {
        #expect(parse("https://evil.example.com/u/alice") == .otherServer)
        #expect(parse("https://evil.example.com/c/\(token)") == .otherServer)
        #expect(parse("https://chat.example.org.evil.com/u/alice") == .otherServer)
        // Not even shaped like one of ours.
        #expect(parse("https://evil.example.com/other") == .notALink)
        #expect(parse("https://evil.example.com/u/A!") == .notALink)
    }

    @Test func notHttps() {
        #expect(parse("http://chat.example.org/u/alice") == .notALink)
        #expect(parse("ftp://chat.example.org/u/alice") == .notALink)
        #expect(parse("myapp://chat.example.org/u/alice") == .notALink)
    }

    @Test func badUsername() {
        for name in ["Alice", "1abc", "ab", "a-b-c", "alice%20x", String(repeating: "a", count: 33), "al.ce"] {
            #expect(parse("https://chat.example.org/u/\(name)") == .notALink, "\(name)")
        }
        #expect(parse("https://chat.example.org/u/") == .notALink)
        #expect(parse("https://chat.example.org/u") == .notALink)
        #expect(parse("https://chat.example.org/u/abc") == .user("abc"))
        #expect(parse("https://chat.example.org/u/\(String(repeating: "a", count: 32))") == .user(String(repeating: "a", count: 32)))
    }

    @Test func tokenLength() {
        let short = String(token.dropLast())
        #expect(short.count == 21)
        #expect(parse("https://chat.example.org/c/\(short)") == .notALink)
        #expect(parse("https://chat.example.org/c/\(token)A") == .notALink)   // 23
        #expect(parse("https://chat.example.org/c/\(token.dropLast())+") == .notALink)
        #expect(parse("https://chat.example.org/c/\(token.dropLast())=") == .notALink)
    }

    @Test func trailingSlashAndExtraSegments() {
        #expect(parse("https://chat.example.org/u/alice/") == .notALink)
        #expect(parse("https://chat.example.org/u/alice/more") == .notALink)
        #expect(parse("https://chat.example.org/c/\(token)/") == .notALink)
        #expect(parse("https://chat.example.org/c/\(token)/x") == .notALink)
        #expect(parse("https://chat.example.org/x/u/alice") == .notALink)
        #expect(parse("https://chat.example.org//u/alice") == .notALink)
        #expect(parse("https://chat.example.org/u/al%2Fice") == .notALink)
        #expect(parse("https://chat.example.org/invite/abcdef") == .notALink)
    }

    @Test func queryAndFragmentIgnored() {
        #expect(parse("https://chat.example.org/u/alice?x=1") == .user("alice"))
        #expect(parse("https://chat.example.org/u/alice#top") == .user("alice"))
        #expect(parse("https://chat.example.org/c/\(token)?utm=1#f") == .invite(token))
    }

    @Test func urlsAreBuiltFromHostAndName() {
        #expect(ContactLink.userURL(host: host, username: "alice")?.absoluteString == "https://chat.example.org/u/alice")
        #expect(ContactLink.userURL(host: host, username: "Bad!") == nil)
        #expect(ContactLink.inviteURL(host: host, token: token)?.absoluteString == "https://chat.example.org/c/\(token)")
        #expect(ContactLink.inviteURL(host: host, token: "short") == nil)
    }

    @Test func tokenIsNeverPrinted() {
        let link = ContactLink.invite(token)
        #expect(!"\(link)".contains(token))
        #expect(!String(reflecting: link).contains(token))
    }
}
