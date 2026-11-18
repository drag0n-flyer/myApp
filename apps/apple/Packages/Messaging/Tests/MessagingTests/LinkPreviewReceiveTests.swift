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

    @Test func aBadCardNeverSpoilsTheText() {
        let json = #"{"v":1,"id":"m1","at":1,"k":"text","t":"see https://example.com/a","lp":{"url":"http://x","title":""}}"#
        let envelope = ContentEnvelope.decode(Data(json.utf8))
        #expect(envelope.preview == nil)
        if case .text(let text, _) = envelope.body { #expect(text == "see https://example.com/a") } else { Issue.record("text lost") }
    }
}
