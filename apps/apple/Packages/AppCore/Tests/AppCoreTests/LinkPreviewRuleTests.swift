import Foundation
import Testing
@testable import AppCore

@Suite struct LinkPreviewRuleTests {
    @Test func firstLinkTable() {
        let cases: [(String, String?)] = [
            ("see https://example.com/a?b=1 now", "https://example.com/a?b=1"),
            ("https://example.com", "https://example.com"),
            ("no link here", nil),
            ("http://example.com", nil),
            ("http://a.example then https://b.example", nil),
            ("mail me mailto:a@b.example", nil),
            ("`https://example.com`", nil),
            ("```\nhttps://example.com\n```", nil),
            ("code `x` then https://example.com", "https://example.com"),
            ("https://", nil),
        ]
        for (text, expected) in cases {
            #expect(LinkPreviewRule.firstLink(text)?.absoluteString == expected, "\(text)")
        }
    }

    @Test func theLinkIsAlwaysFoundInTheText() {
        let text = "look: https://example.com/path_one/two"
        if let url = LinkPreviewRule.firstLink(text) { #expect(text.contains(url.absoluteString)) }
    }

    private func allowed(_ host: String, _ addresses: [String]) -> Bool { LinkPreviewRule.allowedHost(host, resolved: addresses) }

    @Test func publicNamesPass() {
        #expect(allowed("example.com", ["93.184.216.34"]))
        #expect(allowed("Example.COM.", ["93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946"]))
        #expect(allowed("a.b.example.org", ["8.8.8.8"]))
    }

    @Test func ipLiteralHostsAreRefused() {
        for host in ["127.0.0.1", "[::1]", "::1", "10.0.0.1", "169.254.169.254", "8.8.8.8", "2130706433", "0x7f.0.0.1",
                     "127.1", "017700000001", "[2606:2800::1]", "1.2.3.4."] {
            #expect(!allowed(host, ["93.184.216.34"]), "\(host)")
        }
    }

    @Test func localNamesAreRefused() {
        for host in ["localhost", "foo.localhost", "printer.local", "db.internal", "intranet", "x.localdomain", "router.home.arpa", ""] {
            #expect(!allowed(host, ["93.184.216.34"]), "\(host)")
        }
    }

    @Test func aNameResolvingToANonPublicAddressIsRefused() {
        for address in ["192.168.1.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "127.0.0.1", "169.254.169.254", "100.64.0.1",
                        "100.127.255.255", "0.0.0.0", "224.0.0.1", "255.255.255.255", "240.0.0.1", "192.0.2.1", "198.18.0.1",
                        "::1", "::", "fe80::1", "fe80::1%en0", "fc00::1", "fd12:3456::1", "ff02::1", "::ffff:127.0.0.1",
                        "::ffff:10.0.0.1", "::ffff:7f00:1", "64:ff9b::a00:1", "2002:a00:1::1", "2001:db8::1", "2001::1", "fec0::1"] {
            #expect(!allowed("example.com", [address]), "\(address)")
        }
    }

    @Test func oneBadAddressRefusesTheWholeAnswer() {
        #expect(!allowed("example.com", ["93.184.216.34", "192.168.1.1"]))
    }

    @Test func publicBoundariesPass() {
        for address in ["172.15.255.255", "172.32.0.0", "100.63.255.255", "100.128.0.0", "11.0.0.1", "169.253.1.1", "223.255.255.255",
                        "::ffff:8.8.8.8", "2606:4700::1111"] {
            #expect(allowed("example.com", [address]), "\(address)")
        }
    }

    @Test func noAnswerOrABadAnswerIsRefused() {
        #expect(!allowed("example.com", []))
        #expect(!allowed("example.com", ["not-an-address"]))
        #expect(!allowed("example.com", ["1.2.3"]))
        #expect(!allowed("example.com", ["1.2.3.256"]))
        #expect(!allowed("example.com", ["1::2::3"]))
    }
}

@Suite struct LinkPreviewParseTests {
    private func parse(_ html: String, limit: Int = 524_288, base: String? = "https://example.com/dir/page") -> LinkPreviewParse.Result? {
        LinkPreviewParse.fromHTML(Data(html.utf8), limit: limit, base: base.flatMap(URL.init(string:)))
    }

    @Test func openGraphIsRead() {
        let html = """
        <html><head><title>Fallback</title>
        <meta property="og:title" content="The &amp; Title">
        <meta property='og:description' content="A  description
        over lines">
        <meta property="og:image" content="/img/a.jpg"></head></html>
        """
        let result = parse(html)
        #expect(result?.title == "The & Title")
        #expect(result?.desc == "A description over lines")
        #expect(result?.imageURL?.absoluteString == "https://example.com/img/a.jpg")
    }

    @Test func titleTagIsTheFallback() {
        let result = parse("<head><TITLE>  Plain\n title </TITLE></head>")
        #expect(result?.title == "Plain title")
        #expect(result?.desc == nil)
        #expect(result?.imageURL == nil)
    }

    @Test func nameDescriptionIsUsedWhenThereIsNoOpenGraphOne() {
        #expect(parse("<title>T</title><meta name=\"description\" content=\"D\">")?.desc == "D")
    }

    @Test func malformedHTMLNeverCrashes() {
        for html in ["", "<", "<<<>>>", "<meta", "<meta property=\"og:title\" content=\"unterminated", "<title>no end",
                     "<!-- <title>hidden</title> -->", "\u{0}\u{1}\u{2}", "<meta property=og:title content=>"] {
            _ = parse(html)
        }
        #expect(parse("") == nil)
        #expect(parse("<!-- <title>hidden</title> -->") == nil)
        #expect(parse("<script>var t='<title>x</title>'</script>") == nil)
        #expect(parse("<title>no end")?.title == nil)
    }

    @Test func invalidUTF8IsReplacedNotFatal() {
        let bytes = Data("<title>ab".utf8) + Data([0xFF, 0xFE]) + Data("cd</title>".utf8)
        #expect(LinkPreviewParse.fromHTML(bytes, limit: 1000, base: nil)?.title?.hasPrefix("ab") == true)
    }

    @Test func aFiveHundredCharacterTitleIsCutToTwoHundredCodePoints() {
        let long = String(repeating: "a", count: 500)
        #expect(parse("<title>\(long)</title>")?.title?.unicodeScalars.count == 200)
        let desc = String(repeating: "b", count: 400)
        #expect(parse("<title>T</title><meta property=\"og:description\" content=\"\(desc)\">")?.desc?.unicodeScalars.count == 300)
        // Code points, not characters or bytes.
        let emoji = String(repeating: "\u{1F600}", count: 300)
        #expect(parse("<title>\(emoji)</title>")?.title?.unicodeScalars.count == 200)
    }

    @Test func onlyTheFirstLimitBytesAreRead() {
        let html = "<title>Early</title>" + String(repeating: " ", count: 100) + "<meta property=\"og:description\" content=\"late\">"
        #expect(parse(html, limit: 40)?.title == "Early")
        #expect(parse(html, limit: 40)?.desc == nil)
        #expect(parse(html, limit: 10) == nil)
    }

    @Test func controlAndDirectionCharactersAreDropped() {
        #expect(parse("<title>a\u{202E}b\u{0007}c\u{200F}d</title>")?.title == "abcd")
    }

    @Test func imageMustBeAnHTTPSURL() {
        #expect(parse("<title>T</title><meta property=\"og:image\" content=\"http://example.com/a.jpg\">")?.imageURL == nil)
        #expect(parse("<title>T</title><meta property=\"og:image\" content=\"data:image/png;base64,AAAA\">")?.imageURL == nil)
        #expect(parse("<title>T</title><meta property=\"og:image\" content=\"//cdn.example.com/a.jpg\">")?.imageURL?.absoluteString == "https://cdn.example.com/a.jpg")
        #expect(parse("<title>T</title><meta property=\"og:image\" content=\"https://cdn.example.com/a.jpg\">")?.imageURL?.host == "cdn.example.com")
    }

    @Test func aBlankTitleGivesNoPreview() {
        #expect(parse("<title>   </title>") == nil)
        #expect(parse("<meta property=\"og:title\" content=\"  \"><title>Real</title>")?.title == "Real")
    }
}
