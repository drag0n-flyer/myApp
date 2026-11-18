import Foundation
import Testing
@testable import Messaging

/// A card from another device is not trusted (spec 6.3, Review Focus): it is cleaned or refused when the envelope is read.
@Suite struct LinkPreviewReceiveTests {
    private func decode(text: String = "see https://example.com/a", lp: String) -> LinkPreview? {
        let json = #"{"v":1,"id":"m1","at":1,"k":"text","t":"\#(text)","lp":\#(lp)}"#
        return ContentEnvelope.decode(Data(json.utf8)).preview
    }

    @Test func aGoodCardIsKept() {
        let card = decode(lp: #"{"url":"https://example.com/a","title":"Hi","desc":"There"}"#)
        #expect(card == LinkPreview(url: "https://example.com/a", title: "Hi", desc: "There"))
    }

    @Test func controlAndDirectionCharactersAreStripped() {
        let card = decode(lp: #"{"url":"https://example.com/a","title":"A‮b\u0007c‏d\n","desc":"x⁦y\u0000z"}"#)
        #expect(card?.title == "Abcd")
        #expect(card?.desc == "xyz")
    }

    @Test func aBlankOrControlOnlyTitleRefusesTheCard() {
        #expect(decode(lp: #"{"url":"https://example.com/a","title":""}"#) == nil)
        #expect(decode(lp: #"{"url":"https://example.com/a","title":"   "}"#) == nil)
        #expect(decode(lp: #"{"url":"https://example.com/a","title":"‮\u0007"}"#) == nil)
    }

    @Test func theUrlMustBePlainHTTPSInTheText() {
        for url in ["http://example.com/a", "https://user@example.com/a", "https://user:pw@example.com/a", "https://example.com:8443/a",
                    "ftp://example.com/a", "https://", "javascript:alert(1)"] {
            #expect(decode(text: "see \(url)", lp: #"{"url":"\#(url)","title":"T"}"#) == nil, "\(url)")
        }
        #expect(decode(text: "see https://example.com:443/a", lp: #"{"url":"https://example.com:443/a","title":"T"}"#) != nil)
        // Not in the text.
        #expect(decode(text: "no link", lp: #"{"url":"https://example.com/a","title":"T"}"#) == nil)
    }

    @Test func lengthsAreCountedInCodePointsAfterCleaning() {
        let long = String(repeating: "a", count: 201)
        #expect(decode(lp: #"{"url":"https://example.com/a","title":"\#(long)"}"#) == nil)
        let ok = String(repeating: "a", count: 200)
        #expect(decode(lp: #"{"url":"https://example.com/a","title":"\#(ok)"}"#) != nil)
        let desc = String(repeating: "b", count: 301)
        #expect(decode(lp: #"{"url":"https://example.com/a","title":"T","desc":"\#(desc)"}"#) == nil)
    }

    /// A small JPEG whose frame header declares this size.
    private func jpegBase64(_ w: Int, _ h: Int) -> String {
        let sof: [UInt8] = [0xFF, 0xC0, 0, 17, 8, UInt8(h >> 8), UInt8(h & 255), UInt8(w >> 8), UInt8(w & 255), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1]
        return Data([0xFF, 0xD8, 0xFF, 0xE0, 0, 4, 0, 0] + sof + [0xFF, 0xD9]).base64EncodedString()
    }

    @Test func theImagesDeclaredSizeIsCheckedBeforeAnythingDecodesIt() {
        func card(_ w: Int, _ h: Int) -> LinkPreview? { decode(lp: #"{"url":"https://example.com/a","title":"T","img":"\#(jpegBase64(w, h))"}"#) }
        #expect(card(1_024, 1_024)?.img != nil)
        #expect(card(160, 90)?.img != nil)
        #expect(card(1_025, 100) == nil, "too wide: the whole card is refused")
        #expect(card(100, 1_025) == nil)
        #expect(card(65_535, 65_535) == nil, "a decompression bomb in 40 bytes")
        #expect(card(0, 0) == nil)
    }

    @Test func theExtraLookAlikeBlanksAreStrippedAndAHiddenTitleIsRefused() {
        let card = decode(lp: #"{"url":"https://example.com/a","title":"AㅤB⠀CᅟDᅠEﾠF G H"}"#)
        #expect(card?.title == "ABCDEFGH")
        #expect(decode(lp: #"{"url":"https://example.com/a","title":"ㅤ⠀ﾠᅟ"}"#) == nil)
        let family = "{\"url\":\"https://example.com/a\",\"title\":\"\u{1F468}\u{200D}\u{1F469}\"}"
        #expect(decode(lp: family)?.title == "\u{1F468}\u{200D}\u{1F469}", "joiners stay")
    }

    @Test func anEmptyCaptionIsNoCaption() {
        let att = Att(id: "a", key: Data(count: 32), dg: Data(count: 32), sz: 1, csz: 1)
        for text in ["", "  ", "\n"] {
            let sent = ContentEnvelope(id: "m1", at: 1, body: .file(att, name: "a.bin", mime: "x/y", replyTo: nil), caption: text).encoded()
            #expect(ContentEnvelope.decode(sent).caption == nil, "\(text.debugDescription)")
        }
        let kept = ContentEnvelope(id: "m1", at: 1, body: .file(att, name: "a.bin", mime: "x/y", replyTo: nil), caption: "hi").encoded()
        #expect(ContentEnvelope.decode(kept).caption == "hi")
    }

    @Test func aBadCardNeverSpoilsTheText() {
        let json = #"{"v":1,"id":"m1","at":1,"k":"text","t":"see https://example.com/a","lp":{"url":"http://x","title":""}}"#
        let envelope = ContentEnvelope.decode(Data(json.utf8))
        #expect(envelope.preview == nil)
        if case .text(let text, _) = envelope.body { #expect(text == "see https://example.com/a") } else { Issue.record("text lost") }
    }
}
